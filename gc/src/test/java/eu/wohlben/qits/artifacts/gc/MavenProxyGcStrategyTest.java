package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.control.MavenRegistryCollection;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The maven cache's live rule: cold files and their cold documents go, and the platform's own
 * deployed jars are never in reach.
 *
 * <p>Three things are on trial and each one is a way this type can go wrong. The <b>scope</b>:
 * {@code maven_artifact} is one table for both maven types, so an enumeration that leaked would put
 * the platform's own published library under a cache's eviction rule — or a cached jar under the
 * release belt. The <b>staleness rule</b> for a document, which folds in the access of the files
 * under its directory or it evicts the metadata of an artifact something is actively building
 * against. And the <b>identity being a path</b> rather than a coordinate, which is the one place
 * this adapter deliberately differs from {@code MavenPackagesGcAdapter}: a cache repairs itself on
 * the next request, so there is no half-version to prevent.
 */
@QuarkusTest
class MavenProxyGcStrategyTest extends GcFixture {

  private static final Duration WINDOW = Duration.ofDays(90);

  @Inject MavenProxyGcStrategy strategy;
  @Inject MavenRegistryCollection mavenCollection;

  @Test
  void onlyCachedRowsAreEnumeratedAndTheHostedRepositorysAreNotTouched() throws Exception {
    // The scope, asserted over a store holding both. seedMaven() deploys a jar and a pom into
    // `maven`; seedMavenProxy() caches two files into `central`. Nothing hosted may appear in this
    // type's plan at all — not as dead, and not as kept either, since a keep here would mean the
    // enumeration reached rows another engine owns.
    seedMaven();
    seedMavenProxy();

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(RepositoryType.MAVEN_PROXY, strategy.type());
    assertEquals(
        List.of(
            MAVEN_PROXY_COLD_PATH,
            MAVEN_PROXY_WARM_PATH,
            MAVEN_PROXY_METADATA_PATH + MavenProxyGcAdapter.METADATA),
        Stream.concat(plan.dead().stream(), plan.kept().stream())
            .map(GcIdentity::identity)
            .sorted()
            .toList());
    assertTrue(
        Stream.concat(plan.dead().stream(), plan.kept().stream())
            .allMatch(identity -> MAVEN_PROXY_REPO.equals(identity.repository())),
        "every identity is the cache's; the hosted repository is another engine's business");
    assertTrue(mavenArtifacts.findOne(MAVEN_REPO, MAVEN_JAR_PATH).isPresent());
  }

  @Test
  void aColdFileGoesAndAWarmOneKeepsItselfAndTheDocumentAboveIt() throws Exception {
    // The rule and the staleness rule in one case. The 1.7.36 jar was last resolved 200 days ago, so
    // it goes. The DOCUMENT was last revalidated 200 days ago too — but the 2.0.13 jar under it was
    // resolved yesterday, and a document judged on fetched_at alone would be evicted out from under
    // an artifact something is actively building against.
    seedMavenProxy();

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of(MAVEN_PROXY_COLD_PATH), identities(plan.dead()));
    assertEquals(
        List.of(MAVEN_PROXY_WARM_PATH, MAVEN_PROXY_METADATA_PATH + MavenProxyGcAdapter.METADATA),
        identities(plan.kept()));
    assertTrue(plan.dead().stream()
        .allMatch(dead -> CacheEvictionStrategy.deadUnaccessed(WINDOW).equals(dead.rule())));
    assertTrue(plan.kept().stream()
        .allMatch(kept -> CacheEvictionStrategy.keptAccessed(WINDOW).equals(kept.rule())));
  }

  @Test
  void oneColdFileGoesWithoutDraggingItsWarmSiblingWithIt() throws Exception {
    // The deliberate difference from maven-packages, whose identity is a whole coordinate because
    // half a published version is a broken resolve nothing can repair. Here the next request
    // re-fetches whatever is missing, so a file is the unit and a warm sibling neither saves a cold
    // one nor is dragged out by it.
    MavenProxyStore proxy = seedMavenProxy();

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());
    GcStrategy.Applied applied = strategy.apply(plan, blobId -> false);

    assertEquals(List.of(MAVEN_PROXY_COLD_PATH), identities(applied.deleted()));
    assertEquals(List.of(), applied.errors());

    mavenArtifacts.getEntityManager().clear();
    assertTrue(mavenArtifacts.findOne(MAVEN_PROXY_REPO, MAVEN_PROXY_COLD_PATH).isEmpty());
    assertTrue(
        mavenArtifacts.findOne(MAVEN_PROXY_REPO, MAVEN_PROXY_WARM_PATH).isPresent(),
        "the warm file is untouched");
    assertTrue(
        mavenProxyMetadata.findOne(MAVEN_PROXY_REPO, MAVEN_PROXY_METADATA_PATH).isPresent(),
        "and so is the document a resolver still reads");
    assertTrue(
        blobStore.exists(proxy.coldJar()), "the blob is the sweep's question, not this one");
  }

  @Test
  void theHostedDoorRefusesToEvictADeployedFileEvenWhenAskedDirectly() throws Exception {
    // The mechanism's own belt, independent of any policy: the eviction door checks the repository
    // TYPE, because one table holds both kinds of row and a caller that got it wrong would delete a
    // published jar through the cache's door.
    seedMaven();

    RuntimeException refused =
        assertThrows(
            RuntimeException.class,
            () -> mavenCollection.evictProxiedArtifact(MAVEN_REPO, MAVEN_JAR_PATH));

    assertTrue(refused.getMessage().contains("maven-packages"), refused.getMessage());
    assertTrue(mavenArtifacts.findOne(MAVEN_REPO, MAVEN_JAR_PATH).isPresent(), "the row stays");
  }

  @Test
  void aFileInsideTheGraceWindowWithholdsItsRowIntact() throws Exception {
    // The strand hazard on this type: a row deleted over a young file would leave the file row-less,
    // and row-less is untouchable by construction. A document names no file, so it is never withheld
    // — there is nothing for the window to protect.
    seedMavenProxy();

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());
    GcStrategy.Applied applied = strategy.apply(plan, blobId -> true);

    assertEquals(List.of(MAVEN_PROXY_COLD_PATH), identities(applied.withheldByGraceWindow()));
    assertEquals(List.of(), identities(applied.deleted()));
    mavenArtifacts.getEntityManager().clear();
    assertTrue(mavenArtifacts.findOne(MAVEN_PROXY_REPO, MAVEN_PROXY_COLD_PATH).isPresent());
  }

  @Test
  void aPinnedBlobKeepsItsCachedFileWhateverTheAccessTimeSays() throws Exception {
    // Blobs dedupe globally, so the same bytes can be reachable through a cache row and through a
    // pin naming them by digest. That is why this type reads pins at all.
    MavenProxyStore proxy = seedMavenProxy();
    GcPins pinned =
        new GcPins(
            Map.of(), "ci-daemon", Set.of(proxy.coldJar()), Set.of(proxy.coldJar()), List.of());

    GcStrategy.Plan plan = strategy.plan(census.take(), pinned);

    assertEquals(List.of(), identities(plan.dead()));
    assertEquals(
        GcPins.BY_CI,
        plan.kept().stream()
            .filter(kept -> kept.identity().equals(MAVEN_PROXY_COLD_PATH))
            .findFirst()
            .orElseThrow()
            .rule());
  }

  @Test
  void theNoteCarriesTheH2HonestyLineOnEveryReport() throws Exception {
    // Without it a reviewer reads unchanged reclaimable bytes beside a list of condemned documents
    // and concludes the collector is broken. The characters leave H2's live set; the file shrinks
    // only when a maintenance restart compacts it, which nothing here runs.
    seedMavenProxy();

    String note = strategy.note();

    assertTrue(note.contains("SHUTDOWN COMPACT"), note);
    assertTrue(note.contains("0 bytes"), note);
    assertTrue(note.contains("characters"), note);
  }

  @Test
  void aRunWithoutLivePinsRefusesToPlanRatherThanAssumeNothingIsPinned() throws Exception {
    seedMavenProxy();
    GcPins broken =
        new GcPins(
            Map.of(), "", Set.of(), Set.of(),
            List.of("qits-platform-deployments deployment pins: closed"));

    IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> strategy.plan(census.take(), broken));

    assertTrue(refused.getMessage().contains("maven-proxy"), refused.getMessage());
  }

  @Test
  void aStoreWithNoCacheNamespaceIsAnEmptyPlanRatherThanAFailure() throws Exception {
    seedMaven();

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of(), plan.dead());
    assertEquals(List.of(), plan.kept());
    assertFalse(plan.blobsRetained().contains("anything"));
  }

  @Test
  void whatTheTypeRetainsIsTheCensusOwnLiveSetWhenNothingDies() throws Exception {
    // The vocabulary check every strategy suite here carries: the two blob sets a plan returns are
    // the census's, which is what the substrate reconciles over.
    seedMavenProxy();

    GcStrategy.Plan plan =
        new CacheEvictionStrategy()
            .plan(strategy.adapter(), Duration.ofDays(3650), Instant.now(), GcPinned.NONE);

    assertEquals(List.of(), identities(plan.dead()));
    assertEquals(census.take().live(RepositoryType.MAVEN_PROXY).keySet(), plan.blobsRetained());
  }

  private static List<String> identities(List<GcIdentity> identities) {
    return identities.stream().map(GcIdentity::identity).sorted().toList();
  }
}
