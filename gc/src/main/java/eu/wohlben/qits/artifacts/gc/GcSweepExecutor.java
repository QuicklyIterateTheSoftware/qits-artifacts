package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.control.BlobReclaim;
import eu.wohlben.qits.artifacts.control.LiveBlobCensus;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.gc.dto.GcSweepOutcome;
import eu.wohlben.qits.artifacts.gc.dto.GcSweepReport;
import eu.wohlben.qits.artifacts.gc.dto.GcTypeSweepResult;
import eu.wohlben.qits.artifacts.gc.dto.GcUntouchablePool;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The executed sweep: what the dry-run reports, done — plan, delete the identity rows, unlink the
 * blobs, and hand back the receipt.
 *
 * <p>One run is one request, and that is a safety property rather than a convenience: every plan
 * this class applies was computed <b>in this run</b>, from a census taken at its start — the OCI
 * strategy's cd pins included, fetched inside its {@code plan()} moments before the rows go. There
 * is deliberately no way to hand this class a plan: a stored plan is a plan on stale facts, and a
 * plan on stale facts deletes a running image.
 *
 * <p>The dry-run's honesty rules carry over whole. Every type is in the receipt; a strategy that
 * refuses to plan fails closed with its type untouched and its reason on the wire; two claimants
 * are a collision and neither runs; the row-less pool is restated on every receipt. What the
 * receipt adds is the withheld column: <b>the grace window gates identity rows here, not only blob
 * unlinks</b>, because a row deleted while its blob's file is inside the window would strand the
 * blob — row-less, therefore untouchable, therefore never reclaimed. A withheld identity is left
 * whole and re-planned by the next run; row and file mature out of the window together. The
 * expected first receipt on a young store is therefore zeros everywhere, and that no-op is the
 * proof the mechanism is safe.
 *
 * <p>Every run logs one line per type and one for the blob loop, so "what did GC ever do" stays a
 * question with an answer.
 */
@ApplicationScoped
public class GcSweepExecutor {

  @Inject LiveBlobCensus census;
  @Inject BlobSweep sweep;
  @Inject BlobReclaim blobs;
  @Inject Instance<GcStrategy> strategies;
  @Inject GcPinSources pinSources;

  /**
   * One full run: every live pin, a fresh census, every registered strategy, the unlink loop, the
   * receipt.
   *
   * <p><b>The pins are read first, and a source that cannot answer ends the run here</b> — before
   * the census, before a single row is touched. That is the settlement's abort rule and it replaces
   * the per-type fail-closed this method used to rely on: a keep-set assembled while qits-cd or
   * qits-ci is unreachable is a keep-set assembled from "nothing is pinned", and the types that do
   * not read pins are not safe to run beside it either, because a blob one of them releases may be
   * the last reference to content a pinned identity of another type still needs.
   */
  public GcSweepReport sweep() {
    GcPins pins = pinSources.fetch();
    if (!pins.complete()) {
      return aborted(pins);
    }
    return execute(census.take(), strategies.stream().toList(), pins);
  }

  /**
   * The receipt of a run that never started: what was wrong, per type, and zeros everywhere else.
   *
   * <p>No census is taken — the run is over before anything could have moved — so the untouchable
   * pool is reported as uncomputed rather than as empty. An empty list there would read as "this
   * store has no row-less blobs", which is a claim, and this run made none.
   */
  private GcSweepReport aborted(GcPins pins) {
    String why = "the run was aborted before anything was deleted: " + pins.whyIncomplete();
    Log.warnf("gc sweep aborted: %s", pins.whyIncomplete());
    List<GcTypeSweepResult> types = new ArrayList<>();
    for (RepositoryType type : RepositoryType.values()) {
      types.add(new GcTypeSweepResult(type, null, null, why, List.of(), List.of()));
    }
    return new GcSweepReport(
        Instant.now(),
        false,
        GcPlanner.iso(sweep.graceWindow()),
        why,
        types,
        new GcSweepOutcome(0, 0L, 0, 0L, 0, 0, List.of()),
        new GcUntouchablePool(
            "not computed: this run aborted before taking a census, and deleted nothing",
            0,
            0L,
            List.of()));
  }

  GcSweepReport execute(
      LiveBlobCensus.Census taken, Collection<GcStrategy> registered, GcPins pins) {
    Instant executedAt = Instant.now();
    Duration window = sweep.graceWindow();
    Instant graceStartsAt = executedAt.minus(window);
    GcStrategy.GraceWindow grace =
        blobId -> {
          Instant written = blobs.lastWrittenAt(blobId);
          return written != null && written.isAfter(graceStartsAt);
        };

    Map<RepositoryType, List<GcStrategy>> claimants = new EnumMap<>(RepositoryType.class);
    for (GcStrategy strategy : registered) {
      claimants.computeIfAbsent(strategy.type(), type -> new ArrayList<>()).add(strategy);
    }

    Map<RepositoryType, GcStrategy.Plan> plans = new EnumMap<>(RepositoryType.class);
    List<GcTypeSweepResult> types = new ArrayList<>();
    for (RepositoryType type : RepositoryType.values()) {
      List<GcStrategy> claiming = claimants.getOrDefault(type, List.of());
      if (claiming.isEmpty()) {
        types.add(
            new GcTypeSweepResult(
                type,
                null,
                "no strategy registered for " + type.wireName(),
                null,
                List.of(),
                List.of()));
        continue;
      }
      if (claiming.size() > 1) {
        types.add(
            new GcTypeSweepResult(
                type,
                names(claiming),
                null,
                "two strategies claim this type; a type has exactly one policy, and merging them"
                    + " is never the answer",
                List.of(),
                List.of()));
        continue;
      }
      GcStrategy strategy = claiming.get(0);
      String name = GcPlanner.nameOf(strategy);
      try {
        GcStrategy.Plan plan = strategy.plan(taken, pins);
        // In the map before apply: even a partially applied type must contribute its released set
        // to the blob loop, or rows already deleted would leave their blobs unswept and stranded.
        plans.put(type, plan);
        GcStrategy.Applied applied = strategy.apply(plan, grace);
        Log.infof(
            "gc sweep %s: deleted %d identities, withheld %d by grace, %d errors",
            type.wireName(),
            applied.deleted().size(),
            applied.withheldByGraceWindow().size(),
            applied.errors().size());
        types.add(
            new GcTypeSweepResult(
                type,
                name,
                strategy.note(),
                applied.errors().isEmpty() ? null : String.join("; ", applied.errors()),
                applied.deleted(),
                applied.withheldByGraceWindow()));
      } catch (RuntimeException aborted) {
        // Fail-closed, exactly as the planner: an exception out of plan() (or a wholesale one out
        // of apply()) reports the type as failed. The blob loop is safe either way — a row that
        // was not deleted keeps its blob referenced, and the fresh census sees that.
        Log.infof("gc sweep %s: refused — %s", type.wireName(), message(aborted));
        types.add(
            new GcTypeSweepResult(type, name, null, message(aborted), List.of(), List.of()));
      }
    }

    GcSweepOutcome outcome = sweep.execute(taken, plans);
    Log.infof(
        "gc sweep blobs: unlinked %d (%d bytes), withheld %d by grace (%d bytes), %d still"
            + " referenced, %d already gone",
        outcome.blobsUnlinked(),
        outcome.bytesReclaimed(),
        outcome.withheldByGraceWindow(),
        outcome.withheldBytes(),
        outcome.stillReferenced(),
        outcome.alreadyGone());
    return new GcSweepReport(
        executedAt,
        false,
        GcPlanner.iso(window),
        null,
        types,
        outcome,
        // The pool as it stood before this run: a version row deleted moments ago leaves its
        // within-grace tarball row-less in a fresh census, and listing that as "untouchable" would
        // misname a blob the next run is going to sweep.
        sweep.untouchable(taken));
  }

  private static String names(List<GcStrategy> claiming) {
    return String.join(", ", claiming.stream().map(GcPlanner::nameOf).sorted().toList());
  }

  private static String message(RuntimeException aborted) {
    String message = aborted.getMessage();
    return aborted.getClass().getSimpleName() + (message == null ? "" : ": " + message);
  }
}
