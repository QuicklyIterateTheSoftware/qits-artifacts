package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.control.CdDeploymentPins.Deployment;
import eu.wohlben.qits.artifacts.dto.GcIdentity;
import eu.wohlben.qits.artifacts.dto.GcPlanReport;
import eu.wohlben.qits.artifacts.entity.OciManifest;
import eu.wohlben.qits.artifacts.entity.OciTag;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Docker's keep-set, case by case, against real manifests and the real census.
 *
 * <p>The pin source is faked rather than driven over HTTP: what is under test is the rule, and the
 * rule's whole input is "which rows did cd return, in what order". The one case that exercises the
 * transport is the opposite one — cd refusing to answer, which must abort rather than plan.
 *
 * <p>Tags here are written as full 40-hex shas because that is what post-receive pushes and what a
 * deployment row carries; a case that used {@code v1} would prove nothing about the classification
 * these rules turn on.
 */
@QuarkusTest
class OciImageGcStrategyTest extends GcFixture {

  private static final String SHA_A = "a".repeat(40);
  private static final String SHA_B = "b".repeat(40);
  private static final String SHA_C = "c".repeat(40);
  private static final String SHA_D = "d".repeat(40);

  @Inject OciManifestFootprints footprints;
  @Inject GcPlanner planner;

  @Test
  void aCalverTagIsKeptEvenWhenNothingDeploysItAndItIsNotTheNewestBuild() throws Exception {
    // "Releases stay" in its docker spelling: the version tag sits BESIDE the sha tag rather than
    // replacing it, so a release that nobody runs is still a release.
    repository();
    String config = config();
    String release = image("qits-stt", config, Map.of(layer(101), 101L));
    String superseded = image("qits-stt", config, Map.of(layer(102), 102L));
    String newest = image("qits-stt", config, Map.of(layer(103), 103L));
    tag("qits-stt", "2026.801.85448", release, minutesAgo(30));
    tag("qits-stt", SHA_B, superseded, minutesAgo(20));
    tag("qits-stt", SHA_C, newest, minutesAgo(10));

    GcStrategy.Plan plan = strategyWith(pinning()).plan(census.take());

    assertEquals(OciImageGcStrategy.KEPT_RELEASE, ruleFor(plan.kept(), "qits-stt:2026.801.85448"));
    assertEquals(OciImageGcStrategy.KEPT_NEWEST, ruleFor(plan.kept(), "qits-stt:" + SHA_C));
    assertEquals(OciImageGcStrategy.DEAD_TAG, ruleFor(plan.dead(), "qits-stt:" + SHA_B));
    assertTrue(
        plan.blobsRetained().contains(release),
        "the release manifest survives on its version tag alone");
  }

  @Test
  void anActiveDeploymentPinKeepsItsShaEvenWhenAYoungerBuildExists() throws Exception {
    // The IMAGE_MISSING hazard, priced: the container was created from qits/<app>:<sha> and a
    // restart pulls that reference again. The pinned tag is deliberately not the newest one, so the
    // rule that saves it can only be the pin.
    repository();
    String config = config();
    String serving = image("qits-artifacts", config, Map.of(layer(201), 201L));
    String stale = image("qits-artifacts", config, Map.of(layer(202), 202L));
    String newest = image("qits-artifacts", config, Map.of(layer(203), 203L));
    tag("qits-artifacts", SHA_A, serving, minutesAgo(30));
    tag("qits-artifacts", SHA_B, stale, minutesAgo(20));
    tag("qits-artifacts", SHA_C, newest, minutesAgo(10));

    GcStrategy.Plan plan =
        strategyWith(pinning(row("app-1", "qits-artifacts", SHA_A, "ACTIVE"))).plan(census.take());

    assertEquals(OciImageGcStrategy.KEPT_ACTIVE, ruleFor(plan.kept(), "qits-artifacts:" + SHA_A));
    assertEquals(OciImageGcStrategy.KEPT_NEWEST, ruleFor(plan.kept(), "qits-artifacts:" + SHA_C));
    assertEquals(OciImageGcStrategy.DEAD_TAG, ruleFor(plan.dead(), "qits-artifacts:" + SHA_B));
  }

  @Test
  void theRollbackTargetIsTheNewestDifferentShaAndARedeployOfTheSameShaIsNotOne() throws Exception {
    // The ordering subtlety, which is the whole reason "previous DISTINCT" is spelled that way: a
    // redeploy writes a second row at the SAME sha. Reading that as the previous version keeps a
    // duplicate of what is already running and drops the only thing a rollback could pull.
    repository();
    String config = config();
    String serving = image("qits-cd", config, Map.of(layer(301), 301L));
    String rollback = image("qits-cd", config, Map.of(layer(302), 302L));
    String older = image("qits-cd", config, Map.of(layer(303), 303L));
    String newest = image("qits-cd", config, Map.of(layer(304), 304L));
    tag("qits-cd", SHA_A, serving, minutesAgo(40));
    tag("qits-cd", SHA_B, rollback, minutesAgo(30));
    tag("qits-cd", SHA_C, older, minutesAgo(20));
    tag("qits-cd", SHA_D, newest, minutesAgo(10));

    GcStrategy.Plan plan =
        strategyWith(
                pinning(
                    row("app-1", "qits-cd", SHA_A, "ACTIVE"),
                    row("app-1", "qits-cd", SHA_A, "DECOMMISSIONED"),
                    row("app-1", "qits-cd", SHA_B, "DECOMMISSIONED"),
                    row("app-1", "qits-cd", SHA_C, "DECOMMISSIONED")))
            .plan(census.take());

    assertEquals(OciImageGcStrategy.KEPT_ACTIVE, ruleFor(plan.kept(), "qits-cd:" + SHA_A));
    assertEquals(OciImageGcStrategy.KEPT_ROLLBACK, ruleFor(plan.kept(), "qits-cd:" + SHA_B));
    assertEquals(
        OciImageGcStrategy.DEAD_TAG,
        ruleFor(plan.dead(), "qits-cd:" + SHA_C),
        "one rollback step, not every sha a row ever named — keeping them all reclaims nothing");
    assertEquals(OciImageGcStrategy.KEPT_NEWEST, ruleFor(plan.kept(), "qits-cd:" + SHA_D));
  }

  @Test
  void anImageNoDeploymentEverNamedKeepsItsNewestBuildAndDropsTheOlderOnes() throws Exception {
    // qits-spa-home's shape, measured: an image with tags and not a single deployment row. Without
    // the newest-build rule the whole image would be eligible and the next deploy would pull a tag
    // this run had deleted.
    repository();
    String config = config();
    String first = image("qits-spa-home", config, Map.of(layer(401), 401L));
    String second = image("qits-spa-home", config, Map.of(layer(402), 402L));
    String newest = image("qits-spa-home", config, Map.of(layer(403), 403L));
    tag("qits-spa-home", SHA_A, first, minutesAgo(30));
    tag("qits-spa-home", SHA_B, second, minutesAgo(20));
    tag("qits-spa-home", SHA_C, newest, minutesAgo(10));

    GcStrategy.Plan plan = strategyWith(pinning()).plan(census.take());

    assertEquals(OciImageGcStrategy.KEPT_NEWEST, ruleFor(plan.kept(), "qits-spa-home:" + SHA_C));
    assertEquals(1, plan.kept().size());
    assertEquals(
        Stream.of(
                "qits-spa-home:" + SHA_A,
                "qits-spa-home:" + SHA_B,
                "qits-spa-home@sha256:" + first,
                "qits-spa-home@sha256:" + second)
            .sorted()
            .toList(),
        identities(plan.dead()),
        "the older tags and the manifests they were the last coordinate for");
    assertTrue(plan.blobsRetained().contains(newest));
  }

  @Test
  void aManifestNoKeptTagReachesDiesEvenThoughItWasNeverTagged() throws Exception {
    // The 73 untagged manifests, in miniature: a tag re-push moves the tag row to the new manifest
    // and leaves the old row behind, reachable from no coordinate anyone uses.
    repository();
    String config = config();
    String abandoned = image("qits-events", config, Map.of(layer(501), 501L));
    String current = image("qits-events", config, Map.of(layer(502), 502L));
    tag("qits-events", SHA_A, current, minutesAgo(10));

    GcStrategy.Plan plan = strategyWith(pinning()).plan(census.take());

    assertEquals(
        List.of("qits-events@sha256:" + abandoned),
        identities(plan.dead()),
        "only the orphan dies: the tag that moved is this image's newest build");
    assertEquals(OciImageGcStrategy.DEAD_MANIFEST, plan.dead().get(0).rule());
    assertTrue(plan.blobsReleased().contains(abandoned));
    assertTrue(plan.blobsRetained().contains(current));
  }

  @Test
  void aLayerUnderADyingAndASurvivingManifestIsInBothSetsAndSurvivesTheSweep() throws Exception {
    // The seam's central promise: a strategy reports both sets and never subtracts. Every rebuild
    // shares its base layers with the tag before it, so this is the common case, not an edge one.
    repository();
    String config = config();
    String base = layer(601);
    String doomedOnly = layer(602);
    String doomed = image("qits-workspaces", config, Map.of(base, 601L, doomedOnly, 602L));
    String kept = image("qits-workspaces", config, Map.of(base, 601L));
    tag("qits-workspaces", SHA_A, doomed, minutesAgo(20));
    tag("qits-workspaces", SHA_B, kept, minutesAgo(10));
    for (String blobId : List.of(config, base, doomedOnly, doomed, kept)) {
      backdate(blobId, Duration.ofDays(30));
    }

    OciImageGcStrategy strategy = strategyWith(pinning());
    LiveBlobCensus.Census taken = census.take();
    GcStrategy.Plan plan = strategy.plan(taken);

    assertTrue(plan.blobsReleased().contains(base), "the dying tag did name it");
    assertTrue(plan.blobsRetained().contains(base), "and the surviving one still does");
    GcPlanReport report = planner.plan(taken, List.of(strategy));
    assertEquals(
        Stream.of(doomed, doomedOnly).sorted().toList(),
        report.sweep().blobIds(),
        "the shared base layer is not unlinked — the substrate subtracted, this strategy did not");
  }

  @Test
  void qitsCdUnreachableAbortsThePlanRatherThanCondemningEveryTag() throws Exception {
    // Fail-closed, and the reason plan() is allowed to throw at all. An empty pin list would read as
    // "nothing is deployed" and condemn every sha tag on the platform.
    repository();
    String only = image("qits-projects", config(), Map.of(layer(701), 701L));
    tag("qits-projects", SHA_A, only, minutesAgo(10));

    OciImageGcStrategy strategy =
        strategyWith(
            () -> {
              throw new IllegalStateException("qits-cd unreachable at http://qits-cd:8080/cd/api");
            });
    LiveBlobCensus.Census taken = census.take();

    IllegalStateException aborted =
        assertThrows(IllegalStateException.class, () -> strategy.plan(taken));
    assertTrue(aborted.getMessage().contains("qits-cd unreachable"));
  }

  @Test
  void aRepositoryWithNoOciContentPlansNothingRatherThanFailing() throws Exception {
    // The honest answer for rules that exist and match no row: no dead identities, and the type's
    // live set handed back unchanged so the sweep keeps whatever the census found.
    repository();

    LiveBlobCensus.Census taken = census.take();
    GcStrategy.Plan plan = strategyWith(pinning()).plan(taken);

    assertEquals(List.of(), plan.dead());
    assertEquals(List.of(), plan.kept());
    assertEquals(Set.of(), plan.blobsReleased());
    assertEquals(taken.live(RepositoryType.OCI_IMAGES).keySet(), plan.blobsRetained());
  }

  @Test
  void againstTheSubstratesOwnFixtureNothingDiesAndTheRetainedSetIsTheCensusSet() throws Exception {
    // The substrate's store, unchanged: two manifests under one image, tagged v1 and v2. Neither tag
    // is a calver version or a build sha, so both are kept under the belt-and-braces rule — and the
    // set this strategy hands back is exactly the census's own OCI live set, which is what makes
    // "one census, two readers" true here rather than merely intended.
    seed();
    LiveBlobCensus.Census taken = census.take();

    OciImageGcStrategy strategy = strategyWith(pinning());
    GcStrategy.Plan plan = strategy.plan(taken);

    assertEquals(List.of(), plan.dead());
    assertEquals(List.of("alpha:v1", "alpha:v2"), identities(plan.kept()));
    assertTrue(
        plan.kept().stream()
            .allMatch(kept -> OciImageGcStrategy.KEPT_UNCLASSIFIED.equals(kept.rule())));
    assertEquals(taken.live(RepositoryType.OCI_IMAGES).keySet(), plan.blobsRetained());
    assertEquals(List.of(), planner.plan(taken, List.of(strategy)).sweep().blobIds());
  }

  // --- fixture ---------------------------------------------------------------------------------

  /** The strategy as CDI would build it, with the one collaborator a test has to stand in for. */
  private OciImageGcStrategy strategyWith(CdDeploymentPins pins) {
    OciImageGcStrategy strategy = new OciImageGcStrategy();
    strategy.repositories = repositories;
    strategy.tags = ociTags;
    strategy.manifests = ociManifests;
    strategy.footprints = footprints;
    strategy.pins = pins;
    return strategy;
  }

  private static CdDeploymentPins pinning(Deployment... rows) {
    List<Deployment> deployments = List.of(rows);
    return () -> deployments;
  }

  private static Deployment row(
      String applicationId, String application, String sha, String status) {
    return new Deployment(applicationId, application, sha, status);
  }

  private void repository() {
    repositoryService.ensure("qits", RepositoryType.OCI_IMAGES);
  }

  /** One config blob shared by every manifest in a case, the way a rebuilt image shares its base. */
  private String config() throws IOException {
    return store(filled(CONFIG, (byte) 1));
  }

  /** A distinct content blob of the given size, returned by digest. */
  private String layer(int size) throws IOException {
    return store(filled(size, (byte) (size % 251)));
  }

  /** A real image manifest, stored as bytes and given its {@code oci_manifest} row. */
  private String image(String imageName, String config, Map<String, Long> layers)
      throws IOException {
    byte[] document = imageManifest(config, layers);
    String digest = store(document);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              OciManifest row = new OciManifest();
              row.repository = "qits";
              row.imageName = imageName;
              row.digest = digest;
              row.mediaType = OciMediaTypes.OCI_MANIFEST_V1;
              row.size = document.length;
              row.createdAt = Instant.now();
              ociManifests.persist(row);
            });
    return digest;
  }

  private void tag(String imageName, String name, String digest, Instant updatedAt) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              OciTag row = new OciTag();
              row.repository = "qits";
              row.imageName = imageName;
              row.tag = name;
              row.manifestDigest = digest;
              row.updatedAt = updatedAt;
              ociTags.persist(row);
            });
  }

  private static Instant minutesAgo(int minutes) {
    return Instant.now().minus(Duration.ofMinutes(minutes));
  }

  private static List<String> identities(List<GcIdentity> identities) {
    return identities.stream().map(GcIdentity::identity).toList();
  }

  private static String ruleFor(List<GcIdentity> identities, String identity) {
    return identities.stream()
        .filter(candidate -> candidate.identity().equals(identity))
        .map(GcIdentity::rule)
        .findFirst()
        .orElseThrow(() -> new AssertionError(identity + " is in neither list: " + identities));
  }
}
