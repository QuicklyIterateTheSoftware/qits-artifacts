package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The mirror's live rule, and the pin it deliberately replaces.
 *
 * <p><b>This suite used to assert "nothing dies, ever" for this type.</b> That pin is gone on
 * purpose: it recorded a decision with a condition attached — append-only <em>pending access
 * tracking</em> (⚖2) — and the condition is met, so the type now runs the cache engine over V9's
 * {@code accessed_at}. What replaces it is below: cold content dies, warm content stays, and the
 * mechanisms that keep an eviction from breaking something — the grace window, the funnel's own
 * cleanup, the pin check — are asserted rather than assumed.
 */
@QuarkusTest
class OciMirrorGcStrategyTest extends GcFixture {

  private static final Duration WINDOW = Duration.ofDays(30);

  @Inject OciMirrorGcStrategy strategy;

  @Test
  void aTagNobodyHasPulledInsideTheWindowDiesAndItsFreshnessRowGoesWithIt() throws Exception {
    // The behaviour change, end to end: a cached tag cold for two months is evicted, its row is
    // gone, and so is the oci_mirror_tag_check row the miss path wrote beside it — a freshness row
    // for a tag that no longer exists is a row nothing would ever read or delete again.
    MirrorStore mirror = seedMirror();
    mirrorTagCheck("jdk-25", Instant.now().minus(Duration.ofDays(60)));
    ageMirrorRows(Duration.ofDays(60));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());
    GcStrategy.Applied applied = strategy.apply(plan, blobId -> false);

    assertEquals(RepositoryType.OCI_MIRROR, strategy.type());
    assertEquals(
        List.of(MIRROR_IMAGE + ":jdk-25", MIRROR_IMAGE + "@sha256:" + mirror.child()),
        identities(plan.dead()),
        "the tag, and the child manifest no tag names — upstream drift leaves both behind");
    assertEquals(List.of(), plan.kept());
    assertEquals(
        List.of(MIRROR_IMAGE + ":jdk-25", MIRROR_IMAGE + "@sha256:" + mirror.child()),
        identities(applied.deleted()));
    assertEquals(List.of(), applied.errors());

    ociTags.getEntityManager().clear();
    assertTrue(
        ociTags.findOne(MIRROR_REPO, MIRROR_IMAGE, "jdk-25").isEmpty(), "the tag row is gone");
    assertTrue(
        ociManifests.findOne(MIRROR_REPO, MIRROR_IMAGE, mirror.child()).isEmpty(),
        "the untagged child row is gone");
    assertTrue(
        mirrorTagChecks.findOne(MIRROR_REPO, MIRROR_IMAGE, "jdk-25").isEmpty(),
        "the freshness row travels with the tag, through the funnel rather than through a caller");
  }

  @Test
  void aTagPulledInsideTheWindowStaysAndBothLinesNameTheirRule() throws Exception {
    // The comparison a reviewer reads down the page: one identity kept, one condemned, each saying
    // why. "Kept" alone is not reviewable; "accessed inside the P30D window" is.
    MirrorStore mirror = seedMirror();
    ageMirrorRows(Duration.ofDays(60));
    touchMirrorTag("jdk-25", Instant.now().minus(Duration.ofDays(2)));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of(MIRROR_IMAGE + ":jdk-25"), identities(plan.kept()));
    assertEquals(CacheEvictionStrategy.keptAccessed(WINDOW), plan.kept().get(0).rule());
    assertEquals(List.of(MIRROR_IMAGE + "@sha256:" + mirror.child()), identities(plan.dead()));
    assertEquals(CacheEvictionStrategy.deadUnaccessed(WINDOW), plan.dead().get(0).rule());
  }

  @Test
  void aFreshlyCachedImageIsYoungRatherThanNeverPulled() throws Exception {
    // Creation counts as the first access. Without that, everything the mirror ever fetched would
    // be eligible the moment it landed — a cache that deletes what it just paid for.
    seedMirror();

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of(), plan.dead(), "nothing has aged out of a store minutes old");
    assertEquals(2, plan.kept().size(), "the tag and the untagged child");
    assertEquals(
        census.take().live(RepositoryType.OCI_MIRROR).keySet(),
        plan.blobsRetained(),
        "with nothing condemned, what this type retains is the census's own live set for it");
  }

  @Test
  void aChildManifestOfAKeptIndexIsEvictableAndItsBytesSurviveAnyway() throws Exception {
    // The lazy-pull bargain, stated as a test. An architecture nobody pulls ages out; the index
    // that lists it stays; and the child's bytes stay reachable through the index's closure, so the
    // sweep never unlinks them. Re-fetching that architecture costs one upstream request.
    MirrorStore mirror = seedMirror();
    ageMirrorRows(Duration.ofDays(60));
    touchMirrorTag("jdk-25", Instant.now());

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of(MIRROR_IMAGE + "@sha256:" + mirror.child()), identities(plan.dead()));
    assertTrue(
        plan.blobsReleased().contains(mirror.child()), "the dying row did name those bytes");
    assertTrue(
        plan.blobsRetained().contains(mirror.child()),
        "and the surviving index still does — the substrate subtracts, a plan never does");
    assertTrue(plan.blobsRetained().contains(mirror.index()));
  }

  @Test
  void anIdentityOverAYoungBlobIsWithheldWholeWithItsRowsIntact() throws Exception {
    // The strand hazard, carried over to this type: deleting a row while its file is inside the
    // grace window would leave the blob row-less — untouchable by construction, therefore never
    // reclaimed. The identity waits out the window with its file.
    MirrorStore mirror = seedMirror();
    ageMirrorRows(Duration.ofDays(60));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());
    GcStrategy.Applied applied = strategy.apply(plan, blobId -> blobId.equals(mirror.layer()));

    assertEquals(List.of(), applied.deleted(), "the layer under both identities is too young");
    assertEquals(
        List.of(MIRROR_IMAGE + ":jdk-25", MIRROR_IMAGE + "@sha256:" + mirror.child()),
        identities(applied.withheldByGraceWindow()));
    assertEquals(List.of(), applied.errors(), "withheld is the window working, not an error");
    ociTags.getEntityManager().clear();
    assertTrue(ociTags.findOne(MIRROR_REPO, MIRROR_IMAGE, "jdk-25").isPresent(), "the row stays");
  }

  @Test
  void aPinnedBlobKeepsItsIdentityWhateverTheAccessTimeSays() throws Exception {
    // Nothing pins a mirror tag by coordinate — cd pins application shas, ci pins daemon versions —
    // but blobs dedupe globally, so a digest pin can reach content through a cache row. Checked
    // before the access rule, and reported under the pin's own name.
    MirrorStore mirror = seedMirror();
    ageMirrorRows(Duration.ofDays(60));
    GcPins pinned =
        new GcPins(
            Map.of(), "ci-daemon", Set.of(mirror.index()), Set.of(mirror.index()), List.of());

    GcStrategy.Plan plan = strategy.plan(census.take(), pinned);

    assertEquals(List.of(MIRROR_IMAGE + ":jdk-25"), identities(plan.kept()));
    assertEquals(GcPins.BY_CI, plan.kept().get(0).rule());
    assertFalse(identities(plan.dead()).contains(MIRROR_IMAGE + ":jdk-25"));
  }

  @Test
  void aRunWithoutLivePinsRefusesToPlanRatherThanAssumeNothingIsPinned() throws Exception {
    // Fail-closed, and the reason this strategy declares readsPins(): planning against an
    // incomplete aggregate is planning against "nothing is pinned", which is the answer that
    // condemns everything a live service still holds.
    seedMirror();
    GcPins broken =
        new GcPins(Map.of(), "", Set.of(), Set.of(), List.of("qits-ci daemon pin: closed port"));

    IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> strategy.plan(census.take(), broken));

    assertTrue(refused.getMessage().contains("oci-mirror"), refused.getMessage());
    assertTrue(refused.getMessage().contains("closed port"), refused.getMessage());
  }

  @Test
  void aStoreWithNoMirrorNamespaceIsAnEmptyPlanRatherThanAFailure() throws Exception {
    // The shipped state of a platform that has not pulled anything through yet. Nothing here
    // reaches outside this service beyond the pins, so an error on this line means something is
    // genuinely wrong.
    seed();

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of(), plan.kept());
    assertEquals(List.of(), plan.dead());
    assertEquals(Set.of(), plan.blobsRetained());
  }

  private static List<String> identities(List<GcIdentity> identities) {
    return identities.stream().map(GcIdentity::identity).sorted().toList();
  }
}
