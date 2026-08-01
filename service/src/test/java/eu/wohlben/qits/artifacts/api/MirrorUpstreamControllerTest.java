package eu.wohlben.qits.artifacts.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The upstream CRUD on the wire — the shape workstream CA's management panel and workstream BX's
 * miss path both build against.
 *
 * <p>Paths are spelled absolutely, like every suite here: this resource lives under {@code
 * quarkus.rest.path}, and the {@code mirror-upstreams} segment is also the literal
 * {@code AdminWriteGuard} matches on, so the two have to agree and nothing else would notice if
 * they drifted.
 */
@QuarkusTest
class MirrorUpstreamControllerTest {

  @Test
  void registeringAnUpstreamIsIdempotentAndListed() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("slug", "quay"))
        .when()
        .put("/artifacts/api/mirror-upstreams/quay.io")
        .then()
        .statusCode(200)
        .body("upstream.domain", is("quay.io"))
        .body("upstream.slug", is("quay"))
        .body("upstream.cachedImages", is(0));

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("slug", "quay"))
        .when()
        .put("/artifacts/api/mirror-upstreams/quay.io")
        .then()
        .statusCode(200);

    given()
        .when()
        .get("/artifacts/api/mirror-upstreams")
        .then()
        .statusCode(200)
        .body("upstreams.domain", hasItem("quay.io"));
  }

  @Test
  void registeringAnUpstreamCreatesTheMirrorRepositoryItsNamespaceResolvesTo() {
    // The pairing, visible where an operator would look for it: the namespace shows up in the
    // ordinary repository list as an oci-mirror row, which is also what makes it browsable.
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("slug", "redhat"))
        .when()
        .put("/artifacts/api/mirror-upstreams/registry.access.redhat.com")
        .then()
        .statusCode(200);

    given()
        .when()
        .get("/artifacts/api/repositories")
        .then()
        .statusCode(200)
        .body("repositories.find { it.name == 'redhat' }.type", is("oci-mirror"));
  }

  @Test
  void anUnknownDomainIs404AndAnUnusableSlugIs400() {
    given()
        .when()
        .get("/artifacts/api/mirror-upstreams/nothing.example")
        .then()
        .statusCode(404);

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("slug", "Not A Slug"))
        .when()
        .put("/artifacts/api/mirror-upstreams/gcr.io")
        .then()
        .statusCode(400);
  }

  @Test
  void deletingAnUpstreamLeavesItsNamespaceAndItsCacheBehind() {
    // ⚖2 on the wire. The upstream is gone, so nothing new can be fetched into the namespace; the
    // repository row stays, so everything already cached under it still serves. A caller wanting the
    // bytes gone is asking for deletion, which this service does not do.
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("slug", "ghcr"))
        .when()
        .put("/artifacts/api/mirror-upstreams/ghcr.io")
        .then()
        .statusCode(200);

    given().when().delete("/artifacts/api/mirror-upstreams/ghcr.io").then().statusCode(204);

    given().when().get("/artifacts/api/mirror-upstreams/ghcr.io").then().statusCode(404);
    given()
        .when()
        .get("/artifacts/api/repositories")
        .then()
        .body("repositories.find { it.name == 'ghcr' }.type", is("oci-mirror"));
  }
}
