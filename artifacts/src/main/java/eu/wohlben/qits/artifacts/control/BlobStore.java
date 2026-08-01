package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.error.InternalServerErrorException;
import eu.wohlben.qits.artifacts.error.NotFoundException;
import eu.wohlben.qits.artifacts.error.PayloadTooLargeException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.Closeable;
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

  /**
   * The store's one write funnel is {@link #promote}, so it is also the complete set of events that
   * can make a directory listing stale — see {@link BlobDiskIndex}. Injected rather than the other
   * way round: the index reads the config key itself and knows nothing about this class.
   */
  @Inject BlobDiskIndex diskIndex;

  /** A blob staged in the temp area, not yet promoted to its content-addressed path. */
  public record StagedBlob(String sha256, long size, Path tempPath) {}

  /**
   * Streams {@code in} into a temp file, computing its SHA-256 and size, aborting past {@code
   * capBytes} with a 413 (the temp file is cleaned up on any failure).
   */
  public StagedBlob stage(InputStream in, long capBytes) {
    try (IncrementalStage staged = stageIncremental()) {
      staged.append(in, capBytes);
      return staged.finish();
    }
  }

  /**
   * Opens a blob that will be written across <b>more than one call</b>, in the same temp area {@link
   * #stage} uses.
   *
   * <p>This exists for the OCI upload session, whose {@code PATCH} and {@code PUT} arrive as
   * separate HTTP requests — {@link #stage} cannot express that, because it consumes a whole stream
   * and returns a finished {@link StagedBlob}. Everything else is unchanged: the digest is still
   * computed while streaming and the cap still aborts mid-stream, so a gigabyte never materialises
   * in memory here either.
   */
  public IncrementalStage stageIncremental() {
    try {
      return new IncrementalStage(newStagingFile());
    } catch (IOException e) {
      throw new InternalServerErrorException("Could not create artifacts temp dir", e);
    }
  }

  /**
   * A fresh, unused path in this store's temp area, for a writer that {@link IncrementalStage}
   * cannot serve — one that has to <b>read back</b> what it has written.
   *
   * <p>{@code IncrementalStage} is write-only: it wraps an {@code OutputStream} over a running
   * {@link MessageDigest} and offers no way to read a byte again. JGit's pack parser does exactly
   * that (a {@code DfsOutputStream} declares {@code read(long, ByteBuffer)}), so the git host's
   * blob adapter opens its own read-write channel over this path, hashes the finished file, and
   * hands it to {@link #promote}.
   *
   * <p>The path is in <b>this store's</b> temp area rather than {@code java.io.tmpdir}, and that is
   * the reason this method exists at all rather than the caller picking a directory: {@link
   * #promote} finishes with an {@code ATOMIC_MOVE}, which only holds within one filesystem. The
   * caller owns the file — nothing here creates or deletes it — so a caller that never promotes
   * must delete it.
   */
  public Path newStagingFile() {
    Path tmp = tempDir().resolve(UUID.randomUUID().toString());
    try {
      Files.createDirectories(tmp.getParent());
    } catch (IOException e) {
      throw new InternalServerErrorException("Could not create artifacts temp dir", e);
    }
    return tmp;
  }

  /**
   * A blob being written incrementally. Feed it with {@link #append}, then {@link #finish} to get a
   * {@link StagedBlob} for {@link BlobStore#promote}.
   *
   * <p><b>Not thread-safe</b>, and deliberately so: one session, one writer. The running {@link
   * MessageDigest} is JVM state that cannot be persisted, so an unfinished stage does not survive a
   * restart — which is exactly the Distribution spec's session contract, where an upload id is
   * opaque and may expire at any time.
   */
  public final class IncrementalStage implements Closeable {

    private final Path tmp;
    private final MessageDigest digest = sha256Digest();
    private final OutputStream out;
    private long written;
    private boolean finished;

    private IncrementalStage(Path tmp) throws IOException {
      this.tmp = tmp;
      this.out = Files.newOutputStream(tmp);
    }

    /** Bytes accepted so far — the offset a resumed upload must continue from. */
    public long written() {
      return written;
    }

    /**
     * Appends {@code in} to the running blob.
     *
     * @param capBytes the cap on the blob's <b>total</b> size, not on this call's contribution
     * @return the new total
     * @throws PayloadTooLargeException past the cap; the temp file is discarded first
     */
    public long append(InputStream in, long capBytes) {
      try {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
          written += n;
          if (written > capBytes) {
            throw new PayloadTooLargeException(
                "Upload exceeds the repository type's size cap of " + capBytes + " bytes");
          }
          digest.update(buf, 0, n);
          out.write(buf, 0, n);
        }
        return written;
      } catch (IOException e) {
        discard();
        throw new InternalServerErrorException("Failed to stage upload", e);
      } catch (RuntimeException e) {
        discard();
        throw e;
      }
    }

    /** Closes the file and returns the finished stage, ready for {@link BlobStore#promote}. */
    public StagedBlob finish() {
      try {
        out.close();
      } catch (IOException e) {
        discard();
        throw new InternalServerErrorException("Failed to stage upload", e);
      }
      finished = true;
      return new StagedBlob(HexFormat.of().formatHex(digest.digest()), written, tmp);
    }

    /** Closes and deletes the temp file. Idempotent, and safe after {@link #finish}. */
    public void discard() {
      if (finished) {
        return;
      }
      finished = true;
      try {
        out.close();
      } catch (IOException ignored) {
        // best-effort: we are about to delete the file anyway
      }
      deleteQuietly(tmp);
    }

    /** {@code discard()} unless {@link #finish} already ran, so try-with-resources is always safe. */
    @Override
    public void close() {
      discard();
    }
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
    diskIndex.invalidate();
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

  /**
   * The on-disk path of an existing blob, for zero-copy serving ({@code
   * HttpServerResponse.sendFile}) — the one thing {@link #open} cannot express, because piping its
   * stream into a response holds a worker thread for the whole transfer while {@code sendFile} hands
   * the file to Netty and returns.
   *
   * <p>Absolute and normalised deliberately: Vert.x resolves a file <em>name</em> through its
   * {@code FileResolver}, which falls back to the classpath for anything it cannot find relative to
   * the process working directory. A native image has no such entry, and a relative {@code
   * qits.artifacts.blobs-dir} could in principle collide with one — an absolute path short-circuits
   * the resolver entirely.
   *
   * <p>Goes through the same {@code requireExisting} gate as {@link #open}, so a malformed id is a
   * 404 here too and this accessor cannot become the path-traversal hole that a caller building
   * {@code Path.of(blobsDir, id)} for itself would be.
   */
  public Path locate(String blobId) {
    return requireExisting(blobId).toAbsolutePath().normalize();
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
