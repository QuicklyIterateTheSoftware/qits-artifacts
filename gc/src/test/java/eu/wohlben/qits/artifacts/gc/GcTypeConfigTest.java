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
import java.time.Instant;
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
 * <p>Three questions. What does this deployment have configured; does the report echo it; and —
 * type by type — is every plan this workstream did not touch still identity-for-identity what it
 * was. The third is the one that carries, and it is why this comparison is edited <b>deliberately,
 * once per workstream</b>: the types moving onto an engine get their new dead sets written out here,
 * and every other type stays pinned exactly as it was. A diff that quietly widened would be the one
 * change nobody could review.
 *
 * <p>Moved so far: {@code oci-mirror} and {@code npm-proxy} onto the cache engine, then {@code
 * oci-images} and {@code daemon-binaries} onto the own engine.
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
  void thetwoCachesAndTheTwoOwnTypesCollectAndTheRestIsIdentityForIdentityUnchanged()
      throws Exception {
    // The definition of done for this workstream, and the shape of the comparison it inherits.
    // Everything is seeded and aged past its own window; the plan is taken with complete pins so
    // the change under test is the POLICY rather than this suite's closed pin ports.
    //
    // What changed, deliberately: oci-images condemns a cold build sha that no pin and no
    // newest-build belt holds, and daemon-binaries condemns the third version down its belt.
    // What must not have changed: npm-packages, maven-packages and the two CI types, which are
    // still exactly what their own strategies produced.
    Store store = seed();
    MirrorStore mirror = seedMirror();
    seedMaven();
    ProxyStore proxy = seedProxy();
    ageMirrorRows(Duration.ofDays(60));
    // One image, two build shas: the older one is what the access rule condemns, the newer one is
    // the pull target the belt protects for an image cd has never deployed.
    ociTag("alpha", COLD_SHA, store.manifestKept(), Instant.now().minus(Duration.ofDays(60)));
    ociTag("alpha", WARM_SHA, store.manifestKept(), Instant.now());
    String coldDaemon = store(filled(64, (byte) 64));
    backdate(coldDaemon, Duration.ofDays(30));
    daemonRepository();
    daemonRow(DAEMON, "2026.601.10", coldDaemon, daysAgo(400), null);
    daemonRow(DAEMON, "2026.701.20", store(filled(65, (byte) 65)), daysAgo(300), null);
    daemonRow(DAEMON, "2026.801.30", store(filled(66, (byte) 66)), daysAgo(200), null);

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

    // The two caches, unchanged from the workstream that moved them.
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

    // The two that moved in this workstream.
    assertEquals(
        List.of("alpha:" + COLD_SHA),
        dead.get(RepositoryType.OCI_IMAGES),
        "a build sha no pin holds and no deploy would pull, cold past P30D");
    assertEquals(
        List.of("alpha:" + WARM_SHA, "alpha:v1", "alpha:v2"),
        kept.get(RepositoryType.OCI_IMAGES),
        "the newest build stays for the deploy that has not happened, and the warm tags stay on use");
    assertEquals(
        List.of(DAEMON + "@2026.601.10"),
        dead.get(RepositoryType.DAEMON_BINARIES),
        "the third version down a belt of two, and nothing has launched it in a year");
    assertEquals(
        List.of(DAEMON + "@2026.701.20", DAEMON + "@2026.801.30"),
        kept.get(RepositoryType.DAEMON_BINARIES));

    // The four this workstream did not touch — condemning nothing, keeping exactly what they kept.
    for (RepositoryType type :
        List.of(
            RepositoryType.NPM_PACKAGES,
            RepositoryType.MAVEN_PACKAGES,
            RepositoryType.CI_SCREENSHOTS,
            RepositoryType.CI_VIDEOS)) {
      assertEquals(List.of(), dead.get(type), type.wireName() + " must still condemn nothing");
    }
    assertEquals(
        List.of("@qits/thing@1.0.0", "@qits/thing@1.1.0"), kept.get(RepositoryType.NPM_PACKAGES));
    assertEquals(
        List.of(MAVEN_JAR_PATH, MAVEN_POM_PATH).stream().sorted().toList(),
        kept.get(RepositoryType.MAVEN_PACKAGES));
    assertEquals(List.of(), kept.get(RepositoryType.CI_SCREENSHOTS));
    assertEquals(List.of(), kept.get(RepositoryType.CI_VIDEOS));

    // The blob half of the same comparison: the whole cached image, the cold package's tarball, and
    // now the cold daemon binary. The cold image tag frees nothing on its own — its manifest is
    // still named by the tags beside it, which is the reconciliation doing its job.
    assertEquals(
        List.of(
                mirror.child(),
                mirror.config(),
                mirror.index(),
                mirror.layer(),
                proxy.coldTarball(),
                coldDaemon)
            .stream()
            .sorted()
            .toList(),
        report.sweep().blobIds());
    assertEquals(List.of(store.rowless()), report.untouchable().blobIds());
  }

  @Test
  void everyPinReadingTypeRefusesToPlanWhileThePinSourcesCannotAnswer() throws Exception {
    // The other side of the same wiring, on the report a deployment actually gets when qits-cd or
    // qits-ci is down: every type on an engine reads pins, so every one of them is refused rather
    // than planned against "nothing is pinned". This suite's pin urls are closed ports, which is
    // that state exactly.
    seedMirror();
    seedProxy();
    ageMirrorRows(Duration.ofDays(60));

    GcPlanReport report = planner.plan();

    assertFalse(report.executable());
    for (RepositoryType type :
        List.of(
            RepositoryType.OCI_MIRROR,
            RepositoryType.NPM_PROXY,
            RepositoryType.OCI_IMAGES,
            RepositoryType.DAEMON_BINARIES)) {
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

  // --- fixture ---------------------------------------------------------------------------------

  private static final String COLD_SHA = "a".repeat(40);
  private static final String WARM_SHA = "b".repeat(40);
  private static final String DAEMON = "qits-ci-daemon";

  private void daemonRepository() {
    repositoryService.ensure(DAEMON_REPO, RepositoryType.DAEMON_BINARIES);
  }

  /** One more tag on the substrate's own image, with {@code updated_at} under the case's control. */
  private void ociTag(String image, String name, String digest, Instant updatedAt) {
    io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
        .run(
            () -> {
              eu.wohlben.qits.artifacts.entity.OciTag row =
                  new eu.wohlben.qits.artifacts.entity.OciTag();
              row.repository = "qits";
              row.imageName = image;
              row.tag = name;
              row.manifestDigest = digest;
              row.updatedAt = updatedAt;
              ociTags.persist(row);
            });
  }

  private static Instant daysAgo(int days) {
    return Instant.now().minus(Duration.ofDays(days));
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
