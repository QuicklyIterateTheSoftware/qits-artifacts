package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.gc.dto.GcPlanReport;
import eu.wohlben.qits.artifacts.gc.dto.GcTypeConfiguration;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The settlement as shipped configuration, and the proof that shipping it changed nothing yet.
 *
 * <p>Three questions, and the third is the one that matters most today. What does this deployment
 * have configured; does the report echo it; and — with the two engines present but wired to nothing
 * — is what the collector would delete <b>exactly what it would have deleted before</b>. The engines
 * ship dark on purpose: the per-type strategies still answer the planner, and the day that changes
 * is a workstream with its own review.
 */
@QuarkusTest
class GcTypeConfigTest extends GcFixture {

  @Inject GcTypeConfig config;
  @Inject GcPlanner planner;

  @Test
  void everyRepositoryTypeIsConfiguredAndTheShippedValuesAreTheSettlements() {
    // The settlement's own numbers: P30D for the caches and for oci-images/npm-packages, P90D for
    // maven-packages and daemon-binaries; the two CI types excluded and honest about it. Looping
    // over values() rather than listing eight cases is what makes a new RepositoryType constant fail
    // here — a type with no policy is a decision nobody took, not a default.
    Map<RepositoryType, GcPolicy> strategies = new EnumMap<>(RepositoryType.class);
    Map<RepositoryType, Optional<Duration>> windows = new EnumMap<>(RepositoryType.class);
    for (RepositoryType type : RepositoryType.values()) {
      strategies.put(type, config.of(type).strategy());
      windows.put(type, config.of(type).window());
    }

    assertEquals(GcPolicy.CACHE, strategies.get(RepositoryType.OCI_MIRROR));
    assertEquals(GcPolicy.CACHE, strategies.get(RepositoryType.NPM_PROXY));
    assertEquals(GcPolicy.OWN, strategies.get(RepositoryType.OCI_IMAGES));
    assertEquals(GcPolicy.OWN, strategies.get(RepositoryType.NPM_PACKAGES));
    assertEquals(GcPolicy.OWN, strategies.get(RepositoryType.MAVEN_PACKAGES));
    assertEquals(GcPolicy.OWN, strategies.get(RepositoryType.DAEMON_BINARIES));
    assertEquals(GcPolicy.EXCLUDED, strategies.get(RepositoryType.CI_SCREENSHOTS));
    assertEquals(GcPolicy.EXCLUDED, strategies.get(RepositoryType.CI_VIDEOS));

    assertEquals(Duration.ofDays(30), config.requireWindow(RepositoryType.OCI_MIRROR));
    assertEquals(Duration.ofDays(30), config.requireWindow(RepositoryType.NPM_PROXY));
    assertEquals(Duration.ofDays(30), config.requireWindow(RepositoryType.OCI_IMAGES));
    assertEquals(Duration.ofDays(30), config.requireWindow(RepositoryType.NPM_PACKAGES));
    assertEquals(Duration.ofDays(90), config.requireWindow(RepositoryType.MAVEN_PACKAGES));
    assertEquals(Duration.ofDays(90), config.requireWindow(RepositoryType.DAEMON_BINARIES));
    assertEquals(Optional.empty(), windows.get(RepositoryType.CI_SCREENSHOTS));
    assertEquals(Optional.empty(), windows.get(RepositoryType.CI_VIDEOS));
  }

  @Test
  void thePlanEchoesEveryTypesStrategyWindowAndEffectiveRule() {
    // The echo is what a reviewer reads the mapping off, so it carries the sentence rather than the
    // enum: "own, P90D" is not reviewable, "keep the last 2 released versions … delete the rest once
    // unaccessed for longer than P90D" is.
    GcPlanReport report = planner.plan();

    assertEquals(RepositoryType.values().length, report.configuration().size());
    assertEquals(
        List.of(RepositoryType.values()),
        report.configuration().stream().map(GcTypeConfiguration::type).toList(),
        "in the enum's own order, so the echo and the per-type plans read down the page together");

    GcTypeConfiguration mirror = line(report, RepositoryType.OCI_MIRROR);
    assertEquals("cache", mirror.strategy());
    assertEquals("P30D", mirror.window());
    assertTrue(mirror.rule().contains("unaccessed for longer than P30D"));
    assertTrue(mirror.rule().contains("Creation counts as the first access"));

    GcTypeConfiguration maven = line(report, RepositoryType.MAVEN_PACKAGES);
    assertEquals("own", maven.strategy());
    assertEquals("P90D", maven.window());
    assertTrue(maven.rule().contains("last 2 released versions"));
    assertTrue(maven.rule().contains("P90D"));

    GcTypeConfiguration videos = line(report, RepositoryType.CI_VIDEOS);
    assertEquals("excluded", videos.strategy());
    assertNull(videos.window(), "a window beside a type nobody collects reads as a running rule");
    assertEquals(GcRules.EXCLUDED, videos.rule());
  }

  @Test
  void theEnginesAreDarkSoWhatDiesAndWhatIsKeptIsUnchanged() throws Exception {
    // The definition of done for this workstream, asserted rather than asserted-by-absence. Three
    // seeded types with real rows, and the plan is identity-for-identity the one the six per-type
    // strategies produced before the engines and the configuration existed: nothing dies anywhere,
    // oci-images still aborts on an unreachable qits-cd, and every keep is still the rule its own
    // strategy names.
    Store store = seed();
    MirrorStore mirror = seedMirror();
    seedMaven();

    GcPlanReport report = planner.plan();

    Map<RepositoryType, List<String>> dead = new LinkedHashMap<>();
    Map<RepositoryType, List<String>> kept = new LinkedHashMap<>();
    report
        .types()
        .forEach(
            plan -> {
              dead.put(plan.type(), plan.dead().stream().map(identity -> identity.identity()).toList());
              kept.put(
                  plan.type(), plan.kept().stream().map(identity -> identity.identity()).sorted().toList());
            });

    for (RepositoryType type : RepositoryType.values()) {
      assertEquals(List.of(), dead.get(type), type.wireName() + " must still condemn nothing");
    }
    assertEquals(
        List.of("@qits/thing@1.0.0", "@qits/thing@1.1.0"), kept.get(RepositoryType.NPM_PACKAGES));
    assertEquals(
        List.of(MAVEN_JAR_PATH, MAVEN_POM_PATH).stream().sorted().toList(),
        kept.get(RepositoryType.MAVEN_PACKAGES));
    assertEquals(
        List.of(MIRROR_IMAGE + "@sha256:" + mirror.child(), MIRROR_IMAGE + ":jdk-25").stream()
            .sorted()
            .toList(),
        kept.get(RepositoryType.OCI_MIRROR));
    assertEquals(List.of(), kept.get(RepositoryType.CI_SCREENSHOTS));
    assertEquals(List.of(), kept.get(RepositoryType.CI_VIDEOS));
    assertEquals(List.of(), kept.get(RepositoryType.NPM_PROXY));
    assertEquals(List.of(), kept.get(RepositoryType.DAEMON_BINARIES));
    assertNotNull(
        typePlan(report, RepositoryType.OCI_IMAGES).error(),
        "no qits-cd in this suite: the type still aborts fail-closed rather than planning");

    assertEquals(0, report.sweep().blobCount(), "nothing is swept, exactly as before");
    assertEquals(List.of(store.rowless()), report.untouchable().blobIds());
  }

  private static GcTypeConfiguration line(GcPlanReport report, RepositoryType type) {
    return report.configuration().stream()
        .filter(configured -> configured.type() == type)
        .findFirst()
        .orElseThrow();
  }

  private static eu.wohlben.qits.artifacts.gc.dto.GcTypePlan typePlan(
      GcPlanReport report, RepositoryType type) {
    List<eu.wohlben.qits.artifacts.gc.dto.GcTypePlan> plans = new ArrayList<>(report.types());
    return plans.stream().filter(plan -> plan.type() == type).findFirst().orElseThrow();
  }
}
