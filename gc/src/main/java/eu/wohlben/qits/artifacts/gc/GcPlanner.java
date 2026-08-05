package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.control.LiveBlobCensus;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.gc.dto.GcPlanReport;
import eu.wohlben.qits.artifacts.gc.dto.GcSweepPlan;
import eu.wohlben.qits.artifacts.gc.dto.GcTypeConfiguration;
import eu.wohlben.qits.artifacts.gc.dto.GcTypePlan;
import io.quarkus.arc.ClientProxy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The dry run: every repository type, what its strategy would delete, and what a sweep would then
 * unlink.
 *
 * <p>This is the artifact the user reads before any collection is switched on, so its job is to be
 * <b>honest about absence</b>. Every type appears in every report, including the ones nobody
 * collects: a missing entry would read as "nothing to collect here", and "no strategy registered" is
 * a different fact. With no strategies registered — which is what ships today — the whole report is
 * zeros with five reasons, and that is the correct output rather than an empty one.
 *
 * <p>Strategies are found by CDI type. Adding one to a repository type is a bean implementing {@link
 * GcStrategy}; nothing here is edited, and nothing here knows what a tag or a version is.
 *
 * <p>Two failure shapes are reported rather than thrown, because a report that 500s tells a reviewer
 * nothing about the other four types: a strategy that refuses to plan (fail-closed — its type keeps
 * every blob), and two strategies claiming one type, which is a policy collision and never a merge.
 *
 * <p><b>The report also echoes the configuration</b> ({@link GcRules}): per type, the configured
 * engine, its window and the effective rule as a sentence. The settlement moved the policy into
 * configuration, and configuration is the half of a plan the dead and kept lists cannot show —
 * "nothing died" reads identically whether the rule is right or the window is a year. For the two
 * cache types the echo now reads as the rule that actually ran: {@link CacheEvictionStrategy}
 * writes the sentence and the same class produced the plan beside it.
 */
@ApplicationScoped
public class GcPlanner {

  @Inject LiveBlobCensus census;
  @Inject BlobSweep sweep;
  @Inject Instance<GcStrategy> strategies;
  @Inject GcTypeConfig config;
  @Inject GcPinSources pinSources;

  /**
   * A fresh census, every live pin, every registered strategy, and the reconciliation over all of
   * them.
   *
   * <p>The pins are read <b>first</b> and once. A source that cannot answer does not make this
   * method throw — a dry-run that 500s tells a reviewer nothing about the types that are fine — so
   * the report comes back with the pin-dependent types failed and {@code executable} false.
   */
  public GcPlanReport plan() {
    return plan(census.take(), registered(), pinSources.fetch());
  }

  /** What CDI found. Empty is the shipped state and a supported one. */
  List<GcStrategy> registered() {
    return strategies.stream().toList();
  }

  GcPlanReport plan(
      LiveBlobCensus.Census census, Collection<GcStrategy> strategies, GcPins pins) {
    Map<RepositoryType, List<GcStrategy>> claimants = new EnumMap<>(RepositoryType.class);
    for (GcStrategy strategy : strategies) {
      claimants.computeIfAbsent(strategy.type(), type -> new ArrayList<>()).add(strategy);
    }

    Map<RepositoryType, GcStrategy.Plan> plans = new EnumMap<>(RepositoryType.class);
    List<GcTypePlan> types = new ArrayList<>();
    for (RepositoryType type : RepositoryType.values()) {
      List<GcStrategy> claiming = claimants.getOrDefault(type, List.of());
      if (claiming.isEmpty()) {
        types.add(unclaimed(type));
        continue;
      }
      if (claiming.size() > 1) {
        types.add(
            failed(
                type,
                names(claiming),
                "two strategies claim this type; a type has exactly one policy, and merging them"
                    + " is never the answer"));
        continue;
      }
      GcStrategy strategy = claiming.get(0);
      String name = nameOf(strategy);
      if (strategy.readsPins() && !pins.complete()) {
        // Not asked to plan at all: its keep-set is partly qits-cd's or qits-ci's answer, and
        // planning it against "nothing is pinned" is the one mistake that condemns everything.
        types.add(failed(type, name, "live pins unavailable — " + pins.whyIncomplete()));
        continue;
      }
      try {
        GcStrategy.Plan plan = strategy.plan(census, pins);
        plans.put(type, plan);
        GcSweepPlan attributed = sweep.planForOneType(census, type, plan);
        types.add(
            new GcTypePlan(
                type,
                name,
                GcRules.note(config, type, strategy.note()),
                null,
                plan.dead(),
                plan.kept(),
                plan.blobsReleased().size(),
                attributed.blobCount(),
                attributed.reclaimableBytes()));
      } catch (RuntimeException aborted) {
        // Fail-closed: no entry in `plans`, so the sweep keeps this type's whole census set. A
        // strategy whose keep-set comes from elsewhere (the OCI rule reads qits-cd's live pins)
        // must land here rather than plan on facts it could not fetch.
        types.add(failed(type, name, message(aborted)));
      }
    }

    // The configuration echo, beside the outcomes rather than instead of them: what each type is
    // configured to do today, in the sentence the engine that will do it writes for itself.
    List<GcTypeConfiguration> configuration = GcRules.echo(config);
    GcSweepPlan sweepPlan = sweep.plan(census, plans);
    String graceWindow = iso(sweep.graceWindow());
    return new GcPlanReport(
        // First in the record, so it is first on the wire and first on the page: a reviewer reads
        // the summary, then goes looking in the detail for the line that surprised them.
        GcSummary.of(configuration, types, sweepPlan, pins, graceWindow),
        census.takenAt(),
        true,
        graceWindow,
        // Executable only when every pin source answered: a plan whose keep-set is missing a live
        // pin is a plan nobody may execute, and saying so on the report is how a reader knows the
        // zeros beside a pin-dependent type are a refusal rather than a finding.
        pins.complete(),
        pins.failures(),
        // How the pins were read, which is the provenance of every keep the report claims below.
        pins.sources(),
        configuration,
        types,
        sweepPlan,
        sweep.untouchable(census));
  }

  private static GcTypePlan unclaimed(RepositoryType type) {
    return new GcTypePlan(
        type,
        null,
        "no strategy registered for " + type.wireName(),
        null,
        List.of(),
        List.of(),
        0,
        0,
        0L);
  }

  private static GcTypePlan failed(RepositoryType type, String strategy, String error) {
    return new GcTypePlan(type, strategy, null, error, List.of(), List.of(), 0, 0, 0L);
  }

  private static String names(List<GcStrategy> claiming) {
    return String.join(", ", claiming.stream().map(GcPlanner::nameOf).sorted().toList());
  }

  /**
   * The class a reviewer would recognise, not the class CDI handed over.
   *
   * <p>A normal-scoped bean is injected as a client proxy, so {@code getClass().getSimpleName()}
   * reads {@code SomeGcStrategy_ClientProxy} — a name that appears in no source file and sends
   * whoever greps for it nowhere. Every strategy so far is {@code @Singleton} (a pseudo-scope, so no
   * proxy) and the shipped names are asserted, but that is a choice each new strategy has to make
   * correctly, and the report is the wrong place to find out it was made wrong. Unwrapping here
   * costs one call and removes the question.
   */
  static String nameOf(Object strategy) {
    return ClientProxy.unwrap(strategy).getClass().getSimpleName();
  }

  /**
   * The window, spelled the way it is configured. {@code Duration.toString()} normalises days into
   * hours — the shipped {@code P7D} prints as {@code PT168H} — and a safety window a reviewer has to
   * divide by 24 is a safety window they will misread. Package-visible because the sweep receipt
   * spells the same window and must spell it the same way.
   */
  static String iso(Duration window) {
    return window.toDaysPart() > 0 && window.minusDays(window.toDaysPart()).isZero()
        ? "P" + window.toDaysPart() + "D"
        : window.toString();
  }

  private static String message(RuntimeException aborted) {
    String message = aborted.getMessage();
    return aborted.getClass().getSimpleName() + (message == null ? "" : ": " + message);
  }
}
