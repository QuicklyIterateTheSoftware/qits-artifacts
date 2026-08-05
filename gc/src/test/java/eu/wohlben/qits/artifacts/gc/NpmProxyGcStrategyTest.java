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
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The proxy's live rule: cold versions and their cold documents go, and <b>no tombstone is
 * written</b>.
 *
 * <p>Three things are on trial and each one is a way this type can go wrong. The <b>scope</b>:
 * {@code npm_version} is one table for both npm types, so an enumeration that leaked would put the
 * platform's own published packages under a cache's eviction rule. The <b>staleness rule</b> for a
 * packument, which has to fold in its versions' access or it evicts the document of a package
 * something is actively installing. And the <b>missing tombstone</b>, which is what makes an
 * eviction a cache decision rather than an unpublish of somebody else's package.
 */
@QuarkusTest
class NpmProxyGcStrategyTest extends GcFixture {

  private static final Duration WINDOW = Duration.ofDays(30);

  @Inject NpmProxyGcStrategy strategy;
  @Inject eu.wohlben.qits.artifacts.control.NpmRegistryCollection npmCollection;

  @Test
  void onlyProxyRowsAreEnumeratedAndTheHostedRegistrysAreNotTouched() throws Exception {
    // The scope, asserted over a store holding both. seed() publishes two hosted versions into
    // `npm`; seedProxy() caches two proxied ones into `npmjs`. Nothing hosted may appear in this
    // type's plan at all — not as dead, and not as kept either, since a keep here would mean the
    // enumeration reached rows another engine owns.
    seed();
    seedProxy();

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(RepositoryType.NPM_PROXY, strategy.type());
    List<String> everyIdentity =
        java.util.stream.Stream.concat(plan.dead().stream(), plan.kept().stream())
            .map(GcIdentity::identity)
            .sorted()
            .toList();
    assertEquals(
        List.of(
            PROXY_WARM_PACKAGE + NpmProxyGcAdapter.PACKUMENT,
            PROXY_WARM_PACKAGE + "@5.3.0",
            PROXY_COLD_PACKAGE + NpmProxyGcAdapter.PACKUMENT,
            PROXY_COLD_PACKAGE + "@1.3.0"),
        everyIdentity);
    assertTrue(
        java.util.stream.Stream.concat(plan.dead().stream(), plan.kept().stream())
            .allMatch(identity -> PROXY_REPO.equals(identity.repository())),
        "every identity is the proxy's; the hosted repository is another engine's business");
    assertTrue(npmVersions.findOne("npm", "@qits/thing", "1.0.0").isPresent());
  }

  @Test
  void aColdPackageLosesItsVersionAndItsDocumentAndAWarmOneKeepsBoth() throws Exception {
    // The rule and the staleness rule in one case. left-pad was last installed 200 days ago, so
    // both its rows go. chalk's DOCUMENT was last revalidated 200 days ago too — but its tarball
    // was pulled yesterday, and a packument judged on fetched_at alone would evict the document of
    // a package something is actively installing.
    seedProxy();

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        List.of(PROXY_COLD_PACKAGE + NpmProxyGcAdapter.PACKUMENT, PROXY_COLD_PACKAGE + "@1.3.0"),
        identities(plan.dead()));
    assertEquals(
        List.of(PROXY_WARM_PACKAGE + NpmProxyGcAdapter.PACKUMENT, PROXY_WARM_PACKAGE + "@5.3.0"),
        identities(plan.kept()));
    assertTrue(plan.dead().stream()
        .allMatch(dead -> CacheEvictionStrategy.deadUnaccessed(WINDOW).equals(dead.rule())));
    assertTrue(plan.kept().stream()
        .allMatch(kept -> CacheEvictionStrategy.keptAccessed(WINDOW).equals(kept.rule())));
  }

  @Test
  void anEvictedVersionLeavesNoTombstoneBecauseReFetchingItIsThePoint() throws Exception {
    // The assertion this type exists to make. A tombstone records "this name is spent forever",
    // which is what a hosted registry owes its consumers and the opposite of what a cache owes:
    // the version is upstream's, and the next install must be able to pull it through again.
    ProxyStore proxy = seedProxy();

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());
    GcStrategy.Applied applied = strategy.apply(plan, blobId -> false);

    assertEquals(
        List.of(PROXY_COLD_PACKAGE + NpmProxyGcAdapter.PACKUMENT, PROXY_COLD_PACKAGE + "@1.3.0"),
        identities(applied.deleted()));
    assertEquals(List.of(), applied.errors());

    npmVersions.getEntityManager().clear();
    assertTrue(npmVersions.findOne(PROXY_REPO, PROXY_COLD_PACKAGE, "1.3.0").isEmpty());
    assertTrue(npmProxyPackuments.findOne(PROXY_REPO, PROXY_COLD_PACKAGE).isEmpty());
    assertTrue(
        npmVersionTombstones.findOne(PROXY_REPO, PROXY_COLD_PACKAGE, "1.3.0").isEmpty(),
        "no tombstone: a tombstoned cache entry would refuse the re-cache the proxy exists for");
    assertEquals(0, npmVersionTombstones.count(), "and none anywhere else either");
    assertTrue(
        npmVersions.findOne(PROXY_REPO, PROXY_WARM_PACKAGE, "5.3.0").isPresent(),
        "the warm package is untouched");
    assertTrue(blobStore.exists(proxy.coldTarball()), "the blob is the sweep's question, not this one");
  }

  @Test
  void theHostedDoorRefusesToEvictWithoutATombstoneEvenWhenAskedDirectly() throws Exception {
    // The mechanism's own belt, independent of any policy: the eviction door checks the repository
    // TYPE, because one table holds both kinds of row and a caller that got it wrong would strip a
    // published version of the tombstone its immutability guarantee rests on.
    seed();

    RuntimeException refused =
        assertThrows(
            RuntimeException.class,
            () -> npmCollection.evictProxiedVersion("npm", "@qits/thing", "1.0.0"));

    assertTrue(refused.getMessage().contains("npm-packages"), refused.getMessage());
    assertTrue(refused.getMessage().contains("tombstone"), refused.getMessage());
    assertTrue(npmVersions.findOne("npm", "@qits/thing", "1.0.0").isPresent(), "the row stays");
  }

  @Test
  void aTarballInsideTheGraceWindowWithholdsItsVersionRowIntact() throws Exception {
    // The strand hazard on this type: a row deleted over a young file would leave the file
    // row-less, and row-less is untouchable by construction. A packument names no file, so it is
    // never withheld — there is nothing for the window to protect.
    seedProxy();

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());
    GcStrategy.Applied applied = strategy.apply(plan, blobId -> true);

    assertEquals(
        List.of(PROXY_COLD_PACKAGE + "@1.3.0"), identities(applied.withheldByGraceWindow()));
    assertEquals(
        List.of(PROXY_COLD_PACKAGE + NpmProxyGcAdapter.PACKUMENT), identities(applied.deleted()));
    npmVersions.getEntityManager().clear();
    assertTrue(npmVersions.findOne(PROXY_REPO, PROXY_COLD_PACKAGE, "1.3.0").isPresent());
  }

  @Test
  void aPinnedTarballKeepsItsVersionWhateverTheAccessTimeSays() throws Exception {
    ProxyStore proxy = seedProxy();
    GcPins pinned =
        new GcPins(
            Map.of(), "ci-daemon", Set.of(proxy.coldTarball()), Set.of(proxy.coldTarball()),
            List.of());

    GcStrategy.Plan plan = strategy.plan(census.take(), pinned);

    assertEquals(
        List.of(PROXY_COLD_PACKAGE + NpmProxyGcAdapter.PACKUMENT), identities(plan.dead()));
    assertEquals(
        GcPins.BY_CI,
        plan.kept().stream()
            .filter(kept -> kept.identity().equals(PROXY_COLD_PACKAGE + "@1.3.0"))
            .findFirst()
            .orElseThrow()
            .rule());
  }

  @Test
  void theNoteCarriesTheH2HonestyLineOnEveryReport() throws Exception {
    // Without it a reviewer reads "0 reclaimable bytes" beside a hundred condemned packuments and
    // concludes the collector is broken. The characters leave H2's live set; the file shrinks only
    // when a maintenance restart compacts it, which nothing here runs.
    seedProxy();

    String note = strategy.note();

    assertTrue(note.contains("SHUTDOWN COMPACT"), note);
    assertTrue(note.contains("0 bytes"), note);
    assertTrue(note.contains("characters"), note);
  }

  @Test
  void aRunWithoutLivePinsRefusesToPlanRatherThanAssumeNothingIsPinned() throws Exception {
    seedProxy();
    GcPins broken =
        new GcPins(Map.of(), "", Set.of(), Set.of(), List.of("qits-cd deployment pins: closed"));

    IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> strategy.plan(census.take(), broken));

    assertTrue(refused.getMessage().contains("npm-proxy"), refused.getMessage());
  }

  @Test
  void aStoreWithNoProxyNamespaceIsAnEmptyPlanRatherThanAFailure() throws Exception {
    seed();

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of(), plan.dead());
    assertEquals(List.of(), plan.kept());
    assertFalse(plan.blobsRetained().contains("anything"));
  }

  private static List<String> identities(List<GcIdentity> identities) {
    return identities.stream().map(GcIdentity::identity).sorted().toList();
  }
}
