package eu.wohlben.qits.artifacts.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class BlobControllerTest {

  @BeforeEach
  void ensureRepo() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("type", "ci-screenshots"))
        .when()
        .put("/api/artifacts/repositories/ci-screenshots")
        .then()
        .statusCode(200);
  }

  private io.restassured.specification.RequestSpecification upload(
      String branch, String flow, byte[] png) {
    return given()
        .contentType("image/png")
        .headers(ArtifactsTestMedia.screenshotHeaders(branch, flow, 100, 50))
        .body(png);
  }

  @Test
  void acceptsUploadsLargerThanThe10mDefaultWireLimit() {
    // The service raises quarkus.http.limits.max-body-size (10M default) to accommodate media
    // uploads; an 11 MB blob must therefore be accepted (a plain default would 413 it at the HTTP
    // layer). Regression guard for the sizing (docs/issues on the max-body-size tradeoff).
    byte[] big = Arrays.copyOf(ArtifactsTestMedia.png(100, 50, 99), 11 * 1024 * 1024);
    upload("main", "big", big)
        .when()
        .post("/api/artifacts/repositories/ci-screenshots/blobs")
        .then()
        .statusCode(201);
  }

  @Test
  void uploadServeAndQueryRoundTrip() {
    byte[] png = ArtifactsTestMedia.png(100, 50, 1);
    String id =
        upload("main", "checkout", png)
            .when()
            .post("/api/artifacts/repositories/ci-screenshots/blobs")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    // Serve: exact bytes, stored mediatype, immutable cache header.
    byte[] served =
        given()
            .when()
            .get("/api/artifacts/repositories/ci-screenshots/blobs/" + id)
            .then()
            .statusCode(200)
            .contentType("image/png")
            .header("Cache-Control", containsString("immutable"))
            .extract()
            .asByteArray();
    assertArrayEquals(png, served);

    // Query by branch, latest collapse.
    given()
        .queryParam("meta.git.branch.name", "main")
        .queryParam("latest", "true")
        .when()
        .get("/api/artifacts/repositories/ci-screenshots/blobs")
        .then()
        .statusCode(200)
        .body("records.id", hasItem(id))
        .body("records.mediatype", hasItem("image/png"));
  }

  @Test
  void sniffOverridesALyingContentTypeHeader() {
    byte[] png = ArtifactsTestMedia.png(100, 50, 2);
    String id =
        given()
            .contentType("image/jpeg") // lie — bytes are PNG
            .headers(ArtifactsTestMedia.screenshotHeaders("main", "checkout", 100, 50))
            .body(png)
            .when()
            .post("/api/artifacts/repositories/ci-screenshots/blobs")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    given()
        .when()
        .get("/api/artifacts/repositories/ci-screenshots/blobs/" + id)
        .then()
        .statusCode(200)
        .contentType("image/png");
  }

  @Test
  void missingRequiredMetadataKeyIs400() {
    var headers = ArtifactsTestMedia.screenshotHeaders("main", "checkout", 100, 50);
    headers.remove("X-Artifacts-Meta-qits.diff.hash");
    given()
        .contentType("image/png")
        .headers(headers)
        .body(ArtifactsTestMedia.png(100, 50, 3))
        .when()
        .post("/api/artifacts/repositories/ci-screenshots/blobs")
        .then()
        .statusCode(400);
  }

  @Test
  void uploadToUnknownRepositoryIs404() {
    upload("main", "checkout", ArtifactsTestMedia.png(100, 50, 4))
        .when()
        .post("/api/artifacts/repositories/nope/blobs")
        .then()
        .statusCode(404);
  }

  @Test
  void serveUnknownBlobIs404() {
    given()
        .when()
        .get(
            "/api/artifacts/repositories/ci-screenshots/blobs/"
                + "0000000000000000000000000000000000000000000000000000000000000000")
        .then()
        .statusCode(404);
  }

  @Test
  void serveMalformedIdIs404NotATraversal() {
    given()
        .when()
        .get("/api/artifacts/repositories/ci-screenshots/blobs/not-a-sha")
        .then()
        .statusCode(404);
  }
}
