package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.control.LiveBlobCensus;
import eu.wohlben.qits.artifacts.entity.NpmDistTag;
import eu.wohlben.qits.artifacts.entity.NpmVersion;
import eu.wohlben.qits.artifacts.control.NpmPackagesProfile;
import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The own engine over npm's facts, case by case, against real rows.
 *
 * <p><b>This suite is the port of {@code NpmPackagesGcStrategyTest}</b>, and the version strings are
 * the measured ones wherever a case allows it — {@code @qits/ui-components} really does hold two
 * calver releases and four {@code -main.g<sha7>} builds, and the two builds sharing the {@code
 * 2026.801.85149} core are what makes the ordering rule worth testing at all: they differ only in a
 * hex sha, which semver compares as ASCII. A case written with {@code -rc.1} and {@code -rc.2} would
 * order correctly for the wrong reason.
 *
 * <p>What changed with the settlement is what condemns a version: not "a newer main build exists"
 * but "nothing has installed it inside P30D". So every case says how old its rows are, and the
 * releases that used to be kept forever are now kept as the last two — with the third surviving on
 * <em>use</em>, which is the case the structural rule could not have had.
 */
@QuarkusTest
class NpmPackagesGcAdapterTest extends GcFixture {

  /** The configured window for this type, and the number every case below is aged against. */
  private static final Duration WINDOW = Duration.ofDays(30);

  private static final String UI = "@qits/ui-components";

  @Inject NpmPackagesGcStrategy strategy;
  @Inject GcPlanner planner;

  @Test
  void theLastTwoReleasesOfAPackageStayAndTheThirdOneAgesOut() throws Exception {
    // "Releases stay" in its settled npm spelling: last 2 per package, ranked by SEMVER PRECEDENCE
    // rather than by publish order — 2026.801.85149 outranks 2026.801.63140 whichever landed first.
    // All four here are equally cold, so only the belt separates them.
    hosted();
    String oldest = version(UI, "0.0.1", 11, daysAgo(400));
    String second = version(UI, "0.0.4", 12, daysAgo(380));
    version(UI, "2026.801.63140", 13, daysAgo(360));
    version(UI, "2026.801.85149", 14, daysAgo(340));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        Stream.of(UI + "@0.0.1", UI + "@0.0.4").sorted().toList(), identities(plan.dead()));
    assertTrue(
        plan.dead().stream()
            .allMatch(dead -> OwnArtifactsStrategy.deadUnaccessed(WINDOW).equals(dead.rule())));
    assertEquals(
        OwnArtifactsStrategy.KEPT_RELEASE, ruleFor(plan.kept(), UI + "@2026.801.85149"));
    assertEquals(Set.of(oldest, second), plan.blobsReleased(), "one version, one tarball");
  }

  @Test
  void anOlderReleaseSomethingStillInstallsSurvivesOnUseRatherThanOnPolicy() throws Exception {
    // The other half of the settlement's sentence, and the case the old rule could not have had:
    // consumers pin ranges, and ^0.0.1 resolving is a fact about what is being installed rather than
    // about what policy protects. The report says "accessed", not "release", because naming the rule
    // that actually saved a version is the point of the report.
    hosted();
    version(UI, "0.0.1", 21, daysAgo(7));
    version(UI, "2026.801.63140", 22, daysAgo(360));
    version(UI, "2026.801.85149", 23, daysAgo(340));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of(), plan.dead());
    assertEquals(OwnArtifactsStrategy.keptAccessed(WINDOW), ruleFor(plan.kept(), UI + "@0.0.1"));
  }

  @Test
  void aColdPrereleaseDiesAndAWarmOneDoesNotHoweverManyBuildsSitAboveIt() throws Exception {
    // Prereleases earn no belt — own-ness protects releases, and a main build is not one. What used
    // to condemn them structurally the moment a newer build existed now condemns only the ones
    // nothing has installed, which is a loosening: the third build here is superseded twice over and
    // stays, because something pulled it last week.
    hosted();
    version(UI, "2026.801.85149", 31, daysAgo(200));
    String coldest = version(UI, "2026.801.63140-main.gab854a1", 32, daysAgo(400));
    String cold = version(UI, "2026.801.85149-main.g21655ba", 33, daysAgo(300));
    version(UI, "2026.801.85149-main.g0fe7780", 34, daysAgo(400), daysAgo(7));
    String newest = version(UI, "2026.801.85149-main.gd43d710", 35, Instant.now());

    LiveBlobCensus.Census taken = census.take();
    GcStrategy.Plan plan = strategy.plan(taken, GcPins.none());

    assertEquals(
        Stream.of(
                UI + "@2026.801.63140-main.gab854a1", UI + "@2026.801.85149-main.g21655ba")
            .sorted()
            .toList(),
        identities(plan.dead()));
    assertEquals(
        OwnArtifactsStrategy.keptAccessed(WINDOW),
        ruleFor(plan.kept(), UI + "@2026.801.85149-main.g0fe7780"));
    assertEquals(Set.of(coldest, cold), plan.blobsReleased());
    assertTrue(plan.blobsRetained().contains(newest));

    // And through the substrate: a tarball is named by exactly one version, so every released blob
    // loses its last reference and the whole reclaim is real.
    assertEquals(
        Stream.of(coldest, cold).sorted().toList(),
        planner.plan(taken, List.of(strategy), GcPins.none()).sweep().blobIds());
  }

  @Test
  void aDistTagKeepsAVersionTheWindowWouldHaveCondemned() throws Exception {
    // Belt and braces, made to matter: `main` is pointed at a build nothing has installed in a year,
    // which is what a pinned pipeline or a rolled-back release leaves behind. A packument whose
    // dist-tags names a version its versions object does not list is a broken package to every npm
    // client, and no access timestamp should be the only thing standing between here and there.
    hosted();
    version(UI, "2026.801.85149", 41, daysAgo(200));
    String tagged = version(UI, "2026.801.85149-main.g11111aa", 42, daysAgo(400));
    version(UI, "2026.801.85149-main.g22222bb", 43, daysAgo(400));
    distTag(UI, "latest", "2026.801.85149");
    distTag(UI, "main", "2026.801.85149-main.g11111aa");

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        NpmPackagesGcAdapter.keptByDistTag("main"),
        ruleFor(plan.kept(), UI + "@2026.801.85149-main.g11111aa"));
    assertEquals(
        List.of(UI + "@2026.801.85149-main.g22222bb"),
        identities(plan.dead()),
        "only the build that is neither warm nor named");
    assertTrue(plan.blobsRetained().contains(tagged));
  }

  @Test
  void theBeltIsPerPackageAndOnePackagesReleasesNeverSpendAnothers() throws Exception {
    // The rule reads "per package" and the tables are keyed that way, so this is the case that would
    // catch a group that forgot the package name — and a package with fewer releases than the belt
    // keeps all of them.
    hosted();
    version("@qits/angular", "0.0.1", 51, daysAgo(600));
    version(UI, "1.0.0", 52, daysAgo(400));
    version(UI, "1.1.0", 53, daysAgo(390));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of(), plan.dead());
    assertEquals(
        OwnArtifactsStrategy.KEPT_RELEASE, ruleFor(plan.kept(), "@qits/angular@0.0.1"),
        "a package with one release keeps it, however cold");
  }

  @Test
  void aVersionThatIsNotSemverIsNeverAReleaseAndAgesOutOnAccessLikeAnythingElse()
      throws Exception {
    // What cannot be ordered cannot be proved to be the last two of anything, so it earns no belt.
    // It is not thereby condemned for being unrecognised either — the window is what decides, which
    // is narrower than the old keep-forever and wider than deleting it on sight.
    hosted();
    version(UI, "1.0.0", 61, daysAgo(400));
    version(UI, "1.1.0", 62, daysAgo(390));
    version(UI, "nightly", 63, daysAgo(7));
    String coldNightly = version(UI, "rolling", 64, daysAgo(400));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(OwnArtifactsStrategy.keptAccessed(WINDOW), ruleFor(plan.kept(), UI + "@nightly"));
    assertEquals(List.of(UI + "@rolling"), identities(plan.dead()));
    assertEquals(Set.of(coldNightly), plan.blobsReleased());
  }

  @Test
  void aCondemnedVersionLosesItsRowAndGainsItsTombstoneInTheSameTransaction() throws Exception {
    // The tombstone is npm's alone and it STAYS: deleting a version row re-opens that version's
    // name for a publish with different bytes, which is one coordinate resolving to two tarballs
    // over its lifetime. The guarantee is the funnel's rather than this rule's, and the property
    // asserted here is that the collector really goes through it.
    hosted();
    String doomed = version(UI, "1.0.0", 81, daysAgo(400));
    version(UI, "1.1.0", 82, daysAgo(390));
    version(UI, "1.2.0", 83, daysAgo(380));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());
    GcStrategy.Applied applied = strategy.apply(plan, blobId -> false);

    assertEquals(List.of(UI + "@1.0.0"), identities(applied.deleted()));
    assertEquals(List.of(), applied.errors());
    npmVersions.getEntityManager().clear();
    assertTrue(npmVersions.findOne("npm", UI, "1.0.0").isEmpty(), "the row is gone");
    assertTrue(
        npmVersionTombstones.findOne("npm", UI, "1.0.0").isPresent(),
        "and the name can never be silently republished");
  }

  @Test
  void aTarballInsideTheGraceWindowWithholdsTheWholeIdentityRowIntactAndNoTombstone()
      throws Exception {
    // The strand hazard: a row deleted over a young file leaves the file row-less, and row-less
    // blobs are untouchable by construction. No tombstone either — the version is not collected yet.
    hosted();
    String doomed = version(UI, "1.0.0", 91, daysAgo(400));
    version(UI, "1.1.0", 92, daysAgo(390));
    version(UI, "1.2.0", 93, daysAgo(380));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());
    GcStrategy.Applied applied = strategy.apply(plan, blobId -> blobId.equals(doomed));

    assertEquals(List.of(), applied.deleted());
    assertEquals(List.of(UI + "@1.0.0"), identities(applied.withheldByGraceWindow()));
    npmVersions.getEntityManager().clear();
    assertTrue(npmVersions.findOne("npm", UI, "1.0.0").isPresent(), "the row stays");
    assertTrue(npmVersionTombstones.findOne("npm", UI, "1.0.0").isEmpty(), "no tombstone");
  }

  @Test
  void anEmptyRepositoryPlansNothingRatherThanFailing() throws Exception {
    hosted();

    LiveBlobCensus.Census taken = census.take();
    GcStrategy.Plan plan = strategy.plan(taken, GcPins.none());

    assertEquals(List.of(), plan.dead());
    assertEquals(List.of(), plan.kept());
    assertEquals(Set.of(), plan.blobsReleased());
    assertEquals(taken.live(NpmPackagesProfile.KEY).keySet(), plan.blobsRetained());
  }

  @Test
  void againstTheSubstratesOwnFixtureNothingDiesAndTheRetainedSetIsTheCensusSet() throws Exception {
    // The substrate's store: one package at 1.0.0 and 1.1.0, both releases, and one of the two
    // tarballs is the same blob an image layer uses. Nothing dies — and the set handed back is
    // exactly the census's own npm-packages live set, which is what makes "one census, two readers"
    // true here rather than merely intended.
    seed();
    LiveBlobCensus.Census taken = census.take();

    GcStrategy.Plan plan = strategy.plan(taken, GcPins.none());

    assertEquals(List.of(), plan.dead());
    assertEquals(List.of("@qits/thing@1.0.0", "@qits/thing@1.1.0"), identities(plan.kept()));
    assertEquals(taken.live(NpmPackagesProfile.KEY).keySet(), plan.blobsRetained());
    assertEquals(List.of(), planner.plan(taken, List.of(strategy), GcPins.none()).sweep().blobIds());
  }

  // --- fixture ---------------------------------------------------------------------------------

  private void hosted() {
    repositoryService.ensure("npm", NpmPackagesProfile.KEY);
  }

  private String version(String packageName, String version, int size, Instant createdAt)
      throws IOException {
    return version(packageName, version, size, createdAt, null);
  }

  /** A version whose tarball is a real blob, with both of V11's timestamps under the case's control. */
  private String version(
      String packageName, String version, int size, Instant createdAt, Instant accessedAt)
      throws IOException {
    String blobId = store(filled(size, (byte) (size % 251)));
    // Aged past the sweep's grace window, so a case asserts on the reconciliation rather than on
    // what was withheld for being written a moment ago.
    backdate(blobId, Duration.ofDays(30));
    versionRow("npm", packageName, version, blobId, createdAt, accessedAt);
    return blobId;
  }

  private void versionRow(
      String repository,
      String packageName,
      String version,
      String blobId,
      Instant createdAt,
      Instant accessedAt) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              NpmVersion row = new NpmVersion();
              row.repository = repository;
              row.packageName = packageName;
              row.version = version;
              row.tarballBlobId = blobId;
              row.manifestJson = "{}";
              row.createdAt = createdAt;
              row.accessedAt = accessedAt;
              npmVersions.persist(row);
            });
  }

  private void distTag(String packageName, String tag, String version) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              NpmDistTag row = new NpmDistTag();
              row.repository = "npm";
              row.packageName = packageName;
              row.tag = tag;
              row.version = version;
              row.updatedAt = Instant.now();
              npmDistTags.persist(row);
            });
  }

  private static Instant daysAgo(int days) {
    return Instant.now().minus(Duration.ofDays(days));
  }

  private static List<String> identities(List<GcIdentity> identities) {
    return identities.stream().map(GcIdentity::identity).toList();
  }

  private static String ruleFor(List<GcIdentity> identities, String identity) {
    return identities.stream()
        .filter(candidate -> candidate.identity().equals(identity))
        .map(GcIdentity::rule)
        .findFirst()
        .orElseThrow(() -> new AssertionError(identity + " is in neither list: " + identities));
  }
}
