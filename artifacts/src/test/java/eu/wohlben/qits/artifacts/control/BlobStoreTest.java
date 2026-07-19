package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.error.NotFoundException;
import eu.wohlben.qits.artifacts.error.PayloadTooLargeException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

@QuarkusTest
class BlobStoreTest extends ArtifactsTestSupport {

  @Inject BlobStore blobStore;

  @Test
  void stagePromoteAndServeRoundTrip() throws Exception {
    byte[] bytes = TestMedia.png(2, 2, 42);
    BlobStore.StagedBlob staged = blobStore.stage(new ByteArrayInputStream(bytes), 1024);

    assertEquals(sha256(bytes), staged.sha256());
    assertEquals(bytes.length, staged.size());

    assertFalse(blobStore.promote(staged), "fresh content is not a dedupe");
    assertTrue(blobStore.exists(staged.sha256()));
    assertEquals(bytes.length, blobStore.size(staged.sha256()));
    try (InputStream in = blobStore.open(staged.sha256())) {
      assertArrayEquals(bytes, in.readAllBytes());
    }
  }

  @Test
  void identicalBytesDedupeToOneFile() {
    byte[] bytes = TestMedia.png(2, 2, 7);
    assertFalse(blobStore.promote(blobStore.stage(new ByteArrayInputStream(bytes), 1024)));
    assertTrue(
        blobStore.promote(blobStore.stage(new ByteArrayInputStream(bytes), 1024)),
        "second identical upload dedupes");
  }

  @Test
  void capAbortsOversizedStream() {
    byte[] bytes = TestMedia.png(2, 2, 9);
    assertThrows(
        PayloadTooLargeException.class, () -> blobStore.stage(new ByteArrayInputStream(bytes), 4));
  }

  @Test
  void malformedIdIsNotFoundNotATraversal() {
    assertThrows(NotFoundException.class, () -> blobStore.open("../../etc/passwd"));
    assertThrows(NotFoundException.class, () -> blobStore.open("not-a-sha"));
    assertFalse(blobStore.exists("../../etc/passwd"));
  }

  @Test
  void unknownButWellShapedIdIsNotFound() {
    assertThrows(NotFoundException.class, () -> blobStore.open("a".repeat(64)));
  }

  private static String sha256(byte[] bytes) throws IOException {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IOException(e);
    }
  }
}
