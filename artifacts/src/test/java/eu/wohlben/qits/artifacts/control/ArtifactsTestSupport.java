package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.persistence.ArtifactRecordRepository;
import eu.wohlben.qits.artifacts.persistence.ArtifactRepositoryRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;

/** Wipes the on-disk blobs and both tables before each test so every case starts empty. */
abstract class ArtifactsTestSupport {

  @Inject ArtifactRecordRepository records;

  @Inject ArtifactRepositoryRepository repositories;

  @ConfigProperty(name = "qits.artifacts.blobs-dir")
  String blobsDir;

  @BeforeEach
  void reset() throws IOException {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              records.deleteAll();
              repositories.deleteAll();
            });
    Path dir = Path.of(blobsDir);
    if (Files.exists(dir)) {
      try (var walk = Files.walk(dir)) {
        walk.sorted(Comparator.reverseOrder()).forEach(ArtifactsTestSupport::deleteQuietly);
      }
    }
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
