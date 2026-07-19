package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.error.InternalServerErrorException;
import eu.wohlben.qits.artifacts.error.NotFoundException;
import eu.wohlben.qits.artifacts.error.PayloadTooLargeException;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Content-addressed blob storage on disk, decoupled from the metadata rows. Bytes live at {@code
 * <blobs-dir>/<sha[0:2]>/<sha>} (fan-out dirs). Writes stage to a temp file (hashing + counting +
 * capping <em>while streaming</em>, so a huge video never materialises in memory) then atomically
 * rename into place; identical bytes dedupe to one file. Reads validate the id shape before
 * touching the filesystem (path-traversal defence on the fan-out dirs).
 */
@ApplicationScoped
public class BlobStore {

  private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

  @ConfigProperty(name = "qits.artifacts.blobs-dir", defaultValue = "data/artifacts/blobs")
  String blobsDir;

  /** A blob staged in the temp area, not yet promoted to its content-addressed path. */
  public record StagedBlob(String sha256, long size, Path tempPath) {}

  /**
   * Streams {@code in} into a temp file, computing its SHA-256 and size, aborting past {@code
   * capBytes} with a 413 (the temp file is cleaned up on any failure).
   */
  public StagedBlob stage(InputStream in, long capBytes) {
    Path tmp = tempDir().resolve(UUID.randomUUID().toString());
    try {
      Files.createDirectories(tmp.getParent());
    } catch (IOException e) {
      throw new InternalServerErrorException("Could not create artifacts temp dir", e);
    }
    MessageDigest digest = sha256Digest();
    long total = 0;
    try (OutputStream out = Files.newOutputStream(tmp)) {
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) != -1) {
        total += n;
        if (total > capBytes) {
          throw new PayloadTooLargeException(
              "Upload exceeds the repository type's size cap of " + capBytes + " bytes");
        }
        digest.update(buf, 0, n);
        out.write(buf, 0, n);
      }
    } catch (IOException e) {
      deleteQuietly(tmp);
      throw new InternalServerErrorException("Failed to stage upload", e);
    } catch (RuntimeException e) {
      deleteQuietly(tmp);
      throw e;
    }
    return new StagedBlob(HexFormat.of().formatHex(digest.digest()), total, tmp);
  }

  /**
   * Moves a staged blob to its content-addressed path, or discards the temp file if the content is
   * already stored.
   *
   * @return whether the bytes already existed (dedupe)
   */
  public boolean promote(StagedBlob staged) {
    Path dest = pathFor(staged.sha256());
    if (Files.exists(dest)) {
      deleteQuietly(staged.tempPath());
      return true;
    }
    try {
      Files.createDirectories(dest.getParent());
      try {
        Files.move(staged.tempPath(), dest, StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException atomicUnsupported) {
        // A concurrent upload of identical bytes may have won the race, or the filesystem may not
        // support atomic move — either way the content is what matters, and it is
        // content-addressed.
        if (Files.exists(dest)) {
          deleteQuietly(staged.tempPath());
          return true;
        }
        Files.move(staged.tempPath(), dest, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      deleteQuietly(staged.tempPath());
      throw new InternalServerErrorException("Failed to store blob " + staged.sha256(), e);
    }
    return false;
  }

  public boolean exists(String blobId) {
    return isValidId(blobId) && Files.exists(pathFor(blobId));
  }

  /**
   * Opens the content stream for serving. 404 on a malformed id (path-traversal defence) or a miss.
   */
  public InputStream open(String blobId) {
    Path path = requireExisting(blobId);
    try {
      return Files.newInputStream(path);
    } catch (IOException e) {
      throw new InternalServerErrorException("Failed to open blob " + blobId, e);
    }
  }

  public long size(String blobId) {
    Path path = requireExisting(blobId);
    try {
      return Files.size(path);
    } catch (IOException e) {
      throw new InternalServerErrorException("Failed to size blob " + blobId, e);
    }
  }

  private Path requireExisting(String blobId) {
    if (!isValidId(blobId)) {
      throw new NotFoundException("No such blob: " + blobId);
    }
    Path path = pathFor(blobId);
    if (!Files.exists(path)) {
      throw new NotFoundException("No such blob: " + blobId);
    }
    return path;
  }

  private Path pathFor(String blobId) {
    return root().resolve(blobId.substring(0, 2)).resolve(blobId);
  }

  private Path tempDir() {
    return root().resolve("tmp");
  }

  private Path root() {
    return Path.of(blobsDir);
  }

  private static boolean isValidId(String blobId) {
    return blobId != null && SHA256_HEX.matcher(blobId).matches();
  }

  private static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new InternalServerErrorException("SHA-256 unavailable", e);
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // best-effort temp cleanup
    }
  }
}
