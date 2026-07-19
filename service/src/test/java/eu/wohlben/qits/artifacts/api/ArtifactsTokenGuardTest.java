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
        .put("/api/artifacts/repositories/guarded")
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
        .put("/api/artifacts/repositories/guarded")
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
        .put("/api/artifacts/repositories/guarded")
        .then()
        .statusCode(200);

    String id =
        given()
            .header("X-Artifacts-Token", TOKEN)
            .contentType("image/png")
            .headers(ArtifactsTestMedia.screenshotHeaders("main", "checkout", 100, 50))
            .body(ArtifactsTestMedia.png(100, 50, 11))
            .when()
            .post("/api/artifacts/repositories/guarded/blobs")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    // Serve is a read — no token required.
    given()
        .when()
        .get("/api/artifacts/repositories/guarded/blobs/" + id)
        .then()
        .statusCode(200)
        .contentType("image/png");
  }

  @Test
  void uploadIsRejectedWithoutTheToken() {
    given()
        .contentType("image/png")
        .headers(ArtifactsTestMedia.screenshotHeaders("main", "checkout", 100, 50))
        .body(ArtifactsTestMedia.png(100, 50, 12))
        .when()
        .post("/api/artifacts/repositories/guarded/blobs")
        .then()
        .statusCode(401);
  }
}
