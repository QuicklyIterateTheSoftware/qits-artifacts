package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.dto.GcIdentity;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The append-only stub, and the two things a stub still has to get right.
 *
 * <p>It must <b>claim</b> its type — an unclaimed type reports "no strategy registered", which is the
 * honest report of a decision nobody has taken, and here a decision has been taken (⚖2). And it must
 * report the type's live set as retained, because the sweep reconciles over what every type says it
 * still needs: a strategy that condemned nothing but also retained nothing would let another type's
 * plan free a blob this one is serving.
 */
@QuarkusTest
class OciMirrorGcStrategyTest extends GcFixture {

  @Inject OciMirrorGcStrategy strategy;

  @Test
  void itClaimsTheMirrorTypeAndCondemnsNothingInIt() throws Exception {
    seedMirror();

    GcStrategy.Plan plan = strategy.plan(census.take());

    assertEquals(RepositoryType.OCI_MIRROR, strategy.type());
    assertEquals(List.of(), plan.dead());
    assertEquals(Set.of(), plan.blobsReleased());
  }

  @Test
  void everyKeptIdentityNamesTheRuleThatKeptIt() throws Exception {
    // A dry-run report exists to be argued with. "Nothing dies" argued with is "nothing dies
    // BECAUSE eviction needs access tracking and this store has none" — which is a sentence a
    // reviewer can disagree with, unlike an empty list.
    MirrorStore mirror = seedMirror();

    GcStrategy.Plan plan = strategy.plan(census.take());

    assertEquals(
        List.of(
            MIRROR_IMAGE + ":jdk-25",
            MIRROR_IMAGE + "@" + OciDigest.wire(mirror.child())),
        plan.kept().stream().map(GcIdentity::identity).sorted().toList(),
        "the tag, and the child manifest no tag names — the second is what upstream tag movement"
            + " leaves behind, so a report that showed only tags would hide the thing growing");
    assertTrue(
        plan.kept().stream().allMatch(kept -> OciMirrorGcStrategy.KEPT_APPEND_ONLY.equals(kept.rule())),
        "one rule, and it is the settled posture rather than a placeholder");
    assertTrue(plan.kept().stream().allMatch(kept -> MIRROR_REPO.equals(kept.repository())));
  }

  @Test
  void whatItRetainsIsExactlyTheCensussLiveSetForTheType() throws Exception {
    // The claim the substrate depends on, and the same assertion the other two strategies carry for
    // the case where nothing dies.
    seedMirror();
    LiveBlobCensus.Census taken = census.take();

    GcStrategy.Plan plan = strategy.plan(taken);

    assertEquals(taken.live(RepositoryType.OCI_MIRROR).keySet(), plan.blobsRetained());
    assertTrue(plan.blobsRetained().size() >= 4, "index, child, config, layer at the very least");
  }

  @Test
  void aStoreWithNoMirrorNamespaceIsAnEmptyPlanRatherThanAFailure() throws Exception {
    // The shipped state of a platform that has not pulled anything through yet. Nothing here reaches
    // outside this service, so there is nothing for it to fail closed on — which is what makes an
    // error on this type's line mean something is genuinely wrong.
    seed();

    GcStrategy.Plan plan = strategy.plan(census.take());

    assertEquals(List.of(), plan.kept());
    assertEquals(Set.of(), plan.blobsRetained());
  }
}
