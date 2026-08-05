package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.control.LiveBlobCensus;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The maven claim, and the two things a "nothing dies" strategy still has to get right — the same
 * pair the mirror's suite pins.
 *
 * <p>It must <b>claim</b> its type — an unclaimed type reports "no strategy registered", which is
 * the honest report of a decision nobody has taken, and here a decision has been taken
 * (maven-repository-plan.md §3.8). And it must report the type's live set as retained, because the
 * sweep reconciles over what every type says it still needs: a strategy that condemned nothing but
 * also retained nothing would let another type's plan free a blob this one is serving.
 */
@QuarkusTest
class MavenPackagesGcStrategyTest extends GcFixture {

  @Inject MavenPackagesGcStrategy strategy;

  @Test
  void itClaimsTheMavenTypeAndCondemnsNothingInIt() throws Exception {
    seedMaven();

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(RepositoryType.MAVEN_PACKAGES, strategy.type());
    assertEquals(List.of(), plan.dead());
    assertEquals(Set.of(), plan.blobsReleased());
  }

  @Test
  void everyKeptIdentityNamesTheRuleThatKeptIt() throws Exception {
    // A dry-run report exists to be argued with. "Nothing dies" argued with is "nothing dies
    // BECAUSE releases are never eligible and snapshot cleanup is its own named rule" — a sentence
    // a reviewer can disagree with, unlike an empty list.
    seedMaven();

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        List.of(MAVEN_JAR_PATH, MAVEN_POM_PATH),
        plan.kept().stream().map(GcIdentity::identity).sorted().toList());
    assertTrue(
        plan.kept().stream()
            .allMatch(kept -> MavenPackagesGcStrategy.KEPT_APPEND_ONLY.equals(kept.rule())),
        "one rule, and it is the settled posture rather than a placeholder");
    assertTrue(plan.kept().stream().allMatch(kept -> MAVEN_REPO.equals(kept.repository())));
    assertTrue(strategy.note().contains("snapshot"), "the note names the intended cleanup rule");
  }

  @Test
  void whatItRetainsIsExactlyTheCensussLiveSetForTheType() throws Exception {
    // The claim the substrate depends on, and the same assertion the other claimed types carry for
    // the case where nothing dies.
    seedMaven();
    LiveBlobCensus.Census taken = census.take();

    GcStrategy.Plan plan = strategy.plan(taken, GcPins.none());

    assertEquals(taken.live(RepositoryType.MAVEN_PACKAGES).keySet(), plan.blobsRetained());
    assertEquals(2, plan.blobsRetained().size(), "the jar and the pom");
  }

  @Test
  void aStoreWithNoMavenRepositoryIsAnEmptyPlanRatherThanAFailure() throws Exception {
    // The shipped state of a platform that has not deployed anything yet. Nothing here reaches
    // outside this service, so there is nothing for it to fail closed on — which is what makes an
    // error on this type's line mean something is genuinely wrong.
    seed();

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of(), plan.kept());
    assertEquals(Set.of(), plan.blobsRetained());
  }
}
