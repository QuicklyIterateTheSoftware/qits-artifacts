package eu.wohlben.qits.daemon;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.MachineTokens;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The daemon publish carries no write guard, and this suite pins that it stays that way <b>even with
 * the machine-token gate on</b> — the twin of {@code registry/RegistryOpenPushTest} and {@code
 * npm/NpmOpenPublishTest}, asserting the same property for the fourth wire surface.
 *
 * <p>A guard here existed for one commit and was removed as a decision, which is why this suite is
 * worth its lines: the surface reads like the one that ought to be gated (it is the platform's own
 * executables), so "add a token check to the daemon publish" is the change most likely to be
 * proposed again. It must not be proposed piecemeal. Machine auth arrives wholesale with qits-idp,
 * for every publish path at once — gating this one alone reports a posture the other three do not
 * have.
 *
 * <p><b>What stands in for write auth.</b> A version is immutable: republishing {@code
 * (name, version)} is {@code 409} even for identical bytes, so an open publish can add a version and
 * can never change one. Consumers pin the digest this route echoes, so what a launcher runs is
 * decided by content addressing rather than by who was allowed to PUT. That is the same trade {@code
 * /v2} and {@code /artifacts/npm} make.
 *
 * <p>It cannot come back by accident either: {@code AdminWriteGuard} is a JAX-RS filter and these
 * are raw Vert.x routes, so turning the gate on guards the JSON admin API and leaves this route
 * exactly as it is.
 */
@QuarkusTest
@TestProfile(MachineTokens.Enforced.class)
class DaemonOpenPublishTest {

  @TestHTTPResource("/")
  URL root;

  @BeforeEach
  void ensureRepository() {
    given()
        .header("Authorization", "Bearer " + MachineTokens.forThisService())
        .contentType("application/json")
        .body("{\"type\":\"daemon-binaries\"}")
        .when()
        .put("/artifacts/api/repositories/daemons")
        .then()
        .statusCode(200);
  }

  @Test
  void anAnonymousPublishAndDownloadRoundTripDespiteTheGate() {
    byte[] binary = TinyDaemon.binary("open-publish", 512);
    try (DaemonClient daemons = client()) {
      HttpResponse<String> published = daemons.put("qits-ci-daemon", "open-publish", binary);
      assertEquals(201, published.statusCode(), published.body());
      assertTrue(published.body().contains("sha256:" + TinyDaemon.sha256(binary)), published.body());

      HttpResponse<byte[]> served = daemons.get("qits-ci-daemon", "open-publish");
      assertEquals(200, served.statusCode());
      assertEquals(binary.length, served.body().length);
      assertEquals(200, daemons.head("qits-ci-daemon", "open-publish").statusCode());
    }
  }

  @Test
  void aStrayBearerDoesNotTriggerOidcOnTheTokenlessRoute() {
    // The npm lesson, on this route: this service ships quarkus.http.auth.proactive=false, so
    // nothing resolves an identity unless something asks. A publisher that happens to send an
    // Authorization header — a pipeline reusing one curl invocation for several surfaces — must
    // still be served, not refused by an OIDC mechanism nobody invoked.
    byte[] binary = TinyDaemon.binary("stray-bearer", 512);
    try (DaemonClient daemons = client()) {
      HttpResponse<String> published =
          daemons.putAuthorized("qits-ci-daemon", "stray-bearer", binary, "qits-ci");
      assertEquals(201, published.statusCode(), published.body());
    }
  }

  @Test
  void theBlobStoreJsonApiIsStillGuarded() {
    // The same gate keeps guarding the JSON API — the daemon publish staying open must not have
    // widened anything else.
    given()
        .contentType("application/json")
        .body("{\"type\":\"daemon-binaries\"}")
        .when()
        .put("/artifacts/api/repositories/unauthenticated")
        .then()
        .statusCode(401);
  }

  private DaemonClient client() {
    return new DaemonClient(URI.create(root.toString()));
  }
}
