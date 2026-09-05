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
 *
 * <p><b>The window is P0D since 2026-09-05</b>, so nothing here is kept by being warm: a coordinate
 * lives because a pin, a belt or the pom closure names it, and every "how old is this row" in a
 * fixture below decides nothing except how honest the case reads. The one case that used to prove
 * "use keeps it alive" now proves the opposite doctrine under its own name, and the closure cases at
 * the end are what replaced it for a library.
 */
@QuarkusTest
class MavenPackagesGcAdapterTest extends GcFixture {

  /** The configured window for this type — zero since 2026-09-05, and every case is aged past it. */
  private static final Duration WINDOW = Duration.ZERO;

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
  void aResolveNoLongerKeepsAVersionAliveByItselfAndTheReferenceIsWhatDoes() throws Exception {
    // THE FLIP of 2026-09-05, and this case used to say the opposite: a version three deep in the
    // belt, resolved yesterday, was kept because it was warm. At P0D it is not — being pulled is a
    // fact about a timestamp, and the timestamp stopped being a keep-class. The version is condemned
    // whole, jar and pom together, exactly as if nobody had touched it.
    //
    // Nothing was lost by that: the resolve happened because a consumer's pom names 1.0.0, and that
    // pom is what the maintenance dependency pin reads. So the second half of this case is the same
    // store with the reference stated instead of inferred — which is the whole realignment in two
    // assertions.
    maven();
    release("1.0.0", "jar", 31, daysAgo(400));
    release("1.0.0", "pom", 32, daysAgo(400), daysAgo(1));
    release("1.1.0", "jar", 33, daysAgo(380));
    release("2.0.0", "jar", 34, daysAgo(360));

    GcStrategy.Plan onAccessAlone = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of(COORDINATE + "1.0.0"), identities(onAccessAlone.dead()));
    assertEquals(
        OwnArtifactsStrategy.deadUnaccessed(WINDOW), onAccessAlone.dead().get(0).rule());

    GcStrategy.Plan referenced = strategy.plan(census.take(), referencing(COORDINATE + "1.0.0"));

    assertEquals(List.of(), referenced.dead());
    assertEquals(GcPins.BY_MANIFEST, ruleFor(referenced.kept(), COORDINATE + "1.0.0"));
  }

  @Test
  void aVersionSomeRepositorysPomStillReferencesOutlivesTheWindowThatCondemnedItsNeighbour()
      throws Exception {
    // The keep that makes a zero window defensible for a library. A consumer's pom names 1.0.0 on
    // main and nothing has built it for a year — no install, no resolve, and no belt slot, with two
    // newer releases holding both of them. Before the dependency pin the only thing that could save
    // it was a resolve inside the window, which is a fact about CI scheduling rather than about
    // whether anything needs it.
    //
    // The pin joins on the identity UNCHANGED: "g:a:v" is what a pom writes and what this adapter
    // folds its rows into, so the case is written with the same string on both sides deliberately.
    // Three releases, so the belt of two leaves exactly one eligible and the pin is the only thing
    // that can be answering.
    maven();
    release("1.0.0", "jar", 101, daysAgo(400));
    release("1.0.0", "pom", 102, daysAgo(400));
    release("1.1.0", "jar", 103, daysAgo(390));
    release("2.0.0", "jar", 104, daysAgo(380));

    GcStrategy.Plan referenced =
        strategy.plan(census.take(), referencing(COORDINATE + "1.0.0"));

    assertEquals(List.of(), referenced.dead(), "a referenced version is never a candidate");
    assertEquals(GcPins.BY_MANIFEST, ruleFor(referenced.kept(), COORDINATE + "1.0.0"));

    // And the other half, so the keep is a fact about the pin rather than about this fixture: with
    // nothing referencing it, the same coordinate dies as one thing, jar and pom together.
    GcStrategy.Plan unreferenced = strategy.plan(census.take(), GcPins.none());
    assertEquals(List.of(COORDINATE + "1.0.0"), identities(unreferenced.dead()));
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

  // --- the pom closure -------------------------------------------------------------------------

  @Test
  void aPinnedPomsDependencyIsKeptAndSoIsWhatThatDependencyResolvesInTurn() throws Exception {
    // THE MEASURED GAP, closed. A manifest pin names what a repository's own pom writes —
    // qits-registries — and nothing at all names what that pom then resolves. The 01:55 sweep of
    // 2026-09-05 condemned exactly this shape: qits-blobstore and qits-userflows-parent versions
    // that pinned poms reference and no manifest mentions.
    //
    // Two hops, so the fixpoint is what is under test rather than one lookup: the pinned pom names
    // qits-blobstore:1.0.0, whose own pom takes its parent from qits-parent:1.0.0. Both are the
    // third release of their artifact, so the belt covers neither and only the closure can be
    // answering. And each keep NAMES ITS REFERRER, which is what makes the report reviewable: a
    // reader who wants to know why a coordinate survived is told which pom asked for it.
    maven();
    pom("qits-registries", "2026.903.85122", pomXml("qits-registries", "2026.903.85122", null,
        List.of(GROUP_ID + ":qits-blobstore:1.0.0")), daysAgo(400));
    // Two newer releases of the referrer, so that with no pin it is not on the belt either — which
    // is what makes the second half of this case a statement about the closure.
    release("qits-registries", "2026.904.10000", "jar", 125, daysAgo(400));
    release("qits-registries", "2026.905.10000", "jar", 126, daysAgo(400));
    pom("qits-blobstore", "1.0.0", pomXml("qits-blobstore", "1.0.0",
        GROUP_ID + ":qits-parent:1.0.0", List.of()), daysAgo(400));
    release("qits-blobstore", "1.1.0", "jar", 121, daysAgo(400));
    release("qits-blobstore", "2.0.0", "jar", 122, daysAgo(400));
    pom("qits-parent", "1.0.0", pomXml("qits-parent", "1.0.0", null, List.of()), daysAgo(400));
    release("qits-parent", "1.1.0", "jar", 123, daysAgo(400));
    release("qits-parent", "2.0.0", "jar", 124, daysAgo(400));

    GcStrategy.Plan plan =
        strategy.plan(census.take(), referencing(GROUP_ID + ":qits-registries:2026.903.85122"));

    assertEquals(List.of(), plan.dead(), "nothing the pinned pom reaches is a candidate");
    assertEquals(
        MavenPomClosure.reachableFrom(GROUP_ID + ":qits-registries:2026.903.85122"),
        ruleFor(plan.kept(), GROUP_ID + ":qits-blobstore:1.0.0"));
    assertEquals(
        MavenPomClosure.reachableFrom(GROUP_ID + ":qits-blobstore:1.0.0"),
        ruleFor(plan.kept(), GROUP_ID + ":qits-parent:1.0.0"),
        "the second hop, named after the pom that took it as its parent");

    // And the other half, so the keeps above are the closure and not this fixture: with nothing
    // pinned, the three seeds are the belt's and both old coordinates go.
    assertEquals(
        List.of(
            GROUP_ID + ":qits-blobstore:1.0.0",
            GROUP_ID + ":qits-parent:1.0.0",
            GROUP_ID + ":qits-registries:2026.903.85122"),
        identities(strategy.plan(census.take(), GcPins.none()).dead()));
  }

  @Test
  void aReferencedCoordinateThisStoreDoesNotHoldIsSimplyNotAKeep() throws Exception {
    // What "internal" means, and it needs no configuration to mean it: a coordinate this store holds
    // is one a resolve would come HERE for, and a coordinate it does not hold is maven central's
    // problem. So a third-party dependency contributes nothing — no keep, no error, no attempt to
    // read a pom that is not there — while the internal one beside it is kept.
    maven();
    pom("qits-registries", "2026.903.85122", pomXml("qits-registries", "2026.903.85122", null,
        List.of("org.apache.commons:commons-lang3:3.14.0", GROUP_ID + ":qits-blobstore:1.0.0")),
        daysAgo(400));
    pom("qits-blobstore", "1.0.0", pomXml("qits-blobstore", "1.0.0", null, List.of()), daysAgo(400));
    release("qits-blobstore", "1.1.0", "jar", 131, daysAgo(400));
    release("qits-blobstore", "2.0.0", "jar", 132, daysAgo(400));

    GcStrategy.Plan plan =
        strategy.plan(census.take(), referencing(GROUP_ID + ":qits-registries:2026.903.85122"));

    assertEquals(List.of(), plan.dead());
    assertEquals(
        MavenPomClosure.reachableFrom(GROUP_ID + ":qits-registries:2026.903.85122"),
        ruleFor(plan.kept(), GROUP_ID + ":qits-blobstore:1.0.0"));
    assertTrue(
        plan.kept().stream().noneMatch(kept -> kept.identity().contains("commons-lang3")),
        "a coordinate this store never had cannot be kept in it: " + identities(plan.kept()));
  }

  @Test
  void projectVersionResolvesAgainstThePomsOwnCoordinatesAndAnyOtherPropertyIsSkipped()
      throws Exception {
    // The one interpolation this closure resolves, and the boundary around it. ${project.version} is
    // a fact the document itself carries, so a reactor sibling declared that way is read exactly.
    // Anything else — a property, a profile, a parent's property table — is NOT resolved and the
    // reference is dropped: the platform's own poms spell internal versions literally by house rule,
    // so a half-built interpolator would only produce a keep-set that looks complete.
    maven();
    pom("qits-registries", "1.5.0", pomXml("qits-registries", "1.5.0", null,
        List.of(GROUP_ID + ":qits-sibling:${project.version}",
            GROUP_ID + ":qits-elsewhere:${qits.elsewhere.version}")), daysAgo(400));
    pom("qits-sibling", "1.5.0", pomXml("qits-sibling", "1.5.0", null, List.of()), daysAgo(400));
    release("qits-sibling", "1.6.0", "jar", 141, daysAgo(400));
    release("qits-sibling", "1.7.0", "jar", 142, daysAgo(400));
    release("qits-elsewhere", "1.5.0", "jar", 143, daysAgo(400));
    release("qits-elsewhere", "1.6.0", "jar", 144, daysAgo(400));
    release("qits-elsewhere", "1.7.0", "jar", 145, daysAgo(400));

    GcStrategy.Plan plan =
        strategy.plan(census.take(), referencing(GROUP_ID + ":qits-registries:1.5.0"));

    assertEquals(
        MavenPomClosure.reachableFrom(GROUP_ID + ":qits-registries:1.5.0"),
        ruleFor(plan.kept(), GROUP_ID + ":qits-sibling:1.5.0"),
        "${project.version} is the pom's own version and nothing else");
    assertEquals(
        List.of(GROUP_ID + ":qits-elsewhere:1.5.0"),
        identities(plan.dead()),
        "an unresolvable property drops the reference rather than guessing a version");
  }

  @Test
  void aCycleBetweenTwoPomsTerminatesAndKeepsBoth() throws Exception {
    // Two artifacts whose poms name each other — a test-scoped back-dependency, or two BOMs
    // importing one another. The walk visits a coordinate once, so the cycle ends on the second
    // visit rather than by a depth limit, and both sides are kept.
    maven();
    pom("qits-left", "1.0.0", pomXml("qits-left", "1.0.0", null,
        List.of(GROUP_ID + ":qits-right:1.0.0")), daysAgo(400));
    release("qits-left", "1.1.0", "jar", 151, daysAgo(400));
    release("qits-left", "2.0.0", "jar", 152, daysAgo(400));
    pom("qits-right", "1.0.0", pomXml("qits-right", "1.0.0", null,
        List.of(GROUP_ID + ":qits-left:1.0.0")), daysAgo(400));
    release("qits-right", "1.1.0", "jar", 153, daysAgo(400));
    release("qits-right", "2.0.0", "jar", 154, daysAgo(400));

    GcStrategy.Plan plan = strategy.plan(census.take(), referencing(GROUP_ID + ":qits-left:1.0.0"));

    assertEquals(List.of(), plan.dead());
    assertEquals(GcPins.BY_MANIFEST, ruleFor(plan.kept(), GROUP_ID + ":qits-left:1.0.0"));
    assertEquals(
        MavenPomClosure.reachableFrom(GROUP_ID + ":qits-left:1.0.0"),
        ruleFor(plan.kept(), GROUP_ID + ":qits-right:1.0.0"),
        "and the seed keeps its own stronger reason rather than being renamed by the cycle");
  }

  @Test
  void theClosureIsSeededByTheBeltAsWellAsByThePinsAndReadsAnImportedBom() throws Exception {
    // The seeds are everything the keep-set already holds, which is pins AND belts — so a release
    // nothing outside this service names still drags its own dependencies along. Here there is no
    // pin at all: the newest release of qits-registries is kept by the belt, and its pom imports a
    // BOM whose third-oldest version would otherwise go.
    //
    // dependencyManagement is read for IMPORTS only, which the second managed entry proves: an
    // ordinary managed version states what somebody MAY declare, and keeping every one of them would
    // keep versions no build resolves.
    maven();
    pom("qits-registries", "2026.903.85122",
        pomWithManagedImport("qits-registries", "2026.903.85122",
            GROUP_ID + ":qits-bom:1.0.0", GROUP_ID + ":qits-managed:1.0.0"), daysAgo(400));
    pom("qits-bom", "1.0.0", pomXml("qits-bom", "1.0.0", null, List.of()), daysAgo(400));
    release("qits-bom", "1.1.0", "jar", 161, daysAgo(400));
    release("qits-bom", "2.0.0", "jar", 162, daysAgo(400));
    release("qits-managed", "1.0.0", "jar", 163, daysAgo(400));
    release("qits-managed", "1.1.0", "jar", 164, daysAgo(400));
    release("qits-managed", "2.0.0", "jar", 165, daysAgo(400));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        MavenPomClosure.reachableFrom(GROUP_ID + ":qits-registries:2026.903.85122"),
        ruleFor(plan.kept(), GROUP_ID + ":qits-bom:1.0.0"),
        "seeded by the belt, with no pin anywhere in this case");
    assertEquals(
        List.of(GROUP_ID + ":qits-managed:1.0.0"),
        identities(plan.dead()),
        "a managed version that is not an import is not a reference");
  }

  // --- fixture ---------------------------------------------------------------------------------

  private void maven() {
    repositoryService.ensure(MAVEN_REPO, MavenPackagesProfile.KEY);
  }

  /** A real pom document for a coordinate, with an optional parent and any dependencies. */
  private static String pomXml(
      String artifactId, String version, String parent, List<String> dependencies) {
    StringBuilder xml = new StringBuilder(header(artifactId, version, parent));
    xml.append("  <dependencies>\n");
    for (String dependency : dependencies) {
      xml.append(dependencyXml(dependency, null));
    }
    xml.append("  </dependencies>\n</project>\n");
    return xml.toString();
  }

  /** The same, with an imported BOM and an ordinary managed version beside it. */
  private static String pomWithManagedImport(
      String artifactId, String version, String imported, String managed) {
    return header(artifactId, version, null)
        + "  <dependencyManagement>\n    <dependencies>\n"
        + dependencyXml(imported, "import")
        + dependencyXml(managed, null)
        + "    </dependencies>\n  </dependencyManagement>\n</project>\n";
  }

  private static String header(String artifactId, String version, String parent) {
    StringBuilder xml =
        new StringBuilder(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                + "  <modelVersion>4.0.0</modelVersion>\n");
    if (parent != null) {
      xml.append("  <parent>\n").append(coordinateXml(parent)).append("  </parent>\n");
    }
    return xml.append("  <groupId>")
        .append(GROUP_ID)
        .append("</groupId>\n  <artifactId>")
        .append(artifactId)
        .append("</artifactId>\n  <version>")
        .append(version)
        .append("</version>\n")
        .toString();
  }

  private static String dependencyXml(String coordinate, String scope) {
    return "    <dependency>\n"
        + coordinateXml(coordinate)
        + (scope == null ? "" : "      <scope>" + scope + "</scope>\n")
        + "      <type>pom</type>\n"
        + "    </dependency>\n";
  }

  /** {@code g:a:v} written out as maven's three elements; the version may be an expression. */
  private static String coordinateXml(String coordinate) {
    int firstColon = coordinate.indexOf(':');
    int lastColon = coordinate.lastIndexOf(':');
    return "      <groupId>"
        + coordinate.substring(0, firstColon)
        + "</groupId>\n      <artifactId>"
        + coordinate.substring(firstColon + 1, lastColon)
        + "</artifactId>\n      <version>"
        + coordinate.substring(lastColon + 1)
        + "</version>\n";
  }

  /** The aggregate a run would have read, with these maven coordinates named by a pom on main. */
  private static GcPins referencing(String... coordinates) {
    return new GcPins(
        java.util.Map.of(),
        "",
        Set.of(),
        Set.of(),
        Set.of(coordinates),
        Set.of(),
        Set.of(),
        Set.of(),
        List.of());
  }

  private static String releasePath(String version, String extension) {
    return releasePath(ARTIFACT_ID, version, extension);
  }

  /** The layout path of one release file of any artifact under this suite's group. */
  private static String releasePath(String artifactId, String version, String extension) {
    return "eu/wohlben/qits/"
        + artifactId
        + "/"
        + version
        + "/"
        + artifactId
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

  /** One deployed release file of another artifact of the same group. */
  private String release(
      String artifactId, String version, String extension, int size, Instant createdAt)
      throws IOException {
    return row(releasePath(artifactId, version, extension), size, createdAt, null);
  }

  /**
   * One deployed {@code .pom} whose bytes are a REAL pom document.
   *
   * <p>The closure parses what the store holds, so a fixture of filler bytes would prove the walk
   * ran and nothing about what it read. Every other file in this suite stays filler on purpose —
   * only the pom is ever parsed.
   */
  private String pom(String artifactId, String version, String xml, Instant createdAt)
      throws IOException {
    String blobId = store(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              MavenArtifact artifact = new MavenArtifact();
              artifact.repository = MAVEN_REPO;
              artifact.path = releasePath(artifactId, version, "pom");
              artifact.blobId = blobId;
              artifact.sizeBytes = xml.length();
              artifact.createdAt = createdAt;
              mavenArtifacts.persist(artifact);
            });
    return blobId;
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
