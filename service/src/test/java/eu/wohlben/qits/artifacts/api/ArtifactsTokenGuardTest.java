package eu.wohlben.qits.artifacts.api;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies the static-token write guard when a token is configured (the deployed posture). Writes
 * (PUT/POST) require {@code X-Artifacts-Token}; reads (serves) stay open so a blob is usable as an
 * {@code <img>} src.
 */
@QuarkusTest
@TestProfile(ArtifactsTokenGuardTest.WithToken.class)
class ArtifactsTokenGuardTest {

  static final String TOKEN = "s3cr3t-token";

  public static class WithToken implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.artifacts.token", TOKEN);
    }
  }

  @Test
  void writeIsRejectedWithoutTheToken() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("type", "ci-screenshots"))
        .when()
        .put("/artifacts/api/repositories/guarded")
        .then()
        .statusCode(401);
  }

  @Test
  void writeIsRejectedWithAWrongToken() {
    given()
        .header("X-Artifacts-Token", "nope")
        .contentType(ContentType.JSON)
        .body(Map.of("type", "ci-screenshots"))
        .when()
        .put("/artifacts/api/repositories/guarded")
        .then()
        .statusCode(401);
  }

  @Test
  void writeSucceedsWithTheTokenAndServeStaysOpen() {
    given()
        .header("X-Artifacts-Token", TOKEN)
        .contentType(ContentType.JSON)
        .body(Map.of("type", "ci-screenshots"))
        .when()
        .put("/artifacts/api/repositories/guarded")
        .then()
        .statusCode(200);

    String id =
        given()
            .header("X-Artifacts-Token", TOKEN)
            .contentType("image/png")
            .headers(ArtifactsTestMedia.screenshotHeaders("main", "checkout", 100, 50))
            .body(ArtifactsTestMedia.png(100, 50, 11))
            .when()
            .post("/artifacts/api/repositories/guarded/blobs")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    // Serve is a read — no token required.
    given()
        .when()
        .get("/artifacts/api/repositories/guarded/blobs/" + id)
        .then()
        .statusCode(200)
        .contentType("image/png");
  }

  @Test
  void registeringAMirrorUpstreamIsGuardedAndListingThemIsNot() {
    // The prefix set is extended by hand, never inherited — a resource served outside it ships
    // unguarded. This route decides which public registry the service dials on a miss, so an
    // unguarded PUT here would be handing out an outbound fetch; the read stays open like every
    // other read.
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("slug", "quay"))
        .when()
        .put("/artifacts/api/mirror-upstreams/quay.io")
        .then()
        .statusCode(401);

    given()
        .header("X-Artifacts-Token", TOKEN)
        .contentType(ContentType.JSON)
        .body(Map.of("slug", "quay"))
        .when()
        .put("/artifacts/api/mirror-upstreams/quay.io")
        .then()
        .statusCode(200);

    given().when().get("/artifacts/api/mirror-upstreams").then().statusCode(200);

    given().when().delete("/artifacts/api/mirror-upstreams/quay.io").then().statusCode(401);
    given()
        .header("X-Artifacts-Token", TOKEN)
        .when()
        .delete("/artifacts/api/mirror-upstreams/quay.io")
        .then()
        .statusCode(204);
  }

  @Test
  void uploadIsRejectedWithoutTheToken() {
    given()
        .contentType("image/png")
        .headers(ArtifactsTestMedia.screenshotHeaders("main", "checkout", 100, 50))
        .body(ArtifactsTestMedia.png(100, 50, 12))
        .when()
        .post("/artifacts/api/repositories/guarded/blobs")
        .then()
        .statusCode(401);
  }
}
