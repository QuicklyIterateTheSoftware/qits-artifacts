package eu.wohlben.qits.npm;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The pull-through cache, against an in-process upstream.
 *
 * <p>Never against registry.npmjs.org: this repo's suite runs from a bare clone with no network, so
 * a test that reached the real thing would fail offline and pass in CI for reasons that have nothing
 * to do with this code. {@link StubNpmRegistry} is also the only way to <em>count</em> upstream
 * requests, which is what every caching claim here actually rests on.
 *
 * <p>The TTL is left at its shipped default, so these are the cache-<b>hit</b> cases;
 * {@code NpmProxyRevalidationTest} runs the same stack with the TTL at zero and covers what happens
 * when it expires.
 */
@QuarkusTest
@TestProfile(NpmProxyTest.ProxiedUpstream.class)
class NpmProxyTest {

  private static final AtomicInteger UNIQUE = new AtomicInteger();

  /**
   * Points the proxy at the stub. The stub's port is only knowable at runtime, which is why it is a
   * process-wide singleton: this method runs while Quarkus is starting, before any {@code @BeforeAll}.
   */
  public static class ProxiedUpstream implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.artifacts.npm.proxy.upstream", StubNpmRegistry.INSTANCE.baseUrl());
    }
  }

  @TestHTTPResource("/")
  URL root;

  @BeforeEach
  void ensureRepositoryAndUpstream() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("type", "npm-proxy"))
        .when()
        .put("/artifacts/api/repositories/npmjs")
        .then()
        .statusCode(200);
    StubNpmRegistry.INSTANCE.reset();
  }

  @Test
  void aPackumentIsFetchedOnceAndThenServedFromCache() {
    TinyPackage subject = upstreamPackage("1.3.0");

    try (NpmClient npm = client()) {
      JsonNode first = npm.packumentJson("npmjs", subject.name());
      assertEquals("1.3.0", first.path("dist-tags").path("latest").asText());
      assertEquals(1, StubNpmRegistry.INSTANCE.packumentRequests());

      npm.packumentJson("npmjs", subject.name());
      npm.packumentJson("npmjs", subject.name());
      assertEquals(
          1,
          StubNpmRegistry.INSTANCE.packumentRequests(),
          "a packument within its TTL must not reach upstream at all");
    }
  }

  @Test
  void everyTarballUrlIsRewrittenToPointAtThisProxy() {
    TinyPackage subject = upstreamPackage("1.3.0");

    try (NpmClient npm = client()) {
      JsonNode packument = npm.packumentJson("npmjs", subject.name());
      String tarball = NpmClient.tarballUrl(packument, "1.3.0");
      assertTrue(
          tarball.startsWith("http://" + root.getHost() + ":" + root.getPort() + "/artifacts/npm/npmjs/"),
          "upstream's url must not survive into the served document; got " + tarball);
      assertTrue(tarball.endsWith("/" + subject.name() + "/-/" + subject.tarballFile()), tarball);

      // Everything else is upstream's, unchanged — including a field this service knows nothing
      // about, which is the general claim the marker stands in for.
      assertEquals("stub", packument.path("_upstream").asText());
    }
  }

  @Test
  void upstreamIntegrityIsReEmittedUnmodified() {
    // The whole safety argument of the proxy: the client verifies the bytes it downloads against a
    // hash this service never computed, so a corrupting proxy is caught by the client rather than
    // trusted. Rewriting or recomputing integrity here would quietly remove that.
    TinyPackage subject = upstreamPackage("1.3.0");

    try (NpmClient npm = client()) {
      JsonNode dist = npm.packumentJson("npmjs", subject.name()).path("versions").path("1.3.0").path("dist");
      assertEquals(subject.integrity(), dist.path("integrity").asText());
      assertEquals(subject.shasum(), dist.path("shasum").asText());
    }
  }

  @Test
  void aTarballIsFetchedOnceAndThenServedFromDisk() {
    TinyPackage subject = upstreamPackage("1.3.0");

    try (NpmClient npm = client()) {
      String url = NpmClient.tarballUrl(npm.packumentJson("npmjs", subject.name()), "1.3.0");

      HttpResponse<byte[]> first = npm.tarball(url);
      assertEquals(200, first.statusCode());
      assertArrayEquals(subject.tarball(), first.body());
      assertEquals(1, StubNpmRegistry.INSTANCE.tarballRequests());

      // Tarballs are immutable, so the second pull is a sendFile off local disk — the whole point
      // of the cache, and what makes an npm install in CI stop paying for npmjs per run.
      HttpResponse<byte[]> second = npm.tarball(url);
      assertArrayEquals(subject.tarball(), second.body());
      assertEquals(
          1, StubNpmRegistry.INSTANCE.tarballRequests(), "a cached tarball must never be refetched");
    }
  }

  @Test
  void aCachedTarballSurvivesAnUpstreamOutageEntirely() {
    TinyPackage subject = upstreamPackage("1.3.0");

    try (NpmClient npm = client()) {
      String url = NpmClient.tarballUrl(npm.packumentJson("npmjs", subject.name()), "1.3.0");
      assertEquals(200, npm.tarball(url).statusCode());

      StubNpmRegistry.INSTANCE.reachable(false);
      HttpResponse<byte[]> served = npm.tarball(url);
      assertEquals(200, served.statusCode(), "a cached tarball needs no upstream at all");
      assertArrayEquals(subject.tarball(), served.body());
    }
  }

  @Test
  void anUncachedTarballFromADeadUpstreamIsA502RatherThanA500() {
    TinyPackage subject = upstreamPackage("1.3.0");

    try (NpmClient npm = client()) {
      String url = NpmClient.tarballUrl(npm.packumentJson("npmjs", subject.name()), "1.3.0");
      StubNpmRegistry.INSTANCE.reachable(false);

      HttpResponse<byte[]> failed = npm.tarball(url);
      assertEquals(502, failed.statusCode(), "upstream's failure is upstream's, and says so");
    }
  }

  @Test
  void aPackageUpstreamDoesNotHaveIs404() {
    try (NpmClient npm = client()) {
      HttpResponse<String> answered = npm.packument("npmjs", "no-such-package-anywhere");
      assertEquals(404, answered.statusCode());
      assertTrue(NpmClient.parse(answered.body()).has("error"), answered.body());
    }
  }

  @Test
  void publishingToTheProxyIsRefusedBeforeAnythingIsFetched() {
    TinyPackage subject = TinyPackage.of("brand-new-" + UNIQUE.incrementAndGet(), "1.0.0");
    try (NpmClient npm = client()) {
      assertEquals(
          405, npm.publish("npmjs", subject.name(), subject.publishDocument("latest")).statusCode());
      assertEquals(
          0,
          StubNpmRegistry.INSTANCE.packumentRequests(),
          "the type check must happen before any upstream work");
    }
  }

  @Test
  void aScopedPackageProxiesThroughBothSpellingsOfItsName() {
    TinyPackage subject =
        TinyPackage.of("@upstream-org/lib-" + UNIQUE.incrementAndGet(), "0.4.2");
    StubNpmRegistry.INSTANCE.hostPackage(subject);

    try (NpmClient npm = client()) {
      assertEquals(200, npm.packument("npmjs", subject.name().replace("/", "%2f")).statusCode());
      JsonNode packument = npm.packumentJson("npmjs", subject.name());
      assertArrayEquals(
          subject.tarball(), npm.tarball(NpmClient.tarballUrl(packument, "0.4.2")).body());
    }
  }

  private TinyPackage upstreamPackage(String version) {
    TinyPackage subject = TinyPackage.of("left-pad-" + UNIQUE.incrementAndGet(), version);
    StubNpmRegistry.INSTANCE.hostPackage(subject);
    return subject;
  }

  private NpmClient client() {
    return new NpmClient(URI.create(root.toString()));
  }
}
