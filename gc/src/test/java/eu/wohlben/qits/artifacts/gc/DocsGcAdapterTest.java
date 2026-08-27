package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.control.DocsProfile;
import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The docs adapter's facts, now that two kinds of version share one site: calver rows from the
 * release train are <b>releases</b> and spend belt slots; bare-sha rows from the per-commit
 * userflow pipelines are working artifacts that live on the access window alone. The lexical-hex
 * trap — "oldest" meaning "smallest sha string" — is the specific bug this suite pins shut.
 */
@QuarkusTest
class DocsGcAdapterTest extends GcFixture {

  /** The configured window for this type, and the number every case below is aged against. */
  private static final Duration WINDOW = Duration.ofDays(90);

  private static final String DOCS_REPO = "docs";
  private static final String SITE = "@userflows/qits-githost";

  @Inject DocsGcStrategy strategy;
  @Inject DocsGcAdapter adapter;

  @Test
  void aCalverRowIsAReleaseAndAShaRowIsNot() throws Exception {
    repository();
    docsSite(DOCS_REPO, SITE, "2026.801.30", daysAgo(10), null, Map.of(), blob(11));
    docsSite(DOCS_REPO, SITE, "a".repeat(40), daysAgo(10), null, Map.of(), blob(12));

    List<GcCandidate> candidates = adapter.enumerate();
    assertTrue(released(candidates, SITE + "@2026.801.30"), "a calver version is a release");
    assertFalse(released(candidates, SITE + "@" + "a".repeat(40)), "a sha version is not");
  }

  @Test
  void theBeltKeepsCalverReleasesWhileShaBundlesLiveOnTheWindowAlone() throws Exception {
    // Three calver releases: the newest two hold their belt slots forever, the oldest dies once
    // unread past the window. Two sha bundles: no slot for either — the stale one dies with the
    // oldest release, the fresh one survives as merely young.
    repository();
    String doomedRelease = blob(21);
    String doomedSha = blob(22);
    backdate(doomedRelease, Duration.ofDays(30));
    backdate(doomedSha, Duration.ofDays(30));
    docsSite(DOCS_REPO, SITE, "2026.601.10", daysAgo(400), null, Map.of(), doomedRelease);
    docsSite(DOCS_REPO, SITE, "2026.701.20", daysAgo(300), null, Map.of(), blob(23));
    docsSite(DOCS_REPO, SITE, "2026.801.30", daysAgo(200), null, Map.of(), blob(24));
    docsSite(DOCS_REPO, SITE, "a".repeat(40), daysAgo(100), null, Map.of(), doomedSha);
    docsSite(DOCS_REPO, SITE, "b".repeat(40), daysAgo(10), null, Map.of(), blob(25));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        Set.of(SITE + "@2026.601.10", SITE + "@" + "a".repeat(40)),
        Set.copyOf(identities(plan.dead())));
    assertEquals(OwnArtifactsStrategy.KEPT_RELEASE, ruleFor(plan.kept(), SITE + "@2026.701.20"));
    assertEquals(OwnArtifactsStrategy.KEPT_RELEASE, ruleFor(plan.kept(), SITE + "@2026.801.30"));
    // The fresh sha bundle is kept, and NOT as a release — publication counts as its first access.
    assertTrue(identities(plan.kept()).contains(SITE + "@" + "b".repeat(40)));
    assertFalse(
        OwnArtifactsStrategy.KEPT_RELEASE.equals(ruleFor(plan.kept(), SITE + "@" + "b".repeat(40))),
        "a sha bundle must never hold a belt slot");
  }

  @Test
  void versionAgeIsNumericForCalverAndAccessTimeForShasNeverLexical() throws Exception {
    // 2026.101.x sorts lexically BELOW 2026.9.x; numerically month 9 is older than month 101's
    // (i.e. the lexical trap inverts the calver order). And two shas order by when they were last
    // wanted, not by hex — 'z…' published first must rank older than 'a…' published later.
    repository();
    docsSite(DOCS_REPO, SITE, "2026.9.10", daysAgo(40), null, Map.of(), blob(31));
    docsSite(DOCS_REPO, SITE, "2026.101.10", daysAgo(30), null, Map.of(), blob(32));
    docsSite(DOCS_REPO, SITE, "z".repeat(40), daysAgo(20), null, Map.of(), blob(33));
    docsSite(DOCS_REPO, SITE, "a".repeat(40), daysAgo(10), null, Map.of(), blob(34));

    Comparator<GcCandidate> byAge = adapter.byAge();
    List<String> oldestFirst =
        adapter.enumerate().stream().sorted(byAge).map(GcCandidate::identity).toList();

    assertEquals(
        List.of(
            SITE + "@" + "z".repeat(40), // oldest access, and shas rank below every calver
            SITE + "@" + "a".repeat(40),
            SITE + "@2026.9.10", // numerically older than 101, lexically "newer"
            SITE + "@2026.101.10"),
        oldestFirst);
  }

  @Test
  void twoSitesDoNotSpendEachOthersBeltSlots() throws Exception {
    // The existing per-site group doctrine, pinned for docs now that it matters more: a site with
    // one old release keeps it as a release although the OTHER site published three since.
    repository();
    String otherSite = "@userflows/qits-artifacts";
    docsSite(DOCS_REPO, SITE, "2026.601.10", daysAgo(400), null, Map.of(), blob(41));
    docsSite(DOCS_REPO, otherSite, "2026.701.20", daysAgo(300), null, Map.of(), blob(42));
    docsSite(DOCS_REPO, otherSite, "2026.801.30", daysAgo(200), null, Map.of(), blob(43));
    docsSite(DOCS_REPO, otherSite, "2026.815.40", daysAgo(150), null, Map.of(), blob(44));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(OwnArtifactsStrategy.KEPT_RELEASE, ruleFor(plan.kept(), SITE + "@2026.601.10"));
    assertEquals(
        List.of(otherSite + "@2026.701.20"),
        identities(plan.dead()),
        "only the other site's third-newest release dies");
  }

  @Test
  void aCollectedShaVersionTakesItsMetadataWithIt() throws Exception {
    // The V2 cascade, driven through the real funnel: collecting the version removes its
    // docs_site_metadata rows with its files — no orphaned branch facts about a bundle that no
    // longer lists itself.
    repository();
    String doomed = blob(51);
    backdate(doomed, Duration.ofDays(30));
    docsSite(
        DOCS_REPO,
        SITE,
        "a".repeat(40),
        daysAgo(200),
        null,
        Map.of("git.branch.name", "feature/x", "git.commit.hash", "a".repeat(40)),
        doomed);
    docsSite(DOCS_REPO, SITE, "2026.801.30", daysAgo(10), null, Map.of(), blob(52));
    assertEquals(2, metadataRows());

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());
    GcStrategy.Applied applied = strategy.apply(plan, blobId -> false);

    assertEquals(List.of(SITE + "@" + "a".repeat(40)), identities(applied.deleted()));
    assertEquals(List.of(), applied.errors());
    assertEquals(0, metadataRows(), "fk_docs_site_metadata_site cascaded with the version");
  }

  // --- fixture ---------------------------------------------------------------------------------

  private void repository() {
    repositoryService.ensure(DOCS_REPO, DocsProfile.KEY);
  }

  private String blob(int size) throws IOException {
    return store(filled(size, (byte) (size % 251)));
  }

  private static Instant daysAgo(int days) {
    return Instant.now().minus(Duration.ofDays(days));
  }

  private static List<String> identities(List<GcIdentity> identities) {
    return identities.stream().map(GcIdentity::identity).toList();
  }

  private static String ruleFor(List<GcIdentity> kept, String identity) {
    return kept.stream()
        .filter(entry -> entry.identity().equals(identity))
        .map(GcIdentity::rule)
        .findFirst()
        .orElseThrow(() -> new AssertionError("not kept at all: " + identity));
  }

  private static boolean released(List<GcCandidate> candidates, String identity) {
    return candidates.stream()
        .filter(candidate -> candidate.identity().equals(identity))
        .findFirst()
        .orElseThrow(() -> new AssertionError("not enumerated: " + identity))
        .released();
  }

  private int metadataRows() throws Exception {
    try (Connection connection = blobs.getConnection();
        Statement statement = connection.createStatement();
        ResultSet counted =
            statement.executeQuery("select count(*) from docs_site_metadata")) {
      counted.next();
      return counted.getInt(1);
    }
  }
}
