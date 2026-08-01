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
 * reasons attached: three types naming that nobody collects them, {@code oci-images} naming the
 * strategy that does and the reason it refused, {@code npm-packages} naming the strategy that ran
 * and found nothing to condemn, a sweep that would unlink nothing, and the row-less pool listed as
 * untouchable. A report that answered {@code {}} would be indistinguishable from a broken one.
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
 * <p>There is no execute route to test, deliberately, and the missing method is asserted: a {@code
 * POST} to the plan path must not quietly find some other resource.
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
        .body("types", hasSize(6))
        .body(
            "types.type",
            org.hamcrest.Matchers.containsInAnyOrder(
                "ci-screenshots",
                "ci-videos",
                "oci-images",
                "npm-packages",
                "npm-proxy",
                "oci-mirror"))
        .body("types.find { it.type == 'oci-images' }.strategy", is("OciImageGcStrategy"))
        .body("types.find { it.type == 'npm-packages' }.strategy", is("NpmPackagesGcStrategy"))
        .body("types.find { it.type == 'oci-mirror' }.strategy", is("OciMirrorGcStrategy"))
        .body(
            "types.findAll { !(it.type in ['oci-images', 'npm-packages', 'oci-mirror']) }.note",
            everyItem(org.hamcrest.Matchers.startsWith("no strategy registered")))
        .body(
            "types.findAll { !(it.type in ['oci-images', 'npm-packages', 'oci-mirror']) }.strategy",
            everyItem(nullValue()))
        .body("types.reclaimableBytes", everyItem(is(0)));
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
  void npmPackagesPlansForItselfAndLeavesTheProxyToItsOwnDecision() {
    // The scope, on the wire. npm-proxy shares the npm_version table with the hosted registry and is
    // deliberately unclaimed — its content is a cache, so its policy is eviction and the plan parks
    // it. "No strategy registered" is the honest report of a decision nobody has taken yet, and a
    // strategy quietly claiming the type to answer "nothing to do" would erase it.
    given()
        .when()
        .get("/artifacts/api/gc/plan")
        .then()
        .statusCode(200)
        .body("types.find { it.type == 'npm-packages' }.error", nullValue())
        .body("types.find { it.type == 'npm-packages' }.note", nullValue())
        .body("types.find { it.type == 'npm-packages' }.dead", hasSize(0))
        .body("types.find { it.type == 'npm-proxy' }.strategy", nullValue())
        .body(
            "types.find { it.type == 'npm-proxy' }.note",
            is("no strategy registered for npm-proxy"));
  }

  @Test
  void theMirrorClaimsItsTypeToSayItKeepsEverythingOnPurpose() {
    // The contrast with npm-proxy one line above is the whole point of both. "No strategy
    // registered" is a decision nobody has taken; a strategy reporting nothing dead is a decision
    // that was taken and can be argued with — append-only until access tracking exists (⚖2). A
    // report cannot distinguish the two unless the second one claims its type.
    given()
        .when()
        .get("/artifacts/api/gc/plan")
        .then()
        .statusCode(200)
        .body("types.find { it.type == 'oci-mirror' }.note", nullValue())
        .body("types.find { it.type == 'oci-mirror' }.error", nullValue())
        .body("types.find { it.type == 'oci-mirror' }.dead", hasSize(0))
        .body("types.find { it.type == 'oci-mirror' }.blobsSweepable", is(0));
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
  void thereIsNoExecuteSurface() {
    // Not "not yet wired" — the absence is the design. Nothing about this feature may become a URL
    // that deletes, and a route added under this path would be caught here first.
    given().when().post("/artifacts/api/gc/plan").then().statusCode(405);
    given().when().post("/artifacts/api/gc/sweep").then().statusCode(404);
  }
}
