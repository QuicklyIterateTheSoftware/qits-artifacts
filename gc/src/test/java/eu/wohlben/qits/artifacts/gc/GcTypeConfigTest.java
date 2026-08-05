package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * The settlement as shipped configuration, and the proof that switching the caches on changed
 * <b>only</b> the caches.
 *
 * <p>Three questions. What does this deployment have configured; does the report echo it; and — now
 * that the cache engine answers for {@code oci-mirror} and {@code npm-proxy} — is every other type's
 * plan still identity-for-identity what it was. The third is the one that carries: this is the first
 * behaviour change to what dies on this platform, so the comparison that used to prove "nothing
 * moved" is kept, with the two moving types named explicitly and the other six pinned as before.
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
  void onlyTheTwoCacheTypesCollectNowAndTheOtherSixAreIdentityForIdentityUnchanged()
      throws Exception {
    // The definition of done for the cache workstream, and the shape of the comparison it replaces.
    // The same four seeded types as before, aged past the window; the plan is taken with complete
    // pins so the change under test is the POLICY rather than this suite's closed pin ports.
    //
    // What changed, deliberately: oci-mirror and npm-proxy now condemn their cold identities.
    // What must not have changed: the other six types' dead and kept sets, which are still exactly
    // what the per-type strategies produced before any engine was wired to anything.
    Store store = seed();
    MirrorStore mirror = seedMirror();
    seedMaven();
    ProxyStore proxy = seedProxy();
    ageMirrorRows(Duration.ofDays(60));

    GcPlanReport report = planner.plan(census.take(), planner.registered(), GcPins.none());

    Map<RepositoryType, List<String>> dead = new LinkedHashMap<>();
    Map<RepositoryType, List<String>> kept = new LinkedHashMap<>();
    report
        .types()
        .forEach(
            plan -> {
              dead.put(
                  plan.type(),
                  plan.dead().stream().map(identity -> identity.identity()).sorted().toList());
              kept.put(
                  plan.type(),
                  plan.kept().stream().map(identity -> identity.identity()).sorted().toList());
            });

    // The two that changed.
    assertEquals(
        List.of(MIRROR_IMAGE + "@sha256:" + mirror.child(), MIRROR_IMAGE + ":jdk-25").stream()
            .sorted()
            .toList(),
        dead.get(RepositoryType.OCI_MIRROR),
        "cold cached content is what the settlement configured this type to delete");
    assertEquals(List.of(), kept.get(RepositoryType.OCI_MIRROR));
    assertEquals(
        List.of(PROXY_COLD_PACKAGE + NpmProxyGcAdapter.PACKUMENT, PROXY_COLD_PACKAGE + "@1.3.0"),
        dead.get(RepositoryType.NPM_PROXY));
    assertEquals(
        List.of(PROXY_WARM_PACKAGE + NpmProxyGcAdapter.PACKUMENT, PROXY_WARM_PACKAGE + "@5.3.0"),
        kept.get(RepositoryType.NPM_PROXY),
        "a package installed yesterday keeps its tarball and its document");

    // The other six, unchanged — condemning nothing, keeping exactly what they kept before.
    for (RepositoryType type : RepositoryType.values()) {
      if (type == RepositoryType.OCI_MIRROR || type == RepositoryType.NPM_PROXY) {
        continue;
      }
      assertEquals(List.of(), dead.get(type), type.wireName() + " must still condemn nothing");
    }
    assertEquals(
        List.of("@qits/thing@1.0.0", "@qits/thing@1.1.0"), kept.get(RepositoryType.NPM_PACKAGES));
    assertEquals(
        List.of(MAVEN_JAR_PATH, MAVEN_POM_PATH).stream().sorted().toList(),
        kept.get(RepositoryType.MAVEN_PACKAGES));
    assertEquals(List.of(), kept.get(RepositoryType.CI_SCREENSHOTS));
    assertEquals(List.of(), kept.get(RepositoryType.CI_VIDEOS));
    assertEquals(List.of(), kept.get(RepositoryType.DAEMON_BINARIES));
    assertEquals(
        List.of("alpha:v1", "alpha:v2"),
        kept.get(RepositoryType.OCI_IMAGES),
        "with pins in hand the hosted type plans as it always did: two tags, neither a build sha");

    // The blob half of the same comparison, and it is the first time this platform's plan has
    // proposed unlinking anything: the whole cached image, plus the cold package's tarball, once no
    // identity of any type names them. Every other blob in the store is still named by something.
    assertEquals(
        List.of(
                mirror.child(),
                mirror.config(),
                mirror.index(),
                mirror.layer(),
                proxy.coldTarball())
            .stream()
            .sorted()
            .toList(),
        report.sweep().blobIds());
    assertEquals(List.of(store.rowless()), report.untouchable().blobIds());
  }

  @Test
  void theTwoCacheTypesRefuseToPlanWhileThePinSourcesCannotAnswer() throws Exception {
    // The other side of the same wiring, on the report a deployment actually gets when qits-cd or
    // qits-ci is down: both cache types read pins, so both are refused rather than planned against
    // "nothing is pinned". This suite's pin urls are closed ports, which is that state exactly.
    seedMirror();
    seedProxy();
    ageMirrorRows(Duration.ofDays(60));

    GcPlanReport report = planner.plan();

    assertFalse(report.executable());
    for (RepositoryType type :
        List.of(RepositoryType.OCI_MIRROR, RepositoryType.NPM_PROXY, RepositoryType.OCI_IMAGES)) {
      assertTrue(
          typePlan(report, type).error().contains("live pins unavailable"),
          type.wireName() + ": " + typePlan(report, type).error());
      assertEquals(List.of(), typePlan(report, type).dead());
    }
    assertEquals(0, report.sweep().blobCount(), "a refusal reclaims nothing, by construction");
  }

  @Test
  void everyReportCarriesTheProxysH2HonestyLine() throws Exception {
    // reclaimableBytes counts files, and a packument is not one. Without this line on the type's
    // own report line, a run that condemned a hundred documents reads as a run that did nothing.
    seedProxy();

    GcPlanReport report = planner.plan(census.take(), planner.registered(), GcPins.none());

    String note = typePlan(report, RepositoryType.NPM_PROXY).note();
    assertNotNull(note);
    assertTrue(note.contains("SHUTDOWN COMPACT"), note);
    assertTrue(note.contains("0 bytes"), note);
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
