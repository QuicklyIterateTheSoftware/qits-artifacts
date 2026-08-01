package eu.wohlben.qits.registry;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.MachineTokens;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.net.URI;
import java.net.URL;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The registry carries no write guard, and this suite pins that it stays that way <b>even with the
 * machine-token gate on</b> — that gate guards the blob-store JSON admin API and must not drag
 * {@code /v2} back behind a docker login. The coupling existed once (the retired {@code
 * RegistryAuthGuard} shared the JSON API's static secret), so the regression this profile exists to
 * catch is precisely "someone turned enforcement on and every producer's push started failing".
 *
 * <p>It cannot come back by accident either: {@code AdminWriteGuard} is a JAX-RS filter and these
 * are raw Vert.x routes, and no docker client can present a bearer from qits-idp anyway. Guarding
 * {@code /v2} would be its own decision, with its own credential.
 *
 * <p>Why the registry is open: on qits-net, producers are trusted (the platform posture — and what
 * lets an automated publisher push with no credential store), and from outside, qits-gateway keeps
 * {@code /v2} write methods off its token-free allowlist, so an internet push dies on a session
 * challenge no registry client can answer. External write protection is the gateway's; see the
 * comment in {@code RegistryRoutes.init}.
 */
@QuarkusTest
@TestProfile(MachineTokens.Enforced.class)
class RegistryOpenPushTest {

  private static final String IMAGE = "qits/open-push";

  @TestHTTPResource("/")
  URL root;

  @BeforeEach
  void ensureRepository() {
    given()
        .contentType(ContentType.JSON)
        .header("Authorization", "Bearer " + MachineTokens.forThisService())
        .body(Map.of("type", "oci-images"))
        .when()
        .put("/artifacts/api/repositories/qits")
        .then()
        .statusCode(200);
  }

  @Test
  void anAnonymousPushAndPullRoundTripDespiteTheGate() {
    try (OciClient anonymous = new OciClient(URI.create(root.toString()))) {
      TinyImage subject = TinyImage.of("open-push");
      anonymous.push(IMAGE, "latest", subject);
      assertEquals(200, anonymous.versionProbe(), "the probe is unconditionally 200");
      assertArrayEquals(subject.manifest(), anonymous.pull(IMAGE, "latest").manifest());
    }
  }

  @Test
  void theBlobStoreJsonApiIsStillGuarded() {
    // The same gate keeps guarding the JSON API — the registry staying open must not have widened
    // anything else.
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("type", "oci-images"))
        .when()
        .put("/artifacts/api/repositories/unauthenticated")
        .then()
        .statusCode(401);
  }
}
