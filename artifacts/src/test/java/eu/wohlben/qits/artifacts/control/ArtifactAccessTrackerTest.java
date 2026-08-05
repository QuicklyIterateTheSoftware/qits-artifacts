package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.artifacts.entity.ArtifactRecord;
import eu.wohlben.qits.artifacts.entity.DaemonBinary;
import eu.wohlben.qits.artifacts.entity.MavenArtifact;
import eu.wohlben.qits.artifacts.entity.NpmVersion;
import eu.wohlben.qits.artifacts.entity.OciManifest;
import eu.wohlben.qits.artifacts.entity.OciTag;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ArtifactAccessTrackerTest extends ArtifactsTestSupport {

  @Inject ArtifactAccessTracker tracker;
  @Inject ArtifactRepositoryService repositoryService;

  private static final Instant FIRST = Instant.parse("2026-01-01T10:00:00Z");

  @Test
  void aContentReadTouchesEveryMatchingRecordButNoOtherRepository() {
    repositoryService.ensure("shots", RepositoryType.CI_SCREENSHOTS);
    repositoryService.ensure("other", RepositoryType.CI_SCREENSHOTS);
    persistRecord("shots", "same");
    persistRecord("shots", "same");
    persistRecord("other", "same");

    tracker.touchArtifact("shots", "same", FIRST);

    assertEquals(2, records.list("repository", "shots").stream()
        .filter(row -> FIRST.equals(row.accessedAt)).count());
    assertNull(records.list("repository", "other").getFirst().accessedAt);
  }

  @Test
  void writesAreCoalescedUntilTheTimestampIsOneHourOld() {
    repositoryService.ensure("shots", RepositoryType.CI_SCREENSHOTS);
    persistRecord("shots", "blob");
    tracker.touchArtifact("shots", "blob", FIRST);
    tracker.touchArtifact("shots", "blob", FIRST.plusSeconds(3599));
    records.getEntityManager().clear();
    assertEquals(FIRST, records.find("blobId", "blob").firstResult().accessedAt);

    tracker.touchArtifact("shots", "blob", FIRST.plusSeconds(3600));
    records.getEntityManager().clear();
    assertEquals(FIRST.plusSeconds(3600), records.find("blobId", "blob").firstResult().accessedAt);
  }

  @Test
  void aTagPullTouchesTagAndManifestWhileADigestPullCanTouchOnlyManifest() {
    repositoryService.ensure("qits", RepositoryType.OCI_IMAGES);
    persistOci();

    tracker.touchManifest("qits", "app", "digest", "latest", FIRST);
    assertEquals(FIRST, ociManifests.findOne("qits", "app", "digest").orElseThrow().accessedAt);
    assertEquals(FIRST, ociTags.findOne("qits", "app", "latest").orElseThrow().accessedAt);

    Instant later = FIRST.plusSeconds(7200);
    tracker.touchManifest("qits", "app", "digest", null, later);
    ociManifests.getEntityManager().clear();
    assertEquals(later, ociManifests.findOne("qits", "app", "digest").orElseThrow().accessedAt);
    assertEquals(FIRST, ociTags.findOne("qits", "app", "latest").orElseThrow().accessedAt);
  }

  @Test
  void anNpmTarballReadTouchesOneVersionInOneRepositoryAndCoalesces() {
    // One table serves both npm types, so the scope assertion is what proves a proxy pull of the
    // same coordinate does not age the hosted row (or the reverse).
    repositoryService.ensure("npm", RepositoryType.NPM_PACKAGES);
    repositoryService.ensure("npmjs", RepositoryType.NPM_PROXY);
    persistNpmVersion("npm", "@qits/ui", "1.0.0");
    persistNpmVersion("npm", "@qits/ui", "1.0.1");
    persistNpmVersion("npmjs", "@qits/ui", "1.0.0");

    tracker.touchNpmVersion("npm", "@qits/ui", "1.0.0", FIRST);
    assertEquals(FIRST, npmVersions.findOne("npm", "@qits/ui", "1.0.0").orElseThrow().accessedAt);
    assertNull(npmVersions.findOne("npm", "@qits/ui", "1.0.1").orElseThrow().accessedAt);
    assertNull(npmVersions.findOne("npmjs", "@qits/ui", "1.0.0").orElseThrow().accessedAt);

    tracker.touchNpmVersion("npm", "@qits/ui", "1.0.0", FIRST.plusSeconds(3599));
    npmVersions.getEntityManager().clear();
    assertEquals(FIRST, npmVersions.findOne("npm", "@qits/ui", "1.0.0").orElseThrow().accessedAt);

    tracker.touchNpmVersion("npm", "@qits/ui", "1.0.0", FIRST.plusSeconds(3600));
    npmVersions.getEntityManager().clear();
    assertEquals(
        FIRST.plusSeconds(3600),
        npmVersions.findOne("npm", "@qits/ui", "1.0.0").orElseThrow().accessedAt);
  }

  @Test
  void aMavenFileReadTouchesOnePathAndCoalesces() {
    repositoryService.ensure("maven", RepositoryType.MAVEN_PACKAGES);
    persistMavenArtifact("maven", "eu/wohlben/qits/lib/1.0.0/lib-1.0.0.jar");
    persistMavenArtifact("maven", "eu/wohlben/qits/lib/1.0.0/lib-1.0.0.pom");

    tracker.touchMavenArtifact("maven", "eu/wohlben/qits/lib/1.0.0/lib-1.0.0.jar", FIRST);
    assertEquals(
        FIRST,
        mavenArtifacts.findOne("maven", "eu/wohlben/qits/lib/1.0.0/lib-1.0.0.jar")
            .orElseThrow().accessedAt);
    assertNull(
        mavenArtifacts.findOne("maven", "eu/wohlben/qits/lib/1.0.0/lib-1.0.0.pom")
            .orElseThrow().accessedAt);

    tracker.touchMavenArtifact(
        "maven", "eu/wohlben/qits/lib/1.0.0/lib-1.0.0.jar", FIRST.plusSeconds(3599));
    mavenArtifacts.getEntityManager().clear();
    assertEquals(
        FIRST,
        mavenArtifacts.findOne("maven", "eu/wohlben/qits/lib/1.0.0/lib-1.0.0.jar")
            .orElseThrow().accessedAt);

    tracker.touchMavenArtifact(
        "maven", "eu/wohlben/qits/lib/1.0.0/lib-1.0.0.jar", FIRST.plusSeconds(3600));
    mavenArtifacts.getEntityManager().clear();
    assertEquals(
        FIRST.plusSeconds(3600),
        mavenArtifacts.findOne("maven", "eu/wohlben/qits/lib/1.0.0/lib-1.0.0.jar")
            .orElseThrow().accessedAt);
  }

  @Test
  void aDaemonDownloadTouchesOneVersionAndCoalesces() {
    repositoryService.ensure("daemons", RepositoryType.DAEMON_BINARIES);
    persistDaemonBinary("daemons", "qits-ci-daemon", "2026.801.120000");
    persistDaemonBinary("daemons", "qits-ci-daemon", "2026.802.120000");

    tracker.touchDaemonBinary("daemons", "qits-ci-daemon", "2026.801.120000", FIRST);
    assertEquals(
        FIRST,
        daemonBinaries.findOne("daemons", "qits-ci-daemon", "2026.801.120000")
            .orElseThrow().accessedAt);
    assertNull(
        daemonBinaries.findOne("daemons", "qits-ci-daemon", "2026.802.120000")
            .orElseThrow().accessedAt);

    tracker.touchDaemonBinary(
        "daemons", "qits-ci-daemon", "2026.801.120000", FIRST.plusSeconds(3599));
    daemonBinaries.getEntityManager().clear();
    assertEquals(
        FIRST,
        daemonBinaries.findOne("daemons", "qits-ci-daemon", "2026.801.120000")
            .orElseThrow().accessedAt);

    tracker.touchDaemonBinary(
        "daemons", "qits-ci-daemon", "2026.801.120000", FIRST.plusSeconds(3600));
    daemonBinaries.getEntityManager().clear();
    assertEquals(
        FIRST.plusSeconds(3600),
        daemonBinaries.findOne("daemons", "qits-ci-daemon", "2026.801.120000")
            .orElseThrow().accessedAt);
  }

  private void persistRecord(String repository, String blob) {
    QuarkusTransaction.requiringNew().run(() -> {
      ArtifactRecord row = new ArtifactRecord();
      row.id = UUID.randomUUID().toString();
      row.repository = repository;
      row.blobId = blob;
      row.mediatype = "image/png";
      row.size = 1;
      row.createdAt = FIRST.minusSeconds(10);
      records.persist(row);
    });
  }

  private void persistNpmVersion(String repository, String packageName, String version) {
    QuarkusTransaction.requiringNew().run(() -> {
      NpmVersion row = new NpmVersion();
      row.repository = repository;
      row.packageName = packageName;
      row.version = version;
      row.tarballBlobId = "a".repeat(64);
      row.manifestJson = "{}";
      row.createdAt = FIRST.minusSeconds(10);
      npmVersions.persist(row);
    });
  }

  private void persistMavenArtifact(String repository, String path) {
    QuarkusTransaction.requiringNew().run(() -> {
      MavenArtifact row = new MavenArtifact();
      row.repository = repository;
      row.path = path;
      row.blobId = "b".repeat(64);
      row.sizeBytes = 1;
      row.createdAt = FIRST.minusSeconds(10);
      mavenArtifacts.persist(row);
    });
  }

  private void persistDaemonBinary(String repository, String name, String version) {
    QuarkusTransaction.requiringNew().run(() -> {
      DaemonBinary row = new DaemonBinary();
      row.repository = repository;
      row.name = name;
      row.version = version;
      row.blobId = "c".repeat(64);
      row.sizeBytes = 1;
      row.publishedAt = FIRST.minusSeconds(10);
      daemonBinaries.persist(row);
    });
  }

  private void persistOci() {
    QuarkusTransaction.requiringNew().run(() -> {
      OciManifest manifest = new OciManifest();
      manifest.repository = "qits";
      manifest.imageName = "app";
      manifest.digest = "digest";
      manifest.mediaType = OciMediaTypes.OCI_MANIFEST_V1;
      manifest.size = 1;
      manifest.createdAt = FIRST.minusSeconds(10);
      ociManifests.persist(manifest);
      OciTag tag = new OciTag();
      tag.repository = "qits";
      tag.imageName = "app";
      tag.tag = "latest";
      tag.manifestDigest = "digest";
      tag.updatedAt = FIRST.minusSeconds(10);
      ociTags.persist(tag);
    });
  }
}
