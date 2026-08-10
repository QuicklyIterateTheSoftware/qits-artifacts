package eu.wohlben.qits.registry;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Where {@code quarkus.http.limits.max-body-size} is enforced, and where it is not. This is
 * milestone M0 of the OCI registry: the feature document asserted the setting was "a hard global
 * ceiling — a custom Vert.x route does NOT bypass it", and the registry's whole streaming design
 * depends on whether that is true.
 *
 * <p>It is true for half the traffic. Quarkus installs the check as a Vert.x route at order -2
 * ({@code HttpServerCommonHandlers.enforceMaxBodySize}) on the same router {@code GitHostRoutes} and
 * {@code RegistryRoutes} register on. With a declared {@code Content-Length} it answers 413 before
 * any application handler runs. With <b>no</b> {@code Content-Length} it only stashes the limit in
 * the routing context under {@code io.quarkus.max-request-size} and calls {@code next()} — leaving
 * enforcement to whatever reads the body. A raw route that reads {@code HttpServerRequest} itself
 * reads that key never, and is therefore not bounded at all.
 *
 * <p>That is not a hypothetical: {@code docker push} streams a layer from a reader of unknown
 * length, so the {@code PATCH} arrives chunked. The registry's hot path is exactly the cell where
 * the documented protection does not exist, which is why it reads through {@code OciRequestBody}
 * rather than off the request.
 *
 * <p>The earlier investigation that produced the "does not bypass it" claim used RestAssured, which
 * always sends a {@code Content-Length} — it measured one cell of this matrix and generalised. The
 * chunked cases below need a JDK {@code HttpClient}; see {@link #postChunked}.
 */
@QuarkusTest
@TestProfile(BodyCeilingProbeTest.OneMebibyteCeiling.class)
class BodyCeilingProbeTest {

  /**
   * {@code quarkus.http.limits.*} is run-time config, so a profile moves it with no re-augmentation.
   * A profile rather than {@code src/test/resources/application.properties} on purpose: max-body-size
   * is a shipped app-level default, and a second copy in test resources is exactly the drift
   * CLAUDE.md forbids. This one is scoped to this class and vanishes with it.
   */
  public static class OneMebibyteCeiling implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("quarkus.http.limits.max-body-size", "1M");
    }
  }

  private static final byte[] TWO_MEBIBYTES = new byte[2 * 1024 * 1024];

  @TestHTTPResource("")
  URL base;

  @Test
  void aContentLengthOverTheCeilingIs413BeforeAnyRouteRuns() {
    BodyCeilingProbeRoutes.RAW_BYTES_SEEN.set(-1);
    given().body(TWO_MEBIBYTES).when().post("/probe/raw-drain").then().statusCode(413);
    assertEquals(
        -1L,
        BodyCeilingProbeRoutes.RAW_BYTES_SEEN.get(),
        "the check sits at router order -2, so the route must never be reached");
  }

  @Test
  void aChunkedBodyOnARawRouteIsNotGatedAtAll() throws Exception {
    // THE FINDING. With no Content-Length the order -2 handler has nothing to compare: it stashes
    // the limit and calls next(). A route that never reads that key reads the whole body.
    HttpResponse<String> response = postChunked("/probe/raw-drain", TWO_MEBIBYTES);
    assertEquals(200, response.statusCode());
    assertEquals(String.valueOf(TWO_MEBIBYTES.length), response.body());
  }

  @Test
  void aChunkedBodyReadThroughVertxInputStreamIs413() throws Exception {
    // The mitigation, proved: the stream reads the stashed key and enforces it mid-stream against
    // request.bytesRead(). This is the only reason the registry's PATCH has a wire limit.
    assertEquals(413, postChunked("/probe/streamed", TWO_MEBIBYTES).statusCode());
  }

  @Test
  void aChunkedBodyUnderTheCeilingStreamsThroughIntact() throws Exception {
    HttpResponse<String> response = postChunked("/probe/streamed", new byte[512 * 1024]);
    assertEquals(200, response.statusCode());
    assertEquals(
        String.valueOf(512 * 1024),
        response.body(),
        "the pause-then-worker handoff must not drop the chunks that arrive during it");
  }

  /**
   * {@code BodyPublishers.ofInputStream} reports {@code contentLength() == -1}, so the JDK client
   * sends {@code Transfer-Encoding: chunked}. RestAssured cannot express that reliably — which is
   * exactly why the chunked half of this matrix went untested for so long.
   */
  private HttpResponse<String> postChunked(String path, byte[] body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(base.toURI().resolve(path))
            .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
            .build();
    // HTTP/1.1 explicitly, for two reasons. It is what docker, podman and skopeo speak to a
    // registry, so it is the protocol whose behaviour we actually need to know. And Quarkus' 413
    // path adds `Connection: close`, which is a prohibited header in HTTP/2 — over h2 the JDK
    // client rejects the response as malformed before the status code is visible, turning a proven
    // 413 into a ProtocolException.
    try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
  }
}
