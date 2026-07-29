package eu.wohlben.qits.registry;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
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
 * The registry's write guard, with a token actually configured.
 *
 * <p>{@code ArtifactsTokenFilter} is a JAX-RS provider and never runs on these raw Vert.x routes, so
 * {@code /v2} is unguarded unless {@code RegistryAuthGuard} guards it. The two share only the secret
 * and the blank-is-open rule, through {@code ArtifactsToken} — which is exactly the kind of
 * arrangement that rots silently, so it is pinned here.
 *
 * <p>Its own profile because the rest of the suite runs with a blank token (the dev/test default),
 * which is also the deployment posture where {@code docker push} works unchanged.
 */
@QuarkusTest
@TestProfile(RegistryAuthTest.TokenConfigured.class)
class RegistryAuthTest {

  static final String TOKEN = "s3cr3t-push-token";

  public static class TokenConfigured implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.artifacts.token", TOKEN);
    }
  }

  private static final String IMAGE = "qits/guarded";

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
  void anUnauthenticatedWriteIsChallengedWithBasic() {
    given()
        .when()
        .post("/v2/" + IMAGE + "/blobs/uploads/")
        .then()
        .statusCode(401)
        .header("WWW-Authenticate", containsString("Basic realm=\"qits-artifacts\""))
        .body("errors[0].code", equalTo("UNAUTHORIZED"));
  }

  @Test
  void aWrongPasswordIsAlsoChallenged() {
    given()
        .auth()
        .preemptive()
        .basic("qits", "not-the-token")
        .when()
        .post("/v2/" + IMAGE + "/blobs/uploads/")
        .then()
        .statusCode(401);
  }

  @Test
  void theUsernameIsIgnoredAndThePasswordIsTheToken() {
    // What `skopeo copy --dest-creds <anything>:<token>` and `podman push --creds` send. Neither can
    // be told to set a custom header, which is the whole reason Basic is the mechanism here.
    try (OciClient client = new OciClient(URI.create(root.toString())).basicAuth("anyone", TOKEN)) {
      TinyImage subject = TinyImage.of("guarded");
      client.push(IMAGE, "latest", subject);
      assertEquals(200, client.versionProbe());
    }
  }

  @Test
  void readsStayAnonymousEvenWithATokenConfigured() {
    // Privacy is the deployment's — the registry is reachable only on the container network or
    // behind the gateway. Image names are meant to be SHARED, which is also why /v2/_catalog stays
    // unimplemented rather than being defended.
    try (OciClient authenticated =
            new OciClient(URI.create(root.toString())).basicAuth("qits", TOKEN);
        OciClient anonymous = new OciClient(URI.create(root.toString()))) {
      TinyImage subject = TinyImage.of("anonymous-read");
      authenticated.push(IMAGE, "readable", subject);

      assertEquals(200, anonymous.versionProbe(), "the probe is unconditionally 200");
      org.junit.jupiter.api.Assertions.assertArrayEquals(
          subject.manifest(), anonymous.pull(IMAGE, "readable").manifest());
    }
  }
}
