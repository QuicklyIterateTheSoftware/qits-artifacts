package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.control.ArtifactRepositoryService;
import eu.wohlben.qits.artifacts.control.BlobDiskIndex;
import eu.wohlben.qits.artifacts.control.BlobStore;
import eu.wohlben.qits.artifacts.control.LiveBlobCensus;
import eu.wohlben.qits.artifacts.control.OciMediaTypes;
import eu.wohlben.qits.artifacts.entity.DaemonBinary;
import eu.wohlben.qits.artifacts.entity.MavenArtifact;
import eu.wohlben.qits.artifacts.entity.NpmProxyPackument;
import eu.wohlben.qits.artifacts.entity.NpmVersion;
import eu.wohlben.qits.artifacts.entity.OciManifest;
import eu.wohlben.qits.artifacts.entity.OciMirrorTagCheck;
import eu.wohlben.qits.artifacts.entity.OciTag;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.persistence.ArtifactRecordRepository;
import eu.wohlben.qits.artifacts.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.artifacts.persistence.DaemonBinaryRepository;
import eu.wohlben.qits.artifacts.persistence.MavenArtifactRepository;
import eu.wohlben.qits.artifacts.persistence.NpmDistTagRepository;
import eu.wohlben.qits.artifacts.persistence.NpmProxyPackumentRepository;
import eu.wohlben.qits.artifacts.persistence.NpmVersionRepository;
import eu.wohlben.qits.artifacts.persistence.NpmVersionTombstoneRepository;
import eu.wohlben.qits.artifacts.persistence.OciManifestRepository;
import eu.wohlben.qits.artifacts.persistence.OciMirrorTagCheckRepository;
import eu.wohlben.qits.artifacts.persistence.OciMirrorUpstreamRepository;
import eu.wohlben.qits.artifacts.persistence.OciTagRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;

/**
 * A store small enough to reason about and shaped like the one hazard that matters: content shared
 * across repository types.
 *
 * <p>{@code shared} is one blob that an image layer and an npm tarball both name — the same bytes
 * published twice, which is what content addressing makes of them. Every reconciliation case here is
 * some version of "does that blob survive when only one side lets go", and a fixture where no blob
 * crossed a type could not ask it.
 *
 * <p><b>This module's own base class, and it carries its own wipe rather than extending {@code
 * artifacts}' {@code ArtifactsTestSupport}.</b> That is the repository's standing rule, not an
 * oversight: modules here share no test classpath — the same reason {@code service} has its own
 * {@code ArtifactsTestMedia} — so a shared base would mean publishing a test jar and making a
 * package-private support class public across a jar boundary, to save a wipe and a backdate. The
 * seeding below is what this module actually needs; the two files drift apart honestly rather than
 * coupling four modules' suites to one classpath.
 */
abstract class GcFixture {

  @Inject ArtifactRepositoryService repositoryService;
  @Inject BlobStore blobStore;
  @Inject LiveBlobCensus census;

  @Inject ArtifactRecordRepository records;
  @Inject ArtifactRepositoryRepository repositories;
  @Inject OciManifestRepository ociManifests;
  @Inject OciTagRepository ociTags;
  @Inject NpmVersionRepository npmVersions;
  @Inject NpmDistTagRepository npmDistTags;
  @Inject NpmVersionTombstoneRepository npmVersionTombstones;
  @Inject NpmProxyPackumentRepository npmProxyPackuments;
  @Inject MavenArtifactRepository mavenArtifacts;
  @Inject DaemonBinaryRepository daemonBinaries;
  @Inject OciMirrorUpstreamRepository mirrorUpstreams;
  @Inject OciMirrorTagCheckRepository mirrorTagChecks;
  @Inject BlobDiskIndex diskIndex;

  @ConfigProperty(name = "qits.artifacts.blobs-dir")
  String blobsDir;

  /** Wipes the on-disk blobs and every table before each test so every case starts empty. */
  @BeforeEach
  void reset() throws IOException {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              // The protocol tables first: every one of them carries a foreign key to
              // artifact_repository.
              mirrorTagChecks.deleteAll();
              ociTags.deleteAll();
              ociManifests.deleteAll();
              npmDistTags.deleteAll();
              npmVersions.deleteAll();
              npmVersionTombstones.deleteAll();
              npmProxyPackuments.deleteAll();
              mavenArtifacts.deleteAll();
              daemonBinaries.deleteAll();
              records.deleteAll();
              // The mirror upstreams too: their slug is a foreign key into artifact_repository, so
              // the pairing that makes a namespace resolvable is also what makes the wipe ordered.
              mirrorUpstreams.deleteAll();
              repositories.deleteAll();
            });
    Path dir = Path.of(blobsDir);
    if (Files.exists(dir)) {
      try (var walk = Files.walk(dir)) {
        walk.sorted(Comparator.reverseOrder()).forEach(GcFixture::deleteQuietly);
      }
    }
    // The disk index is invalidated by BlobStore.promote, which is every write the service makes —
    // but this wipes the directory from outside it, which is exactly the out-of-band change its age
    // ceiling exists for. Saying so here rather than waiting a minute for it.
    diskIndex.invalidate();
  }

  /**
   * Ages a blob file past the sweep's grace window.
   *
   * <p>The window is read off the file's mtime, and a test's blobs are always seconds old — so
   * without this every GC case would assert on what was withheld rather than on the reconciliation.
   * Backdating is also the honest way round: it exercises the same clock comparison production runs
   * instead of configuring the window away.
   */
  void backdate(String blobId, Duration age) throws IOException {
    Path path = Path.of(blobsDir, blobId.substring(0, 2), blobId);
    Files.setLastModifiedTime(path, FileTime.from(Instant.now().minus(age)));
  }

  /** The full required-key set for a ci-screenshots upload of the given dimensions. */
  static Map<String, String> screenshotMeta(String branch, String flow, int width, int height) {
    Map<String, String> m = new HashMap<>();
    m.put("git.branch.name", branch);
    m.put("git.commit.hash", "abc123");
    m.put("qits.userflow.name", flow);
    m.put("qits.userflow.hash", "flowhash");
    m.put("qits.display.name", "step 1");
    m.put("qits.diff.hash", "diffhash");
    m.put("media.resolution.width", Integer.toString(width));
    m.put("media.resolution.height", Integer.toString(height));
    return m;
  }

  private static void deleteQuietly(Path p) {
    try {
      Files.deleteIfExists(p);
    } catch (IOException ignored) {
      // best effort
    }
  }

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
    repositoryService.ensure("qits", RepositoryType.OCI_IMAGES);
    repositoryService.ensure("npm", RepositoryType.NPM_PACKAGES);

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

  /** What {@link #seedMirror()} built. */
  record MirrorStore(
      String config, String layer, String child, String index, String absentChild) {}

  static final int MIRROR_CONFIG = 11;
  static final int MIRROR_LAYER = 700;
  static final int ABSENT_CHILD = 900;

  /**
   * One cached multi-arch image in a mirror namespace, <b>with one child that was never fetched</b>.
   *
   * <p>That missing child is the fixture's whole point. A push arrives children-first — the registry
   * refuses an index whose children it does not have — but a <em>pull</em> arrives index-first, and
   * the mirror binds it immediately and fetches children lazily, each on its own miss, so it never
   * pays an upstream for an architecture nobody pulled. A mirror index referencing a child with no
   * local row is therefore the normal state of a partially-pulled image, not a corruption, and every
   * reader that walks manifests has to survive it.
   */
  MirrorStore seedMirror() throws IOException {
    repositoryService.ensure(MIRROR_REPO, RepositoryType.OCI_MIRROR);

    String config = store(filled(MIRROR_CONFIG, (byte) 6));
    String layer = store(filled(MIRROR_LAYER, (byte) 7));
    byte[] childBytes = imageManifest(config, Map.of(layer, (long) MIRROR_LAYER), MIRROR_CONFIG);
    String child = store(childBytes);
    // Never stored and never rowed: the architecture nobody pulled.
    String absentChild = "a".repeat(64);
    byte[] indexBytes =
        indexManifest(Map.of(child, (long) childBytes.length, absentChild, (long) ABSENT_CHILD));
    String index = store(indexBytes);

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ociManifests.persist(
                  mirrorManifest(child, childBytes.length, OciMediaTypes.OCI_MANIFEST_V1));
              ociManifests.persist(
                  mirrorManifest(index, indexBytes.length, OciMediaTypes.OCI_INDEX_V1));
              ociTags.persist(mirrorTag("jdk-25", index));
            });

    for (String blobId : List.of(config, layer, child, index)) {
      backdate(blobId, Duration.ofDays(30));
    }
    return new MirrorStore(config, layer, child, index, absentChild);
  }

  static final String MIRROR_REPO = "quay";
  static final String MIRROR_IMAGE = "quarkus/ubi9-quarkus-mandrel-builder-image";

  /**
   * Ages every mirror row — tags and manifests, both timestamps each — so the cache window bites.
   *
   * <p>The blob <em>files</em> are already backdated by {@link #seedMirror()}; this is the other
   * half, and the two are deliberately separate. A file's mtime drives the grace window, a row's
   * timestamps drive the eviction rule, and a case that could not move one without the other could
   * not tell the two mechanisms apart.
   */
  void ageMirrorRows(Duration age) {
    Instant at = Instant.now().minus(age);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ociTags.update(
                  "updatedAt = ?1, accessedAt = ?1 where repository = ?2", at, MIRROR_REPO);
              ociManifests.update(
                  "createdAt = ?1, accessedAt = ?1 where repository = ?2", at, MIRROR_REPO);
            });
  }

  /** Moves one mirrored tag's {@code accessed_at}, as a pull through the mirror would. */
  void touchMirrorTag(String tag, Instant at) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                ociTags.update(
                    "accessedAt = ?1 where repository = ?2 and imageName = ?3 and tag = ?4",
                    at, MIRROR_REPO, MIRROR_IMAGE, tag));
  }

  /** Moves one mirrored manifest's {@code accessed_at}, as a digest-addressed pull would. */
  void touchMirrorManifest(String digest, Instant at) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                ociManifests.update(
                    "accessedAt = ?1 where repository = ?2 and imageName = ?3 and digest = ?4",
                    at, MIRROR_REPO, MIRROR_IMAGE, digest));
  }

  /** The mirror's freshness row for a tag — what the miss path writes, and what eviction must clear. */
  void mirrorTagCheck(String tag, Instant checkedAt) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              OciMirrorTagCheck check = new OciMirrorTagCheck();
              check.repository = MIRROR_REPO;
              check.imageName = MIRROR_IMAGE;
              check.tag = tag;
              check.checkedAt = checkedAt;
              mirrorTagChecks.persist(check);
            });
  }

  static final String PROXY_REPO = "npmjs";
  static final int PROXY_COLD_TARBALL = 70;
  static final int PROXY_WARM_TARBALL = 80;

  /** What {@link #seedProxy()} built. */
  record ProxyStore(String coldTarball, String warmTarball) {}

  static final String PROXY_COLD_PACKAGE = "left-pad";
  static final String PROXY_WARM_PACKAGE = "chalk";

  /**
   * Two proxied packages, one cold and one warm, each with its cached packument.
   *
   * <p>The warm one is the case the staleness rule exists for: its <b>document</b> was last
   * revalidated upstream long ago, while its tarball was pulled yesterday. A packument judged on
   * {@code fetched_at} alone would evict the document of a package something is actively
   * installing, and the next install would pay upstream for it again.
   */
  ProxyStore seedProxy() throws IOException {
    repositoryService.ensure(PROXY_REPO, RepositoryType.NPM_PROXY);
    String cold = store(filled(PROXY_COLD_TARBALL, (byte) 11));
    String warm = store(filled(PROXY_WARM_TARBALL, (byte) 12));
    Instant longAgo = Instant.now().minus(Duration.ofDays(200));
    Instant yesterday = Instant.now().minus(Duration.ofDays(1));
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              npmVersions.persist(
                  proxiedVersion(PROXY_COLD_PACKAGE, "1.3.0", cold, longAgo, longAgo));
              npmVersions.persist(
                  proxiedVersion(PROXY_WARM_PACKAGE, "5.3.0", warm, longAgo, yesterday));
              npmProxyPackuments.persist(cachedPackument(PROXY_COLD_PACKAGE, longAgo));
              npmProxyPackuments.persist(cachedPackument(PROXY_WARM_PACKAGE, longAgo));
            });
    for (String blobId : List.of(cold, warm)) {
      backdate(blobId, Duration.ofDays(30));
    }
    return new ProxyStore(cold, warm);
  }

  private static NpmVersion proxiedVersion(
      String packageName, String version, String blobId, Instant createdAt, Instant accessedAt) {
    NpmVersion row = new NpmVersion();
    row.repository = PROXY_REPO;
    row.packageName = packageName;
    row.version = version;
    row.tarballBlobId = blobId;
    row.manifestJson = "{}";
    row.createdAt = createdAt;
    row.accessedAt = accessedAt;
    return row;
  }

  private static NpmProxyPackument cachedPackument(String packageName, Instant fetchedAt) {
    NpmProxyPackument row = new NpmProxyPackument();
    row.repository = PROXY_REPO;
    row.packageName = packageName;
    row.doc = "{\"name\":\"" + packageName + "\",\"versions\":{}}";
    row.etag = "\"seed\"";
    row.fetchedAt = fetchedAt;
    return row;
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
    repositoryService.ensure(MAVEN_REPO, RepositoryType.MAVEN_PACKAGES);
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

  static final String DAEMON_REPO = "daemons";

  /**
   * One published daemon version, with both of V11's timestamps under the case's control.
   *
   * <p>{@code accessedAt} may be null, which is what a version nothing has downloaded since tracking
   * began really carries — the adapter folds {@code published_at} in as the first access, and a
   * fixture that always set both could not show that.
   */
  void daemonRow(String name, String version, String blobId, Instant publishedAt, Instant accessedAt) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              DaemonBinary row = new DaemonBinary();
              row.repository = DAEMON_REPO;
              row.name = name;
              row.version = version;
              row.blobId = blobId;
              row.sizeBytes = 1;
              row.publishedAt = publishedAt;
              row.accessedAt = accessedAt;
              daemonBinaries.persist(row);
            });
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

  private static OciManifest mirrorManifest(String digest, long size, String mediaType) {
    OciManifest row = new OciManifest();
    row.repository = MIRROR_REPO;
    row.imageName = MIRROR_IMAGE;
    row.digest = digest;
    row.mediaType = mediaType;
    row.size = size;
    row.createdAt = Instant.now();
    return row;
  }

  private static OciTag mirrorTag(String name, String digest) {
    OciTag row = new OciTag();
    row.repository = MIRROR_REPO;
    row.imageName = MIRROR_IMAGE;
    row.tag = name;
    row.manifestDigest = digest;
    row.updatedAt = Instant.now();
    return row;
  }

  /** A real OCI index — the children are manifests, not blobs, which is what makes the walk recurse. */
  static byte[] indexManifest(Map<String, Long> children) {
    List<String> descriptors = new ArrayList<>();
    children.forEach(
        (digest, size) ->
            descriptors.add(
                "{\"mediaType\":\""
                    + OciMediaTypes.OCI_MANIFEST_V1
                    + "\",\"digest\":\"sha256:"
                    + digest
                    + "\",\"size\":"
                    + size
                    + "}"));
    return ("{\"schemaVersion\":2,\"mediaType\":\""
            + OciMediaTypes.OCI_INDEX_V1
            + "\",\"manifests\":["
            + String.join(",", descriptors)
            + "]}")
        .getBytes(StandardCharsets.UTF_8);
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
