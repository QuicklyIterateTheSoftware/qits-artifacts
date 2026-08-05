package eu.wohlben.qits.artifacts.gc.dto;

import java.time.Instant;
import java.util.List;

/**
 * What one executed sweep actually did — the plan report's twin, with the tense changed.
 *
 * <p>Where {@code GcPlanReport} says what a run <em>would</em> do, this is the receipt of one that
 * ran: identity rows deleted per type, blob files unlinked, and — just as load-bearing — what was
 * withheld and why. A sweep against a young store is expected to delete nothing and say so in every
 * figure; that no-op is proof the executor is safe, not a failure to look into.
 *
 * @param executedAt when this run started
 * @param dryRun always false — the field {@code GcPlanReport} carries as always-true, kept in both
 *     so a client can never mistake a plan for a receipt or a receipt for a plan
 * @param graceWindow how long a blob's file must have sat untouched before it may be unlinked,
 *     ISO-8601 — the window also gates identity deletion, see {@code GcTypeSweepResult}
 * @param types one entry per repository type, always all of them, same honesty rule as the plan
 * @param sweep the blob unlinks: what was freed, and what was withheld
 * @param untouchable the row-less pool as it stood before this run — restated on every receipt
 *     because it is the list a reviewer checks the ci-daemon binary against
 */
public record GcSweepReport(
    Instant executedAt,
    boolean dryRun,
    String graceWindow,
    List<GcTypeSweepResult> types,
    GcSweepOutcome sweep,
    GcUntouchablePool untouchable) {}
