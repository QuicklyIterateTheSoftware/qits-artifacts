package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.dto.GcSweepOutcome;
import eu.wohlben.qits.artifacts.dto.GcSweepPlan;
import eu.wohlben.qits.artifacts.dto.GcUntouchablePool;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The one mechanism that frees disk, and it carries no policy at all.
 *
 * <p>Strategies kill identities and free nothing. This reconciles what they did: a blob dies only
 * when <b>no type</b> reaches it any more, because the store dedupes globally across types and
 * repositories. That split is what makes "one strategy per type" safe by construction — a strategy
 * cannot free a blob another type still needs, because a strategy never frees anything.
 *
 * <p><b>{@link #plan} still deletes nothing</b> — it is what the dry-run report reads, and it must
 * stay reviewable without side effects. The unlink loop is {@link #execute}, added after the user
 * reviewed the dry-run, and it is still the <b>only</b> caller of {@link BlobStore#delete}: it runs
 * only when {@code GcSweepExecutor} drives it behind {@code POST /artifacts/api/gc/sweep}, after
 * the strategies have deleted their identity rows, against a census taken fresh after those
 * deletions.
 */
@ApplicationScoped
public class BlobSweep {

  @Inject BlobStore blobStore;
  @Inject LiveBlobCensus census;

  /**
   * Blobs no type reaches once every given plan is applied, with what they would free.
   *
   * <p>The reconciliation, in one line: a candidate is released by some strategy and retained by
   * none. A type with no plan keeps its whole census set, which is what makes a partial run — one
   * strategy enabled, four not — as safe as a full one.
   *
   * @param census the store as read; a type absent from {@code plans} contributes its live set
   * @param plans the strategies' answers, at most one per type
   */
  public GcSweepPlan plan(LiveBlobCensus.Census census, Map<RepositoryType, GcStrategy.Plan> plans) {
    return reconcile(census, plans, true);
  }

  /**
   * One type's plan against an otherwise untouched store — the per-type attribution in a report.
   *
   * <p>The grace window is deliberately <b>not</b> applied here. It is a property of when an unlink
   * may happen, not of what a rule structurally frees, and a strategy's worth should not read as
   * zero because its content was pushed this morning. The sweep figure is the one with a date on it.
   */
  public GcSweepPlan planForOneType(
      LiveBlobCensus.Census census, RepositoryType type, GcStrategy.Plan plan) {
    Map<RepositoryType, GcStrategy.Plan> only = new EnumMap<>(RepositoryType.class);
    only.put(type, plan);
    return reconcile(census, only, false);
  }

  private GcSweepPlan reconcile(
      LiveBlobCensus.Census census,
      Map<RepositoryType, GcStrategy.Plan> plans,
      boolean applyGraceWindow) {
    Set<String> released = new TreeSet<>();
    Set<String> live = new HashSet<>();
    for (RepositoryType type : RepositoryType.values()) {
      GcStrategy.Plan plan = plans.get(type);
      if (plan == null) {
        live.addAll(census.live(type).keySet());
      } else {
        released.addAll(plan.blobsReleased());
        live.addAll(plan.blobsRetained());
      }
    }

    Set<String> rowless = census.rowless();
    Instant graceStartsAt = Instant.now().minus(graceWindow());
    List<String> sweepable = new ArrayList<>();
    long reclaimable = 0;
    int withheld = 0;
    long withheldBytes = 0;
    for (String blobId : released) {
      if (live.contains(blobId)) {
        continue; // something that survives still names it — the whole point of reconciling
      }
      Long size = census.onDisk().get(blobId);
      if (size == null) {
        continue; // a row outliving its bytes frees nothing
      }
      // Unreachable by construction — a released blob was in its type's live set, so it had a row —
      // and asserted anyway, because a strategy that invented a digest must not reach the unlink.
      if (rowless.contains(blobId)) {
        continue;
      }
      if (applyGraceWindow) {
        Instant written = blobStore.lastWrittenAt(blobId);
        if (written != null && written.isAfter(graceStartsAt)) {
          withheld++;
          withheldBytes += size;
          continue;
        }
      }
      sweepable.add(blobId);
      reclaimable += size;
    }
    return new GcSweepPlan(sweepable.size(), reclaimable, withheld, withheldBytes, sweepable);
  }

  /**
   * Unlinks what the applied plans freed — the one loop on this platform that deletes bytes.
   *
   * <p>Called after the strategies' identity deletions, with the census the plans were computed
   * from. The safety order inside:
   *
   * <ol>
   *   <li><b>Candidates are structural</b>: released by some plan, retained by none, on disk, and
   *       rowed in the planning census — the same reconciliation the report shows, with the grace
   *       filter left to the gates below.
   *   <li><b>Grace-withheld candidates never reach the unlink.</b> Their identities were withheld
   *       too (rows intact — the executor's gate), so they are counted once, as withheld, rather
   *       than smeared across refusal counters.
   *   <li><b>The pre-unlink re-census</b>: one fresh census taken here, after the row deletions.
   *       A candidate something still references — a withheld identity of another type, a push
   *       since planning — is skipped and counted. The same set backs the {@link
   *       BlobStore.SweepGuard} asked again inside the store's write lock, so the check and the
   *       unlink cannot be separated by a write.
   *   <li>{@link BlobStore#delete} enforces the grace window and the guard once more, per blob,
   *       inside the lock. Every refusal is a counted outcome, never an exception.
   * </ol>
   */
  public GcSweepOutcome execute(
      LiveBlobCensus.Census planned, Map<RepositoryType, GcStrategy.Plan> plans) {
    GcSweepPlan structural = reconcile(planned, plans, false);
    GcSweepPlan matured = reconcile(planned, plans, true);
    Set<String> withheldIds = new HashSet<>(structural.blobIds());
    withheldIds.removeAll(matured.blobIds());

    LiveBlobCensus.Census fresh = census.take();
    Set<String> referenced = fresh.referenced();
    BlobStore.SweepGuard guard = blobId -> !referenced.contains(blobId);

    int unlinked = 0;
    long reclaimed = 0;
    int withheld = matured.withheldByGraceWindow();
    long withheldBytes = matured.withheldBytes();
    int stillReferenced = 0;
    int alreadyGone = 0;
    List<String> unlinkedIds = new ArrayList<>();
    for (String blobId : structural.blobIds()) {
      if (withheldIds.contains(blobId)) {
        continue; // counted in the withheld figures already; its identity rows are intact too
      }
      if (referenced.contains(blobId)) {
        stillReferenced++;
        continue;
      }
      long size = fresh.onDisk().getOrDefault(blobId, planned.onDisk().getOrDefault(blobId, 0L));
      switch (blobStore.delete(blobId, guard)) {
        case DELETED -> {
          unlinked++;
          reclaimed += size;
          unlinkedIds.add(blobId);
        }
        case STILL_REFERENCED -> stillReferenced++;
        case ALREADY_GONE, NOT_A_BLOB_ID -> alreadyGone++;
        case WITHIN_GRACE_WINDOW -> {
          // The store's own belt: the reconcile above judged this blob mature, the store's clock
          // says otherwise at the unlink. Counted as withheld — that is what it is.
          withheld++;
          withheldBytes += size;
        }
      }
    }
    return new GcSweepOutcome(
        unlinked, reclaimed, withheld, withheldBytes, stillReferenced, alreadyGone, unlinkedIds);
  }

  /**
   * The pool this mechanism may never touch, listed rather than counted.
   *
   * <p>It is a report, not a gate: nothing here needs to exclude these blobs, because a candidate
   * has to have lost an identity row and these never had one. Listing them is how a reviewer
   * confirms the ci-daemon binary is on the safe side before agreeing to the first real sweep.
   */
  public GcUntouchablePool untouchable(LiveBlobCensus.Census census) {
    List<String> blobIds = List.copyOf(census.rowless());
    return new GcUntouchablePool(
        "no identity row of any type names these; only a blob that LOSES its last row can ever be"
            + " swept, so these are unreachable from the mechanism",
        blobIds.size(),
        census.bytesOnDisk(blobIds),
        blobIds);
  }

  /** How long a blob's file must sit untouched before {@link BlobStore#delete} will unlink it. */
  public Duration graceWindow() {
    return blobStore.blobGracePeriod();
  }
}
