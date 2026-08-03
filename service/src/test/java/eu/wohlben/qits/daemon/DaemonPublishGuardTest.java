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
 * The publish {@code PUT} with the machine-token gate ON — the posture a deployment with qits-idp
 * runs.
 *
 * <p>This suite is the reason {@link DaemonPublishGuard} exists as a class rather than as one more
 * string in {@code AdminWriteGuard}'s prefix set. That filter is JAX-RS: it runs on <b>no</b> raw
 * Vert.x route, so extending it would have looked exactly like guarding this route and guarded
 * nothing — a green build with an open write surface, which is the failure mode this repo spends
 * most of its conventions avoiding. Every case below would pass against that mistake except the
 * first two, which is precisely why they are here.
 *
 * <p>The gate-OFF posture is what every other suite here runs under, so "unchanged when the gate is
 * off" is asserted by all of them at once rather than by one test.
 */
@QuarkusTest
@TestProfile(MachineTokens.Enforced.class)
class DaemonPublishGuardTest {

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
  void aPublishWithoutATokenIsRefused() {
    try (DaemonClient daemons = client()) {
      HttpResponse<String> refused =
          daemons.put("qits-ci-daemon", "unguarded", TinyDaemon.binary("unguarded", 512));
      assertEquals(401, refused.statusCode(), refused.body());
      assertTrue(refused.body().contains("machine token"), refused.body());

      // And nothing landed: the guard runs before a single byte is staged.
      assertEquals(404, daemons.get("qits-ci-daemon", "unguarded").statusCode());
    }
  }

  @Test
  void aPublishWithATokenForAnotherServiceIsRefused() {
    // Signed by the same issuer and perfectly valid — just not addressed to us. quarkus-oidc
    // refuses it on quarkus.oidc.token.audience before the guard's own audience check is reached,
    // so this is a 401 rather than the 403 an already-authenticated caller would get.
    try (DaemonClient daemons = client()) {
      HttpResponse<String> refused =
          daemons.putAuthorized(
              "qits-ci-daemon",
              "wrong-audience",
              TinyDaemon.binary("wrong-audience", 512),
              MachineTokens.forAnotherService());
      assertEquals(401, refused.statusCode(), refused.body());
      assertEquals(404, daemons.get("qits-ci-daemon", "wrong-audience").statusCode());
    }
  }

  @Test
  void aPublishWithOurTokenSucceeds() {
    byte[] binary = TinyDaemon.binary("guarded", 512);
    try (DaemonClient daemons = client()) {
      HttpResponse<String> published =
          daemons.putAuthorized(
              "qits-ci-daemon", "guarded", binary, MachineTokens.forThisService());
      assertEquals(201, published.statusCode(), published.body());
      assertTrue(
          published.body().contains("sha256:" + TinyDaemon.sha256(binary)), published.body());
    }
  }

  @Test
  void downloadsStayAnonymousWithTheGateOn() {
    // The cold-start path, and the reason reads are never guarded here: a fresh platform fetches a
    // daemon with a bootstrap script that has no credential yet, because there is no CI to mint one
    // until a daemon is running. Guarding the read would make the platform unable to start itself.
    byte[] binary = TinyDaemon.binary("anonymous-read", 512);
    try (DaemonClient daemons = client()) {
      assertEquals(
          201,
          daemons
              .putAuthorized(
                  "qits-ci-daemon", "anonymous-read", binary, MachineTokens.forThisService())
              .statusCode());

      HttpResponse<byte[]> served = daemons.get("qits-ci-daemon", "anonymous-read");
      assertEquals(200, served.statusCode());
      assertEquals(binary.length, served.body().length);
      assertEquals(200, daemons.head("qits-ci-daemon", "anonymous-read").statusCode());
    }
  }

  private DaemonClient client() {
    return new DaemonClient(URI.create(root.toString()));
  }
}
