package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.blobstore.control.CiScreenshotsProfile;
import eu.wohlben.qits.blobstore.control.CiVideosProfile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.dto.StoreSummary;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The census, and the two claims garbage collection rests on.
 *
 * <p><b>One: liveness has an owner.</b> A blob is live because some type's identity names it, and
 * the census says which type — that is what lets five strategies act independently without any of
 * them being able to free something another still needs.
 *
 * <p><b>Two: this is the same reading the explorer serves.</b> The store summary is asserted here
 * against the census rather than recomputed, because the failure this prevents is not arithmetic: it
 * is a second implementation drifting until the page calls a blob referenced and a sweep calls it
 * garbage.
 */
@QuarkusTest
class LiveBlobCensusTest extends SeededStoreFixture {

  @Inject ArtifactExplorerService explorer;

  @Test
  void everyLiveBlobIsAttributedToTheTypeWhoseIdentityNamesIt() throws Exception {
    Store store = seed();

    LiveBlobCensus.Census taken = census.take();

    assertEquals(
        sorted(
            store.config(),
            store.layerKept(),
            store.layerDoomed(),
            store.shared(),
            store.manifestKept(),
            store.manifestDoomed()),
        sorted(taken.live(OciImagesProfile.KEY).keySet()),
        "the manifest closure, manifests included — a manifest is a blob too");
    assertEquals(
        sorted(store.tarball(), store.shared()),
        sorted(taken.live(NpmPackagesProfile.KEY).keySet()));
    assertEquals(Set.of(), taken.live(MavenPackagesProfile.KEY).keySet());
    assertEquals(Set.of(), taken.live(DaemonBinariesProfile.KEY).keySet());
    assertEquals(Set.of(), taken.live(CiScreenshotsProfile.KEY).keySet());
    assertEquals(Set.of(), taken.live(CiVideosProfile.KEY).keySet());
  }

  @Test
  void whatMavenDeployedIsLiveUnderItsOwnTypeRatherThanOrphaned() throws Exception {
    // The pin the maven type's first landing asks for: a deployed jar unknown to the census would
    // be misreported as an ORPHAN — servable, row-less-looking, untouchable. Attribution runs off
    // the repository row's type, so both maven types' sets fill from maven_artifact — which is why
    // the pull-through cache needed no census code at all.
    MavenStore maven = seedMaven();

    LiveBlobCensus.Census taken = census.take();

    assertEquals(
        Set.of(maven.jar(), maven.pom()), taken.live(MavenPackagesProfile.KEY).keySet());
    assertEquals(
        (long) MAVEN_JAR + MAVEN_POM,
        taken.liveBytes(MavenPackagesProfile.KEY),
        "sized from the rows — the one protocol table that has the size, so no disk read");
    assertTrue(
        taken.rowless().stream().noneMatch(Set.of(maven.jar(), maven.pom())::contains),
        "nothing maven deployed may be classified as an orphan");
  }

  @Test
  void oneBlobIsLiveInEveryTypeThatNamesIt() throws Exception {
    // The same bytes as an image layer and as a published tarball. Deduped to one file, and both
    // sides have to let go before it can die — which is only expressible if both sides are recorded.
    Store store = seed();

    LiveBlobCensus.Census taken = census.take();

    assertTrue(taken.live(OciImagesProfile.KEY).containsKey(store.shared()));
    assertTrue(taken.live(NpmPackagesProfile.KEY).containsKey(store.shared()));
    assertEquals(SHARED, taken.onDisk().get(store.shared()));
  }

  @Test
  void aBlobNoIdentityNamesIsRowLessRatherThanDead() throws Exception {
    // The 124 MiB pool, in miniature: bytes with no row anywhere, one of which on the real store is
    // the CI daemon binary every build downloads. Nameable, countable, and out of reach.
    Store store = seed();

    LiveBlobCensus.Census taken = census.take();

    assertEquals(List.of(store.rowless()), List.copyOf(taken.rowless()));
    assertEquals(ROWLESS, taken.rowlessBytes());
  }

  @Test
  void theStoreSummaryIsThisCensusRatherThanASecondReadingOfTheSameStore() throws Exception {
    seed();

    LiveBlobCensus.Census taken = census.take();
    StoreSummary summary = explorer.storeSummary();

    assertEquals(taken.liveBytes(OciImagesProfile.KEY), summary.ociUnionBytes());
    assertEquals(0L, summary.ociMirrorBytes(), "no cache type is registered here");
    assertEquals(taken.liveBytes(NpmPackagesProfile.KEY), summary.npmPublishedBytes());
    assertEquals(0L, summary.npmProxyTarballBytes(), "nor can a row carry one");
    assertEquals(taken.liveBytes(MavenPackagesProfile.KEY), summary.mavenPublishedBytes());
    assertEquals(taken.liveBytes(DaemonBinariesProfile.KEY), summary.daemonBinaryBytes());
    assertEquals(taken.rowlessBytes(), summary.orphanBytes());
    assertEquals(taken.diskTotalBytes(), summary.diskTotalBytes());
    assertEquals(taken.ociPerImageSumBytes(), summary.ociPerImageSumBytes());
  }

  private static Set<String> sorted(String... blobIds) {
    return new TreeSet<>(Set.of(blobIds));
  }

  private static Set<String> sorted(Set<String> blobIds) {
    return new TreeSet<>(blobIds);
  }
}
