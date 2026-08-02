package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.dto.GcIdentity;
import eu.wohlben.qits.artifacts.dto.GcPlanReport;
import eu.wohlben.qits.artifacts.dto.GcTypePlan;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The seam and the reconciliation, which is the only hard part of this design.
 *
 * <p>A strategy says which of its own identities die and which blobs that touches. Nothing else. The
 * question "may this blob be unlinked" is answered here, over every type at once, and the cases
 * below are the four ways the answer is no: another type still names it, a surviving identity of the
 * <em>same</em> type still names it, it never had an identity at all, or its file is too young.
 *
 * <p>The strategies here are fakes constructed in the test rather than beans, and that is
 * deliberate: the reconciliation is what these cases are about, and a real strategy would answer
 * with its own policy instead of the shape a case needs. Registering one as a bean would also take
 * away the "nobody collects this type" case, which {@code npm-proxy} is still in. The two
 * registered beans — {@code OciImageGcStrategy} and {@code NpmPackagesGcStrategy} — are exercised on
 * their own rules in their own suites and appear here only in the first case, as the report's shape.
 */
@QuarkusTest
class GcPlanTest extends GcFixture {

  @Inject GcPlanner planner;

  @Test
  void fiveTypesAreCollectedAndTheLastOneSaysSoRatherThanGoMissing() throws Exception {
    // The report a reviewer sees first. "No plan", "nothing to collect" and "refused to plan" are
    // three different facts, so every type is listed with its own reason rather than omitted — and
    // oci-images demonstrates the third: this suite has no qits-cd, its pin fetch is refused at a
    // closed port, and a keep-set that cannot be established reclaims nothing. npm-packages
    // demonstrates the second: its rules run over the fixture's two released versions and condemn
    // neither. oci-mirror is the second's sharper form: a type whose whole policy is "nothing dies",
    // which is a decision and therefore claims its type, unlike npm-proxy beside it. The two CI
    // stubs are the second form again with a caption: zero rows, a named intended rule, and a note
    // saying the loop has never produced content.
    Store store = seed();

    assertEquals(
        List.of(
            "CiScreenshotsGcStrategy",
            "CiVideosGcStrategy",
            "MavenPackagesGcStrategy",
            "NpmPackagesGcStrategy",
            "OciImageGcStrategy",
            "OciMirrorGcStrategy"),
        planner.registered().stream().map(GcPlanner::nameOf).sorted().toList());
    GcPlanReport report = planner.plan();

    assertTrue(report.dryRun());
    assertEquals(RepositoryType.values().length, report.types().size());
    for (GcTypePlan type : report.types()) {
      switch (type.type()) {
        case OCI_IMAGES -> {
          assertEquals("OciImageGcStrategy", type.strategy());
          assertNull(type.note());
          assertNotNull(type.error(), "no qits-cd here, so the type must abort rather than plan");
        }
        case NPM_PACKAGES -> {
          assertEquals("NpmPackagesGcStrategy", type.strategy());
          assertNull(type.note());
          assertNull(type.error());
          assertEquals(0, type.dead().size(), "both fixture versions are releases");
        }
        case MAVEN_PACKAGES -> {
          assertEquals("MavenPackagesGcStrategy", type.strategy());
          assertEquals(MavenPackagesGcStrategy.NOTE, type.note());
          assertTrue(type.note().contains("snapshot"), "the note names the intended cleanup rule");
          assertNull(type.error(), "it depends on nothing outside this service, so it cannot fail");
          assertEquals(0, type.dead().size(), "append-only: this type condemns nothing, ever");
        }
        case OCI_MIRROR -> {
          assertEquals("OciMirrorGcStrategy", type.strategy());
          assertNull(type.note());
          assertNull(type.error(), "it depends on nothing outside this service, so it cannot fail");
          assertEquals(0, type.dead().size(), "append-only: this type condemns nothing, ever");
        }
        case CI_SCREENSHOTS -> {
          assertEquals("CiScreenshotsGcStrategy", type.strategy());
          assertEquals(CiScreenshotsGcStrategy.NOTE, type.note());
          assertTrue(type.note().contains("branch-scoped"), "the note names the intended rule");
          assertNull(type.error(), "zero rows: the stub plans nothing rather than refusing");
          assertEquals(0, type.dead().size());
        }
        case CI_VIDEOS -> {
          assertEquals("CiVideosGcStrategy", type.strategy());
          assertEquals(CiVideosGcStrategy.NOTE, type.note());
          assertTrue(type.note().contains("byte"), "the note names the intended rule");
          assertNull(type.error(), "zero rows: the stub plans nothing rather than refusing");
          assertEquals(0, type.dead().size());
        }
        default -> {
          assertNull(type.strategy());
          assertEquals("no strategy registered for " + type.type().wireName(), type.note());
        }
      }
      assertEquals(0, type.blobsSweepable());
      assertEquals(0L, type.reclaimableBytes());
    }
    assertEquals(0, report.sweep().blobCount());
    assertEquals(0L, report.sweep().reclaimableBytes());
    assertEquals(List.of(store.rowless()), report.untouchable().blobIds());
    assertEquals(ROWLESS, report.untouchable().bytes());
    assertEquals("P7D", report.graceWindow());
  }

  @Test
  void aStrategyIsNamedByItsOwnClassEvenWhenCdiHandsOverAClientProxy() {
    // Both shipped strategies are @Singleton, so neither is proxied and the assertion above passes
    // either way — which is exactly why this case exists: the next strategy will make that choice
    // again, and a report reading "Something_ClientProxy" names a class that appears in no source
    // file. The census stands in for a normal-scoped bean because it is one; injecting it gives a
    // real proxy rather than a simulated one.
    assertTrue(
        census.getClass().getSimpleName().endsWith("_ClientProxy"),
        "an @ApplicationScoped bean must be injected as a proxy, or this proves nothing: "
            + census.getClass().getSimpleName());
    assertEquals("LiveBlobCensus", GcPlanner.nameOf(census));
  }

  @Test
  void aBlobAnotherTypeStillNamesSurvivesItsOwnTypesDeletion() throws Exception {
    // The whole reason the sweep is one mechanism and the strategies are five: docker lets go of a
    // layer that npm is still serving as a tarball. Deleting it would 404 a package install.
    Store store = seed();
    LiveBlobCensus.Census taken = census.take();
    GcStrategy oci =
        strategy(
            RepositoryType.OCI_IMAGES,
            new GcStrategy.Plan(
                List.of(new GcIdentity("qits", "alpha:v2", "sha tag, no deployment pins it")),
                List.of(new GcIdentity("qits", "alpha:v1", "newest build")),
                Set.of(store.manifestDoomed(), store.layerDoomed(), store.shared(), store.config()),
                Set.of(store.manifestKept(), store.layerKept(), store.config())));

    GcPlanReport report = planner.plan(taken, List.of(oci));

    assertEquals(
        List.of(store.layerDoomed(), store.manifestDoomed()).stream().sorted().toList(),
        report.sweep().blobIds(),
        "the shared layer stays: npm still names it. The config stays: a kept manifest names it");
    assertEquals((long) LAYER_DOOMED + manifestSize(taken, store.manifestDoomed()),
        report.sweep().reclaimableBytes());
    GcTypePlan ociPlan = typePlan(report, RepositoryType.OCI_IMAGES);
    assertEquals(4, ociPlan.blobsReleased(), "released is what the dead identity named");
    assertEquals(2, ociPlan.blobsSweepable(), "sweepable is what nothing else names");
    assertEquals("alpha:v2", ociPlan.dead().get(0).identity());
    assertEquals("newest build", ociPlan.kept().get(0).rule());
  }

  @Test
  void aBlobAKeptIdentityOfTheSameTypeStillNamesSurvives() throws Exception {
    // Intra-type sharing, which is the common case in docker: every rebuild shares its base layers
    // with the tag before it. A strategy reports both sets precisely so it never has to subtract.
    Store store = seed();
    GcStrategy oci =
        strategy(
            RepositoryType.OCI_IMAGES,
            new GcStrategy.Plan(
                List.of(new GcIdentity("qits", "alpha:v2", "superseded")),
                List.of(),
                Set.of(store.config(), store.layerDoomed()),
                Set.of(store.config())));

    GcPlanReport report = planner.plan(census.take(), List.of(oci));

    assertEquals(List.of(store.layerDoomed()), report.sweep().blobIds());
    assertEquals(LAYER_DOOMED, report.sweep().reclaimableBytes());
  }

  @Test
  void aRowLessBlobIsNeverSweptEvenIfAStrategyNamesIt() throws Exception {
    // The ci-daemon rule, enforced rather than trusted. Structurally a strategy cannot release a
    // blob it never had a row for; a strategy that does it anyway must still not reach the unlink.
    Store store = seed();
    backdate(store.rowless(), java.time.Duration.ofDays(30));
    GcStrategy rogue =
        strategy(
            RepositoryType.CI_VIDEOS,
            new GcStrategy.Plan(List.of(), List.of(), Set.of(store.rowless()), Set.of()));

    GcPlanReport report = planner.plan(census.take(), List.of(rogue));

    assertEquals(List.of(), report.sweep().blobIds());
    assertEquals(List.of(store.rowless()), report.untouchable().blobIds());
    assertTrue(report.untouchable().reason().contains("LOSES its last row"));
  }

  @Test
  void aBlobYoungerThanTheGraceWindowIsWithheldRatherThanSwept() throws Exception {
    // Same plan, one difference: the file was written a moment ago. It is not lost, it is not yet —
    // and the number that says so is in the report, because a silent withholding reads as a bug.
    Store store = seed();
    GcStrategy oci =
        strategy(
            RepositoryType.OCI_IMAGES,
            new GcStrategy.Plan(
                List.of(new GcIdentity("qits", "alpha:v2", "superseded")),
                List.of(),
                Set.of(store.layerDoomed()),
                Set.of()));

    // Undo the fixture's backdating for that one blob: it is now as young as a fresh push.
    backdate(store.layerDoomed(), java.time.Duration.ZERO);
    GcPlanReport report = planner.plan(census.take(), List.of(oci));

    assertEquals(0, report.sweep().blobCount());
    assertEquals(1, report.sweep().withheldByGraceWindow());
    assertEquals(LAYER_DOOMED, report.sweep().withheldBytes());
    assertEquals(
        LAYER_DOOMED,
        typePlan(report, RepositoryType.OCI_IMAGES).reclaimableBytes(),
        "the per-type figure is what the rule frees, not what tonight's run would unlink");
  }

  @Test
  void aStrategyThatCannotEstablishItsKeepSetAbortsItsTypeAndFreesNothingOfIt() throws Exception {
    // Fail-closed, which is the whole reason plan() may throw: the OCI rule's keep-set includes the
    // shas qits-cd pins, fetched live. CD unreachable must reclaim nothing rather than guess — and
    // must not let another type's plan free a blob the aborted type still names.
    Store store = seed();
    GcStrategy unreachable =
        new GcStrategy() {
          @Override
          public RepositoryType type() {
            return RepositoryType.OCI_IMAGES;
          }

          @Override
          public Plan plan(LiveBlobCensus.Census census) {
            throw new IllegalStateException("qits-cd unreachable; refusing to plan on stale pins");
          }
        };
    GcStrategy npm =
        strategy(
            RepositoryType.NPM_PACKAGES,
            new GcStrategy.Plan(
                List.of(new GcIdentity("npm", "@qits/thing@1.1.0", "superseded prerelease")),
                List.of(),
                Set.of(store.shared()),
                Set.of(store.tarball())));

    GcPlanReport report = planner.plan(census.take(), List.of(unreachable, npm));

    GcTypePlan aborted = typePlan(report, RepositoryType.OCI_IMAGES);
    assertNotNull(aborted.error());
    assertTrue(aborted.error().contains("qits-cd unreachable"));
    assertEquals(0, aborted.blobsSweepable());
    assertEquals(
        List.of(),
        report.sweep().blobIds(),
        "the aborted type keeps its whole census set, so npm's release frees nothing");
  }

  @Test
  void twoStrategiesForOneTypeIsAPolicyCollisionAndNeitherRuns() throws Exception {
    // A type has exactly one policy. Two beans claiming it is a bug that must not be resolved by
    // merging them — which is what a shared retention framework would have done by design.
    Store store = seed();
    GcStrategy.Plan doomsEverything =
        new GcStrategy.Plan(
            List.of(), List.of(), Set.of(store.layerDoomed(), store.layerKept()), Set.of());

    GcPlanReport report =
        planner.plan(
            census.take(),
            List.of(
                strategy(RepositoryType.OCI_IMAGES, doomsEverything),
                strategy(RepositoryType.OCI_IMAGES, doomsEverything)));

    GcTypePlan collided = typePlan(report, RepositoryType.OCI_IMAGES);
    assertTrue(collided.error().contains("two strategies claim this type"));
    assertEquals(0, report.sweep().blobCount());
    assertFalse(report.sweep().blobIds().contains(store.layerKept()));
  }

  private static long manifestSize(LiveBlobCensus.Census census, String digest) {
    return census.onDisk().get(digest);
  }

  private static GcTypePlan typePlan(GcPlanReport report, RepositoryType type) {
    return report.types().stream()
        .filter(plan -> plan.type() == type)
        .findFirst()
        .orElseThrow();
  }

  /** A strategy that hands back a fixed answer — the seam is the contract, not the policy. */
  private static GcStrategy strategy(RepositoryType type, GcStrategy.Plan plan) {
    return new GcStrategy() {
      @Override
      public RepositoryType type() {
        return type;
      }

      @Override
      public Plan plan(LiveBlobCensus.Census census) {
        return plan;
      }
    };
  }
}
