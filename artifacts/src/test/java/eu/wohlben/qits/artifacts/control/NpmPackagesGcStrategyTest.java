package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.dto.GcIdentity;
import eu.wohlben.qits.artifacts.entity.NpmDistTag;
import eu.wohlben.qits.artifacts.entity.NpmVersion;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
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
 * npm's keep-set, case by case, against real rows.
 *
 * <p>The version strings are the measured ones wherever a case allows it — {@code @qits/ui-components}
 * really does hold two calver releases and four {@code -main.g<sha7>} builds, and the two builds
 * sharing the {@code 2026.801.85149} core are what makes the ordering rule worth testing at all: they
 * differ only in a hex sha, which semver compares as ASCII text. A case written with {@code -rc.1}
 * and {@code -rc.2} would order correctly for the wrong reason.
 */
@QuarkusTest
class NpmPackagesGcStrategyTest extends GcFixture {

  @Inject GcPlanner planner;

  private static final String UI = "@qits/ui-components";

  @Test
  void everyUnsuffixedVersionIsKeptHoweverOldAndHoweverManyReleasesSitAboveIt() throws Exception {
    // "Releases stay" in its npm spelling, including the pre-calver line: consumers pin ranges, and
    // ^0.0.1 has to keep resolving long after nobody publishes 0.0.x any more.
    hosted();
    version(UI, "0.0.1", 11);
    version(UI, "0.0.4", 12);
    version(UI, "2026.801.63140", 13);
    version(UI, "2026.801.85149", 14);

    GcStrategy.Plan plan = strategy().plan(census.take());

    assertEquals(List.of(), plan.dead(), "a package with only releases collects nothing");
    assertEquals(Set.of(), plan.blobsReleased());
    assertEquals(
        List.of(
            UI + "@0.0.1", UI + "@0.0.4", UI + "@2026.801.63140", UI + "@2026.801.85149"),
        identities(plan.kept()));
    assertTrue(
        plan.kept().stream().allMatch(kept -> NpmPackagesGcStrategy.KEPT_RELEASE.equals(kept.rule())));
  }

  @Test
  void onlyTheNewestMainBuildSurvivesAndSemverPrecedenceDecidesWhichOneThatIs() throws Exception {
    // The measured store, and the three versions the plan says die today. gd43d710 beats g21655ba on
    // the same core version because alphanumeric prerelease identifiers compare as ASCII, and both
    // beat anything built from 63140 because the core version is compared first.
    hosted();
    version(UI, "2026.801.63140", 21);
    version(UI, "2026.801.85149", 22);
    String olderBase = version(UI, "2026.801.63140-main.gab854a1", 23);
    String olderBaseTwin = version(UI, "2026.801.63140-main.g0fe7780", 24);
    String superseded = version(UI, "2026.801.85149-main.g21655ba", 25);
    String newest = version(UI, "2026.801.85149-main.gd43d710", 26);

    NpmPackagesGcStrategy strategy = strategy();
    LiveBlobCensus.Census taken = census.take();
    GcStrategy.Plan plan = strategy.plan(taken);

    assertEquals(
        Stream.of(
                UI + "@2026.801.63140-main.g0fe7780",
                UI + "@2026.801.63140-main.gab854a1",
                UI + "@2026.801.85149-main.g21655ba")
            .sorted()
            .toList(),
        identities(plan.dead()));
    assertTrue(
        plan.dead().stream().allMatch(dead -> NpmPackagesGcStrategy.DEAD_BUILD.equals(dead.rule())));
    assertEquals(
        NpmPackagesGcStrategy.KEPT_NEWEST_BUILD,
        ruleFor(plan.kept(), UI + "@2026.801.85149-main.gd43d710"));
    assertEquals(
        Set.of(olderBase, olderBaseTwin, superseded),
        plan.blobsReleased(),
        "one version, one tarball — there is no closure to walk here");
    assertTrue(plan.blobsRetained().contains(newest));

    // And through the substrate: a tarball is named by exactly one version, so every released blob
    // loses its last reference and the whole reclaim is real.
    assertEquals(
        Stream.of(olderBase, olderBaseTwin, superseded).sorted().toList(),
        planner.plan(taken, List.of(strategy)).sweep().blobIds());
  }

  @Test
  void aDistTagKeepsAVersionTheOrderingRuleWouldHaveCondemned() throws Exception {
    // Belt and braces, made to matter: `main` is pointed at a build that is NOT the newest, which is
    // what a re-tag or a rolled-back pipeline leaves behind. A packument whose dist-tags names a
    // version its versions object does not list is a broken package to every npm client.
    hosted();
    version(UI, "2026.801.85149", 31);
    String oldest = version(UI, "2026.801.85149-main.g11111aa", 32);
    version(UI, "2026.801.85149-main.g22222bb", 33);
    version(UI, "2026.801.85149-main.g33333cc", 34);
    distTag(UI, "latest", "2026.801.85149");
    distTag(UI, "main", "2026.801.85149-main.g11111aa");

    GcStrategy.Plan plan = strategy().plan(census.take());

    assertEquals(
        NpmPackagesGcStrategy.keptByDistTag("main"),
        ruleFor(plan.kept(), UI + "@2026.801.85149-main.g11111aa"));
    assertEquals(
        NpmPackagesGcStrategy.KEPT_NEWEST_BUILD,
        ruleFor(plan.kept(), UI + "@2026.801.85149-main.g33333cc"));
    assertEquals(
        List.of(UI + "@2026.801.85149-main.g22222bb"),
        identities(plan.dead()),
        "only the build that is neither newest nor named");
    assertTrue(plan.blobsRetained().contains(oldest));
  }

  @Test
  void newestIsPerPackageAndOnePackagesBuildNeverRescuesOrCondemnsAnothers() throws Exception {
    // The rule reads "per package" and the tables are keyed that way, so this is the case that would
    // catch a query that forgot the package_name predicate.
    hosted();
    String angularOld = version("@qits/angular", "0.0.1-main.gaaaaaa1", 41);
    version("@qits/angular", "0.0.1-main.gbbbbbb2", 42);
    String uiOld = version(UI, "9.9.9-main.gaaaaaa1", 43);
    version(UI, "9.9.9-main.gccccc33", 44);

    GcStrategy.Plan plan = strategy().plan(census.take());

    assertEquals(
        List.of("@qits/angular@0.0.1-main.gaaaaaa1", UI + "@9.9.9-main.gaaaaaa1"),
        identities(plan.dead()));
    assertEquals(Set.of(angularOld, uiOld), plan.blobsReleased());
    assertEquals(
        List.of("@qits/angular@0.0.1-main.gbbbbbb2", UI + "@9.9.9-main.gccccc33"),
        identities(plan.kept()),
        "each package keeps exactly one build, and it is its own");
  }

  @Test
  void aPrereleaseShapeThisRegistryDoesNotPublishIsKeptRatherThanGuessedAt() throws Exception {
    // Only main builds are ever condemned. An -rc.1 is not a coordinate any pipeline here produces,
    // so somebody made it by hand and meant it; a version string that is not semver at all cannot
    // even be ordered, and what cannot be ordered cannot be proved superseded.
    hosted();
    version(UI, "1.0.0-rc.1", 51);
    version(UI, "1.0.0-rc.2", 52);
    versionRow(UI, "nightly", store(filled(53, (byte) 53)));
    String superseded = version(UI, "1.0.0-main.g1234abc", 54);
    version(UI, "1.0.0-main.g9999fff", 55);

    GcStrategy.Plan plan = strategy().plan(census.take());

    assertEquals(List.of(UI + "@1.0.0-main.g1234abc"), identities(plan.dead()));
    assertEquals(Set.of(superseded), plan.blobsReleased());
    assertEquals(NpmPackagesGcStrategy.KEPT_UNMODELLED, ruleFor(plan.kept(), UI + "@1.0.0-rc.1"));
    assertEquals(NpmPackagesGcStrategy.KEPT_UNMODELLED, ruleFor(plan.kept(), UI + "@1.0.0-rc.2"));
    assertEquals(NpmPackagesGcStrategy.KEPT_UNORDERABLE, ruleFor(plan.kept(), UI + "@nightly"));
  }

  @Test
  void theProxysVersionsAreNotThisStrategysToCollect() throws Exception {
    // npm-proxy is parked: its content is a cache of upstream, so its policy is eviction rather than
    // retention. It shares the npm_version table with the hosted registry, which is exactly why the
    // scope has to be asserted rather than assumed — and the planner's "no strategy registered for
    // npm-proxy" line stays true because nothing here claims the type.
    hosted();
    repositoryService.ensure("npmjs", RepositoryType.NPM_PROXY);
    versionRow("npmjs", "left-pad", "1.0.0-main.gaaaaaa1", store(filled(61, (byte) 61)));
    versionRow("npmjs", "left-pad", "1.0.0-main.gbbbbbb2", store(filled(62, (byte) 62)));

    NpmPackagesGcStrategy strategy = strategy();
    GcStrategy.Plan plan = strategy.plan(census.take());

    assertEquals(RepositoryType.NPM_PACKAGES, strategy.type());
    assertEquals(List.of(), plan.dead());
    assertEquals(List.of(), plan.kept(), "not one proxied version appears in a hosted plan");
    assertEquals(Set.of(), plan.blobsRetained(), "and none of their tarballs is claimed as live");
  }

  @Test
  void anEmptyRepositoryPlansNothingRatherThanFailing() throws Exception {
    hosted();

    LiveBlobCensus.Census taken = census.take();
    GcStrategy.Plan plan = strategy().plan(taken);

    assertEquals(List.of(), plan.dead());
    assertEquals(List.of(), plan.kept());
    assertEquals(Set.of(), plan.blobsReleased());
    assertEquals(taken.live(RepositoryType.NPM_PACKAGES).keySet(), plan.blobsRetained());
  }

  @Test
  void againstTheSubstratesOwnFixtureNothingDiesAndTheRetainedSetIsTheCensusSet() throws Exception {
    // The substrate's store: one package at 1.0.0 and 1.1.0, both releases, and one of the two
    // tarballs is the same blob an image layer uses. Nothing dies — and the set handed back is
    // exactly the census's own npm-packages live set, which is what makes "one census, two readers"
    // true here rather than merely intended.
    seed();
    LiveBlobCensus.Census taken = census.take();

    NpmPackagesGcStrategy strategy = strategy();
    GcStrategy.Plan plan = strategy.plan(taken);

    assertEquals(List.of(), plan.dead());
    assertEquals(List.of("@qits/thing@1.0.0", "@qits/thing@1.1.0"), identities(plan.kept()));
    assertEquals(taken.live(RepositoryType.NPM_PACKAGES).keySet(), plan.blobsRetained());
    assertEquals(List.of(), planner.plan(taken, List.of(strategy)).sweep().blobIds());
  }

  // --- fixture ---------------------------------------------------------------------------------

  /** The strategy as CDI would build it. It has no collaborator a test has to stand in for. */
  private NpmPackagesGcStrategy strategy() {
    NpmPackagesGcStrategy strategy = new NpmPackagesGcStrategy();
    strategy.repositories = repositories;
    strategy.versions = npmVersions;
    strategy.distTags = npmDistTags;
    return strategy;
  }

  private void hosted() {
    repositoryService.ensure("npm", RepositoryType.NPM_PACKAGES);
  }

  /** A version whose tarball is a real blob of the given size, aged past the sweep's grace window. */
  private String version(String packageName, String version, int size) throws IOException {
    String blobId = store(filled(size, (byte) (size % 251)));
    backdate(blobId, Duration.ofDays(30));
    versionRow("npm", packageName, version, blobId);
    return blobId;
  }

  private void versionRow(String packageName, String version, String blobId) {
    versionRow("npm", packageName, version, blobId);
  }

  private void versionRow(String repository, String packageName, String version, String blobId) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              NpmVersion row = new NpmVersion();
              row.repository = repository;
              row.packageName = packageName;
              row.version = version;
              row.tarballBlobId = blobId;
              row.manifestJson = "{}";
              row.createdAt = Instant.now();
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
