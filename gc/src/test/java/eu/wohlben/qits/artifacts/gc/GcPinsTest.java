package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.entity.NpmVersion;
import eu.wohlben.qits.artifacts.control.CiVideosProfile;
import eu.wohlben.qits.artifacts.control.NpmPackagesProfile;
import eu.wohlben.qits.artifacts.control.OciImagesProfile;
import eu.wohlben.qits.artifacts.entity.RepositoryTypeProfile;
import eu.wohlben.qits.artifacts.gc.dto.GcPinSource;
import eu.wohlben.qits.artifacts.gc.dto.GcPlanReport;
import eu.wohlben.qits.artifacts.gc.dto.GcSweepReport;
import eu.wohlben.qits.artifacts.gc.dto.GcTypePlan;
import eu.wohlben.qits.artifacts.gc.dto.GcTypeSweepResult;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What a run does when a pin source cannot answer: the sweep refuses whole, the dry-run degrades.
 *
 * <p>This suite runs under the shipped test configuration, which points both pin urls at a closed
 * port — so every case here is the deployed failure rather than a simulated one. Neither qits-platform-deployments nor
 * qits-ci exists in this repository, and that is what makes the refusal path the easy one to
 * exercise honestly.
 *
 * <p><b>The settlement's rule is all-or-nothing, and the second case is why.</b> It would be
 * tempting to let the types that read no pins run anyway — npm's rule needs nothing from qits-platform-deployments.
 * But blobs dedupe globally: a tarball npm releases may be the last reference to bytes an image a
 * deployment pins also names, and with the pins unavailable nothing can tell. So the run stops
 * before the census, and the receipt says why.
 */
@QuarkusTest
class GcPinsTest extends GcFixture {

  /** The seven types this deployment registers — see {@code GcPlanTest} for the arithmetic. */
  private static final int REGISTERED_TYPES = 7;

  @Inject GcPlanner planner;
  @Inject GcSweepExecutor executor;
  @Inject GcPinSources pinSources;

  private static final String DIGEST =
      "3ff84c05e2a01c3f2b9d1e8a7c6b5d4e3f2a1b0c9d8e7f6a5b4c3d2e1f0a9b8c";
  private static final String PKG = "@qits/pins-case";
  private static final String RELEASE = "2026.801.85149";
  private static final String SUPERSEDED = RELEASE + "-main.g1111111";
  private static final String NEWEST = RELEASE + "-main.g2222222";

  @Test
  void bothPinSourcesFailingIsReportedRatherThanThrown() {
    GcPins pins = pinSources.fetch();

    assertFalse(pins.complete());
    assertEquals(2, pins.failures().size(), "both sources are asked, so both outages are named");
    assertTrue(pins.whyIncomplete().contains("qits-platform-deployments"));
    assertTrue(pins.whyIncomplete().contains("qits-ci"));

    // And the provenance says which url produced which outage, so a fix starts from the report
    // rather than from a grep for the config key.
    assertEquals(2, pins.sources().size());
    GcPinSource cd = source(pins, "qits-platform-deployments");
    assertFalse(cd.answered());
    assertTrue(cd.url().endsWith("/platform-deployments/api/pins"), cd.url());
    assertTrue(cd.outcome().contains("qits-platform-deployments"), cd.outcome());
    assertEquals(0, cd.pinCount());
    assertEquals(List.of(), cd.keeps());
    assertTrue(source(pins, "qits-ci").url().endsWith("/ci/api/daemon"));
  }

  @Test
  void aSourceThatAnsweredReportsItsUrlItsCountsAndTheKeepsItProduced() {
    // The pins section a healthy run carries, which is the half of a keep-set a reviewer can check
    // against their own deployments: not "3 pins" but which image at which sha, and — for the
    // daemon — the blob a digest rung protects beside the row it names. Built by hand because
    // neither qits-platform-deployments nor qits-ci exists in this repository.
    GcPinSources sources = new GcPinSources();
    sources.cd =
        () ->
            List.of(
                new CdDeploymentPins.ApplicationPin("qits-ci", List.of("aaaa", "bbbb")),
                new CdDeploymentPins.ApplicationPin("qits-platform-deployments", List.of("cccc")));
    sources.ci = () -> new CiDaemonPins.DaemonPin("qits-ci-daemon", DIGEST, "2026.802.40", "adopted");

    GcPins pins = sources.fetch();

    assertTrue(pins.complete());
    GcPinSource cd = source(pins, "qits-platform-deployments");
    assertTrue(cd.answered());
    assertEquals(2, cd.pinCount(), "applications, which is what the deployer answers with");
    // Sorted: the keeps are a TreeSet, so this is name order and not the order the pins arrived in.
    assertEquals(List.of("qits-ci:aaaa", "qits-ci:bbbb", "qits-platform-deployments:cccc"), cd.keeps());
    assertTrue(cd.outcome().contains("3 image shas"), cd.outcome());
    assertTrue(cd.tookMillis() >= 0);

    GcPinSource ci = source(pins, "qits-ci");
    assertEquals(2, ci.pinCount(), "both rungs of the ladder pin");
    assertEquals(
        List.of("blob " + DIGEST, "qits-ci-daemon@2026.802.40", "qits-ci-daemon@" + DIGEST),
        ci.keeps(),
        "a 64-hex rung pins the bytes as well as the row, and the section shows both");
    assertTrue(ci.outcome().contains("adopted"), ci.outcome());
  }

  @Test
  void aBlankDaemonVersionIsReportedAsAnAnswerRatherThanAsAnOutage() {
    // The shipped default on a platform that has published no daemon. It must read as a source that
    // answered with nothing pinned, because a reviewer who reads it as an outage goes looking for a
    // broken qits-ci that is working perfectly.
    GcPinSources sources = new GcPinSources();
    sources.cd = List::of;
    sources.ci = () -> new CiDaemonPins.DaemonPin("", "", "", "none");

    GcPins pins = sources.fetch();

    assertTrue(pins.complete());
    GcPinSource ci = source(pins, "qits-ci");
    assertTrue(ci.answered());
    assertEquals(0, ci.pinCount());
    assertEquals(List.of(), ci.keeps());
    assertTrue(ci.outcome().contains("an answer, not an absence"), ci.outcome());
  }

  private static GcPinSource source(GcPins pins, String name) {
    return pins.sources().stream()
        .filter(source -> source.source().equals(name))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void aSweepWithUnreadablePinsAbortsTheWholeRunAndDeletesNothing() throws Exception {
    // The content below is exactly what a healthy run deletes — a superseded main build whose
    // tarball has aged past the grace window — and npm's rule needs no pin to condemn it. It
    // survives anyway, which is the settlement's abort rule doing the only thing it is for.
    repositoryService.ensure("npm", NpmPackagesProfile.KEY);
    String supersededBlob = agedBlob(62);
    versionRow(PKG, RELEASE, agedBlob(61));
    versionRow(PKG, SUPERSEDED, supersededBlob);
    versionRow(PKG, NEWEST, agedBlob(63));

    GcSweepReport report = executor.sweep();

    assertNotNull(report.aborted(), "a receipt of a run that never started still says why");
    assertTrue(report.aborted().contains("qits-platform-deployments"));
    assertTrue(report.aborted().contains("qits-ci"));
    assertFalse(report.dryRun(), "it is still the execute surface's receipt");
    assertEquals(REGISTERED_TYPES, report.types().size());
    for (GcTypeSweepResult type : report.types()) {
      assertEquals(report.aborted(), type.error(), type.type());
      assertEquals(0, type.deleted().size());
    }
    assertEquals(0, report.sweep().blobsUnlinked());
    assertEquals(0L, report.sweep().bytesReclaimed());
    assertTrue(
        report.untouchable().reason().contains("not computed"),
        "no census was taken, so claiming an empty row-less pool would be claiming something");
    assertEquals(
        2,
        report.pins().size(),
        "a receipt of a run that never started still shows what it tried to read");
    assertFalse(report.pins().get(0).answered());

    npmVersions.getEntityManager().clear();
    assertTrue(
        npmVersions.findOne("npm", PKG, SUPERSEDED).isPresent(),
        "the row a healthy run would have collected is untouched");
    assertTrue(blobStore.exists(supersededBlob), "and so is its tarball");
  }

  @Test
  void theDryRunStillAnswersButMarksItselfNonExecutable() throws Exception {
    // A report that 500s tells a reviewer nothing about the types that are fine, so the plan is
    // still computed. What it must not do is read like a finding: executable says a sweep would
    // refuse, the failures are named, and every pin-dependent type carries the refusal instead of
    // zeros nobody can interpret.
    seed();

    GcPlanReport report = planner.plan();

    assertFalse(report.executable());
    assertEquals(2, report.pinFailures().size());
    assertEquals(2, report.pins().size(), "and the same two sources are named in the pins section");
    GcTypePlan oci = typePlan(report, OciImagesProfile.KEY);
    assertNotNull(oci.error());
    assertTrue(oci.error().contains("live pins unavailable"));
    assertEquals(0, oci.dead().size());
    assertEquals(0, oci.kept().size(), "not planned at all, rather than planned against no pins");

    // Every type on an engine reads pins now, so the useful half of a broken run is the CI stubs:
    // they carry their caption rather than an error, which is what keeps the report readable when
    // half of it is a refusal.
    GcTypePlan npm = typePlan(report, NpmPackagesProfile.KEY);
    assertNotNull(npm.error(), "the own engine reads pins too");
    assertEquals(0, npm.dead().size());
    GcTypePlan videos = typePlan(report, CiVideosProfile.KEY);
    assertNull(videos.error(), "a type that reads no pins is still planned");
    assertNotNull(videos.note());
    assertEquals(0, report.sweep().blobCount());
  }

  @Test
  void aCompleteAggregateMakesThePlanExecutableAgain() throws Exception {
    // The other half of the flag, so it is a fact about the pins rather than a constant: with an
    // aggregate that answered, the report is executable and oci-images is planned like any type.
    seed();

    GcPlanReport report = planner.plan(census.take(), planner.registered(), GcPins.none());

    assertTrue(report.executable());
    assertEquals(0, report.pinFailures().size());
    assertNull(typePlan(report, OciImagesProfile.KEY).error());
  }

  private static GcTypePlan typePlan(GcPlanReport report, String type) {
    return report.types().stream()
        .filter(plan -> RepositoryTypeProfile.wireNameOf(type).equals(plan.type()))
        .findFirst()
        .orElseThrow();
  }

  private String agedBlob(int size) throws IOException {
    String blobId = store(filled(size, (byte) (size % 251)));
    backdate(blobId, Duration.ofDays(30));
    return blobId;
  }

  private void versionRow(String packageName, String version, String blobId) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              NpmVersion row = new NpmVersion();
              row.repository = "npm";
              row.packageName = packageName;
              row.version = version;
              row.tarballBlobId = blobId;
              row.manifestJson = "{}";
              row.createdAt = Instant.now();
              npmVersions.persist(row);
            });
  }
}
