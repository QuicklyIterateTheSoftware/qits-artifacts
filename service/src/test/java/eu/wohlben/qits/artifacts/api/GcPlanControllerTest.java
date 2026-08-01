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
 * The GC plan on the wire, and the two things it must say when there is nothing to say.
 *
 * <p>This service ships no strategies. The report is therefore all zeros — and the whole point of
 * this suite is that zeros arrive with reasons attached: five types each naming that nobody collects
 * them, a sweep that would unlink nothing, and the row-less pool listed as untouchable. A report
 * that answered {@code {}} would be indistinguishable from a broken one.
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
        .body("types.strategy", everyItem(nullValue()))
        .body("types.note", everyItem(org.hamcrest.Matchers.startsWith("no strategy registered")))
        .body("types.error", everyItem(nullValue()))
        .body("types.reclaimableBytes", everyItem(is(0)));
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
