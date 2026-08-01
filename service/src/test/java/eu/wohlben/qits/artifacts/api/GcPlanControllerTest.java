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
 * reasons attached: four types naming that nobody collects them, {@code oci-images} naming the
 * strategy that does and the reason it refused, a sweep that would unlink nothing, and the row-less
 * pool listed as untouchable. A report that answered {@code {}} would be indistinguishable from a
 * broken one.
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
        .body("types", hasSize(5))
        .body(
            "types.type",
            org.hamcrest.Matchers.containsInAnyOrder(
                "ci-screenshots", "ci-videos", "oci-images", "npm-packages", "npm-proxy"))
        .body(
            "types.find { it.type == 'oci-images' }.strategy", is("OciImageGcStrategy"))
        .body(
            "types.findAll { it.type != 'oci-images' }.note",
            everyItem(org.hamcrest.Matchers.startsWith("no strategy registered")))
        .body("types.findAll { it.type != 'oci-images' }.strategy", everyItem(nullValue()))
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
