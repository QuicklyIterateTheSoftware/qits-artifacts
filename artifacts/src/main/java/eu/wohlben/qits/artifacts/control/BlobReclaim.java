package eu.wohlben.qits.artifacts.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;

/**
 * The blob store's unlink door, opened exactly wide enough for the {@code gc} module and no wider.
 *
 * <p><b>A narrow facade rather than a widened method.</b> {@link BlobStore#delete} stays
 * package-private for the reason {@code promote} is the one write funnel: the grace window, the
 * pre-unlink guard taken inside the store's write lock, and the {@link BlobDiskIndex} invalidation
 * only hold while there is one way in. Making it {@code public} would remove that guarantee for
 * every package on the classpath at once — every route, every registry, every future caller — to
 * serve one module. This class is the alternative the repository already uses where a seam has to
 * cross a jar boundary: a named door with a documented owner, delegating to the funnel rather than
 * replacing it.
 *
 * <p><b>The owner is {@code eu.wohlben.qits.artifacts.gc} and nothing else.</b> Concretely: {@code
 * BlobSweep} for the unlink, {@code BlobSweep} and {@code GcSweepExecutor} for the two clock reads.
 * A second caller appearing anywhere is the signal that this door has become an API, which it is
 * not — the registries' {@code 405} on client deletes stays exactly as it is, and no client gains
 * deletion semantics from any of this.
 *
 * <p>Nothing here adds a rule or removes one. Every constraint {@link BlobStore#delete} documents
 * is enforced by {@link BlobStore#delete}, on the far side of this call.
 */
@ApplicationScoped
public class BlobReclaim {

  @Inject BlobStore blobStore;

  /**
   * Unlinks one blob, through the store's own funnel. See {@link BlobStore#delete} for the three
   * constraints it enforces; every outcome is a normal answer rather than an exception.
   */
  public BlobStore.DeleteResult delete(String blobId, BlobStore.SweepGuard guard) {
    return blobStore.delete(blobId, guard);
  }

  /** When this blob's file was last written — {@code promote}'s move. Null if there is no file. */
  public Instant lastWrittenAt(String blobId) {
    return blobStore.lastWrittenAt(blobId);
  }

  /**
   * The window {@link BlobStore#delete} enforces, so a plan can withhold identities on the same
   * clock the unlink will be judged against — and a report can name what it withheld and why.
   */
  public Duration graceWindow() {
    return blobStore.blobGracePeriod();
  }
}
