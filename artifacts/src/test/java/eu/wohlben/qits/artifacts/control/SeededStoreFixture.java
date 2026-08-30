package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.entity.MavenArtifact;
import eu.wohlben.qits.artifacts.entity.NpmVersion;
import eu.wohlben.qits.artifacts.entity.OciManifest;
import eu.wohlben.qits.artifacts.entity.OciTag;
import eu.wohlben.qits.blobstore.control.ArtifactRepositoryService;
import eu.wohlben.qits.blobstore.control.BlobStore;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * A store small enough to reason about and shaped like the one hazard that matters: content shared
 * across repository types.
 *
 * <p>{@code shared} is one blob that an image layer and an npm tarball both name — the same bytes
 * published twice, which is what content addressing makes of them. Every question this store is
 * asked is some version of "does that blob survive when only one side lets go", and a fixture where
 * no blob crossed a type could not ask it.
 *
 * <p><b>The {@code gc} module carries its own copy of this seeding</b> ({@code
 * eu.wohlben.qits.artifacts.gc.GcFixture}), and the copy is deliberate rather than an oversight:
 * modules here share no test classpath — the same rule that keeps {@code ArtifactsTestSupport} out
 * of {@code service}, which has its own {@code ArtifactsTestMedia}. Sharing would mean publishing a
 * test jar and widening a package-private support class across a jar boundary, which couples three
 * modules' suites to one classpath to save a seeding method. Either copy may grow the cases its own
 * module needs; neither is the other's contract.
 */
abstract class SeededStoreFixture extends ArtifactsTestSupport {

  @Inject ArtifactRepositoryService repositoryService;
  @Inject BlobStore blobStore;
  @Inject LiveBlobCensus census;

  static final int CONFIG = 10;
  static final int LAYER_KEPT = 100;
  static final int LAYER_DOOMED = 300;
  static final int SHARED = 200;
  static final int TARBALL = 40;
  static final int ROWLESS = 500;

  /**
   * What the seeding built. Digests, not sizes: the cases are about which blob survives, and the
   * arithmetic is spelled from the constants above.
   */
  record Store(
      String config,
      String layerKept,
      String layerDoomed,
      String shared,
      String tarball,
      String rowless,
      String manifestKept,
      String manifestDoomed) {}

  /**
   * Two manifests under one image, one npm package, and one blob nothing names.
   *
   * <p>Every file is backdated past the grace window except {@link #rowless}, so a case that means
   * to test the window has to make its own young blob and say so.
   */
  Store seed() throws IOException {
    repositoryService.ensure("qits", OciImagesProfile.KEY);
    repositoryService.ensure("npm", NpmPackagesProfile.KEY);

    String config = store(filled(CONFIG, (byte) 1));
    String layerKept = store(filled(LAYER_KEPT, (byte) 2));
    String layerDoomed = store(filled(LAYER_DOOMED, (byte) 3));
    String shared = store(filled(SHARED, (byte) 4));
    String tarball = store(filled(TARBALL, (byte) 5));
    String rowless = store(filled(ROWLESS, (byte) 9));

    byte[] kept = imageManifest(config, Map.of(layerKept, (long) LAYER_KEPT));
    byte[] doomed =
        imageManifest(config, Map.of(layerDoomed, (long) LAYER_DOOMED, shared, (long) SHARED));
    String manifestKept = store(kept);
    String manifestDoomed = store(doomed);

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ociManifests.persist(manifest("alpha", manifestKept, kept.length));
              ociManifests.persist(manifest("alpha", manifestDoomed, doomed.length));
              ociTags.persist(tag("alpha", "v1", manifestKept));
              ociTags.persist(tag("alpha", "v2", manifestDoomed));
              // The cross-type case: the npm registry serves the same bytes as an image layer.
              npmVersions.persist(version("@qits/thing", "1.0.0", tarball));
              npmVersions.persist(version("@qits/thing", "1.1.0", shared));
            });

    for (String blobId :
        List.of(config, layerKept, layerDoomed, shared, tarball, manifestKept, manifestDoomed)) {
      backdate(blobId, Duration.ofDays(30));
    }
    return new Store(
        config,
        layerKept,
        layerDoomed,
        shared,
        tarball,
        rowless,
        manifestKept,
        manifestDoomed);
  }

  /** What {@link #seedMaven()} built. */
  record MavenStore(String jar, String pom) {}

  static final String MAVEN_REPO = "maven";
  static final String MAVEN_JAR_PATH = "eu/wohlben/qits/qits-eventstream/1.0.0/qits-eventstream-1.0.0.jar";
  static final String MAVEN_POM_PATH = "eu/wohlben/qits/qits-eventstream/1.0.0/qits-eventstream-1.0.0.pom";
  static final int MAVEN_JAR = 60;
  static final int MAVEN_POM = 30;

  /**
   * One deployed release — a jar and its pom — under the hosted maven repository. The rows are what
   * the maven GC strategy lists as kept identities and what the census attributes to the type; the
   * sizes ride on the rows, which is the whole reason this type needs no disk read.
   */
  MavenStore seedMaven() throws IOException {
    repositoryService.ensure(MAVEN_REPO, MavenPackagesProfile.KEY);
    String jar = store(filled(MAVEN_JAR, (byte) 8));
    String pom = store(filled(MAVEN_POM, (byte) 10));
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              mavenArtifacts.persist(mavenArtifact(MAVEN_JAR_PATH, jar, MAVEN_JAR));
              mavenArtifacts.persist(mavenArtifact(MAVEN_POM_PATH, pom, MAVEN_POM));
            });
    for (String blobId : List.of(jar, pom)) {
      backdate(blobId, Duration.ofDays(30));
    }
    return new MavenStore(jar, pom);
  }

  private static MavenArtifact mavenArtifact(String path, String blobId, long size) {
    MavenArtifact row = new MavenArtifact();
    row.repository = MAVEN_REPO;
    row.path = path;
    row.blobId = blobId;
    row.sizeBytes = size;
    row.createdAt = Instant.now();
    return row;
  }

  String store(byte[] bytes) {
    BlobStore.StagedBlob staged = blobStore.stage(new ByteArrayInputStream(bytes), Long.MAX_VALUE);
    blobStore.promote(staged);
    return staged.sha256();
  }

  static byte[] filled(int length, byte value) {
    byte[] bytes = new byte[length];
    Arrays.fill(bytes, value);
    return bytes;
  }

  /** A real OCI image manifest — the footprint parser reads these bytes, so a stub proves nothing. */
  static byte[] imageManifest(String configDigest, Map<String, Long> layers) {
    return imageManifest(configDigest, layers, CONFIG);
  }

  /** The same, for a fixture whose config blob is not {@link #CONFIG} bytes long. */
  static byte[] imageManifest(String configDigest, Map<String, Long> layers, int configSize) {
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

  private static OciManifest manifest(String image, String digest, long size) {
    OciManifest row = new OciManifest();
    row.repository = "qits";
    row.imageName = image;
    row.digest = digest;
    row.mediaType = OciMediaTypes.OCI_MANIFEST_V1;
    row.size = size;
    row.createdAt = Instant.now();
    return row;
  }

  private static OciTag tag(String image, String name, String digest) {
    OciTag row = new OciTag();
    row.repository = "qits";
    row.imageName = image;
    row.tag = name;
    row.manifestDigest = digest;
    row.updatedAt = Instant.now();
    return row;
  }

  private static NpmVersion version(String packageName, String version, String blobId) {
    NpmVersion row = new NpmVersion();
    row.repository = "npm";
    row.packageName = packageName;
    row.version = version;
    row.tarballBlobId = blobId;
    row.manifestJson = "{}";
    row.createdAt = Instant.now();
    return row;
  }
}
