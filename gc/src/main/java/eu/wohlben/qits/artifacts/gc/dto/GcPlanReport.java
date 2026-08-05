package eu.wohlben.qits.artifacts.gc.dto;

import java.time.Instant;
import java.util.List;

/**
 * What garbage collection would do, and what it would cost — the artifact a sweep is authorised
 * from. Nothing on this platform has ever deleted a byte without one of these being read first.
 *
 * @param summary the whole plan as a human reads it: executable or not, what it would free, and one
 *     line per type. Derived from the fields below and never a second opinion about them; first in
 *     the record because it is what a reviewer starts with.
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
 * @param pins how this run read its live pins: per source, the url, the outcome, the time it took
 *     and the keep-identities it produced. The keep-set is partly another service's answer, so the
 *     report has to show the answer as well as its consequences — checking that the pinned shas are
 *     the shas the deployments are running is the first thing a review of this report does.
 * @param configuration what this deployment has configured per type, and what each setting means in
 *     a sentence. The half of a plan the outcomes cannot show: "nothing died" reads the same whether
 *     the rule is right or the window is a year.
 * @param types one entry per repository type, always all of them — a type with no strategy says so
 *     rather than being absent, because "nothing to collect" and "nobody is collecting" are
 *     different answers and only one of them is fine
 * @param repositories the same plan attributed one level down, one entry per {@code
 *     artifact_repository} row. Read off this report rather than computed again, and carried here
 *     rather than only on the per-repository route so that a reviewer of the whole store sees the
 *     figures the repository listing shows. They are deliberately not additive — see {@link
 *     GcRepositoryPlanSummary}.
 * @param sweep the cross-type reconciliation: what would actually be unlinked
 * @param untouchable the row-less pool, which no plan may ever include
 */
public record GcPlanReport(
    GcPlanSummary summary,
    Instant generatedAt,
    boolean dryRun,
    String graceWindow,
    boolean executable,
    List<String> pinFailures,
    List<GcPinSource> pins,
    List<GcTypeConfiguration> configuration,
    List<GcTypePlan> types,
    List<GcRepositoryPlanSummary> repositories,
    GcSweepPlan sweep,
    GcUntouchablePool untouchable) {}
