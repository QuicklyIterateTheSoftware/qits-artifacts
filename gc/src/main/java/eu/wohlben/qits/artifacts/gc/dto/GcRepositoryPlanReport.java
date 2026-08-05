package eu.wohlben.qits.artifacts.gc.dto;

import eu.wohlben.qits.artifacts.entity.RepositoryType;
import java.time.Instant;
import java.util.List;

/**
 * What collecting one repository would do, in full — the artifact a per-repository sweep is
 * authorised from.
 *
 * <p>{@code GcPlanReport}'s twin at repository scope, and it carries the same sections for the same
 * reasons: the configuration echo, because "nothing died" reads identically whether the rule is
 * right or the window is a year; the pins provenance, because half the keep-set is another
 * service's answer; the <b>kept</b> list beside the dead one, because a list of doomed coordinates
 * with nothing kept beside it cannot be argued with; and the row-less pool, because it is the list
 * a reviewer checks the ci-daemon binary against before agreeing to anything.
 *
 * <p><b>Two blob figures, and neither can stand alone.</b> {@link #structural} is what the rule
 * condemns whatever its age, and {@link #sweep} is what a run right now would actually unlink, with
 * the difference reported as withheld. A page showing only the first promises disk the next run
 * cannot deliver; one showing only the second reads as "nothing to clean" for a repository that was
 * pushed to this morning.
 *
 * @param repository the {@code artifact_repository} row this plan is about
 * @param type its type, in the wire spelling
 * @param generatedAt when the census behind this plan was taken. A plan is a photograph: a push
 *     since makes it stale, which is why a sweep re-censuses immediately before each unlink.
 * @param dryRun always true, the same contract {@code GcPlanReport} carries
 * @param graceWindow how long a blob's file must sit untouched before it may be unlinked, ISO-8601
 * @param executable whether a sweep run now could execute this plan — false when a pin source could
 *     not answer, in which case a sweep would abort whole and the figures below are what the rule
 *     says rather than what a run would do
 * @param pinFailures one sentence per pin source that could not answer
 * @param pins how this run read its live pins: per source, url, outcome, duration and the
 *     keep-identities it produced
 * @param configuration this type's configured engine, window and rule, as a sentence
 * @param strategy the class that produced this plan, or null when no strategy claims the type
 * @param note the standing caption for this type, when it has one
 * @param error why there is no plan: pins unavailable, a policy collision, a refusal to establish a
 *     keep-set. Fail-closed — nothing of this repository would be touched.
 * @param dead this repository's identities the rule condemns, each naming the rule that condemned
 *     it
 * @param kept this repository's identities it keeps, each naming the rule that saved it
 * @param sweep what a run <b>now</b> would unlink for this repository alone, plus what the grace
 *     window withholds
 * @param structural the same reconciliation with the grace window not applied: what the rule frees
 *     regardless of how young the files are
 * @param untouchable the row-less pool, restated — no plan of any scope may include it
 */
public record GcRepositoryPlanReport(
    String repository,
    RepositoryType type,
    Instant generatedAt,
    boolean dryRun,
    String graceWindow,
    boolean executable,
    List<String> pinFailures,
    List<GcPinSource> pins,
    GcTypeConfiguration configuration,
    String strategy,
    String note,
    String error,
    List<GcIdentity> dead,
    List<GcIdentity> kept,
    GcSweepPlan sweep,
    GcSweepPlan structural,
    GcUntouchablePool untouchable) {}
