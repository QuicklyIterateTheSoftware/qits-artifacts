package eu.wohlben.qits.artifacts.gc.dto;

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
 * @param executable whether a sweep run right now could execute this plan. False when a pin source
 *     — qits-cd's deployments, qits-ci's daemon ladder — could not answer: a sweep would abort
 *     whole, so the pin-dependent types below carry a refusal rather than a finding, and their zeros
 *     must not be read as "nothing to collect"
 * @param pinFailures one sentence per pin source that could not answer; empty when the plan is
 *     executable
 * @param configuration what this deployment has configured per type, and what each setting means in
 *     a sentence. The half of a plan the outcomes cannot show: "nothing died" reads the same whether
 *     the rule is right or the window is a year.
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
    boolean executable,
    List<String> pinFailures,
    List<GcTypeConfiguration> configuration,
    List<GcTypePlan> types,
    GcSweepPlan sweep,
    GcUntouchablePool untouchable) {}
