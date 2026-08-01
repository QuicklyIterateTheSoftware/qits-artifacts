package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.persistence.ArtifactRecordRepository;
import eu.wohlben.qits.artifacts.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.artifacts.persistence.NpmDistTagRepository;
import eu.wohlben.qits.artifacts.persistence.NpmProxyPackumentRepository;
import eu.wohlben.qits.artifacts.persistence.NpmVersionRepository;
import eu.wohlben.qits.artifacts.persistence.NpmVersionTombstoneRepository;
import eu.wohlben.qits.artifacts.persistence.OciManifestRepository;
import eu.wohlben.qits.artifacts.persistence.OciTagRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;

/** Wipes the on-disk blobs and both tables before each test so every case starts empty. */
abstract class ArtifactsTestSupport {

  @Inject ArtifactRecordRepository records;

  @Inject ArtifactRepositoryRepository repositories;

  @Inject OciManifestRepository ociManifests;

  @Inject OciTagRepository ociTags;

  @Inject NpmVersionRepository npmVersions;

  @Inject NpmDistTagRepository npmDistTags;

  @Inject NpmVersionTombstoneRepository npmVersionTombstones;

  @Inject NpmProxyPackumentRepository npmProxyPackuments;

  @Inject BlobDiskIndex diskIndex;

  @ConfigProperty(name = "qits.artifacts.blobs-dir")
  String blobsDir;

  @BeforeEach
  void reset() throws IOException {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              // The protocol tables first: every one of them carries a foreign key to
              // artifact_repository.
              ociTags.deleteAll();
              ociManifests.deleteAll();
              npmDistTags.deleteAll();
              npmVersions.deleteAll();
              npmVersionTombstones.deleteAll();
              npmProxyPackuments.deleteAll();
              records.deleteAll();
              repositories.deleteAll();
            });
    Path dir = Path.of(blobsDir);
    if (Files.exists(dir)) {
      try (var walk = Files.walk(dir)) {
        walk.sorted(Comparator.reverseOrder()).forEach(ArtifactsTestSupport::deleteQuietly);
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

  /** The full required-key set for a ci-videos upload. */
  static Map<String, String> videoMeta(String branch, String flow) {
    Map<String, String> m = new HashMap<>();
    m.put("git.branch.name", branch);
    m.put("git.commit.hash", "abc123");
    m.put("qits.userflow.name", flow);
    m.put("qits.userflow.hash", "flowhash");
    m.put("qits.display.name", "clip 1");
    m.put("qits.diff.hash", "diffhash");
    m.put("media.resolution.length", "12");
    return m;
  }

  private static void deleteQuietly(Path p) {
    try {
      Files.deleteIfExists(p);
    } catch (IOException ignored) {
      // best effort
    }
  }
}
