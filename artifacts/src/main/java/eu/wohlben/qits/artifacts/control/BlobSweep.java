package eu.wohlben.qits.artifacts.control;

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
 * <p><b>It does not delete, this workstream.</b> {@link #plan} reports what it would unlink and
 * there is no method that unlinks: the platform has never deleted a byte, and the first one goes
 * after a human has read these reports. {@link BlobStore#delete} exists, is package-private, and
 * names this class as its only permitted caller — the loop that calls it lands with the flag that
 * turns collection on, not before.
 */
@ApplicationScoped
public class BlobSweep {

  @Inject BlobStore blobStore;

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
