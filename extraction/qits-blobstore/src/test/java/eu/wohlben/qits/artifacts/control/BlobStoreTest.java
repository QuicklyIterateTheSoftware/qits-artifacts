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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
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
  void appendingInChunksGivesTheSameDigestAsOneShot() {
    // The OCI upload session spans three HTTP requests, so the registry cannot use stage(): it
    // needs a handle that survives between them. What must not change is the answer.
    byte[] bytes = TestMedia.png(4, 4, 11);
    String oneShot = blobStore.stage(new ByteArrayInputStream(bytes), 1024).sha256();

    int third = bytes.length / 3;
    try (BlobStore.IncrementalStage staged = blobStore.stageIncremental()) {
      staged.append(new ByteArrayInputStream(bytes, 0, third), 1024);
      assertEquals(third, staged.written(), "written() is the offset a resume must continue from");
      staged.append(new ByteArrayInputStream(bytes, third, third), 1024);
      staged.append(
          new ByteArrayInputStream(bytes, 2 * third, bytes.length - 2 * third), 1024);

      BlobStore.StagedBlob finished = staged.finish();
      assertEquals(oneShot, finished.sha256(), "three chunks must hash to the same blob as one");
      assertEquals(bytes.length, finished.size());
      assertFalse(blobStore.promote(finished));
    }
    assertTrue(blobStore.exists(oneShot));
  }

  @Test
  void aCapTrippedMidSessionLeavesNoTempFile() throws Exception {
    // The cap counts the blob's TOTAL size, not one call's contribution, and the temp file must go
    // with it — a session that dies holding 900 MB of a rejected layer is the disk-exhaustion path.
    byte[] bytes = TestMedia.png(4, 4, 12);
    BlobStore.IncrementalStage staged = blobStore.stageIncremental();
    staged.append(new ByteArrayInputStream(bytes, 0, 4), 8);
    assertThrows(
        PayloadTooLargeException.class,
        () -> staged.append(new ByteArrayInputStream(bytes, 4, bytes.length - 4), 8));
    assertEquals(0, tempFileCount(), "the aborted stage must not leave its temp file behind");
  }

  @Test
  void locateIsAbsoluteAndGuardsTheSameIdsOpenDoes() {
    // sendFile needs a path, but handing one out must not become the traversal hole that a caller
    // building Path.of(blobsDir, id) itself would be — so locate() goes through the same gate.
    byte[] bytes = TestMedia.png(2, 2, 13);
    BlobStore.StagedBlob staged = blobStore.stage(new ByteArrayInputStream(bytes), 1024);
    blobStore.promote(staged);

    Path located = blobStore.locate(staged.sha256());
    assertTrue(located.isAbsolute(), "Vert.x' FileResolver falls back to the classpath on a name");
    assertTrue(Files.exists(located));

    assertThrows(NotFoundException.class, () -> blobStore.locate("../../etc/passwd"));
    assertThrows(NotFoundException.class, () -> blobStore.locate("a".repeat(64)));
  }

  @Test
  void malformedIdIsNotFoundNotATraversal() {
    assertThrows(NotFoundException.class, () -> blobStore.open("../../etc/passwd"));
    assertThrows(NotFoundException.class, () -> blobStore.open("not-a-sha"));
    assertFalse(blobStore.exists("../../etc/passwd"));
  }

  private long tempFileCount() throws IOException {
    Path temp = Path.of(blobsDir, "tmp");
    if (!Files.isDirectory(temp)) {
      return 0;
    }
    try (var entries = Files.list(temp)) {
      return entries.count();
    }
  }

  @Test
  void unknownButWellShapedIdIsNotFound() {
    assertThrows(NotFoundException.class, () -> blobStore.open("a".repeat(64)));
  }

  @Test
  void deleteRefusesABlobWhoseFileIsInsideTheGraceWindow() {
    // The upload race, closed by arithmetic rather than by hope: a blob written moments ago may be
    // the one a manifest is about to name, and the sweep's census cannot see a request in flight.
    String blobId = stored(21);

    assertEquals(BlobStore.DeleteResult.WITHIN_GRACE_WINDOW, blobStore.delete(blobId, id -> true));
    assertTrue(blobStore.exists(blobId), "refused means the bytes are still there");
  }

  @Test
  void deleteRefusesWhenTheRecensusStillFindsAReference() throws Exception {
    // A plan is a photograph. Between the census it was built from and this unlink, a push may have
    // made the blob live again — so the last word is the guard's, inside the store's write lock.
    String blobId = stored(22);
    backdate(blobId, Duration.ofDays(30));

    assertEquals(BlobStore.DeleteResult.STILL_REFERENCED, blobStore.delete(blobId, id -> false));
    assertTrue(blobStore.exists(blobId));
  }

  @Test
  void deleteUnlinksAnAgedUnreferencedBlobAndInvalidatesTheDiskIndex() throws Exception {
    // The one path that removes bytes. It sends the same write signal promote does, or the store
    // summary would keep reporting a file that is gone until the index's age ceiling expired.
    String blobId = stored(23);
    backdate(blobId, Duration.ofDays(30));
    assertTrue(diskIndex.sizes().containsKey(blobId));

    assertEquals(BlobStore.DeleteResult.DELETED, blobStore.delete(blobId, id -> true));

    assertFalse(blobStore.exists(blobId));
    assertFalse(diskIndex.sizes().containsKey(blobId), "the summary must not still count it");
  }

  @Test
  void deleteAnswersRatherThanThrowsForAMissingFileAndAMalformedId() {
    // Every outcome here is normal. A sweep runs against a store that moved under it, and a
    // primitive that threw on "already gone" would make the ordinary case look like a failure.
    assertEquals(BlobStore.DeleteResult.ALREADY_GONE, blobStore.delete("a".repeat(64), id -> true));
    assertEquals(
        BlobStore.DeleteResult.NOT_A_BLOB_ID, blobStore.delete("../../etc/passwd", id -> true));
  }

  private String stored(int seed) {
    BlobStore.StagedBlob staged =
        blobStore.stage(new ByteArrayInputStream(TestMedia.png(2, 2, seed)), 1024);
    blobStore.promote(staged);
    return staged.sha256();
  }

  private static String sha256(byte[] bytes) throws IOException {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IOException(e);
    }
  }
}
