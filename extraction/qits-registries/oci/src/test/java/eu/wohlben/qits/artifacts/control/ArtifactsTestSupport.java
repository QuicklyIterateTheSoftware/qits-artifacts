package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.persistence.ArtifactRecordRepository;
import eu.wohlben.qits.artifacts.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.artifacts.persistence.OciManifestRepository;
import eu.wohlben.qits.artifacts.persistence.OciMirrorTagCheckRepository;
import eu.wohlben.qits.artifacts.persistence.OciMirrorUpstreamRepository;
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
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;

/**
 * Wipes the on-disk blobs and this module's tables before each test so every case starts empty. One
 * copy per module — see the npm module's twin for why it is not shared.
 */
abstract class ArtifactsTestSupport {

  @Inject ArtifactRecordRepository records;

  @Inject ArtifactRepositoryRepository repositories;

  @Inject OciManifestRepository ociManifests;

  @Inject OciTagRepository ociTags;

  @Inject OciMirrorTagCheckRepository ociMirrorTagChecks;

  @Inject OciMirrorUpstreamRepository mirrorUpstreams;

  @Inject BlobDiskIndex diskIndex;

  @ConfigProperty(name = "qits.artifacts.blobs-dir")
  String blobsDir;

  @BeforeEach
  void reset() throws IOException {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ociTags.deleteAll();
              ociManifests.deleteAll();
              ociMirrorTagChecks.deleteAll();
              records.deleteAll();
              // The mirror upstreams too: their slug is a foreign key into artifact_repository, so
              // the pairing that makes a namespace resolvable is also what makes the wipe ordered.
              mirrorUpstreams.deleteAll();
              repositories.deleteAll();
            });
    Path dir = Path.of(blobsDir);
    if (Files.exists(dir)) {
      try (var walk = Files.walk(dir)) {
        walk.sorted(Comparator.reverseOrder()).forEach(ArtifactsTestSupport::deleteQuietly);
      }
    }
    diskIndex.invalidate();
  }

  /** Ages a blob file past the sweep's grace window. */
  void backdate(String blobId, Duration age) throws IOException {
    Path path = Path.of(blobsDir, blobId.substring(0, 2), blobId);
    Files.setLastModifiedTime(path, FileTime.from(Instant.now().minus(age)));
  }

  private static void deleteQuietly(Path p) {
    try {
      Files.deleteIfExists(p);
    } catch (IOException ignored) {
      // best effort
    }
  }
}
