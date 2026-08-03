package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    assertEquals(Set.of(), taken.live(RepositoryType.OCI_MIRROR).keySet());
    assertEquals(Set.of(), taken.live(RepositoryType.MAVEN_PACKAGES).keySet());
    assertEquals(Set.of(), taken.live(RepositoryType.DAEMON_BINARIES).keySet());
    assertEquals(Set.of(), taken.live(RepositoryType.CI_SCREENSHOTS).keySet());
    assertEquals(Set.of(), taken.live(RepositoryType.CI_VIDEOS).keySet());
  }

  @Test
  void whatMavenDeployedIsLiveUnderItsOwnTypeRatherThanOrphaned() throws Exception {
    // The pin the maven type's first landing asks for: a deployed jar unknown to the census would
    // be misreported as an ORPHAN — servable, row-less-looking, untouchable. Attribution runs off
    // the repository row's type, so both maven types' sets fill from maven_artifact and the
    // pull-through workstream adds no census code when its constant lands.
    MavenStore maven = seedMaven();

    LiveBlobCensus.Census taken = census.take();

    assertEquals(
        Set.of(maven.jar(), maven.pom()), taken.live(RepositoryType.MAVEN_PACKAGES).keySet());
    assertEquals(
        (long) MAVEN_JAR + MAVEN_POM,
        taken.liveBytes(RepositoryType.MAVEN_PACKAGES),
        "sized from the rows — the one protocol table that has the size, so no disk read");
    assertTrue(
        taken.rowless().stream().noneMatch(Set.of(maven.jar(), maven.pom())::contains),
        "nothing maven deployed may be classified as an orphan");
  }

  @Test
  void whatAMirrorCachedIsLiveUnderItsOwnTypeRatherThanOrphaned() throws Exception {
    // The pin the mirror plan asks for. Cached bytes have manifest rows like any other, so the
    // closure reaches them — but only if the census WALKS the new type. Without that line they are
    // row-less: reported as orphans by the store summary and, worse, indistinguishable from the
    // untouchable pool in every later reading.
    seed();
    MirrorStore mirror = seedMirror();

    LiveBlobCensus.Census taken = census.take();

    assertTrue(
        taken.live(RepositoryType.OCI_MIRROR).keySet().containsAll(
            Set.of(mirror.index(), mirror.child(), mirror.config(), mirror.layer())),
        "the index, its fetched child, and that child's config and layer");
    assertTrue(
        taken.rowless().stream().noneMatch(Set.of(mirror.index(), mirror.child(), mirror.config(), mirror.layer())::contains),
        "nothing a mirror cached may be classified as an orphan");
    assertFalse(
        taken.live(RepositoryType.OCI_IMAGES).containsKey(mirror.layer()),
        "and it is the MIRROR's live set, not the hosted registry's — the two types are collected"
            + " under different rules");
  }

  @Test
  void aMirrorIndexWhoseChildWasNeverFetchedIsWalkedRatherThanRefused() throws Exception {
    // The lazy pull order, as the census sees it: an index binds first and its children arrive one
    // miss at a time, so an index pointing at a manifest with no local row is the normal state of a
    // partially-pulled image. The walk must survive it and keep counting — the property is lenient
    // by construction (OciManifestParser.sizedReferences), and this is the test rather than the
    // assumption the plan asked for.
    MirrorStore mirror = seedMirror();

    LiveBlobCensus.Census taken = census.take();

    assertTrue(
        taken.live(RepositoryType.OCI_MIRROR).containsKey(mirror.child()),
        "the child that WAS fetched is still reached — the walk did not stop at the missing one");
    assertTrue(
        taken.live(RepositoryType.OCI_MIRROR).containsKey(mirror.absentChild()),
        "and the missing one is counted at its declared size, which is all that is known of it");
    assertEquals(
        0L,
        taken.bytesOnDisk(Set.of(mirror.absentChild())),
        "it frees nothing, because there is no file: a declared size is not bytes on disk");
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
    assertEquals(taken.liveBytes(RepositoryType.OCI_MIRROR), summary.ociMirrorBytes());
    assertEquals(taken.liveBytes(RepositoryType.NPM_PACKAGES), summary.npmPublishedBytes());
    assertEquals(taken.liveBytes(RepositoryType.NPM_PROXY), summary.npmProxyTarballBytes());
    assertEquals(taken.liveBytes(RepositoryType.MAVEN_PACKAGES), summary.mavenPublishedBytes());
    assertEquals(taken.liveBytes(RepositoryType.DAEMON_BINARIES), summary.daemonBinaryBytes());
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
