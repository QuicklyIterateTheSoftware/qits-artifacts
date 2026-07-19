package eu.wohlben.qits.artifacts.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
class RepositoryControllerTest {

  @Test
  void ensureIsIdempotentAndListed() {
    // First ensure creates it.
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("type", "ci-screenshots"))
        .when()
        .put("/api/artifacts/repositories/ci-screenshots")
        .then()
        .statusCode(200)
        .body("repository.name", is("ci-screenshots"))
        .body("repository.type", is("ci-screenshots"));

    // Re-ensuring is a no-op success (idempotent).
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("type", "ci-screenshots"))
        .when()
        .put("/api/artifacts/repositories/ci-screenshots")
        .then()
        .statusCode(200);

    given()
        .when()
        .get("/api/artifacts/repositories")
        .then()
        .statusCode(200)
        .body("repositories.name", hasItem("ci-screenshots"));
  }

  @Test
  void ensureRejectsATypeChange() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("type", "ci-videos"))
        .when()
        .put("/api/artifacts/repositories/clip-repo")
        .then()
        .statusCode(200);
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("type", "ci-screenshots"))
        .when()
        .put("/api/artifacts/repositories/clip-repo")
        .then()
        .statusCode(400);
  }
}
