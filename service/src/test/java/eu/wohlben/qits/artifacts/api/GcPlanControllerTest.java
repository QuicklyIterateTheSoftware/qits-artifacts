package eu.wohlben.qits.artifacts.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * The GC plan on the wire, and the things it must say when there is nothing to say.
 *
 * <p>The report is all zeros here — and the whole point of this suite is that zeros arrive with
 * reasons attached: the four pin-reading types ({@code oci-images}, {@code daemon-binaries} and both
 * caches) naming the strategy that would collect them and the reason it refused, {@code
 * npm-packages} naming the strategy that ran and found nothing to condemn, a sweep that would unlink
 * nothing, and the row-less pool listed as untouchable. A report that answered {@code {}} would be
 * indistinguishable from a broken one.
 *
 * <p>{@code npm-packages} planning <b>zero</b> reclaimable bytes is a fact about the npm suite, not
 * a coincidence: every case there publishes under a uniquely generated package name and at most one
 * {@code -main.g<sha>} build per name, so no build is ever superseded. A new npm case that published
 * two builds of one package would condemn one of them and land here — which is the right place to
 * find that out.
 *
 * <p>{@code oci-images} refusing is the deployed behaviour under a broken dependency, not a test
 * artefact: its keep-set is fetched from qits-cd at plan time, this repository has no qits-cd, and
 * the suite points the base url at a closed port. Fail-closed on the wire is worth an assertion of
 * its own — a plan that answered with an empty keep-set would condemn every sha tag on the platform.
 *
 * <p>The execute route exists now — {@code POST /gc/sweep}, landed after the user reviewed the
 * dry-run — and here it never gets as far as the store: the pin sources are closed ports, so the
 * run aborts whole with nothing deleted and a reason on the receipt. Two independent things would
 * have to break for a byte to go: the pins would have to answer, and the grace window would have to
 * pass on content this suite created seconds ago. The plan path still refuses a {@code POST}:
 * reading and executing stay two different URLs.
 */
@QuarkusTest
class GcPlanControllerTest {

  @Test
  void thePlanNamesEveryTypeIncludingTheOnesNobodyCollects() {
    given()
        .when()
        .get("/artifacts/api/gc/plan")
        .then()
        .statusCode(200)
        .body("dryRun", is(true))
        .body("generatedAt", notNullValue())
        .body("graceWindow", is("P7D"))
        .body("types", hasSize(8))
        .body(
            "types.type",
            org.hamcrest.Matchers.containsInAnyOrder(
                "ci-screenshots",
                "ci-videos",
                "oci-images",
                "npm-packages",
                "npm-proxy",
                "oci-mirror",
                "maven-packages",
                "daemon-binaries"))
        .body("types.find { it.type == 'oci-images' }.strategy", is("OciImageGcStrategy"))
        .body("types.find { it.type == 'npm-packages' }.strategy", is("NpmPackagesGcStrategy"))
        .body("types.find { it.type == 'oci-mirror' }.strategy", is("OciMirrorGcStrategy"))
        .body(
            "types.find { it.type == 'ci-screenshots' }.strategy", is("CiScreenshotsGcStrategy"))
        .body("types.find { it.type == 'ci-videos' }.strategy", is("CiVideosGcStrategy"))
        .body(
            "types.find { it.type == 'maven-packages' }.strategy", is("MavenPackagesGcStrategy"))
        .body("types.find { it.type == 'maven-packages' }.dead", hasSize(0))
        .body("types.find { it.type == 'maven-packages' }.note",
            org.hamcrest.Matchers.containsString("snapshot"))
        .body("types.find { it.type == 'npm-proxy' }.strategy", is("NpmProxyGcStrategy"))
        // daemon-binaries is claimed now, and it refuses here for the reason it was unclaimed
        // before: its keep-set is qits-ci's ladder, this suite has no qits-ci, and the one blob
        // class a running service EXECUTES must never be planned against "nothing is pinned".
        .body(
            "types.find { it.type == 'daemon-binaries' }.strategy",
            is("DaemonBinariesGcStrategy"))
        .body(
            "types.find { it.type == 'daemon-binaries' }.error",
            org.hamcrest.Matchers.containsString("live pins unavailable"))
        .body("types.find { it.type == 'daemon-binaries' }.dead", hasSize(0))
        .body("types.reclaimableBytes", everyItem(is(0)));
  }

  @Test
  void thePlanEchoesTheConfiguredPolicyAndSaysWhetherASweepCouldExecuteIt() {
    // Two halves a reader needs before the outcomes mean anything: what this deployment told the
    // collector to do (the settlement's mapping, echoed with its window and its rule as a sentence),
    // and whether a sweep run now could execute the plan at all. It could not here — the pin sources
    // are closed ports — so the zeros beside oci-images are a refusal, not a finding.
    given()
        .when()
        .get("/artifacts/api/gc/plan")
        .then()
        .statusCode(200)
        .body("configuration", hasSize(8))
        .body("configuration.find { it.type == 'oci-mirror' }.strategy", is("cache"))
        .body("configuration.find { it.type == 'oci-mirror' }.window", is("P30D"))
        .body("configuration.find { it.type == 'maven-packages' }.strategy", is("own"))
        .body("configuration.find { it.type == 'maven-packages' }.window", is("P90D"))
        .body(
            "configuration.find { it.type == 'maven-packages' }.rule",
            org.hamcrest.Matchers.containsString("last 2 released versions"))
        .body("configuration.find { it.type == 'ci-videos' }.strategy", is("excluded"))
        .body("configuration.find { it.type == 'ci-videos' }.window", nullValue())
        .body("executable", is(false))
        .body("pinFailures", hasSize(2))
        .body(
            "types.find { it.type == 'oci-images' }.error",
            org.hamcrest.Matchers.containsString("live pins unavailable"));
  }

  @Test
  void theStubsClaimTheirTypesAndSayEitherTheirRuleOrTheirRefusal() {
    // The claimed set is complete: both CI types are claimed by stubs. Their wire state depends
    // on suite order — BlobControllerTest uploads real screenshot records into this shared process —
    // so both honest answers are accepted and pinned: at zero rows, a note naming the intended rule
    // (branch-scoped for screenshots, byte-budgeted for videos) and that the loop has never
    // produced content; with rows, the fail-closed refusal that names the rule to implement. What
    // is never acceptable is a plan: dead stays empty in both states.
    io.restassured.path.json.JsonPath json =
        given().when().get("/artifacts/api/gc/plan").then().statusCode(200).extract().jsonPath();
    assertStubState(json, "ci-screenshots", "branch-scoped", "branch", "dead");
    assertStubState(json, "ci-videos", "byte-budgeted", "byte", "dead");
  }

  private static void assertStubState(
      io.restassured.path.json.JsonPath json,
      String type,
      String ruleWord,
      String refusalWord,
      String deadKey) {
    java.util.Map<String, Object> plan = json.getMap("types.find { it.type == '" + type + "' }");
    org.junit.jupiter.api.Assertions.assertEquals(
        java.util.List.of(), plan.get(deadKey), type + " must never plan a deletion as a stub");
    Object error = plan.get("error");
    if (error == null) {
      String note = (String) plan.get("note");
      org.junit.jupiter.api.Assertions.assertNotNull(note, type + " at zero rows carries its note");
      org.junit.jupiter.api.Assertions.assertTrue(note.contains(ruleWord), note);
      org.junit.jupiter.api.Assertions.assertTrue(note.contains("never produced"), note);
    } else {
      String refused = (String) error;
      org.junit.jupiter.api.Assertions.assertTrue(refused.contains("stub"), refused);
      org.junit.jupiter.api.Assertions.assertTrue(
          refused.contains(refusalWord), "the refusal names the rule to implement: " + refused);
    }
  }

  @Test
  void anUnreachableQitsCdAbortsTheOciPlanRatherThanCondemningEveryTag() {
    given()
        .when()
        .get("/artifacts/api/gc/plan")
        .then()
        .statusCode(200)
        .body(
            "types.find { it.type == 'oci-images' }.error",
            org.hamcrest.Matchers.containsString("qits-cd"))
        .body("types.find { it.type == 'oci-images' }.dead", hasSize(0))
        .body("types.find { it.type == 'oci-images' }.kept", hasSize(0))
        .body("types.find { it.type == 'oci-images' }.blobsSweepable", is(0));
  }

  @Test
  void npmPackagesPlansForItselfAndTheProxyIsCollectedByItsOwnEngine() {
    // The scope, on the wire. npm-proxy shares the npm_version table with the hosted registry, and
    // the two are now collected by different ENGINES over that one table: the hosted rows by the
    // own-artifacts rule, the cached ones by eviction. npm-proxy used to be unclaimed here — "no
    // strategy registered", the honest report of a decision nobody had taken. The settlement took
    // it, so the line is a strategy's now.
    given()
        .when()
        .get("/artifacts/api/gc/plan")
        .then()
        .statusCode(200)
        .body("types.find { it.type == 'npm-packages' }.error", nullValue())
        .body("types.find { it.type == 'npm-packages' }.note", nullValue())
        .body("types.find { it.type == 'npm-packages' }.dead", hasSize(0))
        .body("types.find { it.type == 'npm-proxy' }.strategy", is("NpmProxyGcStrategy"));
  }

  @Test
  void bothCacheTypesReadPinsSoBothRefuseWhileTheSourcesAreClosedPorts() {
    // The mirror used to answer "nothing dies, append-only pending access tracking" here. Access
    // tracking shipped, the settlement configured both caches onto the eviction engine, and the
    // engine checks live pins before the access rule — so with no qits-cd and no qits-ci both types
    // now refuse rather than plan against "nothing is pinned". Zeros with a reason, which is the
    // one property every line of this report has to keep.
    given()
        .when()
        .get("/artifacts/api/gc/plan")
        .then()
        .statusCode(200)
        .body("types.find { it.type == 'oci-mirror' }.strategy", is("OciMirrorGcStrategy"))
        .body(
            "types.find { it.type == 'oci-mirror' }.error",
            org.hamcrest.Matchers.containsString("live pins unavailable"))
        .body("types.find { it.type == 'oci-mirror' }.dead", hasSize(0))
        .body("types.find { it.type == 'oci-mirror' }.blobsSweepable", is(0))
        .body(
            "types.find { it.type == 'npm-proxy' }.error",
            org.hamcrest.Matchers.containsString("live pins unavailable"))
        .body("types.find { it.type == 'npm-proxy' }.dead", hasSize(0))
        .body("types.find { it.type == 'npm-proxy' }.blobsSweepable", is(0));
  }

  @Test
  void theSweepWouldUnlinkNothingAndTheRowLessPoolIsReportedAsUntouchable() {
    given()
        .when()
        .get("/artifacts/api/gc/plan")
        .then()
        .statusCode(200)
        .body("sweep.blobCount", is(0))
        .body("sweep.reclaimableBytes", is(0))
        .body("sweep.blobIds", hasSize(0))
        .body("untouchable.reason", notNullValue())
        .body("untouchable.blobCount", greaterThanOrEqualTo(0))
        .body("untouchable.bytes", greaterThanOrEqualTo(0));
  }

  @Test
  void theSweepRefusesTheWholeRunWhileThePinSourcesCannotAnswer() {
    // The settlement's abort rule on the wire. This suite has neither qits-cd nor qits-ci, so the
    // run stops before the census: nothing is deleted, every type carries the same reason, and the
    // row-less pool is reported as UNCOMPUTED rather than empty — an empty list there would be a
    // claim about a store this run never read.
    given()
        .when()
        .post("/artifacts/api/gc/sweep")
        .then()
        .statusCode(200)
        .body("dryRun", is(false))
        .body("executedAt", notNullValue())
        .body("aborted", org.hamcrest.Matchers.containsString("qits-cd"))
        .body("aborted", org.hamcrest.Matchers.containsString("qits-ci"))
        .body("types", hasSize(8))
        .body("types.deleted.flatten()", hasSize(0))
        .body("sweep.blobsUnlinked", is(0))
        .body("sweep.bytesReclaimed", is(0))
        .body("sweep.unlinkedBlobIds", hasSize(0))
        .body("untouchable.reason", org.hamcrest.Matchers.containsString("not computed"));
  }

  @Test
  void readingAndExecutingStayTwoDifferentUrls() {
    // A POST to the plan path must not quietly find some other resource, and a GET must never
    // sweep: the reviewed-report-then-invoke order is carried by the verbs.
    given().when().post("/artifacts/api/gc/plan").then().statusCode(405);
    given().when().get("/artifacts/api/gc/sweep").then().statusCode(405);
  }
}
