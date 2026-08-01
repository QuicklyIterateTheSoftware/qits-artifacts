package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.dto.StoreSummary;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
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
class LiveBlobCensusTest extends GcFixture {

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
        sorted(taken.live(RepositoryType.OCI_IMAGES).keySet()),
        "the manifest closure, manifests included — a manifest is a blob too");
    assertEquals(
        sorted(store.tarball(), store.shared()),
        sorted(taken.live(RepositoryType.NPM_PACKAGES).keySet()));
    assertEquals(Set.of(), taken.live(RepositoryType.NPM_PROXY).keySet());
    assertEquals(Set.of(), taken.live(RepositoryType.CI_SCREENSHOTS).keySet());
    assertEquals(Set.of(), taken.live(RepositoryType.CI_VIDEOS).keySet());
  }

  @Test
  void oneBlobIsLiveInEveryTypeThatNamesIt() throws Exception {
    // The same bytes as an image layer and as a published tarball. Deduped to one file, and both
    // sides have to let go before it can die — which is only expressible if both sides are recorded.
    Store store = seed();

    LiveBlobCensus.Census taken = census.take();

    assertTrue(taken.live(RepositoryType.OCI_IMAGES).containsKey(store.shared()));
    assertTrue(taken.live(RepositoryType.NPM_PACKAGES).containsKey(store.shared()));
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

    assertEquals(taken.liveBytes(RepositoryType.OCI_IMAGES), summary.ociUnionBytes());
    assertEquals(taken.liveBytes(RepositoryType.NPM_PACKAGES), summary.npmPublishedBytes());
    assertEquals(taken.liveBytes(RepositoryType.NPM_PROXY), summary.npmProxyTarballBytes());
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
