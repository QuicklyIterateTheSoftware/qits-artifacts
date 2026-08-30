package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.blobstore.control.CiScreenshotsProfile;
import eu.wohlben.qits.blobstore.control.CiVideosProfile;
import eu.wohlben.qits.artifacts.control.DaemonBinariesProfile;
import eu.wohlben.qits.artifacts.control.DocsProfile;
import eu.wohlben.qits.blobstore.control.RepositoryTypeProfiles;
import eu.wohlben.qits.artifacts.control.MavenPackagesProfile;
import eu.wohlben.qits.artifacts.control.NpmPackagesProfile;
import eu.wohlben.qits.artifacts.control.OciImagesProfile;
import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import eu.wohlben.qits.artifacts.gc.dto.GcPlanReport;
import eu.wohlben.qits.artifacts.gc.dto.GcTypeConfiguration;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.TreeMap;
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
 * <p>Every type here is on the OWN engine or excluded. The three cache types went to
 * qits-platform-mirror with the eviction engine that collected them, so what is left is the
 * pin-based half of the settlement: {@code oci-images}, {@code npm-packages}, {@code
 * maven-packages}, {@code daemon-binaries} and {@code docs} collected, the two CI types excluded.
 */
@QuarkusTest
class GcTypeConfigTest extends GcFixture {

  @Inject GcTypeConfig config;
  @Inject GcPlanner planner;
  @Inject RepositoryTypeProfiles repositoryTypes;

  @Test
  void everyRepositoryTypeIsConfiguredAndTheShippedValuesAreTheSettlements() {
    // The settlement's own numbers: P30D for oci-images and npm-packages, P90D for maven-packages,
    // daemon-binaries and docs; the two CI types excluded and honest about it. Looping over the
    // REGISTERED types rather than listing cases is what makes a newly contributed profile fail here
    // — a type with no policy is a decision nobody took, not a default.
    Map<String, GcPolicy> strategies = new TreeMap<>();
    Map<String, Optional<Duration>> windows = new TreeMap<>();
    for (String type : repositoryTypes.keys()) {
      strategies.put(type, config.of(type).strategy());
      windows.put(type, config.of(type).window());
    }

    assertEquals(GcPolicy.OWN, strategies.get(OciImagesProfile.KEY));
    assertEquals(GcPolicy.OWN, strategies.get(NpmPackagesProfile.KEY));
    assertEquals(GcPolicy.OWN, strategies.get(MavenPackagesProfile.KEY));
    assertEquals(GcPolicy.OWN, strategies.get(DaemonBinariesProfile.KEY));
    assertEquals(GcPolicy.OWN, strategies.get(DocsProfile.KEY));
    assertEquals(GcPolicy.EXCLUDED, strategies.get(CiScreenshotsProfile.KEY));
    assertEquals(GcPolicy.EXCLUDED, strategies.get(CiVideosProfile.KEY));
    assertEquals(
        7,
        strategies.size(),
        "the cache types are not merely unconfigured here — they are unregistered");

    assertEquals(Duration.ofDays(30), config.requireWindow(OciImagesProfile.KEY));
    assertEquals(Duration.ofDays(30), config.requireWindow(NpmPackagesProfile.KEY));
    assertEquals(Duration.ofDays(90), config.requireWindow(MavenPackagesProfile.KEY));
    assertEquals(Duration.ofDays(90), config.requireWindow(DaemonBinariesProfile.KEY));
    assertEquals(Duration.ofDays(90), config.requireWindow(DocsProfile.KEY));
    assertEquals(Optional.empty(), windows.get(CiScreenshotsProfile.KEY));
    assertEquals(Optional.empty(), windows.get(CiVideosProfile.KEY));
  }

  @Test
  void thePlanEchoesEveryTypesStrategyWindowAndEffectiveRule() {
    // The echo is what a reviewer reads the mapping off, so it carries the sentence rather than the
    // enum: "own, P90D" is not reviewable, "keep the last 2 released versions … delete the rest once
    // unaccessed for longer than P90D" is.
    GcPlanReport report = planner.plan();

    assertEquals(
        List.of(
            "ci-screenshots",
            "ci-videos",
            "daemon-binaries",
            "docs",
            "maven-packages",
            "npm-packages",
            "oci-images"),
        report.configuration().stream().map(GcTypeConfiguration::type).toList(),
        "one line per registered type, in the registry's own order, so the echo and the per-type"
            + " plans read down the page together");

    GcTypeConfiguration maven = line(report, MavenPackagesProfile.KEY);
    assertEquals("own", maven.strategy());
    assertEquals("P90D", maven.window());
    assertTrue(maven.rule().contains("last 2 released versions"));
    assertTrue(maven.rule().contains("P90D"));

    GcTypeConfiguration videos = line(report, CiVideosProfile.KEY);
    assertEquals("excluded", videos.strategy());
    assertNull(videos.window(), "a window beside a type nobody collects reads as a running rule");
    assertEquals(GcRules.EXCLUDED, videos.rule());
  }

  @Test
  void everyConfiguredTypeCollectsAndTheExcludedOnesStillCondemnNothing() throws Exception {
    // The definition of done for this workstream, and the shape of the comparison it inherits.
    // Everything is seeded and aged past its own window; the plan is taken with complete pins so
    // the change under test is the POLICY rather than this suite's closed pin ports.
    //
    // What changed, deliberately: npm-packages condemns a cold prerelease, and maven-packages
    // condemns the third release version of an artifact — as ONE coordinate, jar and pom together,
    // which is the visible half of maven's new identity model. What must not have changed: the four
    // types moved by earlier workstreams, and the two CI types, which condemn nothing by
    // configuration.
    Store store = seed();
    seedMaven();
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
    // A cold prerelease beside the fixture's two warm releases, and a third release version of a
    // second maven artifact — the two identities this workstream's rules condemn.
    String coldTarball = store(filled(67, (byte) 67));
    backdate(coldTarball, Duration.ofDays(30));
    npmVersionRow("@qits/thing", "0.9.0-main.gaaaaaa1", coldTarball, daysAgo(400));
    String coldJar = store(filled(68, (byte) 68));
    backdate(coldJar, Duration.ofDays(30));
    mavenRow(OTHER_ARTIFACT + "/1.0.0/qits-other-1.0.0.jar", coldJar, daysAgo(400));
    mavenRow(
        OTHER_ARTIFACT + "/1.1.0/qits-other-1.1.0.jar",
        store(filled(69, (byte) 69)),
        daysAgo(390));
    mavenRow(
        OTHER_ARTIFACT + "/2.0.0/qits-other-2.0.0.jar",
        store(filled(70, (byte) 70)),
        daysAgo(380));

    GcPlanReport report = planner.plan(census.take(), planner.registered(), GcPins.none());

    Map<String, List<String>> dead = new LinkedHashMap<>();
    Map<String, List<String>> kept = new LinkedHashMap<>();
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

    // The two that moved in this workstream.
    assertEquals(
        List.of("alpha:" + COLD_SHA),
        dead.get(RepositoryTypeProfile.wireNameOf(OciImagesProfile.KEY)),
        "a build sha no pin holds and no deploy would pull, cold past P30D");
    assertEquals(
        List.of("alpha:" + WARM_SHA, "alpha:v1", "alpha:v2"),
        kept.get(RepositoryTypeProfile.wireNameOf(OciImagesProfile.KEY)),
        "the newest build stays for the deploy that has not happened, and the warm tags stay on use");
    assertEquals(
        List.of(DAEMON + "@2026.601.10"),
        dead.get(RepositoryTypeProfile.wireNameOf(DaemonBinariesProfile.KEY)),
        "the third version down a belt of two, and nothing has launched it in a year");
    assertEquals(
        List.of(DAEMON + "@2026.701.20", DAEMON + "@2026.801.30"),
        kept.get(RepositoryTypeProfile.wireNameOf(DaemonBinariesProfile.KEY)));

    // The two that moved in this workstream.
    assertEquals(
        List.of("@qits/thing@0.9.0-main.gaaaaaa1"),
        dead.get(RepositoryTypeProfile.wireNameOf(NpmPackagesProfile.KEY)),
        "a prerelease earns no belt, and nothing has installed it in a year");
    assertEquals(
        List.of("@qits/thing@1.0.0", "@qits/thing@1.1.0"), kept.get(RepositoryTypeProfile.wireNameOf(NpmPackagesProfile.KEY)));
    assertEquals(
        List.of("eu.wohlben.qits:qits-other:1.0.0"),
        dead.get(RepositoryTypeProfile.wireNameOf(MavenPackagesProfile.KEY)),
        "one coordinate, not one path — the jar of a version the belt no longer covers");
    assertEquals(
        List.of(
            "eu.wohlben.qits:qits-eventstream:1.0.0",
            "eu.wohlben.qits:qits-other:1.1.0",
            "eu.wohlben.qits:qits-other:2.0.0"),
        kept.get(RepositoryTypeProfile.wireNameOf(MavenPackagesProfile.KEY)),
        "the jar and pom of the fixture's release are one identity now");

    // The two nobody collects — excluded by the settlement, and still saying so.
    for (String type : List.of(CiScreenshotsProfile.KEY, CiVideosProfile.KEY)) {
      String wire = RepositoryTypeProfile.wireNameOf(type);
      assertEquals(List.of(), dead.get(wire), wire + " must still condemn nothing");
      assertEquals(List.of(), kept.get(wire));
    }

    // The blob half of the same comparison: the cold daemon binary, the cold published tarball and
    // the cold jar. The cold image tag frees nothing on its own — its manifest is still named by the
    // tags beside it, which is the reconciliation doing its job.
    assertEquals(
        List.of(coldDaemon, coldTarball, coldJar).stream().sorted().toList(),
        report.sweep().blobIds());
    assertEquals(List.of(store.rowless()), report.untouchable().blobIds());
  }

  @Test
  void everyPinReadingTypeRefusesToPlanWhileThePinSourcesCannotAnswer() throws Exception {
    // The other side of the same wiring, on the report a deployment actually gets when qits-platform-deployments or
    // qits-ci is down: every type on an engine reads pins, so every one of them is refused rather
    // than planned against "nothing is pinned". This suite's pin urls are closed ports, which is
    // that state exactly.
    seed();

    GcPlanReport report = planner.plan();

    assertFalse(report.executable());
    for (String type :
        List.of(
            OciImagesProfile.KEY,
            NpmPackagesProfile.KEY,
            MavenPackagesProfile.KEY,
            DaemonBinariesProfile.KEY,
            DocsProfile.KEY)) {
      assertTrue(
          typePlan(report, type).error().contains("live pins unavailable"),
          RepositoryTypeProfile.wireNameOf(type) + ": " + typePlan(report, type).error());
      assertEquals(List.of(), typePlan(report, type).dead());
    }
    assertEquals(0, report.sweep().blobCount(), "a refusal reclaims nothing, by construction");
  }

  // --- fixture ---------------------------------------------------------------------------------

  private static final String COLD_SHA = "a".repeat(40);
  private static final String WARM_SHA = "b".repeat(40);
  private static final String DAEMON = "qits-ci-daemon";
  private static final String OTHER_ARTIFACT = "eu/wohlben/qits/qits-other";

  private void daemonRepository() {
    repositoryService.ensure(DAEMON_REPO, DaemonBinariesProfile.KEY);
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

  /** One published npm version, with its publish time under the case's control. */
  private void npmVersionRow(String packageName, String version, String blobId, Instant createdAt) {
    io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
        .run(
            () -> {
              eu.wohlben.qits.artifacts.entity.NpmVersion row =
                  new eu.wohlben.qits.artifacts.entity.NpmVersion();
              row.repository = "npm";
              row.packageName = packageName;
              row.version = version;
              row.tarballBlobId = blobId;
              row.manifestJson = "{}";
              row.createdAt = createdAt;
              npmVersions.persist(row);
            });
  }

  /** One deployed maven file, with its deploy time under the case's control. */
  private void mavenRow(String path, String blobId, Instant createdAt) {
    io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
        .run(
            () -> {
              eu.wohlben.qits.artifacts.entity.MavenArtifact row =
                  new eu.wohlben.qits.artifacts.entity.MavenArtifact();
              row.repository = MAVEN_REPO;
              row.path = path;
              row.blobId = blobId;
              row.sizeBytes = 1;
              row.createdAt = createdAt;
              mavenArtifacts.persist(row);
            });
  }

  private static Instant daysAgo(int days) {
    return Instant.now().minus(Duration.ofDays(days));
  }

  private static GcTypeConfiguration line(GcPlanReport report, String type) {
    return report.configuration().stream()
        .filter(configured -> RepositoryTypeProfile.wireNameOf(type).equals(configured.type()))
        .findFirst()
        .orElseThrow();
  }

  private static eu.wohlben.qits.artifacts.gc.dto.GcTypePlan typePlan(
      GcPlanReport report, String type) {
    List<eu.wohlben.qits.artifacts.gc.dto.GcTypePlan> plans = new ArrayList<>(report.types());
    return plans.stream().filter(plan -> RepositoryTypeProfile.wireNameOf(type).equals(plan.type())).findFirst().orElseThrow();
  }
}
