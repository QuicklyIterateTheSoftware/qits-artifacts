package eu.wohlben.qits.registry;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.net.URI;
import java.net.URL;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The registry carries no write guard, and this suite pins that it stays that way <b>even with
 * {@code qits.artifacts.token} configured</b> — the token guards the blob-store JSON API
 * ({@code X-Artifacts-Token}) and must not drag {@code /v2} back behind a docker login. That
 * coupling existed once (the retired {@code RegistryAuthGuard} shared the secret), so the
 * regression this profile exists to catch is precisely "someone set the blob token and every
 * producer's push started failing with auth errors".
 *
 * <p>Why the registry is open: on qits-net, producers are trusted (the platform posture — and what
 * lets an automated publisher push with no credential store), and from outside, qits-gateway keeps
 * {@code /v2} write methods off its token-free allowlist, so an internet push dies on a session
 * challenge no registry client can answer. External write protection is the gateway's; see the
 * comment in {@code RegistryRoutes.init}.
 */
@QuarkusTest
@TestProfile(RegistryOpenPushTest.BlobTokenConfigured.class)
class RegistryOpenPushTest {

  static final String TOKEN = "s3cr3t-blob-token";

  public static class BlobTokenConfigured implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.artifacts.token", TOKEN);
    }
  }

  private static final String IMAGE = "qits/open-push";

  @TestHTTPResource("/")
  URL root;

  @BeforeEach
  void ensureRepository() {
    given()
        .contentType(ContentType.JSON)
        .header("X-Artifacts-Token", TOKEN)
        .body(Map.of("type", "oci-images"))
        .when()
        .put("/artifacts/api/repositories/qits")
        .then()
        .statusCode(200);
  }

  @Test
  void anAnonymousPushAndPullRoundTripDespiteTheBlobToken() {
    try (OciClient anonymous = new OciClient(URI.create(root.toString()))) {
      TinyImage subject = TinyImage.of("open-push");
      anonymous.push(IMAGE, "latest", subject);
      assertEquals(200, anonymous.versionProbe(), "the probe is unconditionally 200");
      assertArrayEquals(subject.manifest(), anonymous.pull(IMAGE, "latest").manifest());
    }
  }

  @Test
  void theBlobStoreJsonApiIsStillTokenGuarded() {
    // The same secret keeps guarding the JSON API — dropping the registry's guard must not have
    // widened anything else.
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("type", "oci-images"))
        .when()
        .put("/artifacts/api/repositories/unauthenticated")
        .then()
        .statusCode(401);
  }
}
