package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.dto.GcIdentity;
import eu.wohlben.qits.artifacts.dto.GcSweepReport;
import eu.wohlben.qits.artifacts.dto.GcTypeSweepResult;
import eu.wohlben.qits.artifacts.entity.ArtifactRecord;
import eu.wohlben.qits.artifacts.entity.NpmDistTag;
import eu.wohlben.qits.artifacts.entity.NpmVersion;
import eu.wohlben.qits.artifacts.entity.OciManifest;
import eu.wohlben.qits.artifacts.entity.OciTag;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The executed sweep, end to end against real rows and real files.
 *
 * <p>The dry-run suites prove what a plan says; this one proves what happens when it is applied.
 * The cases are the ways the answer must be "nothing" — a file inside the grace window, a blob
 * another type still serves, a blob that never had a row — plus the one way it must be "gone":
 * matured content whose last identity a strategy's own rule condemned. Each deletion case asserts
 * the whole chain: the row, the tombstone where the type has one, the file, and the receipt.
 *
 * <p>Strategies are wired by hand, as their own suites wire them, so a case controls exactly what
 * is registered — the OCI strategy gets a fake pin source, the npm strategy the real registry
 * service, because {@code collect}'s tombstone-and-refusal mechanics are half of what is on trial.
 */
@QuarkusTest
class GcSweepExecutorTest extends GcFixture {

  @Inject GcSweepExecutor executor;
  @Inject NpmRegistryService npmRegistry;
  @Inject OciRegistryService ociRegistry;
  @Inject OciManifestFootprints footprints;

  private static final String PKG = "@qits/sweep-case";
  private static final String RELEASE = "2026.801.85149";
  private static final String SUPERSEDED = RELEASE + "-main.g1111111";
  private static final String NEWEST = RELEASE + "-main.g2222222";

  @Test
  void aMaturedSupersededBuildLosesItsRowGainsATombstoneAndItsTarballIsUnlinked()
      throws Exception {
    // The full npm chain, executed: the strategy condemns, collect() deletes the row and writes the
    // tombstone in one transaction, and the sweep unlinks the tarball nothing references any more.
    repositoryService.ensure("npm", RepositoryType.NPM_PACKAGES);
    String releaseBlob = agedBlob(41);
    String supersededBlob = agedBlob(42);
    String newestBlob = agedBlob(43);
    versionRow(PKG, RELEASE, releaseBlob);
    versionRow(PKG, SUPERSEDED, supersededBlob);
    versionRow(PKG, NEWEST, newestBlob);

    GcSweepReport report = executor.execute(census.take(), List.of(npmStrategy()));

    assertFalse(report.dryRun());
    assertNotNull(report.executedAt());
    assertEquals("P7D", report.graceWindow());
    GcTypeSweepResult npm = typeResult(report, RepositoryType.NPM_PACKAGES);
    assertNull(npm.error());
    assertEquals(List.of(PKG + "@" + SUPERSEDED), identities(npm.deleted()));
    assertEquals(List.of(), npm.withheldByGraceWindow());

    detachEntities();
    assertTrue(npmVersions.findOne("npm", PKG, SUPERSEDED).isEmpty(), "the row is gone");
    assertTrue(
        npmVersionTombstones.findOne("npm", PKG, SUPERSEDED).isPresent(),
        "the tombstone is written, so the name can never be silently republished");
    assertFalse(blobStore.exists(supersededBlob), "the tarball file is unlinked");
    assertTrue(blobStore.exists(releaseBlob), "a release survives, always");
    assertTrue(blobStore.exists(newestBlob), "the newest main build survives");

    assertEquals(1, report.sweep().blobsUnlinked());
    assertEquals(42L, report.sweep().bytesReclaimed());
    assertEquals(List.of(supersededBlob), report.sweep().unlinkedBlobIds());
    assertEquals(0, report.sweep().withheldByGraceWindow());
    assertEquals(0, report.sweep().stillReferenced());
  }

  @Test
  void aTarballInsideTheGraceWindowWithholdsTheWholeIdentityRowIntactNothingUnlinked()
      throws Exception {
    // The strand hazard, closed: deleting the row first would leave the young blob row-less — and
    // row-less blobs are untouchable by construction, so it would never be reclaimed at all. The
    // identity waits out the window with its file, and the next run past it takes both together.
    repositoryService.ensure("npm", RepositoryType.NPM_PACKAGES);
    String supersededBlob = store(filled(52, (byte) 52)); // NOT backdated: as young as a fresh push
    String newestBlob = store(filled(53, (byte) 53));
    versionRow(PKG, SUPERSEDED, supersededBlob);
    versionRow(PKG, NEWEST, newestBlob);

    GcSweepReport report = executor.execute(census.take(), List.of(npmStrategy()));

    GcTypeSweepResult npm = typeResult(report, RepositoryType.NPM_PACKAGES);
    assertEquals(List.of(), npm.deleted());
    assertEquals(List.of(PKG + "@" + SUPERSEDED), identities(npm.withheldByGraceWindow()));
    assertNull(npm.error(), "withheld is not an error — it is the window working");

    assertTrue(npmVersions.findOne("npm", PKG, SUPERSEDED).isPresent(), "the row stays");
    assertTrue(npmVersionTombstones.findOne("npm", PKG, SUPERSEDED).isEmpty(), "no tombstone");
    assertTrue(blobStore.exists(supersededBlob), "the file stays");
    assertEquals(0, report.sweep().blobsUnlinked());
    assertEquals(1, report.sweep().withheldByGraceWindow());
    assertEquals(52L, report.sweep().withheldBytes());
  }

  @Test
  void theMechanismRefusesADistTagNamedVersionEvenWhenAPlanCondemnsIt() throws Exception {
    // The policy never condemns a dist-tag-named version; this proves the MECHANISM refuses one
    // anyway. A hand-built plan stands in for the policy bug, and collect()'s 409 lands in the
    // receipt's error column instead of breaking the packument.
    repositoryService.ensure("npm", RepositoryType.NPM_PACKAGES);
    String taggedBlob = agedBlob(62);
    versionRow(PKG, SUPERSEDED, taggedBlob);
    distTagRow(PKG, "main", SUPERSEDED);
    GcStrategy.Plan condemned =
        new GcStrategy.Plan(
            List.of(
                new GcIdentity("npm", PKG + "@" + SUPERSEDED, NpmPackagesGcStrategy.DEAD_BUILD)),
            List.of(),
            Set.of(taggedBlob),
            Set.of());

    GcStrategy.Applied applied = npmStrategy().apply(condemned, blobId -> false);

    assertEquals(List.of(), applied.deleted());
    assertEquals(1, applied.errors().size());
    assertTrue(applied.errors().get(0).contains("dist-tag"), applied.errors().get(0));
    assertTrue(npmVersions.findOne("npm", PKG, SUPERSEDED).isPresent(), "the row stays");
    assertTrue(npmVersionTombstones.findOne("npm", PKG, SUPERSEDED).isEmpty(), "no tombstone");
  }

  @Test
  void ociDeletesDeadTagsAndUnreachableManifestsNeverAKeptIdentityAndSharedContentSurvives()
      throws Exception {
    // The whole OCI chain plus the two survival rules in one store: a kept identity's rows are
    // untouched, a blob the npm type still serves outlives its own type's deletion, and a row-less
    // blob outlives everything — asserted through the executor, not just the plan.
    repositoryService.ensure("qits", RepositoryType.OCI_IMAGES);
    repositoryService.ensure("npm", RepositoryType.NPM_PACKAGES);
    String config = agedBlob(10);
    String layerKept = agedBlob(100);
    String layerShared = agedBlob(200);
    String layerDoomed = agedBlob(300);
    String layerOrphan = agedBlob(150);
    String rowless = agedBlob(500);

    byte[] keptBytes =
        imageManifest(config, Map.of(layerKept, 100L, layerShared, 200L));
    byte[] doomedBytes =
        imageManifest(config, Map.of(layerDoomed, 300L, layerShared, 200L));
    byte[] orphanBytes = imageManifest(config, Map.of(layerOrphan, 150L));
    String manifestKept = agedStore(keptBytes);
    String manifestDoomed = agedStore(doomedBytes);
    String manifestOrphan = agedStore(orphanBytes);

    String deadSha = "a".repeat(40);
    String newestSha = "b".repeat(40);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ociManifests.persist(qitsManifest(manifestKept, keptBytes.length));
              ociManifests.persist(qitsManifest(manifestDoomed, doomedBytes.length));
              ociManifests.persist(qitsManifest(manifestOrphan, orphanBytes.length));
              ociTags.persist(qitsTag("2026.801.5", manifestKept, Instant.now()));
              ociTags.persist(qitsTag(newestSha, manifestKept, Instant.now()));
              ociTags.persist(
                  qitsTag(deadSha, manifestDoomed, Instant.now().minus(Duration.ofHours(1))));
            });
    // The cross-type case: the npm registry serves the doomed layer's bytes as a release tarball.
    versionRow(PKG, RELEASE, layerDoomed);

    GcSweepReport report =
        executor.execute(census.take(), List.of(ociStrategy(List.of()), npmStrategy()));

    GcTypeSweepResult oci = typeResult(report, RepositoryType.OCI_IMAGES);
    assertNull(oci.error());
    assertEquals(
        List.of(
                "alpha8:" + deadSha,
                "alpha8@sha256:" + manifestDoomed,
                "alpha8@sha256:" + manifestOrphan)
            .stream()
            .sorted()
            .toList(),
        identities(oci.deleted()).stream().sorted().toList());
    assertEquals(List.of(), oci.withheldByGraceWindow());

    // Never a kept identity: both kept tag rows and the kept manifest row are exactly where they
    // were, and the dead rows are gone.
    detachEntities();
    assertTrue(ociTags.findOne("qits", "alpha8", "2026.801.5").isPresent());
    assertTrue(ociTags.findOne("qits", "alpha8", newestSha).isPresent());
    assertTrue(ociManifests.findOne("qits", "alpha8", manifestKept).isPresent());
    assertTrue(ociTags.findOne("qits", "alpha8", deadSha).isEmpty());
    assertTrue(ociManifests.findOne("qits", "alpha8", manifestDoomed).isEmpty());
    assertTrue(ociManifests.findOne("qits", "alpha8", manifestOrphan).isEmpty());

    // The blobs: dead-only content goes, shared content survives on the side that kept it.
    assertEquals(
        List.of(layerOrphan, manifestDoomed, manifestOrphan).stream().sorted().toList(),
        report.sweep().unlinkedBlobIds());
    assertFalse(blobStore.exists(manifestDoomed));
    assertFalse(blobStore.exists(manifestOrphan));
    assertFalse(blobStore.exists(layerOrphan));
    assertTrue(blobStore.exists(layerDoomed), "npm still serves these bytes as a tarball");
    assertTrue(blobStore.exists(layerShared), "the kept manifest still names it");
    assertTrue(blobStore.exists(config), "the kept manifest still names it");
    assertTrue(blobStore.exists(layerKept));

    // Row-less blobs are untouchable straight through an executed sweep, matured or not.
    assertTrue(blobStore.exists(rowless), "no identity ever named it, so nothing can reach it");
    assertTrue(report.untouchable().blobIds().contains(rowless));
    assertEquals(0, report.sweep().stillReferenced());
  }

  @Test
  void theStubsPlanNothingAtZeroRowsAndRefuseOnceRowsExist() throws Exception {
    // Zero rows: nothingDies under the note naming the intended rule — the honest caption, on the
    // receipt as on the plan. Rows: the stub fails closed rather than guess with an unimplemented
    // rule, and the refusal names what to implement.
    repositoryService.ensure("shots", RepositoryType.CI_SCREENSHOTS);
    repositoryService.ensure("clips", RepositoryType.CI_VIDEOS);

    GcSweepReport quiet =
        executor.execute(census.take(), List.of(screenshotsStub(), videosStub()));
    GcTypeSweepResult shots = typeResult(quiet, RepositoryType.CI_SCREENSHOTS);
    GcTypeSweepResult clips = typeResult(quiet, RepositoryType.CI_VIDEOS);
    assertEquals(CiScreenshotsGcStrategy.NOTE, shots.note());
    assertEquals(CiVideosGcStrategy.NOTE, clips.note());
    assertNull(shots.error());
    assertNull(clips.error());
    assertEquals(List.of(), shots.deleted());
    assertEquals(List.of(), clips.deleted());
    assertEquals(0, quiet.sweep().blobsUnlinked());

    String screenshot = agedBlob(77);
    recordRow("shots", screenshot);
    GcSweepReport refused =
        executor.execute(census.take(), List.of(screenshotsStub(), videosStub()));
    GcTypeSweepResult refusedShots = typeResult(refused, RepositoryType.CI_SCREENSHOTS);
    assertNotNull(refusedShots.error());
    assertTrue(refusedShots.error().contains("stub"), refusedShots.error());
    assertTrue(refusedShots.error().contains("branch"), "the refusal names the rule to implement");
    assertNull(typeResult(refused, RepositoryType.CI_VIDEOS).error(), "videos still has no rows");
    assertTrue(blobStore.exists(screenshot), "fail-closed keeps every blob of the type");
  }

  // --- wiring ----------------------------------------------------------------------------------

  private NpmPackagesGcStrategy npmStrategy() {
    NpmPackagesGcStrategy strategy = new NpmPackagesGcStrategy();
    strategy.repositories = repositories;
    strategy.versions = npmVersions;
    strategy.distTags = npmDistTags;
    strategy.npm = npmRegistry;
    return strategy;
  }

  private OciImageGcStrategy ociStrategy(List<CdDeploymentPins.Deployment> rows) {
    OciImageGcStrategy strategy = new OciImageGcStrategy();
    strategy.repositories = repositories;
    strategy.tags = ociTags;
    strategy.manifests = ociManifests;
    strategy.footprints = footprints;
    strategy.pins = () -> rows;
    strategy.registry = ociRegistry;
    return strategy;
  }

  private CiScreenshotsGcStrategy screenshotsStub() {
    CiScreenshotsGcStrategy strategy = new CiScreenshotsGcStrategy();
    strategy.repositories = repositories;
    strategy.records = records;
    return strategy;
  }

  private CiVideosGcStrategy videosStub() {
    CiVideosGcStrategy strategy = new CiVideosGcStrategy();
    strategy.repositories = repositories;
    strategy.records = records;
    return strategy;
  }

  /**
   * Clears the request-scoped persistence context before asserting on database state.
   *
   * <p>The repo's documented landmine, met head-on: inside a {@code @QuarkusTest} a request context
   * is already active, {@code findOne} resolves through {@code findById}, and a row the sweep
   * deleted (and committed) is still answered from the first-level cache — a lost delete that is a
   * property of the test, not of the service.
   */
  private void detachEntities() {
    npmVersions.getEntityManager().clear();
  }

  /** A stored blob aged past the grace window — the executed cases' default. */
  private String agedBlob(int size) throws IOException {
    String blobId = store(filled(size, (byte) (size % 251)));
    backdate(blobId, Duration.ofDays(30));
    return blobId;
  }

  private String agedStore(byte[] bytes) throws IOException {
    String blobId = store(bytes);
    backdate(blobId, Duration.ofDays(30));
    return blobId;
  }

  private void versionRow(String packageName, String version, String blobId) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              NpmVersion row = new NpmVersion();
              row.repository = "npm";
              row.packageName = packageName;
              row.version = version;
              row.tarballBlobId = blobId;
              row.manifestJson = "{}";
              row.createdAt = Instant.now();
              npmVersions.persist(row);
            });
  }

  private void distTagRow(String packageName, String tag, String version) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              NpmDistTag row = new NpmDistTag();
              row.repository = "npm";
              row.packageName = packageName;
              row.tag = tag;
              row.version = version;
              row.updatedAt = Instant.now();
              npmDistTags.persist(row);
            });
  }

  private void recordRow(String repository, String blobId) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ArtifactRecord row = new ArtifactRecord();
              row.id = java.util.UUID.randomUUID().toString();
              row.repository = repository;
              row.blobId = blobId;
              row.mediatype = "image/png";
              row.size = 77;
              row.createdAt = Instant.now();
              row.metadata = Map.copyOf(screenshotMeta("main", "flow", 1, 1));
              records.persist(row);
            });
  }

  private OciManifest qitsManifest(String digest, long size) {
    OciManifest row = new OciManifest();
    row.repository = "qits";
    row.imageName = "alpha8";
    row.digest = digest;
    row.mediaType = OciMediaTypes.OCI_MANIFEST_V1;
    row.size = size;
    row.createdAt = Instant.now();
    return row;
  }

  private OciTag qitsTag(String name, String digest, Instant updatedAt) {
    OciTag row = new OciTag();
    row.repository = "qits";
    row.imageName = "alpha8";
    row.tag = name;
    row.manifestDigest = digest;
    row.updatedAt = updatedAt;
    return row;
  }

  private static GcTypeSweepResult typeResult(GcSweepReport report, RepositoryType type) {
    return report.types().stream()
        .filter(result -> result.type() == type)
        .findFirst()
        .orElseThrow();
  }

  private static List<String> identities(List<GcIdentity> identities) {
    return identities.stream().map(GcIdentity::identity).toList();
  }
}
