package eu.wohlben.qits.artifactory.api;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies the static-token write guard when a token is configured (the deployed posture). Writes
 * (PUT/POST) require {@code X-Artifactory-Token}; reads (serves) stay open so a blob is usable as
 * an {@code <img>} src.
 */
@QuarkusTest
@TestProfile(ArtifactoryTokenGuardTest.WithToken.class)
class ArtifactoryTokenGuardTest {

  static final String TOKEN = "s3cr3t-token";

  public static class WithToken implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.artifactory.token", TOKEN);
    }
  }

  @Test
  void writeIsRejectedWithoutTheToken() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("type", "ci-screenshots"))
        .when()
        .put("/api/artifactory/repositories/guarded")
        .then()
        .statusCode(401);
  }

  @Test
  void writeIsRejectedWithAWrongToken() {
    given()
        .header("X-Artifactory-Token", "nope")
        .contentType(ContentType.JSON)
        .body(Map.of("type", "ci-screenshots"))
        .when()
        .put("/api/artifactory/repositories/guarded")
        .then()
        .statusCode(401);
  }

  @Test
  void writeSucceedsWithTheTokenAndServeStaysOpen() {
    given()
        .header("X-Artifactory-Token", TOKEN)
        .contentType(ContentType.JSON)
        .body(Map.of("type", "ci-screenshots"))
        .when()
        .put("/api/artifactory/repositories/guarded")
        .then()
        .statusCode(200);

    String id =
        given()
            .header("X-Artifactory-Token", TOKEN)
            .contentType("image/png")
            .headers(ArtifactoryTestMedia.screenshotHeaders("main", "checkout", 100, 50))
            .body(ArtifactoryTestMedia.png(100, 50, 11))
            .when()
            .post("/api/artifactory/repositories/guarded/blobs")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    // Serve is a read — no token required.
    given()
        .when()
        .get("/api/artifactory/repositories/guarded/blobs/" + id)
        .then()
        .statusCode(200)
        .contentType("image/png");
  }

  @Test
  void uploadIsRejectedWithoutTheToken() {
    given()
        .contentType("image/png")
        .headers(ArtifactoryTestMedia.screenshotHeaders("main", "checkout", 100, 50))
        .body(ArtifactoryTestMedia.png(100, 50, 12))
        .when()
        .post("/api/artifactory/repositories/guarded/blobs")
        .then()
        .statusCode(401);
  }
}
