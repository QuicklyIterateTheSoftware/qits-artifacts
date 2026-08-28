package eu.wohlben.qits;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.servicemock.idp.MockIdp;
import eu.wohlben.qits.testdb.EmbeddedPg;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;

/**
 * The whole service as it is <b>packaged</b>, with the machine-token rollout gate <b>on</b> — the
 * posture a deployment with qits-platform-idp runs, and one no other suite here can prove:
 * {@link MachineTokens.Enforced} swaps the tenant's key distribution for a configured public key,
 * so the shipped {@code quarkus.oidc.*} block (auth-server-url + jwks-path against a real listener,
 * the boot-time JWKS fetch under {@code connection-delay}, audience enforcement, groups→roles
 * mapping) is exercised nowhere else. The far side is {@link MockIdp}, whose recordings make the
 * interaction assertable on <b>both ends</b>. qits-githost's IT of the same name is the pattern.
 *
 * <p>It is also this repo's first <b>userflow</b>: the proof doubles as documentation, emitted
 * under {@code target/userstories/} with the interactions drawn as a sequence diagram. The story
 * is browserless (no {@code Flow} parameter), so no Chromium is involved anywhere.
 */
@QuarkusIntegrationTest
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
public class TokenValidationBootstrapIT {

  static final String CATEGORY = "authentication";
  static final String ACCEPTED_SLUG = "on-start-the-registry-fetches-the-platform-s-signing-keys";
  static final String DENIED_SLUG = "a-stranger-s-token-never-opens-the-registry-s-admin-door";

  /**
   * Hands the launched artifact its config the way a deployment does — the resource triple as the
   * <b>variable names the shipped expressions read</b>, and the rollout gate as the {@code
   * qits.auth.machine.required} key the shipped {@code quarkus.oidc.tenant-enabled} expression
   * follows — so the expressions themselves stay under test ({@code PackagedProcessIT} predates
   * that pattern and moves the datasource keys directly; the githost IT is the precedent here).
   *
   * <p>The database is the same embedded postgres the surefire suite spawns, under an IT-own name
   * so nothing is shared with {@code PackagedProcessIT}'s. The mock idp starts here — before the
   * application — via {@link MockIdp#ensureStarted()}, which parks its coordinates in system
   * properties: a test profile is instantiated in more than one classloader, and the property
   * table is the one thing every copy (and the story method's {@link MockIdp#attach()}) shares.
   */
  public static class PackagedWithMockIdp implements QuarkusTestProfile {

    /** The shipped {@code qits.auth.machine.audience} — deliberately NOT overridden. */
    static final String AUDIENCE = "qits-platform-artifacts";

    @Override
    public Map<String, String> getConfigOverrides() {
      MockIdp idp = MockIdp.ensureStarted();
      Map<String, String> overrides = new LinkedHashMap<>();
      overrides.put("QITS_RESOURCE_DB_URL", EmbeddedPg.url("artifacts_userflows_it"));
      overrides.put("QITS_RESOURCE_DB_USERNAME", EmbeddedPg.USER);
      overrides.put("QITS_RESOURCE_DB_PASSWORD", EmbeddedPg.PASSWORD);
      overrides.put("quarkus.flyway.artifacts.clean-at-start", "true");
      // The rollout gate, exactly as a deployment flips it: the shipped
      // quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false} expression is what turns
      // the tenant on, so the gate-follows-tenant coupling is itself under test.
      overrides.put("qits.auth.machine.required", "true");
      // The one seam this test moves: where the idp is. Runtime key, so the packaged artifact is
      // otherwise exactly what ships — jwks-path stays `jwks`, joined onto this base.
      overrides.put("quarkus.oidc.auth-server-url", idp.baseUrl());
      // No qits-platform-deployments, qits-ci or public registry on a build machine — closed ports
      // refuse deterministically, the same three lines PackagedProcessIT passes.
      overrides.put(
          "qits.artifacts.gc.pins.cd-base-url", "http://localhost:1/platform-deployments/api");
      overrides.put("qits.artifacts.gc.pins.ci-base-url", "http://localhost:1/ci/api");
      overrides.put("qits.artifacts.oci.mirror.endpoint-override", "http://localhost:1");
      // Dark outside a deployment, like %dev/%test — a runtime key.
      overrides.put("quarkus.otel.sdk.disabled", "true");
      return overrides;
    }
  }

  @UserStory(
      value = "On start, the registry fetches the platform's signing keys",
      category = "authentication")
  @UserStoryDescription(
      """
      A qits-artifacts deployed with the machine-token gate on must validate service bearers
      before any caller arrives: at startup it fetches the signing keys (JWKS) from
      qits-platform-idp — discovery stays off, the path is configured — so the very first admin
      write carrying a platform token is accepted.
      """)
  void serviceBootFetchesJwksAndAcceptsPlatformTokens(Interactions story) {
    MockIdp idp = MockIdp.attach();

    story.note(
        "qits-artifacts starts with the rollout gate on, beside a reachable qits-platform-idp");
    given().get("/artifacts/q/health/ready").then().statusCode(200);

    // End (a), the idp side: the JWKS was served during startup — before this story presented
    // any token at all. connection-delay=30S is what turns the boot attempt into a retried one.
    assertTrue(
        idp.recordedRequests().stream().anyMatch(r -> "/idp/jwks".equals(r.path())),
        "the packaged service never fetched /idp/jwks at startup");
    story.happened("qits-artifacts", "qits-platform-idp", "GET /idp/jwks (at startup)")
        .as("jwks-fetched");

    // End (b), the registry side: those keys are what token validation now runs on. A platform
    // service's bearer (aud = this service, roles in `groups`) opens the guarded admin write —
    // AdminWriteGuard's MachineAuth.require(), behind the same PUT every CI process uses.
    String platformToken =
        idp.token()
            .subject("qits-ci")
            .audience(PackagedWithMockIdp.AUDIENCE)
            .groups("qits:system")
            .mint();
    given()
        .header("Authorization", "Bearer " + platformToken)
        .contentType("application/json")
        .body("{\"type\":\"ci-screenshots\"}")
        .put("/artifacts/api/repositories/userflows-boot-it")
        .then()
        .statusCode(200);
    story
        .happened(
            "a platform service",
            "qits-artifacts",
            "PUT /artifacts/api/repositories/… (Bearer, groups=[qits:system])")
        .as("admin-write-accepted");
  }

  @UserStory(
      value = "A stranger's token never opens the registry's admin door",
      category = "authentication")
  @UserStoryDescription(
      """
      The flip side of trusting the platform's keys: a token signed by a key the published JWKS
      never carried, or minted for another service's audience, is refused at the admin door —
      however well-formed it looks. The refusal is scoped to that door: the byte wires (`docker`,
      `npm`, `mvn`) keep answering on qits-net trust, gate on or off, until machine auth arrives
      for all of them at once.
      """)
  void aStrangersTokenIsRefused(Interactions story) {
    MockIdp idp = MockIdp.attach();

    String strangersToken =
        idp.token()
            .audience(PackagedWithMockIdp.AUDIENCE)
            .groups("qits:system")
            .signedByUnknownKey()
            .mint();
    given()
        .header("Authorization", "Bearer " + strangersToken)
        .contentType("application/json")
        .body("{\"type\":\"ci-screenshots\"}")
        .put("/artifacts/api/repositories/userflows-denied-it")
        .then()
        .statusCode(401);
    story
        .happened(
            "an impostor",
            "qits-artifacts",
            "PUT /artifacts/api/repositories/… (token signed by an unknown key) -> 401")
        .as("unknown-key-refused");

    String wrongAudienceToken =
        idp.token().audience("some-other-service").groups("qits:system").mint();
    given()
        .header("Authorization", "Bearer " + wrongAudienceToken)
        .contentType("application/json")
        .body("{\"type\":\"ci-screenshots\"}")
        .put("/artifacts/api/repositories/userflows-denied-it")
        .then()
        .statusCode(401);
    story
        .happened(
            "an impostor",
            "qits-artifacts",
            "PUT /artifacts/api/repositories/… (another service's audience) -> 401")
        .as("wrong-audience-refused");

    // The scope of the refusal, asserted from the other side: an anonymous docker ping still
    // answers with the gate on. The wires are unguarded on purpose (qits-net trust; versions are
    // immutable) — RegistryOpenPushTest pins it on the JVM, this pins it in the packaged process
    // with a real tenant validating in front of it.
    given()
        .get("/v2/")
        .then()
        .statusCode(200)
        .header("Docker-Distribution-Api-Version", "registry/2.0");
    story
        .happened("an anonymous docker client", "qits-artifacts", "GET /v2/ -> 200 (qits-net trust)")
        .as("wire-stays-open");
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    // The extension emits each report in its afterEach, so both are on disk before @AfterAll runs.
    ReportAssertions.assertComplete(CATEGORY, ACCEPTED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertInteraction(
        CATEGORY, ACCEPTED_SLUG, "qits-artifacts", "qits-platform-idp", "GET /idp/jwks (at startup)");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "jwks-fetched");

    ReportAssertions.assertComplete(CATEGORY, DENIED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "unknown-key-refused");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "wrong-audience-refused");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "wire-stays-open");
  }
}
