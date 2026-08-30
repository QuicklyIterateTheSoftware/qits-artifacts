package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.control.LiveBlobCensus;
import eu.wohlben.qits.artifacts.entity.MavenArtifact;
import eu.wohlben.qits.artifacts.control.MavenPackagesProfile;
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
import org.junit.jupiter.api.Test;

/**
 * The own engine over maven's facts, and the two things this type has that no other own type does: a
 * <b>coordinate made of several rows</b>, and a derived document that redirects a resolver.
 *
 * <p>This suite replaces {@code MavenPackagesGcStrategyTest}, whose whole subject was "nothing dies,
 * said out loud". That posture was a decision with a condition attached — {@code
 * maven-repository-plan.md} §3.6 named a cleanup rule and never priced it — and the settlement
 * priced every own type at once, so the pin the old suite held is replaced deliberately rather than
 * eroded.
 *
 * <p>The case that carries is {@code theNewestTimestampedSnapshotSetIsAlwaysKept…}: {@code
 * maven-metadata.xml} is computed from the surviving rows at read time, so a resolver asking for
 * {@code 1.0.1-SNAPSHOT} is sent to whatever the document says is newest. A run that deleted that
 * one would leave the document pointing at a file the store no longer has, and that is the single
 * failure this type must not produce.
 */
@QuarkusTest
class MavenPackagesGcAdapterTest extends GcFixture {

  /** The configured window for this type, and the number every case below is aged against. */
  private static final Duration WINDOW = Duration.ofDays(90);

  private static final String GROUP_ID = "eu.wohlben.qits";
  private static final String ARTIFACT_ID = "qits-eventstream";
  private static final String COORDINATE = GROUP_ID + ":" + ARTIFACT_ID + ":";

  @Inject MavenPackagesGcStrategy strategy;
  @Inject GcPlanner planner;

  @Test
  void theLastTwoReleaseVersionsOfAnArtifactStayAndTheThirdOneAgesOutWhole() throws Exception {
    // The settlement's belt, counted in VERSIONS rather than in files — which is the whole reason a
    // coordinate is the identity here. The oldest version dies as one thing, jar and pom together;
    // a plan that condemned the jar and kept the pom would leave a broken resolve behind.
    maven();
    String oldJar = release("1.0.0", "jar", 11, daysAgo(400));
    String oldPom = release("1.0.0", "pom", 12, daysAgo(400));
    release("1.1.0", "jar", 13, daysAgo(380));
    release("1.1.0", "pom", 14, daysAgo(380));
    release("2.0.0", "jar", 15, daysAgo(360));
    release("2.0.0", "pom", 16, daysAgo(360));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of(COORDINATE + "1.0.0"), identities(plan.dead()));
    assertEquals(OwnArtifactsStrategy.deadUnaccessed(WINDOW), plan.dead().get(0).rule());
    assertEquals(Set.of(oldJar, oldPom), plan.blobsReleased(), "the whole version's files");
    assertEquals(
        OwnArtifactsStrategy.KEPT_RELEASE, ruleFor(plan.kept(), COORDINATE + "2.0.0"));
    assertEquals(
        List.of(COORDINATE + "1.1.0", COORDINATE + "2.0.0"), identities(plan.kept()));
  }

  @Test
  void mavensOwnVersionOrderDecidesTheBeltRatherThanTheOrderTheRowsWereWritten() throws Exception {
    // 1.0.10 is above 1.0.9 and a -SNAPSHOT of a version is below its release, which a lexical
    // comparison gets backwards — and the rows here are deliberately written oldest-version-last so
    // insertion order cannot be what answers.
    maven();
    release("1.0.10", "jar", 21, daysAgo(400));
    release("1.0.9", "jar", 22, daysAgo(400));
    release("1.0.2", "jar", 23, daysAgo(400));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of(COORDINATE + "1.0.2"), identities(plan.dead()));
    assertEquals(
        List.of(COORDINATE + "1.0.10", COORDINATE + "1.0.9"), identities(plan.kept()));
  }

  @Test
  void aReleaseSomethingStillResolvesSurvivesOnUseRatherThanOnPolicy() throws Exception {
    // The settlement's other half, and for a library it is the common case: a version three deep in
    // the belt that something still builds against. One warm file keeps the coordinate — a pom read
    // is a resolve of the version, and letting a cold jar drag its own pom out would be the same
    // half-version failure from the other direction.
    maven();
    release("1.0.0", "jar", 31, daysAgo(400));
    release("1.0.0", "pom", 32, daysAgo(400), daysAgo(7));
    release("1.1.0", "jar", 33, daysAgo(380));
    release("2.0.0", "jar", 34, daysAgo(360));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of(), plan.dead());
    assertEquals(
        OwnArtifactsStrategy.keptAccessed(WINDOW), ruleFor(plan.kept(), COORDINATE + "1.0.0"));
  }

  @Test
  void theNewestTimestampedSnapshotSetIsAlwaysKeptSoAResolverNeverResolvesToADeletedFile()
      throws Exception {
    // The property this type must never break. Every timestamped set here is a year cold, so the
    // window alone would take all three — and the newest one is the coordinate the derived
    // version-level maven-metadata.xml redirects 1.0.1-SNAPSHOT to. It is kept under its own rule,
    // and after the plan is applied every file of it is still a row.
    maven();
    snapshot("1.0.1", "20260601.101010", 1, "jar", 41, daysAgo(400));
    snapshot("1.0.1", "20260601.101010", 1, "pom", 42, daysAgo(400));
    snapshot("1.0.1", "20260701.202020", 2, "jar", 43, daysAgo(400));
    snapshot("1.0.1", "20260802.123456", 3, "jar", 44, daysAgo(400));
    snapshot("1.0.1", "20260802.123456", 3, "pom", 45, daysAgo(400));
    String newest = COORDINATE + "1.0.1-20260802.123456-3";

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());
    GcStrategy.Applied applied = strategy.apply(plan, blobId -> false);

    assertEquals(
        List.of(COORDINATE + "1.0.1-20260601.101010-1", COORDINATE + "1.0.1-20260701.202020-2"),
        identities(plan.dead()));
    assertEquals(List.of(newest), identities(plan.kept()));
    assertEquals(MavenPackagesGcAdapter.KEPT_RESOLVABLE_SNAPSHOT, plan.kept().get(0).rule());
    assertEquals(2, applied.deleted().size());

    mavenArtifacts.getEntityManager().clear();
    assertTrue(
        mavenArtifacts.findOne(MAVEN_REPO, snapshotPath("1.0.1", "20260802.123456", 3, "jar"))
            .isPresent(),
        "the file the metadata resolves to");
    assertTrue(
        mavenArtifacts.findOne(MAVEN_REPO, snapshotPath("1.0.1", "20260802.123456", 3, "pom"))
            .isPresent(),
        "and its pom, because a coordinate is removed whole or not at all");
    assertTrue(
        mavenArtifacts.findOne(MAVEN_REPO, snapshotPath("1.0.1", "20260601.101010", 1, "jar"))
            .isEmpty());
    assertTrue(
        mavenArtifacts.findOne(MAVEN_REPO, snapshotPath("1.0.1", "20260601.101010", 1, "pom"))
            .isEmpty());
  }

  @Test
  void twoDeploysInsideOneSecondAreRankedByBuildNumberAsANumber() throws Exception {
    // The one case where a string compare would point the metadata at the earlier deploy: build 10
    // sorts before build 9 lexically. The timestamp dominates in every other case, which is exactly
    // why this one has to be written down.
    maven();
    snapshot("1.0.1", "20260802.123456", 9, "jar", 51, daysAgo(400));
    snapshot("1.0.1", "20260802.123456", 10, "jar", 52, daysAgo(400));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of(COORDINATE + "1.0.1-20260802.123456-9"), identities(plan.dead()));
    assertEquals(
        MavenPackagesGcAdapter.KEPT_RESOLVABLE_SNAPSHOT,
        ruleFor(plan.kept(), COORDINATE + "1.0.1-20260802.123456-10"));
  }

  @Test
  void aLiteralSnapshotSetIsTheNewestDeployableSetOnlyWhenTheLineHasNoTimestampedDeploys()
      throws Exception {
    // uniqueVersion=false: the client asks for a-1.0.2-SNAPSHOT.jar by name, with no metadata to
    // redirect it, so on a line with nothing else that set is what a resolver would break without.
    // Beside timestamped deploys it is an ordinary candidate and ages out like one.
    maven();
    literalSnapshot("1.0.2", "jar", 61, daysAgo(400));
    literalSnapshot("1.0.3", "jar", 62, daysAgo(400));
    snapshot("1.0.3", "20260802.123456", 1, "jar", 63, daysAgo(400));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        MavenPackagesGcAdapter.KEPT_RESOLVABLE_SNAPSHOT,
        ruleFor(plan.kept(), COORDINATE + "1.0.2-SNAPSHOT"),
        "the only deployable set of its line");
    assertEquals(
        MavenPackagesGcAdapter.KEPT_RESOLVABLE_SNAPSHOT,
        ruleFor(plan.kept(), COORDINATE + "1.0.3-20260802.123456-1"));
    assertEquals(List.of(COORDINATE + "1.0.3-SNAPSHOT"), identities(plan.dead()));
  }

  @Test
  void aSnapshotLineNeverSpendsItsArtifactsReleaseBeltAndTheReverseHoldsToo() throws Exception {
    // Two questions wearing one group field, and this is the case that would catch them colliding:
    // three snapshot builds must not push a release off the belt of two, and three releases must not
    // make the snapshot line's newest set eligible.
    maven();
    release("1.0.0", "jar", 71, daysAgo(400));
    release("1.1.0", "jar", 72, daysAgo(390));
    snapshot("1.2.0", "20260601.101010", 1, "jar", 73, daysAgo(400));
    snapshot("1.2.0", "20260701.202020", 2, "jar", 74, daysAgo(400));
    snapshot("1.2.0", "20260802.123456", 3, "jar", 75, daysAgo(400));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        List.of(COORDINATE + "1.2.0-20260601.101010-1", COORDINATE + "1.2.0-20260701.202020-2"),
        identities(plan.dead()));
    assertEquals(OwnArtifactsStrategy.KEPT_RELEASE, ruleFor(plan.kept(), COORDINATE + "1.0.0"));
    assertEquals(OwnArtifactsStrategy.KEPT_RELEASE, ruleFor(plan.kept(), COORDINATE + "1.1.0"));
  }

  @Test
  void oneFileInsideTheGraceWindowWithholdsTheWholeCoordinateEveryRowIntact() throws Exception {
    // The strand hazard, and this type's own version of it: deleting the mature rows of a version
    // and leaving the young one would produce exactly the half-version the identity model exists to
    // prevent, on top of stranding the young blob as row-less and therefore untouchable forever.
    maven();
    String youngPom = release("1.0.0", "pom", 82, daysAgo(400));
    release("1.0.0", "jar", 81, daysAgo(400));
    release("1.1.0", "jar", 83, daysAgo(390));
    release("2.0.0", "jar", 84, daysAgo(380));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());
    GcStrategy.Applied applied = strategy.apply(plan, blobId -> blobId.equals(youngPom));

    assertEquals(List.of(), applied.deleted());
    assertEquals(List.of(COORDINATE + "1.0.0"), identities(applied.withheldByGraceWindow()));
    mavenArtifacts.getEntityManager().clear();
    assertTrue(mavenArtifacts.findOne(MAVEN_REPO, releasePath("1.0.0", "jar")).isPresent());
    assertTrue(mavenArtifacts.findOne(MAVEN_REPO, releasePath("1.0.0", "pom")).isPresent());
  }

  @Test
  void aStoreWithNoMavenRepositoryIsAnEmptyPlanRatherThanAFailure() throws Exception {
    // The shipped state of a platform that has deployed no library yet. Nothing here reaches outside
    // this service, so the only thing that can refuse this type is the run's pin aggregate.
    seed();

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(MavenPackagesProfile.KEY, strategy.type());
    assertEquals(List.of(), plan.dead());
    assertEquals(List.of(), plan.kept());
    assertEquals(Set.of(), plan.blobsRetained());
  }

  @Test
  void againstTheSubstratesOwnFixtureNothingDiesAndTheRetainedSetIsTheCensusSet() throws Exception {
    // The substrate's store: one release, a jar and its pom, deployed moments ago — and now ONE
    // identity rather than two, which is the visible half of the change this workstream made. The
    // set handed back is exactly the census's own maven live set, the assertion every claimed type
    // carries for the case where nothing dies.
    seedMaven();
    LiveBlobCensus.Census taken = census.take();

    GcStrategy.Plan plan = strategy.plan(taken, GcPins.none());

    assertEquals(List.of(), plan.dead());
    assertEquals(List.of(COORDINATE + "1.0.0"), identities(plan.kept()));
    assertTrue(plan.kept().stream().allMatch(kept -> MAVEN_REPO.equals(kept.repository())));
    assertEquals(taken.live(MavenPackagesProfile.KEY).keySet(), plan.blobsRetained());
    assertEquals(2, plan.blobsRetained().size(), "the jar and the pom");
    assertEquals(List.of(), planner.plan(taken, List.of(strategy), GcPins.none()).sweep().blobIds());
  }

  // --- fixture ---------------------------------------------------------------------------------

  private void maven() {
    repositoryService.ensure(MAVEN_REPO, MavenPackagesProfile.KEY);
  }

  private static String releasePath(String version, String extension) {
    return "eu/wohlben/qits/"
        + ARTIFACT_ID
        + "/"
        + version
        + "/"
        + ARTIFACT_ID
        + "-"
        + version
        + "."
        + extension;
  }

  private static String snapshotPath(
      String baseVersion, String timestamp, int buildNumber, String extension) {
    return "eu/wohlben/qits/"
        + ARTIFACT_ID
        + "/"
        + baseVersion
        + "-SNAPSHOT/"
        + ARTIFACT_ID
        + "-"
        + baseVersion
        + "-"
        + timestamp
        + "-"
        + buildNumber
        + "."
        + extension;
  }

  private static String literalSnapshotPath(String baseVersion, String extension) {
    return "eu/wohlben/qits/"
        + ARTIFACT_ID
        + "/"
        + baseVersion
        + "-SNAPSHOT/"
        + ARTIFACT_ID
        + "-"
        + baseVersion
        + "-SNAPSHOT."
        + extension;
  }

  private String release(String version, String extension, int size, Instant createdAt)
      throws IOException {
    return release(version, extension, size, createdAt, null);
  }

  private String release(
      String version, String extension, int size, Instant createdAt, Instant accessedAt)
      throws IOException {
    return row(releasePath(version, extension), size, createdAt, accessedAt);
  }

  private String snapshot(
      String baseVersion, String timestamp, int buildNumber, String extension, int size,
      Instant createdAt)
      throws IOException {
    return row(snapshotPath(baseVersion, timestamp, buildNumber, extension), size, createdAt, null);
  }

  private String literalSnapshot(
      String baseVersion, String extension, int size, Instant createdAt) throws IOException {
    return row(literalSnapshotPath(baseVersion, extension), size, createdAt, null);
  }

  /** One deployed file, with both of V11's timestamps under the case's control. */
  private String row(String path, int size, Instant createdAt, Instant accessedAt)
      throws IOException {
    String blobId = store(filled(size, (byte) (size % 251)));
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              MavenArtifact artifact = new MavenArtifact();
              artifact.repository = MAVEN_REPO;
              artifact.path = path;
              artifact.blobId = blobId;
              artifact.sizeBytes = size;
              artifact.createdAt = createdAt;
              artifact.accessedAt = accessedAt;
              mavenArtifacts.persist(artifact);
            });
    return blobId;
  }

  private static Instant daysAgo(int days) {
    return Instant.now().minus(Duration.ofDays(days));
  }

  private static List<String> identities(List<GcIdentity> identities) {
    return identities.stream().map(GcIdentity::identity).sorted().toList();
  }

  private static String ruleFor(List<GcIdentity> identities, String identity) {
    return identities.stream()
        .filter(candidate -> candidate.identity().equals(identity))
        .map(GcIdentity::rule)
        .findFirst()
        .orElseThrow(() -> new AssertionError(identity + " is in neither list: " + identities));
  }
}
