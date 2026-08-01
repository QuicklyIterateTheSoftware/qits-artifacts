package eu.wohlben.qits.artifacts.dto;

import java.time.Instant;
import java.util.List;

/**
 * What garbage collection would do, if it could. It cannot: there is no execute surface, and this
 * report is the whole feature until the plans below have been read and agreed.
 *
 * @param generatedAt when the census behind this plan was taken. A plan is a photograph — a push
 *     since makes it stale, which is why a sweep re-censuses immediately before each unlink.
 * @param dryRun always true today, and a field rather than a comment so a client cannot mistake this
 *     for a receipt of work done
 * @param graceWindow how long a blob's file must have sat untouched before it may be unlinked, ISO-8601
 * @param types one entry per repository type, always all of them — a type with no strategy says so
 *     rather than being absent, because "nothing to collect" and "nobody is collecting" are
 *     different answers and only one of them is fine
 * @param sweep the cross-type reconciliation: what would actually be unlinked
 * @param untouchable the row-less pool, which no plan may ever include
 */
public record GcPlanReport(
    Instant generatedAt,
    boolean dryRun,
    String graceWindow,
    List<GcTypePlan> types,
    GcSweepPlan sweep,
    GcUntouchablePool untouchable) {}
