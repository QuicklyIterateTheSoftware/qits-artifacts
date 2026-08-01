package eu.wohlben.qits.artifacts.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * What is actually on disk: every blob file, with its size.
 *
 * <p>The database cannot answer this. 96% of the stored bytes have no row of any kind — layers and
 * configs get none by design — and some of what is on disk has no row and no manifest either: the
 * OCI blob-upload session accepts bytes before a manifest binds them, so an upload that never
 * finished the handshake leaves a file reachable from nothing. This store holds 124 MiB of exactly
 * that. A view built only on rows under-reports the store by that much and cannot say so; this index
 * is what lets the summary name the gap instead.
 *
 * <p><b>Invalidated by the write, not by a timer.</b> Every byte this service stores lands through
 * {@code BlobStore.promote} — the registry's layers, npm's tarballs, the JSON API's uploads, the
 * proxy's pull-through — so that one call is the complete set of events that can make this stale,
 * and it is where {@link #invalidate()} is called from. The age ceiling below is a second belt for
 * the things a process cannot see: a volume restored under it, or a sibling writing the same
 * directory.
 *
 * <p>The scan is a two-level directory walk — 1450 entries in this store — and it is shared: one
 * pass answers the disk total, the orphan bytes and every npm tarball's size, none of which has a
 * size column to read instead.
 */
@ApplicationScoped
public class BlobDiskIndex {

  /**
   * How long a scan may be trusted with no write to invalidate it. Short enough that an
   * out-of-band change surfaces on the next look at the page, long enough that a browser refreshing
   * a summary does not re-walk the directory each time.
   */
  private static final Duration MAX_AGE = Duration.ofSeconds(60);

  /** A blob file is named for its content. Anything else under the root is not one — {@code tmp/}. */
  private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

  @ConfigProperty(name = "qits.artifacts.blobs-dir", defaultValue = "data/artifacts/blobs")
  String blobsDir;

  private final AtomicBoolean stale = new AtomicBoolean(true);
  private volatile Snapshot snapshot;

  private record Snapshot(Map<String, Long> sizes, Instant scannedAt) {}

  /**
   * Digest (bare hex) to bytes on disk, for every blob file under the root.
   *
   * @return unmodifiable, and shared — callers must not mutate it
   */
  public Map<String, Long> sizes() {
    Snapshot current = snapshot;
    if (current != null && !stale.get() && !older(current, MAX_AGE)) {
      return current.sizes();
    }
    // Cleared BEFORE the walk: a promote landing mid-scan then re-marks it and the next reader
    // rescans, where clearing afterwards would swallow that write until the age ceiling.
    stale.set(false);
    Snapshot scanned = new Snapshot(scan(), Instant.now());
    snapshot = scanned;
    return scanned.sizes();
  }

  /** Marks the index stale. Called by {@code BlobStore.promote} — every stored byte goes through it. */
  public void invalidate() {
    stale.set(true);
  }

  private static boolean older(Snapshot snapshot, Duration age) {
    return Duration.between(snapshot.scannedAt(), Instant.now()).compareTo(age) > 0;
  }

  private Map<String, Long> scan() {
    Path root = Path.of(blobsDir);
    Map<String, Long> sizes = new HashMap<>();
    if (!Files.isDirectory(root)) {
      return Map.of();
    }
    // Depth 2 is the fan-out layout exactly: <root>/<sha[0:2]>/<sha>. It also keeps the walk out of
    // anything deeper, and the name filter keeps the staging area's temp files out of the count.
    try (Stream<Path> walk = Files.walk(root, 2)) {
      walk.filter(path -> SHA256_HEX.matcher(path.getFileName().toString()).matches())
          .forEach(path -> size(path).ifPresent(size -> sizes.put(path.getFileName().toString(), size)));
    } catch (IOException unreadable) {
      // A store view must not fail because the directory moved under it; an empty index reports
      // zero on disk, which is visibly wrong rather than silently wrong.
      return Map.copyOf(sizes);
    }
    return Map.copyOf(sizes);
  }

  private static java.util.Optional<Long> size(Path path) {
    try {
      return Files.isRegularFile(path)
          ? java.util.Optional.of(Files.size(path))
          : java.util.Optional.empty();
    } catch (IOException gone) {
      return java.util.Optional.empty();
    }
  }
}
