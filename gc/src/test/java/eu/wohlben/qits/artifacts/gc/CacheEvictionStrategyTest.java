package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The cache engine's whole rule: unaccessed past the window dies, a pin outranks the window, and
 * creation counts as the first access.
 *
 * <p>Plain JUnit against {@link FakeGcTypeAdapter}, deliberately: what is under test is the rule,
 * and driving it through a real type would prove that type's adapter works instead. The engine
 * ships dark — the per-type strategies still answer the planner — so these cases are the whole
 * proof it behaves as the settlement says before anything is wired to it.
 */
class CacheEvictionStrategyTest {

  private static final Duration WINDOW = Duration.ofDays(30);
  private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");

  private final CacheEvictionStrategy engine = new CacheEvictionStrategy();

  @Test
  void anIdentityUnaccessedPastTheWindowDiesAndOneInsideItStays() {
    // The rule, in one case. The 31-day entry has not been pulled in a month; the 29-day one has,
    // and one day of difference is what separates them — a cache's whole policy is that comparison.
    FakeGcTypeAdapter adapter =
        new FakeGcTypeAdapter()
            .add("hub/library/node:22", "hub/library/node", false, daysAgo(31), "cold")
            .add("hub/library/node:24", "hub/library/node", false, daysAgo(29), "warm");

    GcStrategy.Plan plan = engine.plan(adapter, WINDOW, NOW, GcPinned.NONE);

    assertEquals(List.of("hub/library/node:22"), identities(plan.dead()));
    assertEquals(List.of("hub/library/node:24"), identities(plan.kept()));
    assertEquals(CacheEvictionStrategy.deadUnaccessed(WINDOW), plan.dead().get(0).rule());
    assertEquals(CacheEvictionStrategy.keptAccessed(WINDOW), plan.kept().get(0).rule());
    assertEquals(Set.of("cold"), plan.blobsReleased());
    assertEquals(Set.of("warm"), plan.blobsRetained());
  }

  @Test
  void somethingCachedTodayAndNeverPulledSinceIsYoungRatherThanNeverRead() {
    // "Creation counts as the first access", which is the difference between a working cache policy
    // and one that deletes everything it just fetched. The adapter folds creation into the access
    // time; this case is what says the engine may rely on that.
    FakeGcTypeAdapter adapter =
        new FakeGcTypeAdapter().add("quay/quarkus:jdk-25", "quay/quarkus", false, daysAgo(0), "b");

    GcStrategy.Plan plan = engine.plan(adapter, WINDOW, NOW, GcPinned.NONE);

    assertEquals(List.of(), plan.dead());
    assertEquals(List.of("quay/quarkus:jdk-25"), identities(plan.kept()));
  }

  @Test
  void aPinnedIdentityIsKeptUnderThePinsNameEvenWhenNothingHasReadItForAYear() {
    // Pins are checked BEFORE the access rule, and this is why: a live service holding a reference
    // is a fact no timestamp implies. The rule name is the pin's, so a reviewer sees which service
    // saved it rather than a generic "kept".
    FakeGcTypeAdapter adapter =
        new FakeGcTypeAdapter().add("hub/library/ubi:9", "hub/library/ubi", false, daysAgo(365), "b");
    GcPinned pinned = candidate -> "pinned by a qits-platform-deployments deployment";

    GcStrategy.Plan plan = engine.plan(adapter, WINDOW, NOW, pinned);

    assertEquals(List.of(), plan.dead());
    assertEquals("pinned by a qits-platform-deployments deployment", plan.kept().get(0).rule());
    assertEquals(Set.of("b"), plan.blobsRetained());
  }

  @Test
  void aBlobUnderADyingAndASurvivingIdentityIsInBothSetsBecauseTheEngineNeverSubtracts() {
    // The seam's central promise, restated for the engines: they report both sets, and which blob
    // may actually be unlinked stays BlobSweep's answer across every type at once.
    FakeGcTypeAdapter adapter =
        new FakeGcTypeAdapter()
            .add("hub/node:20", "hub/node", false, daysAgo(90), "base", "old")
            .add("hub/node:24", "hub/node", false, daysAgo(1), "base", "new");

    GcStrategy.Plan plan = engine.plan(adapter, WINDOW, NOW, GcPinned.NONE);

    assertTrue(plan.blobsReleased().contains("base"), "the dying tag did name it");
    assertTrue(plan.blobsRetained().contains("base"), "and the surviving one still does");
  }

  @Test
  void aReleaseHasNoStandingHereBecauseACacheHoldsNoneOfOurs() {
    // The one rule this engine deliberately does NOT have. Upstream calls jdk-25 a release; keeping
    // it forever on that basis is how a mirror never shrinks. Version protection is own-ness's.
    FakeGcTypeAdapter adapter =
        new FakeGcTypeAdapter().add("quay/mandrel:23.1", "quay/mandrel", true, daysAgo(200), "b");

    GcStrategy.Plan plan = engine.plan(adapter, WINDOW, NOW, GcPinned.NONE);

    assertEquals(List.of("quay/mandrel:23.1"), identities(plan.dead()));
  }

  @Test
  void anEmptyTypePlansNothingRatherThanFailing() {
    GcStrategy.Plan plan = engine.plan(new FakeGcTypeAdapter(), WINDOW, NOW, GcPinned.NONE);

    assertEquals(List.of(), plan.dead());
    assertEquals(List.of(), plan.kept());
    assertEquals(Set.of(), plan.blobsReleased());
    assertEquals(Set.of(), plan.blobsRetained());
  }

  @Test
  void theRuleSentenceCarriesTheConfiguredWindowSoAReportCanBeArguedWith() {
    assertTrue(CacheEvictionStrategy.rule(WINDOW).contains("P30D"));
    assertTrue(CacheEvictionStrategy.rule(Duration.ofDays(90)).contains("P90D"));
  }

  private static Instant daysAgo(int days) {
    return NOW.minus(Duration.ofDays(days));
  }

  private static List<String> identities(List<GcIdentity> identities) {
    return identities.stream().map(GcIdentity::identity).toList();
  }
}
