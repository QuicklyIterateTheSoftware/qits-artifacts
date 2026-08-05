package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.control.LiveBlobCensus;
import eu.wohlben.qits.artifacts.control.OciMediaTypes;
import eu.wohlben.qits.artifacts.entity.OciManifest;
import eu.wohlben.qits.artifacts.entity.OciTag;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import eu.wohlben.qits.artifacts.gc.dto.GcPlanReport;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The own engine over docker's facts, case by case, against real manifests and the real census.
 *
 * <p><b>This suite is the port of {@code OciImageGcStrategyTest}, and the cases carry over one for
 * one</b> — calver, cd's pins, the newest-build belt, the untagged manifests, the grace window —
 * with the one change the settlement made: what condemns a coordinate is no longer "a newer build
 * exists" but "nothing has pulled it inside the window". Every case therefore has to say how old its
 * rows are, and the ones that used to prove a structural kill now prove an <em>access-gated</em>
 * one. The direction of that change is a loosening: {@code aShaTagSomethingStillPulls…} is the case
 * that could not have existed before.
 *
 * <p>The pins are handed in as a value rather than fetched: what is under test is the keep-set, and
 * its whole input is "which shas does cd pin for this image". Which rows those shas came from is
 * cd's rule and is tested in cd's own repository.
 *
 * <p>Tags are written as full 40-hex shas because that is what post-receive pushes and what a
 * deployment row carries; a case using {@code v1} would prove nothing about the classification these
 * facts turn on.
 */
@QuarkusTest
class OciImagesGcAdapterTest extends GcFixture {

  private static final String SHA_A = "a".repeat(40);
  private static final String SHA_B = "b".repeat(40);
  private static final String SHA_C = "c".repeat(40);
  private static final String SHA_D = "d".repeat(40);

  /** The configured window for this type, and the number every case below is aged against. */
  private static final Duration WINDOW = Duration.ofDays(30);

  @Inject OciImageGcStrategy strategy;
  @Inject GcPlanner planner;

  @Test
  void theLastTwoCalverReleasesOfAnImageStayAndTheThirdOneAgesOut() throws Exception {
    // "Releases stay" in its settled docker spelling: last 2, not every calver tag. All three are
    // equally cold, so only the belt separates them — and the two that survive are the two newest
    // by CALVER ORDER, not by the order the rows were written or last touched.
    repository();
    String config = config();
    tag("qits-stt", "2026.801.85448", image("qits-stt", config, 101), daysAgo(400));
    tag("qits-stt", "2026.1201.5", image("qits-stt", config, 102), daysAgo(300));
    tag("qits-stt", "2026.802.10", image("qits-stt", config, 103), daysAgo(200));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of("qits-stt:2026.801.85448"), identities(plan.dead()));
    assertEquals(OwnArtifactsStrategy.deadUnaccessed(WINDOW), plan.dead().get(0).rule());
    assertEquals(
        OwnArtifactsStrategy.KEPT_RELEASE, ruleFor(plan.kept(), "qits-stt:2026.1201.5"),
        "1201 is a later month than 802, which a lexical comparison gets backwards");
    assertEquals(OwnArtifactsStrategy.KEPT_RELEASE, ruleFor(plan.kept(), "qits-stt:2026.802.10"));
  }

  @Test
  void aCalverReleaseIsKeptWhenNothingDeploysItAndItIsNotTheNewestBuild() throws Exception {
    // The release coordinate in docker sits BESIDE the sha tag rather than replacing it, so a
    // release nobody runs is still a release. The cold sha beside it is what the window condemns.
    repository();
    String config = config();
    String release = image("qits-stt", config, 111);
    tag("qits-stt", "2026.801.85448", release, daysAgo(200));
    tag("qits-stt", SHA_B, image("qits-stt", config, 112), daysAgo(60));
    tag("qits-stt", SHA_C, image("qits-stt", config, 113), daysAgo(50));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        OwnArtifactsStrategy.KEPT_RELEASE, ruleFor(plan.kept(), "qits-stt:2026.801.85448"));
    assertEquals(OciImagesGcAdapter.KEPT_NEWEST, ruleFor(plan.kept(), "qits-stt:" + SHA_C));
    assertEquals(
        OwnArtifactsStrategy.deadUnaccessed(WINDOW), ruleFor(plan.dead(), "qits-stt:" + SHA_B));
    assertTrue(
        plan.blobsRetained().contains(release),
        "the release manifest survives on its version tag alone");
  }

  @Test
  void anActiveDeploymentPinKeepsItsShaEvenWhenAYoungerBuildExists() throws Exception {
    // The IMAGE_MISSING hazard, priced: the container was created from qits/<app>:<sha> and a
    // restart pulls that reference again, however long it has been running untouched. The pinned tag
    // is deliberately neither the newest nor recently pulled, so only the pin can save it.
    repository();
    String config = config();
    tag("qits-artifacts", SHA_A, image("qits-artifacts", config, 201), daysAgo(300));
    tag("qits-artifacts", SHA_B, image("qits-artifacts", config, 202), daysAgo(200));
    tag("qits-artifacts", SHA_C, image("qits-artifacts", config, 203), daysAgo(100));

    GcStrategy.Plan plan = strategy.plan(census.take(), pinning("qits-artifacts", SHA_A));

    assertEquals(GcPins.BY_CD, ruleFor(plan.kept(), "qits-artifacts:" + SHA_A));
    assertEquals(OciImagesGcAdapter.KEPT_NEWEST, ruleFor(plan.kept(), "qits-artifacts:" + SHA_C));
    assertEquals(
        OwnArtifactsStrategy.deadUnaccessed(WINDOW),
        ruleFor(plan.dead(), "qits-artifacts:" + SHA_B));
  }

  @Test
  void everyShaCdPinsIsKeptUnderOneRuleAndAnythingElseIsNot() throws Exception {
    // cd answers with a SET of shas per application — what serves and what a rollback restores,
    // unioned over every environment — so this type keeps all of them under one rule and derives
    // nothing. The third sha is one cd did not name, and it dies: applying cd's rule again here
    // would be the drift the pin port exists to remove.
    repository();
    String config = config();
    tag("qits-cd", SHA_A, image("qits-cd", config, 301), daysAgo(400));
    tag("qits-cd", SHA_B, image("qits-cd", config, 302), daysAgo(300));
    tag("qits-cd", SHA_C, image("qits-cd", config, 303), daysAgo(200));
    tag("qits-cd", SHA_D, image("qits-cd", config, 304), daysAgo(100));

    GcStrategy.Plan plan = strategy.plan(census.take(), pinning("qits-cd", SHA_A, SHA_B));

    assertEquals(GcPins.BY_CD, ruleFor(plan.kept(), "qits-cd:" + SHA_A));
    assertEquals(GcPins.BY_CD, ruleFor(plan.kept(), "qits-cd:" + SHA_B));
    assertEquals(
        OwnArtifactsStrategy.deadUnaccessed(WINDOW), ruleFor(plan.dead(), "qits-cd:" + SHA_C));
    assertEquals(OciImagesGcAdapter.KEPT_NEWEST, ruleFor(plan.kept(), "qits-cd:" + SHA_D));
  }

  @Test
  void anImageNoDeploymentEverNamedKeepsItsNewestBuildAndDropsTheColdOnes() throws Exception {
    // qits-spa-home's shape, measured: an image with tags and not a single deployment row, every
    // tag older than the window. Without the newest-build belt the whole image would be eligible and
    // the next deploy would pull a tag this run deleted — which is why the belt reads updated_at
    // rather than the access time the window judges on.
    repository();
    String config = config();
    tag("qits-spa-home", SHA_A, image("qits-spa-home", config, 401), daysAgo(300));
    tag("qits-spa-home", SHA_B, image("qits-spa-home", config, 402), daysAgo(200));
    String newest = image("qits-spa-home", config, 403);
    tag("qits-spa-home", SHA_C, newest, daysAgo(100));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(OciImagesGcAdapter.KEPT_NEWEST, ruleFor(plan.kept(), "qits-spa-home:" + SHA_C));
    assertEquals(1, plan.kept().size());
    assertEquals(
        Stream.of("qits-spa-home:" + SHA_A, "qits-spa-home:" + SHA_B).sorted().toList(),
        identities(plan.dead()),
        "the two cold tags; their manifests are still tagged today and are collected next run");
    assertTrue(plan.blobsRetained().contains(newest));
  }

  @Test
  void aShaTagSomethingStillPullsSurvivesTheWindowThatCondemnedItsNeighbour() throws Exception {
    // The loosening the settlement bought, and the case the structural rule could not have had: two
    // superseded build tags, one of them pulled last week. The old rule condemned both the moment a
    // newer build existed; this one keeps whatever is in use and names the rule that saved it.
    repository();
    String config = config();
    String pulled = image("qits-events", config, 411);
    tag("qits-events", SHA_A, pulled, daysAgo(300), daysAgo(7));
    tag("qits-events", SHA_B, image("qits-events", config, 412), daysAgo(300));
    tag("qits-events", SHA_C, image("qits-events", config, 413), daysAgo(100));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        OwnArtifactsStrategy.keptAccessed(WINDOW), ruleFor(plan.kept(), "qits-events:" + SHA_A));
    assertEquals(List.of("qits-events:" + SHA_B), identities(plan.dead()));
    assertTrue(plan.blobsRetained().contains(pulled));
  }

  @Test
  void aManifestNoTagNamesDiesOnceNothingHasPulledItInsideTheWindow() throws Exception {
    // The 73 untagged manifests, in miniature: a tag re-push moves the tag row to the new manifest
    // and leaves the old row behind, reachable from no coordinate anyone uses. It is an identity of
    // its own here — and only here, because a TAGGED manifest's identity is its tag.
    repository();
    String config = config();
    String abandoned = image("qits-events", config, 501, daysAgo(300));
    String current = image("qits-events", config, 502);
    tag("qits-events", SHA_A, current, daysAgo(2));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        List.of("qits-events" + OciImagesGcAdapter.DIGEST_PREFIX + abandoned),
        identities(plan.dead()),
        "only the orphan: the tagged manifest is not an identity of its own");
    assertEquals(OwnArtifactsStrategy.deadUnaccessed(WINDOW), plan.dead().get(0).rule());
    assertTrue(plan.blobsReleased().contains(abandoned));
    assertTrue(plan.blobsRetained().contains(current));
  }

  @Test
  void aChildOfATaggedIndexIsNotACandidateOfItsOwnHoweverColdItIs() throws Exception {
    // Multi-arch, and the rule that keeps a live coordinate whole: the index is what a tag names,
    // the child is reached through the index's closure, and the child's own row carries no access of
    // its own to speak with. Enumerating it would let the window condemn a manifest a live tag
    // reaches, which is a broken pull rather than a collection.
    repository();
    String config = config();
    byte[] childBytes = imageManifest(config, Map.of(layer(601), 601L));
    String child = store(childBytes);
    byte[] indexBytes = indexManifest(Map.of(child, (long) childBytes.length));
    String index = store(indexBytes);
    manifestRow("qits-multi", child, childBytes.length, OciMediaTypes.OCI_MANIFEST_V1, daysAgo(300));
    manifestRow("qits-multi", index, indexBytes.length, OciMediaTypes.OCI_INDEX_V1, daysAgo(300));
    tag("qits-multi", "2026.801.1", index, daysAgo(300));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of(), plan.dead());
    assertEquals(List.of("qits-multi:2026.801.1"), identities(plan.kept()));
    assertTrue(plan.blobsRetained().contains(child), "the child rides on the index's closure");
  }

  @Test
  void anIdentityWhoseBlobIsStillInsideTheGraceWindowIsWithheldWholeRowsIntact() throws Exception {
    // The strand hazard, closed at the identity rather than at the unlink: deleting the tag row over
    // a young file would leave that file row-less — and row-less blobs are untouchable by
    // construction, so it would never be reclaimed at all. The tag waits out the window with it.
    repository();
    String config = config();
    String doomed = image("qits-projects", config, 701);
    tag("qits-projects", SHA_A, doomed, daysAgo(300));
    tag("qits-projects", SHA_B, image("qits-projects", config, 702), daysAgo(100));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());
    GcStrategy.Applied applied = strategy.apply(plan, blobId -> blobId.equals(doomed));

    assertEquals(List.of("qits-projects:" + SHA_A), identities(plan.dead()));
    assertEquals(List.of(), applied.deleted());
    assertEquals(List.of("qits-projects:" + SHA_A), identities(applied.withheldByGraceWindow()));
    assertEquals(List.of(), applied.errors(), "withheld is not an error — it is the window working");
    ociTags.getEntityManager().clear();
    assertTrue(ociTags.findOne("qits", "qits-projects", SHA_A).isPresent(), "the row stays");
  }

  @Test
  void anIncompletePinAggregateIsRefusedRatherThanReadAsNothingIsDeployed() throws Exception {
    // Belt and braces on the run-level abort: the planner never asks a readsPins() strategy to plan
    // against a broken aggregate, and if something ever did, an empty pin map would read as "nothing
    // is deployed" and take the newest-build belt with it. Refusing is the only safe answer.
    repository();
    tag("qits-projects", SHA_A, image("qits-projects", config(), 711), daysAgo(300));

    LiveBlobCensus.Census taken = census.take();
    GcPins broken =
        new GcPins(
            Map.of(),
            "",
            Set.of(),
            Set.of(),
            List.of("qits-cd deployment pins: unreachable at http://qits-cd:8080/cd/api"));

    IllegalStateException aborted =
        assertThrows(IllegalStateException.class, () -> strategy.plan(taken, broken));
    assertTrue(aborted.getMessage().contains("qits-cd"));
    assertTrue(strategy.readsPins(), "and it says so, which is what makes the planner skip it");
  }

  @Test
  void aRepositoryWithNoOciContentPlansNothingRatherThanFailing() throws Exception {
    // The honest answer for rules that exist and match no row: no dead identities, and the type's
    // live set handed back unchanged so the sweep keeps whatever the census found.
    repository();

    LiveBlobCensus.Census taken = census.take();
    GcStrategy.Plan plan = strategy.plan(taken, GcPins.none());

    assertEquals(List.of(), plan.dead());
    assertEquals(List.of(), plan.kept());
    assertEquals(Set.of(), plan.blobsReleased());
    assertEquals(taken.live(RepositoryType.OCI_IMAGES).keySet(), plan.blobsRetained());
  }

  @Test
  void againstTheSubstratesOwnFixtureNothingDiesAndTheRetainedSetIsTheCensusSet() throws Exception {
    // The substrate's store, unchanged: two manifests under one image, tagged v1 and v2, both
    // written moments ago. Neither tag is a calver release, so what keeps them is the window rather
    // than the belt — and the set this type hands back is exactly the census's own OCI live set,
    // which is what makes "one census, two readers" true here rather than merely intended.
    seed();
    LiveBlobCensus.Census taken = census.take();

    GcStrategy.Plan plan = strategy.plan(taken, GcPins.none());

    assertEquals(List.of(), plan.dead());
    assertEquals(List.of("alpha:v1", "alpha:v2"), identities(plan.kept()));
    assertTrue(
        plan.kept().stream()
            .allMatch(kept -> OwnArtifactsStrategy.keptAccessed(WINDOW).equals(kept.rule())));
    assertEquals(taken.live(RepositoryType.OCI_IMAGES).keySet(), plan.blobsRetained());
    GcPlanReport report = planner.plan(taken, List.of(strategy), GcPins.none());
    assertEquals(List.of(), report.sweep().blobIds());
  }

  // --- fixture ---------------------------------------------------------------------------------

  /** The aggregate a run would have read, with one image's shas pinned. */
  private static GcPins pinning(String image, String... shas) {
    return new GcPins(Map.of(image, Set.of(shas)), "", Set.of(), Set.of(), List.of());
  }

  private void repository() {
    repositoryService.ensure("qits", RepositoryType.OCI_IMAGES);
  }

  /** One config blob shared by every manifest in a case, the way a rebuilt image shares its base. */
  private String config() throws IOException {
    return store(filled(CONFIG, (byte) 1));
  }

  /** A distinct content blob of the given size, returned by digest. */
  private String layer(int size) throws IOException {
    return store(filled(size, (byte) (size % 251)));
  }

  private String image(String imageName, String config, int layerSize) throws IOException {
    return image(imageName, config, layerSize, Instant.now());
  }

  /** A real image manifest, stored as bytes and given its {@code oci_manifest} row. */
  private String image(String imageName, String config, int layerSize, Instant createdAt)
      throws IOException {
    byte[] document = imageManifest(config, Map.of(layer(layerSize), (long) layerSize));
    String digest = store(document);
    manifestRow(imageName, digest, document.length, OciMediaTypes.OCI_MANIFEST_V1, createdAt);
    return digest;
  }

  private void manifestRow(
      String imageName, String digest, long size, String mediaType, Instant createdAt) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              OciManifest row = new OciManifest();
              row.repository = "qits";
              row.imageName = imageName;
              row.digest = digest;
              row.mediaType = mediaType;
              row.size = size;
              row.createdAt = createdAt;
              ociManifests.persist(row);
            });
  }

  private void tag(String imageName, String name, String digest, Instant updatedAt) {
    tag(imageName, name, digest, updatedAt, null);
  }

  /** A tag row with both of V9's timestamps under the case's control. */
  private void tag(
      String imageName, String name, String digest, Instant updatedAt, Instant accessedAt) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              OciTag row = new OciTag();
              row.repository = "qits";
              row.imageName = imageName;
              row.tag = name;
              row.manifestDigest = digest;
              row.updatedAt = updatedAt;
              row.accessedAt = accessedAt;
              ociTags.persist(row);
            });
  }

  private static Instant daysAgo(int days) {
    return Instant.now().minus(Duration.ofDays(days));
  }

  private static List<String> identities(List<GcIdentity> identities) {
    return identities.stream().map(GcIdentity::identity).toList();
  }

  private static String ruleFor(List<GcIdentity> identities, String identity) {
    return identities.stream()
        .filter(candidate -> candidate.identity().equals(identity))
        .map(GcIdentity::rule)
        .findFirst()
        .orElseThrow(() -> new AssertionError(identity + " is in neither list: " + identities));
  }
}
