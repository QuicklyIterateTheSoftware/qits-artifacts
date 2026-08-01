package eu.wohlben.qits.registry;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A mirror namespace on the {@code /v2} wire: what it answers, and what it refuses.
 *
 * <p>Nothing is fetched yet — the miss path is workstream BX — so every read here misses. That is
 * exactly what makes the suite worth having now: the <b>shape</b> of a mirror namespace is settled
 * by this workstream, and BX changes what a miss does, not what a miss is. A puller that gets a 404
 * saying this registry holds no cached copy is being told the truth; one that got {@code
 * NAME_UNKNOWN} would go looking for a namespace that is right there.
 *
 * <p>Paths are spelled absolutely, as everywhere under {@code /v2}: the segment is a literal in the
 * code and no configuration moves it.
 */
@QuarkusTest
class RegistryMirrorTest {

  private static final String ABSENT_DIGEST = "sha256:" + "0".repeat(64);

  @BeforeEach
  void registerUpstreams() {
    register("quay.io", "quay");
    register("docker.io", "hub");
  }

  private static void register(String domain, String slug) {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("slug", slug))
        .when()
        .put("/artifacts/api/mirror-upstreams/" + domain)
        .then()
        .statusCode(200);
  }

  private static io.restassured.specification.RequestSpecification http() {
    // A digest carries a colon, which docker sends raw and RestAssured would percent-encode.
    return given().urlEncodingEnabled(false);
  }

  @Test
  void aManifestMissInAMirrorNamespaceSaysTheMirrorHasNoCopyRatherThanThatTheNameIsUnknown() {
    given()
        .when()
        .get("/v2/quay/quarkus/ubi9-quarkus-mandrel-builder-image/manifests/jdk-25")
        .then()
        .statusCode(404)
        .body("errors[0].code", equalTo("MANIFEST_UNKNOWN"))
        .body("errors[0].message", containsString("no cached copy"))
        .body("errors[0].detail.namespace", equalTo("quay"))
        .body("errors[0].detail.upstream", equalTo("quay.io"));
  }

  @Test
  void aBlobMissInAMirrorNamespaceSaysTheSame() {
    http()
        .when()
        .get("/v2/quay/quarkus/ubi9-quarkus-mandrel-builder-image/blobs/" + ABSENT_DIGEST)
        .then()
        .statusCode(404)
        .body("errors[0].code", equalTo("BLOB_UNKNOWN"))
        .body("errors[0].message", containsString("no cached copy"))
        .body("errors[0].detail.upstream", equalTo("quay.io"));
  }

  @Test
  void aPushToAMirrorNamespaceIs405ByType() {
    // By type, not by configuration: a mirror never accepts content from a client, so cached
    // upstream content and pushed content can never share a namespace. The same rule the npm proxy
    // carries, and the reason both are separate types rather than flags.
    given()
        .when()
        .post("/v2/quay/anything/blobs/uploads/")
        .then()
        .statusCode(405)
        .body("errors[0].code", equalTo("UNSUPPORTED"))
        .body("errors[0].message", containsString("pull-through cache"))
        .body("errors[0].detail.type", equalTo("oci-mirror"));

    given()
        .contentType("application/vnd.oci.image.manifest.v1+json")
        .body("{}")
        .when()
        .put("/v2/quay/anything/manifests/latest")
        .then()
        .statusCode(405)
        .body("errors[0].code", equalTo("UNSUPPORTED"));
  }

  @Test
  void aSingleComponentImageUnderHubIsTheLibraryOneAndBothSpellingsMissTogether() {
    // The docker daemon's own expansion. It matters here because the two spellings must share one
    // cache entry — otherwise the first `docker pull <host>/hub/alpine` and the first
    // `<host>/hub/library/alpine` would each pay their own upstream fetch and store the same bytes
    // under two names.
    given()
        .when()
        .get("/v2/hub/alpine/manifests/latest")
        .then()
        .statusCode(404)
        .body("errors[0].code", equalTo("MANIFEST_UNKNOWN"))
        .body("errors[0].detail.namespace", equalTo("hub"))
        .body("errors[0].detail.upstream", equalTo("docker.io"));
  }

  @Test
  void aBareHubNameWithNoRepositoryOfItsOwnRemapsIntoTheHubNamespace() {
    // The registry-mirrors footnote: a daemon configured to mirror Docker Hub asks for bare Hub
    // names. Nothing else depends on this, and an existing repository always wins the segment.
    given()
        .when()
        .get("/v2/library/alpine/manifests/latest")
        .then()
        .statusCode(404)
        .body("errors[0].code", equalTo("MANIFEST_UNKNOWN"))
        .body("errors[0].detail.namespace", equalTo("hub"));
  }

  @Test
  void aHostedRepositoryStillWinsItsFirstSegment() {
    // The precedence rule, asserted with a Hub upstream registered — which is the only state in
    // which it could be got wrong. A miss in `qits` is the hosted registry's own answer, with no
    // mention of a mirror anywhere in it.
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("type", "oci-images"))
        .when()
        .put("/artifacts/api/repositories/qits")
        .then()
        .statusCode(200);

    given()
        .when()
        .get("/v2/qits/mirror-precedence/manifests/latest")
        .then()
        .statusCode(404)
        .body("errors[0].code", equalTo("MANIFEST_UNKNOWN"))
        .body("errors[0].message", equalTo("manifest unknown to this image"));
  }

  @Test
  void tagsListAnswersForAMirrorNamespaceLikeAnyOtherImage() {
    // An empty list rather than a 404: an image is not a row, so there is nothing to be absent —
    // the same answer the hosted registry gives, because it is the same handler.
    given()
        .when()
        .get("/v2/quay/quarkus/ubi9-quarkus-mandrel-builder-image/tags/list")
        .then()
        .statusCode(200)
        .body("name", equalTo("quay/quarkus/ubi9-quarkus-mandrel-builder-image"))
        .body("tags.size()", equalTo(0));
  }
}
