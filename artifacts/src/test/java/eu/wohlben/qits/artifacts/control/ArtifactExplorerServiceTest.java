package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.dto.ImageSummary;
import eu.wohlben.qits.artifacts.dto.ImageTagSummary;
import eu.wohlben.qits.artifacts.dto.PackageSummary;
import eu.wohlben.qits.artifacts.dto.PackageVersionSummary;
import eu.wohlben.qits.artifacts.dto.RepositorySummary;
import eu.wohlben.qits.artifacts.dto.StoreSummary;
import eu.wohlben.qits.artifacts.entity.NpmDistTag;
import eu.wohlben.qits.artifacts.entity.NpmProxyPackument;
import eu.wohlben.qits.artifacts.entity.NpmVersion;
import eu.wohlben.qits.artifacts.entity.OciManifest;
import eu.wohlben.qits.artifacts.entity.OciTag;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.error.BadRequestException;
import eu.wohlben.qits.artifacts.error.NotFoundException;
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
  void theEightStoreFiguresAccountForEveryByteOnDisk() {
    // The panel's whole claim: disk = both OCI unions + both npm tarball figures + the orphans. The
    // packument total is deliberately outside that sum — those bytes are H2 CLOBs, not files.
    Fixture fixture = seedImages();
    seedNpm();
    store(filled(ORPHAN, (byte) 9));

    StoreSummary summary = explorer.storeSummary();
    assertEquals(
        summary.diskTotalBytes(),
        summary.ociUnionBytes()
            + summary.ociMirrorBytes()
            + summary.npmPublishedBytes()
            + summary.npmProxyTarballBytes()
            + summary.orphanBytes());
    assertEquals(
        0L,
        summary.ociMirrorBytes(),
        "nothing has been pulled through a mirror here, and zero is the honest figure for that");
    assertEquals(40 + 60, summary.npmPublishedBytes());
    assertEquals(70, summary.npmProxyTarballBytes());
    assertEquals(PACKUMENT_DOC.length(), summary.npmProxyPackumentBytes());
    assertTrue(fixture.ma1() > 0);
  }

  @Test
  void packagesAndVersionsAreEnumeratedForBothNpmTypes() {
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

    // A proxy caches tarballs and documents and stores no dist-tag rows, so it has no latest and no
    // tags on any version. That is a property of the type, not missing data.
    List<PackageSummary> proxied = explorer.listPackages("npmjs");
    assertEquals(List.of("left-pad"), proxied.stream().map(PackageSummary::name).toList());
    assertNull(proxied.get(0).latest());
    assertEquals(List.of(), explorer.listVersions("npmjs", "left-pad").get(0).distTags());
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

    Map<String, RepositorySummary> byName = byName(explorer.listRepositories());
    assertEquals(RepositoryType.OCI_IMAGES, byName.get("qits").type());
    assertEquals(2, byName.get("qits").itemCount(), "images, for an oci repository");
    assertEquals(
        fixture.ma1() + fixture.ma2() + fixture.mb1() + C1 + L1 + L2 + L3 + C2,
        byName.get("qits").sizeBytes(),
        "the repository's union, which dedupes L2 across its two images");
    assertEquals(1, byName.get("npm").itemCount(), "packages, for an npm repository");
    assertEquals(100L, byName.get("npm").sizeBytes());
    assertEquals(1, byName.get("npmjs").itemCount());
  }

  @Test
  void anUnknownRepositoryIsNotFoundAndAWrongTypedOneIsABadRequest() {
    seedNpm();

    assertThrows(NotFoundException.class, () -> explorer.listImages("no-such-repo"));
    assertThrows(NotFoundException.class, () -> explorer.listPackages("no-such-repo"));
    // The distinction a client needs: an npm repository has no images and never will, so an empty
    // list would read as an image registry that lost its images.
    repositoryService.ensure("qits", RepositoryType.OCI_IMAGES);
    assertThrows(BadRequestException.class, () -> explorer.listImages("npm"));
    assertThrows(BadRequestException.class, () -> explorer.listPackages("qits"));
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
    repositoryService.ensure("qits", RepositoryType.OCI_IMAGES);
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

  private static final String PACKUMENT_DOC = "{\"name\":\"left-pad\",\"versions\":{}}";

  /** One hosted package with two versions and two dist-tags, and one proxied package with neither. */
  private void seedNpm() {
    repositoryService.ensure("npm", RepositoryType.NPM_PACKAGES);
    repositoryService.ensure("npmjs", RepositoryType.NPM_PROXY);
    String t1 = store(filled(40, (byte) 11));
    String t2 = store(filled(60, (byte) 12));
    String t3 = store(filled(70, (byte) 13));

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              npmVersions.persist(version("npm", "@qits/thing", "1.0.0", t1));
              npmVersions.persist(version("npm", "@qits/thing", "1.1.0", t2));
              npmVersions.persist(version("npmjs", "left-pad", "1.3.0", t3));
              npmDistTags.persist(distTag("npm", "@qits/thing", "latest", "1.1.0"));
              npmDistTags.persist(distTag("npm", "@qits/thing", "main", "1.0.0"));
              NpmProxyPackument cached = new NpmProxyPackument();
              cached.repository = "npmjs";
              cached.packageName = "left-pad";
              cached.doc = PACKUMENT_DOC;
              cached.fetchedAt = Instant.now();
              npmProxyPackuments.persist(cached);
            });
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
