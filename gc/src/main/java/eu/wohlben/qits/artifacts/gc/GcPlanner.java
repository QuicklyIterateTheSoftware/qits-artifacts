package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.control.LiveBlobCensus;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.gc.dto.GcPlanReport;
import eu.wohlben.qits.artifacts.gc.dto.GcSweepPlan;
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
 */
@ApplicationScoped
public class GcPlanner {

  @Inject LiveBlobCensus census;
  @Inject BlobSweep sweep;
  @Inject Instance<GcStrategy> strategies;

  /** A fresh census, every registered strategy, and the reconciliation over both. */
  public GcPlanReport plan() {
    return plan(census.take(), registered());
  }

  /** What CDI found. Empty is the shipped state and a supported one. */
  List<GcStrategy> registered() {
    return strategies.stream().toList();
  }

  GcPlanReport plan(LiveBlobCensus.Census census, Collection<GcStrategy> strategies) {
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
      try {
        GcStrategy.Plan plan = strategy.plan(census);
        plans.put(type, plan);
        GcSweepPlan attributed = sweep.planForOneType(census, type, plan);
        types.add(
            new GcTypePlan(
                type,
                name,
                strategy.note(),
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

    return new GcPlanReport(
        census.takenAt(),
        true,
        iso(sweep.graceWindow()),
        types,
        sweep.plan(census, plans),
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
