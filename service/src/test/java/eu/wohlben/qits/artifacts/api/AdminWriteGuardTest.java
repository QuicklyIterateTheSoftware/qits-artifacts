package eu.wohlben.qits.artifacts.api;

import static io.restassured.RestAssured.given;

import eu.wohlben.qits.MachineTokens;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The JSON admin API with the machine-token gate ON — the posture a deployment with
 * qits-platform-idp runs. Writes (PUT/POST/DELETE) need a bearer minted for qits-platform-artifacts;
 * reads stay open so a blob is usable directly as an {@code <img>} src.
 *
 * <p>The gate-OFF posture is what every other suite here runs under, so "unchanged when the gate is
 * off" is asserted by all of them at once rather than by one test.
 */
@QuarkusTest
@TestProfile(MachineTokens.Enforced.class)
class AdminWriteGuardTest {

  private static String bearer(String token) {
    return "Bearer " + token;
  }

  @Test
  void aWriteWithoutATokenIs401() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("type", "ci-screenshots"))
        .when()
        .put("/artifacts/api/repositories/guarded")
        .then()
        .statusCode(401);
  }

  @Test
  void aWriteWithATokenForAnotherServiceIsRejected() {
    // Signed by the same issuer and perfectly valid — just not addressed to us. quarkus-oidc
    // refuses it on quarkus.oidc.token.audience before MachineAuth's own audience check is even
    // reached, so this is a 401 rather than the 403 an already-authenticated caller would get.
    given()
        .header("Authorization", bearer(MachineTokens.forAnotherService()))
        .contentType(ContentType.JSON)
        .body(Map.of("type", "ci-screenshots"))
        .when()
        .put("/artifacts/api/repositories/guarded")
        .then()
        .statusCode(401);
  }

  @Test
  void aUserHeaderIsNotAMachineToken() {
    // The gateway's forward-auth identity names a person; this API is machine-only, and a browser
    // session must not reach a write just because it reached the service.
    given()
        .header("X-Qits-User", "alice")
        .contentType(ContentType.JSON)
        .body(Map.of("type", "ci-screenshots"))
        .when()
        .put("/artifacts/api/repositories/guarded")
        .then()
        .statusCode(401);
  }

  @Test
  void aWriteWithOurTokenSucceedsAndReadsStayOpen() {
    String token = bearer(MachineTokens.forThisService());

    given()
        .header("Authorization", token)
        .contentType(ContentType.JSON)
        .body(Map.of("type", "ci-screenshots"))
        .when()
        .put("/artifacts/api/repositories/guarded")
        .then()
        .statusCode(200);

    String id =
        given()
            .header("Authorization", token)
            .contentType("image/png")
            .headers(ArtifactsTestMedia.screenshotHeaders("main", "checkout", 100, 50))
            .body(ArtifactsTestMedia.png(100, 50, 11))
            .when()
            .post("/artifacts/api/repositories/guarded/blobs")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    given()
        .when()
        .get("/artifacts/api/repositories/guarded/blobs/" + id)
        .then()
        .statusCode(200)
        .contentType("image/png");
  }

  @Test
  void everyGuardedPrefixIsCoveredAndItsReadsAreNot() {
    // The prefix set is extended by hand, never inherited — a resource served outside it ships
    // unguarded. mirror-upstreams decides which public registry this service dials on a miss, so an
    // unguarded PUT here would be handing out an outbound fetch.
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("slug", "quay"))
        .when()
        .put("/artifacts/api/mirror-upstreams/quay.io")
        .then()
        .statusCode(401);

    given()
        .header("Authorization", bearer(MachineTokens.forThisService()))
        .contentType(ContentType.JSON)
        .body(Map.of("slug", "quay"))
        .when()
        .put("/artifacts/api/mirror-upstreams/quay.io")
        .then()
        .statusCode(200);

    given().when().get("/artifacts/api/mirror-upstreams").then().statusCode(200);
    given().when().get("/artifacts/api/store/summary").then().statusCode(200);
    given().when().get("/artifacts/api/gc/plan").then().statusCode(200);

    given().when().delete("/artifacts/api/mirror-upstreams/quay.io").then().statusCode(401);
    given()
        .header("Authorization", bearer(MachineTokens.forThisService()))
        .when()
        .delete("/artifacts/api/mirror-upstreams/quay.io")
        .then()
        .statusCode(204);
  }

  @Test
  void anUploadWithoutATokenIs401() {
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
