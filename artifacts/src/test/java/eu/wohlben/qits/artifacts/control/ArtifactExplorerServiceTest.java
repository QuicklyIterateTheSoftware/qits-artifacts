package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.blobstore.control.ArtifactRepositoryService;
import eu.wohlben.qits.blobstore.control.BlobStore;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.dto.DaemonSummary;
import eu.wohlben.qits.artifacts.dto.DaemonVersionSummary;
import eu.wohlben.qits.artifacts.dto.DocsSiteSummary;
import eu.wohlben.qits.artifacts.dto.DocsVersionSummary;
import eu.wohlben.qits.artifacts.dto.ImageSummary;
import eu.wohlben.qits.artifacts.dto.ImageTagSummary;
import eu.wohlben.qits.artifacts.dto.PackageSummary;
import eu.wohlben.qits.artifacts.dto.PackageVersionSummary;
import eu.wohlben.qits.artifacts.dto.RepositorySummary;
import eu.wohlben.qits.artifacts.dto.StoreSummary;
import eu.wohlben.qits.artifacts.entity.DaemonBinary;
import eu.wohlben.qits.artifacts.entity.DocsFile;
import eu.wohlben.qits.artifacts.entity.DocsSite;
import eu.wohlben.qits.artifacts.entity.MavenArtifact;
import eu.wohlben.qits.artifacts.entity.NpmDistTag;
import eu.wohlben.qits.artifacts.entity.NpmVersion;
import eu.wohlben.qits.artifacts.entity.OciManifest;
import eu.wohlben.qits.artifacts.entity.OciTag;
import eu.wohlben.qits.blobstore.error.BadRequestException;
import eu.wohlben.qits.blobstore.error.NotFoundException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The store's read side, and the one thing about it that is easy to get wrong.
 *
 * <p><b>A size on this store is a union, not a sum.</b> Blobs are content-addressed and deduped
 * globally, so the same layer under two tags, two images or two repositories is one file. The
 * fixture below is built so all three levels give different answers over the same content — per tag,
 * per image, and across the store — because a bug that adds where it should merge produces a number
 * that still looks plausible. Here it does not: the expectations are spelled as arithmetic, so a
 * regression names the layer it double-counted.
 */
@QuarkusTest
class ArtifactExplorerServiceTest extends ArtifactsTestSupport {

  @Inject ArtifactExplorerService explorer;
  @Inject ArtifactRepositoryService repositoryService;
  @Inject BlobStore blobStore;

  // Content sizes chosen to be distinguishable in a sum: no two subsets add to the same total.
  private static final int C1 = 10;
  private static final int C2 = 20;
  private static final int L1 = 100;
  private static final int L2 = 200;
  private static final int L3 = 300;
  private static final int ORPHAN = 500;
  // The daemon half: one binary only qits-ci-daemon has, one both daemons were built from.
  private static final int DAEMON_OWN = 700;
  private static final int DAEMON_SHARED = 800;
  // The docs half: the font every bundle ships, and one chunk per version that no other has.
  private static final int FONT = 1300;
  private static final int CHUNK_A = 1100;
  private static final int CHUNK_B = 1200;

  private static final String SITE = "@userflows/qits-artifacts";
  private static final String OTHER_SITE = "@qits/ui-components";

  /** What seedImages() built, so the expectations can be written as arithmetic over it. */
  private record Fixture(long ma1, long ma2, long mb1) {}

  @Test
  void imagesAreEnumeratedFromTheManifestRowsWithTheirTagAndManifestCounts() {
    // There is no image table: an image exists exactly as long as a manifest names it, so this
    // enumeration is a distinct scan and both counts are scans too. `alpha` carries two manifests
    // and two tags; `beta` one of each.
    seedImages();

    List<ImageSummary> images = explorer.listImages("qits");
    assertEquals(List.of("alpha", "beta"), images.stream().map(ImageSummary::name).toList());
    assertEquals(2, images.get(0).tagCount());
    assertEquals(2, images.get(0).manifestCount());
    assertEquals(1, images.get(1).tagCount());
    assertEquals(1, images.get(1).manifestCount());
  }

  @Test
  void aPerTagSizeIsWhatOneManifestReferencesAndIsNotAdditive() {
    // The dishonest number, reported honestly. v1 and v2 share a config and a layer, so adding the
    // two figures counts 110 bytes twice — which is why the image above must not be their sum.
    Fixture fixture = seedImages();

    Map<String, ImageTagSummary> tags = byTag(explorer.listTags("qits", "alpha"));
    assertEquals(fixture.ma1() + C1 + L1 + L2, tags.get("v1").sizeBytes());
    assertEquals(fixture.ma2() + C1 + L1 + L3, tags.get("v2").sizeBytes());
    assertTrue(tags.get("v1").digest().startsWith("sha256:"), "the wire form, not the stored hex");
    assertNotEquals(
        tags.get("v1").sizeBytes() + tags.get("v2").sizeBytes(),
        explorer.listImages("qits").get(0).sizeBytes(),
        "an image is the union of its manifests, never the sum of its tags");
  }

  @Test
  void aPerImageSizeIsTheUnionOverThatImagesManifests() {
    // C1 and L1 appear under both tags and are counted once; L2 and L3 once each.
    Fixture fixture = seedImages();

    List<ImageSummary> images = explorer.listImages("qits");
    assertEquals(fixture.ma1() + fixture.ma2() + C1 + L1 + L2 + L3, images.get(0).sizeBytes());
    assertEquals(fixture.mb1() + C2 + L2, images.get(1).sizeBytes());
  }

  @Test
  void theStoreUnionDedupesAcrossImagesWhereThePerImageSumCannot() {
    // L2 belongs to alpha and to beta. Adding the per-image unions counts it twice — which is the
    // 8% the summary's two OCI figures differ by on the real store, and the reason both are named.
    Fixture fixture = seedImages();

    StoreSummary summary = explorer.storeSummary();
    long manifests = fixture.ma1() + fixture.ma2() + fixture.mb1();
    assertEquals(manifests + C1 + L1 + L2 + L3 + C2 + L2, summary.ociPerImageSumBytes());
    assertEquals(manifests + C1 + L1 + L2 + L3 + C2, summary.ociUnionBytes());
    assertEquals(
        L2,
        summary.ociPerImageSumBytes() - summary.ociUnionBytes(),
        "the gap is exactly the layer the two images share");
  }

  @Test
  void aBlobNoManifestAndNoTarballReferencesIsReportedAsAnOrphan() {
    // The 124 MiB case: bytes uploaded through the OCI blob-upload session that never got a
    // manifest. They are servable, reachable from nothing, and invisible to every view built on the
    // database — so the disk total exceeds everything the rows can account for, by exactly this.
    Fixture fixture = seedImages();
    store(filled(ORPHAN, (byte) 9));

    StoreSummary summary = explorer.storeSummary();
    assertEquals(ORPHAN, summary.orphanBytes());
    assertEquals(summary.ociUnionBytes() + ORPHAN, summary.diskTotalBytes());
  }

  @Test
  void theTenStoreFiguresAccountForEveryByteOnDisk() {
    // The panel's whole claim: disk = both OCI unions + both npm tarball figures + both maven
    // figures + the orphans. The packument total is deliberately outside that sum — those bytes are
    // H2 CLOBs, not files.
    Fixture fixture = seedImages();
    seedNpm();
    seedMaven();
    store(filled(ORPHAN, (byte) 9));

    StoreSummary summary = explorer.storeSummary();
    assertEquals(
        summary.diskTotalBytes(),
        summary.ociUnionBytes()
            + summary.ociMirrorBytes()
            + summary.npmPublishedBytes()
            + summary.npmProxyTarballBytes()
            + summary.mavenPublishedBytes()
            + summary.mavenProxyBytes()
            + summary.orphanBytes());
    assertEquals(40 + 60, summary.npmPublishedBytes());
    assertEquals(80 + 25, summary.mavenPublishedBytes(), "the jar and the pom, sized from the rows");
    // The four cache figures are structurally zero: this service registers no cache type, so no row
    // can carry one. They keep their places in the panel because "nothing cached" is an answer.
    assertEquals(0L, summary.ociMirrorBytes());
    assertEquals(0L, summary.npmProxyTarballBytes());
    assertEquals(0L, summary.npmProxyPackumentBytes());
    assertEquals(0L, summary.mavenProxyBytes());
    assertTrue(fixture.ma1() > 0);
  }

  @Test
  void packagesAndVersionsAreEnumeratedForTheHostedNpmRegistry() {
    seedNpm();

    List<PackageSummary> hosted = explorer.listPackages("npm");
    assertEquals(1, hosted.size());
    assertEquals("@qits/thing", hosted.get(0).name());
    assertEquals(2, hosted.get(0).versionCount());
    assertEquals("1.1.0", hosted.get(0).latest());

    List<PackageVersionSummary> published = explorer.listVersions("npm", "@qits/thing");
    assertEquals(List.of("1.0.0", "1.1.0"), published.stream().map(PackageVersionSummary::version).toList());
    assertEquals(40L, published.get(0).tarballSizeBytes());
    assertEquals(List.of("main"), published.get(0).distTags());
    assertEquals(List.of("latest"), published.get(1).distTags());
  }

  @Test
  void mavenCoordinatesDrillDownIntoVersionsAndFiles() {
    seedMaven();
    var packages = explorer.listMavenPackages("maven");
    assertEquals(1, packages.size());
    assertEquals("eu.wohlben.qits:qits-eventstream", packages.getFirst().name());
    assertEquals(1, packages.getFirst().versionCount());
    assertEquals(105, packages.getFirst().sizeBytes());
    var published = explorer.listMavenVersions("maven", packages.getFirst().name());
    assertEquals(List.of("1.0.0"), published.stream().map(v -> v.version()).toList());
    assertEquals(2, published.getFirst().files().size());
    assertEquals(105, published.getFirst().sizeBytes());
  }

  @Test
  void daemonsAreEnumeratedFromTheBinaryRowsWithTheirNewestVersion() {
    // There is no daemon table either: a daemon exists exactly as long as a row names it, so this
    // is a distinct scan and the "latest" is the head of a newest-first list rather than a column.
    seedDaemons();

    List<DaemonSummary> daemons = explorer.listDaemons("daemons");
    assertEquals(
        List.of("qits-ci-daemon", "qits-workspace-daemon"),
        daemons.stream().map(DaemonSummary::name).toList(),
        "by name, so the listing is stable without the caller sorting it");
    assertEquals(2, daemons.get(0).versionCount());
    assertEquals("2026.2.0", daemons.get(0).latestVersion(), "newest by published_at, not lexical");
    assertEquals(1, daemons.get(1).versionCount());
    assertEquals("2026.1.0", daemons.get(1).latestVersion());
  }

  @Test
  void aDaemonsSizeIsTheUnionOverItsVersionsAndTheRepositoryDedupesAcrossDaemons() {
    // The dishonest number, reported honestly, on the daemon plane. qits-workspace-daemon was built
    // from bytes identical to qits-ci-daemon's 2026.2.0 — one blob in the store — so adding the two
    // daemons' figures counts DAEMON_SHARED twice and the repository's union must not be their sum.
    seedDaemons();

    List<DaemonSummary> daemons = explorer.listDaemons("daemons");
    assertEquals(DAEMON_OWN + DAEMON_SHARED, daemons.get(0).sizeBytes());
    assertEquals(DAEMON_SHARED, daemons.get(1).sizeBytes());

    long repository = byName(explorer.listRepositories()).get("daemons").sizeBytes();
    assertEquals(DAEMON_OWN + DAEMON_SHARED, repository);
    assertEquals(
        DAEMON_SHARED,
        daemons.get(0).sizeBytes() + daemons.get(1).sizeBytes() - repository,
        "the gap is exactly the binary the two daemons were built from");
  }

  @Test
  void daemonVersionsAreNewestFirstWithTheWireDigestAndANullAccessTime() {
    seedDaemons();

    List<DaemonVersionSummary> versions = explorer.listDaemonVersions("daemons", "qits-ci-daemon");
    assertEquals(
        List.of("2026.2.0", "2026.1.0"),
        versions.stream().map(DaemonVersionSummary::version).toList(),
        "published_at desc, the order DaemonRegistryService.listVersions answers");
    assertTrue(
        versions.get(0).digest().startsWith("sha256:"),
        "the spelling DaemonRoutes echoes as Docker-Content-Digest, not the stored hex");
    assertEquals(DAEMON_SHARED, versions.get(0).sizeBytes(), "the row's own size — a version is one blob");
    assertEquals(DAEMON_OWN, versions.get(1).sizeBytes());
    assertNull(versions.get(0).accessedAt(), "never served since tracking began, not 'read at zero'");
    // An unknown daemon is an empty list rather than a 404: a daemon is not a row.
    assertEquals(List.of(), explorer.listDaemonVersions("daemons", "never-published"));
  }

  @Test
  void docsSitesAreFoldedFromTheVersionRowsWithoutTouchingMetadata() {
    // The same fold DocsRegistryService.listCatalog does over the same query — deliberately not a
    // delegation to it, because the wire catalog carries no size and this one is the reason to ask.
    seedDocs();

    List<DocsSiteSummary> sites = explorer.listDocsSites("docs");
    assertEquals(
        List.of(OTHER_SITE, SITE),
        sites.stream().map(DocsSiteSummary::name).toList(),
        "by name, then newest-first inside each run — the query's single responsibility");
    assertEquals(2, sites.get(1).versionCount());
    assertEquals("1.0.1", sites.get(1).latestVersion());
    assertEquals(1, sites.get(0).versionCount());
  }

  @Test
  void aDocsSitesSizeIsTheUnionOverItsVersionsWhereTheSumDoubleCountsTheFont() {
    // Docs versions share blobs by design: the font is byte-identical across releases and is stored
    // once. Adding the two versions' figures counts FONT twice, which is why a site is their union.
    seedDocs();

    List<DocsVersionSummary> versions = explorer.listDocsVersions("docs", SITE);
    assertEquals(FONT + CHUNK_B, versions.get(0).sizeBytes());
    assertEquals(FONT + CHUNK_A, versions.get(1).sizeBytes());

    DocsSiteSummary site = explorer.listDocsSites("docs").get(1);
    assertEquals(FONT + CHUNK_A + CHUNK_B, site.sizeBytes());
    assertEquals(
        FONT,
        versions.get(0).sizeBytes() + versions.get(1).sizeBytes() - site.sizeBytes(),
        "the gap is exactly the font both versions ship");
    assertNotEquals(
        versions.get(0).sizeBytes() + versions.get(1).sizeBytes(),
        site.sizeBytes(),
        "a site is the union of its versions, never the sum of their published totals");

    // And one level up: @qits/ui-components vendors the same font, so Σ(per site) over-counts it
    // against the repository's own union in exactly the same way.
    long repository = byName(explorer.listRepositories()).get("docs").sizeBytes();
    assertEquals(FONT + CHUNK_A + CHUNK_B, repository);
    assertEquals(
        FONT,
        site.sizeBytes() + explorer.listDocsSites("docs").get(0).sizeBytes() - repository,
        "the gap is the font the two sites both vendor");
  }

  @Test
  void docsVersionsCarryTheirFileCountAndTheMetadataThePublisherRodeInWith() {
    // The reader DocsSite.metadata's laziness was written for: one site's versions, batched. The
    // listing above must not touch it, and this one must.
    seedDocs();

    List<DocsVersionSummary> versions = explorer.listDocsVersions("docs", SITE);
    assertEquals(
        List.of("1.0.1", "1.0.0"),
        versions.stream().map(DocsVersionSummary::version).toList(),
        "published_at desc, the order DocsRegistryService.listVersions answers");
    assertEquals(2, versions.get(0).fileCount(), "paths served, not distinct blobs");
    assertEquals("main", versions.get(0).metadata().get("git.branch.name"));
    assertEquals(Map.of(), versions.get(1).metadata(), "no metadata is an empty map, never null");
    assertNull(versions.get(0).accessedAt(), "never served since tracking began");
    // An unknown site is an empty list rather than a 404: a site is not a row.
    assertEquals(List.of(), explorer.listDocsVersions("docs", "@qits/never-published"));
  }

  @Test
  void aVersionWhoseTarballIsGoneReportsAnUnknownSizeRatherThanZero() {
    // A row can outlive its bytes. Zero would read as an empty tarball; null says the file is not
    // there, which is the only honest answer with no size column to fall back on.
    seedNpm();
    QuarkusTransaction.requiringNew()
        .run(() -> npmVersions.persist(version("npm", "@qits/thing", "2.0.0", "f".repeat(64))));

    PackageVersionSummary orphaned =
        explorer.listVersions("npm", "@qits/thing").stream()
            .filter(v -> v.version().equals("2.0.0"))
            .findFirst()
            .orElseThrow();
    assertNull(orphaned.tarballSizeBytes());
  }

  @Test
  void theRepositoryListCarriesATypeACountAndAUnion() {
    Fixture fixture = seedImages();
    seedNpm();
    seedMaven();

    Map<String, RepositorySummary> byName = byName(explorer.listRepositories());
    assertEquals("oci-images", byName.get("qits").type(), "the kebab wire form, not the stored key");
    assertEquals(2, byName.get("qits").itemCount(), "images, for an oci repository");
    assertEquals(
        fixture.ma1() + fixture.ma2() + fixture.mb1() + C1 + L1 + L2 + L3 + C2,
        byName.get("qits").sizeBytes(),
        "the repository's union, which dedupes L2 across its two images");
    assertEquals(1, byName.get("npm").itemCount(), "packages, for an npm repository");
    assertEquals(100L, byName.get("npm").sizeBytes());
    assertEquals("maven-packages", byName.get("maven").type());
    assertEquals(2, byName.get("maven").itemCount(), "deployed files, for a maven repository");
    assertEquals(80L + 25L, byName.get("maven").sizeBytes(), "the union over distinct blob ids");
  }

  @Test
  void anUnknownRepositoryIsNotFoundAndAWrongTypedOneIsABadRequest() {
    seedNpm();

    assertThrows(NotFoundException.class, () -> explorer.listImages("no-such-repo"));
    assertThrows(NotFoundException.class, () -> explorer.listPackages("no-such-repo"));
    // The distinction a client needs: an npm repository has no images and never will, so an empty
    // list would read as an image registry that lost its images.
    repositoryService.ensure("qits", OciImagesProfile.KEY);
    assertThrows(BadRequestException.class, () -> explorer.listImages("npm"));
    assertThrows(BadRequestException.class, () -> explorer.listPackages("qits"));

    // The daemon and docs surfaces answer the same two ways, and the guards are copies of
    // requireMaven's for that reason: the split is a client-visible contract, not an internal one.
    assertThrows(NotFoundException.class, () -> explorer.listDaemons("no-such-repo"));
    assertThrows(NotFoundException.class, () -> explorer.listDocsSites("no-such-repo"));
    assertThrows(NotFoundException.class, () -> explorer.listDaemonVersions("no-such-repo", "d"));
    assertThrows(NotFoundException.class, () -> explorer.listDocsVersions("no-such-repo", "s"));
    assertThrows(BadRequestException.class, () -> explorer.listDaemons("npm"));
    assertThrows(BadRequestException.class, () -> explorer.listDocsSites("npm"));
  }

  @Test
  void anUnknownImageIsAnEmptyListRatherThanANotFound() {
    // The same answer /v2/<name>/tags/list gives, and for the same reason: an image is not a row.
    seedImages();
    assertEquals(List.of(), explorer.listTags("qits", "never-pushed"));
  }

  // --- fixture -----------------------------------------------------------------------------

  /**
   * Two images over five content blobs, arranged so every level of the union gives a different
   * answer: {@code alpha} shares a config and a layer between its two tags, and shares one layer
   * with {@code beta}.
   */
  private Fixture seedImages() {
    repositoryService.ensure("qits", OciImagesProfile.KEY);
    String config1 = store(filled(C1, (byte) 1));
    String config2 = store(filled(C2, (byte) 2));
    String layer1 = store(filled(L1, (byte) 3));
    String layer2 = store(filled(L2, (byte) 4));
    String layer3 = store(filled(L3, (byte) 5));

    byte[] alphaV1 = imageManifest(config1, C1, Map.of(layer1, (long) L1, layer2, (long) L2));
    byte[] alphaV2 = imageManifest(config1, C1, Map.of(layer1, (long) L1, layer3, (long) L3));
    byte[] betaLatest = imageManifest(config2, C2, Map.of(layer2, (long) L2));
    String ma1 = store(alphaV1);
    String ma2 = store(alphaV2);
    String mb1 = store(betaLatest);

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ociManifests.persist(manifest("qits", "alpha", ma1, alphaV1.length));
              ociManifests.persist(manifest("qits", "alpha", ma2, alphaV2.length));
              ociManifests.persist(manifest("qits", "beta", mb1, betaLatest.length));
              ociTags.persist(tag("qits", "alpha", "v1", ma1));
              ociTags.persist(tag("qits", "alpha", "v2", ma2));
              ociTags.persist(tag("qits", "beta", "latest", mb1));
            });
    return new Fixture(alphaV1.length, alphaV2.length, betaLatest.length);
  }


  /** One deployed release under the hosted maven repository: an 80-byte jar and a 25-byte pom. */
  private void seedMaven() {
    repositoryService.ensure("maven", MavenPackagesProfile.KEY);
    String jar = store(filled(80, (byte) 21));
    String pom = store(filled(25, (byte) 22));

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              mavenArtifacts.persist(
                  mavenArtifact(
                      "eu/wohlben/qits/qits-eventstream/1.0.0/qits-eventstream-1.0.0.jar", jar, 80));
              mavenArtifacts.persist(
                  mavenArtifact(
                      "eu/wohlben/qits/qits-eventstream/1.0.0/qits-eventstream-1.0.0.pom", pom, 25));
            });
  }

  private static MavenArtifact mavenArtifact(String path, String blobId, long size) {
    MavenArtifact row = new MavenArtifact();
    row.repository = "maven";
    row.path = path;
    row.blobId = blobId;
    row.sizeBytes = size;
    row.createdAt = Instant.now();
    return row;
  }

  /** One hosted package with two versions and two dist-tags. */
  private void seedNpm() {
    repositoryService.ensure("npm", NpmPackagesProfile.KEY);
    String t1 = store(filled(40, (byte) 11));
    String t2 = store(filled(60, (byte) 12));

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              npmVersions.persist(version("npm", "@qits/thing", "1.0.0", t1));
              npmVersions.persist(version("npm", "@qits/thing", "1.1.0", t2));
              npmDistTags.persist(distTag("npm", "@qits/thing", "latest", "1.1.0"));
              npmDistTags.persist(distTag("npm", "@qits/thing", "main", "1.0.0"));
            });
  }

  /**
   * Two daemons over two binaries, one of which both were built from.
   *
   * <p>{@code qits-workspace-daemon}'s only release hashes to the same blob as {@code
   * qits-ci-daemon}'s newest — the case a per-daemon sum gets wrong. The published timestamps are
   * deliberately not in version order, so an implementation reading "latest" lexically fails.
   */
  private void seedDaemons() {
    repositoryService.ensure("daemons", DaemonBinariesProfile.KEY);
    String own = store(filled(DAEMON_OWN, (byte) 31));
    String shared = store(filled(DAEMON_SHARED, (byte) 32));
    Instant now = Instant.now();

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              daemonBinaries.persist(
                  daemon("qits-ci-daemon", "2026.1.0", own, DAEMON_OWN, now.minusSeconds(60)));
              daemonBinaries.persist(
                  daemon(
                      "qits-ci-daemon", "2026.2.0", shared, DAEMON_SHARED, now.minusSeconds(30)));
              daemonBinaries.persist(
                  daemon(
                      "qits-workspace-daemon",
                      "2026.1.0",
                      shared,
                      DAEMON_SHARED,
                      now.minusSeconds(90)));
            });
  }

  /**
   * Two sites over three blobs, arranged so every level of the union gives a different answer.
   *
   * <p>{@link #SITE} ships the font in both of its versions and one chunk in each; {@link
   * #OTHER_SITE} vendors the same font. So a version, a site and the repository are three distinct
   * figures over the same content — the docs restatement of what {@code seedImages} does for OCI.
   */
  private void seedDocs() {
    repositoryService.ensure("docs", DocsProfile.KEY);
    String font = store(filled(FONT, (byte) 41));
    String chunkA = store(filled(CHUNK_A, (byte) 42));
    String chunkB = store(filled(CHUNK_B, (byte) 43));
    Instant now = Instant.now();

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              docsSites.persist(
                  site(
                      SITE,
                      "1.0.0",
                      2,
                      FONT + CHUNK_A,
                      now.minusSeconds(60),
                      Map.of()));
              docsFiles.persist(docsFile(SITE, "1.0.0", "sb-assets/font.woff2", font, FONT));
              docsFiles.persist(docsFile(SITE, "1.0.0", "assets/a.js", chunkA, CHUNK_A));
              docsSites.persist(
                  site(
                      SITE,
                      "1.0.1",
                      2,
                      FONT + CHUNK_B,
                      now.minusSeconds(30),
                      Map.of("git.branch.name", "main")));
              docsFiles.persist(docsFile(SITE, "1.0.1", "sb-assets/font.woff2", font, FONT));
              docsFiles.persist(docsFile(SITE, "1.0.1", "assets/b.js", chunkB, CHUNK_B));
              // The same font, vendored by a second site — what makes Σ(per site) over-count.
              docsSites.persist(
                  site(OTHER_SITE, "1.0.0", 1, FONT, now.minusSeconds(10), Map.of()));
              docsFiles.persist(
                  docsFile(OTHER_SITE, "1.0.0", "sb-assets/font.woff2", font, FONT));
            });
  }

  private static DaemonBinary daemon(
      String name, String version, String blobId, long size, Instant publishedAt) {
    DaemonBinary row = new DaemonBinary();
    row.repository = "daemons";
    row.name = name;
    row.version = version;
    row.blobId = blobId;
    row.sizeBytes = size;
    row.publishedAt = publishedAt;
    return row;
  }

  private static DocsSite site(
      String name,
      String version,
      int fileCount,
      long totalBytes,
      Instant publishedAt,
      Map<String, String> metadata) {
    DocsSite row = new DocsSite();
    row.repository = "docs";
    row.name = name;
    row.version = version;
    row.fileCount = fileCount;
    // The bundle as published — the sum this suite proves the explorer does NOT report.
    row.totalBytes = totalBytes;
    row.publishedAt = publishedAt;
    row.metadata.putAll(metadata);
    return row;
  }

  private static DocsFile docsFile(
      String name, String version, String path, String blobId, long size) {
    DocsFile row = new DocsFile();
    row.repository = "docs";
    row.name = name;
    row.version = version;
    row.path = path;
    row.blobId = blobId;
    row.sizeBytes = size;
    row.mediaType = "application/octet-stream";
    return row;
  }

  private String store(byte[] bytes) {
    BlobStore.StagedBlob staged = blobStore.stage(new ByteArrayInputStream(bytes), Long.MAX_VALUE);
    blobStore.promote(staged);
    return staged.sha256();
  }

  /** A real OCI image manifest — the parser reads these bytes, so a stub would prove nothing. */
  private static byte[] imageManifest(String configDigest, long configSize, Map<String, Long> layers) {
    List<String> descriptors = new ArrayList<>();
    layers.forEach(
        (digest, size) ->
            descriptors.add(
                "{\"mediaType\":\"application/vnd.oci.image.layer.v1.tar+gzip\",\"digest\":\"sha256:"
                    + digest
                    + "\",\"size\":"
                    + size
                    + "}"));
    return ("{\"schemaVersion\":2,\"mediaType\":\""
            + OciMediaTypes.OCI_MANIFEST_V1
            + "\",\"config\":{\"mediaType\":\"application/vnd.oci.image.config.v1+json\","
            + "\"digest\":\"sha256:"
            + configDigest
            + "\",\"size\":"
            + configSize
            + "},\"layers\":["
            + String.join(",", descriptors)
            + "]}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] filled(int length, byte value) {
    byte[] bytes = new byte[length];
    Arrays.fill(bytes, value);
    return bytes;
  }

  private static OciManifest manifest(String repository, String image, String digest, long size) {
    OciManifest row = new OciManifest();
    row.repository = repository;
    row.imageName = image;
    row.digest = digest;
    row.mediaType = OciMediaTypes.OCI_MANIFEST_V1;
    row.size = size;
    row.createdAt = Instant.now();
    return row;
  }

  private static OciTag tag(String repository, String image, String name, String digest) {
    OciTag row = new OciTag();
    row.repository = repository;
    row.imageName = image;
    row.tag = name;
    row.manifestDigest = digest;
    row.updatedAt = Instant.now();
    return row;
  }

  private static NpmVersion version(
      String repository, String packageName, String version, String blobId) {
    NpmVersion row = new NpmVersion();
    row.repository = repository;
    row.packageName = packageName;
    row.version = version;
    row.tarballBlobId = blobId;
    row.manifestJson = "{}";
    row.createdAt = Instant.now();
    return row;
  }

  private static NpmDistTag distTag(
      String repository, String packageName, String tag, String version) {
    NpmDistTag row = new NpmDistTag();
    row.repository = repository;
    row.packageName = packageName;
    row.tag = tag;
    row.version = version;
    row.updatedAt = Instant.now();
    return row;
  }

  private static Map<String, ImageTagSummary> byTag(List<ImageTagSummary> tags) {
    return tags.stream().collect(java.util.stream.Collectors.toMap(ImageTagSummary::tag, t -> t));
  }

  private static Map<String, RepositorySummary> byName(List<RepositorySummary> repositories) {
    return repositories.stream()
        .collect(java.util.stream.Collectors.toMap(RepositorySummary::name, r -> r));
  }
}
