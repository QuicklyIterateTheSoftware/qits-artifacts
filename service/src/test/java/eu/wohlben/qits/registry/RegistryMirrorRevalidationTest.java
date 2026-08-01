package eu.wohlben.qits.registry;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What happens to a cached tag once its TTL is up — and what happens when the upstream is not there
 * to ask.
 *
 * <p>{@code tag-ttl=PT0S} makes every tag expired on arrival, which turns four claims into fast
 * assertions instead of hour-long ones: expiry <b>revalidates</b> rather than refetches, a moved tag
 * is picked up, an unreachable upstream serves the stale copy, and a cold miss against an
 * unreachable upstream is an honest 502. The last two are the offline posture the whole {@code FROM}
 * rewrite rests on: once a base image has been pulled once, every later build succeeds with the
 * internet down.
 *
 * <p>The same profile carries a one-second manifest timeout, because the request bound belongs to
 * the same question — what this cache does when an upstream is not answering properly. A profile is
 * per class and each one restarts the application, so two questions with one configuration share a
 * class rather than paying for two.
 */
@QuarkusTest
@TestProfile(RegistryMirrorRevalidationTest.ExpiredOnArrival.class)
class RegistryMirrorRevalidationTest {

  private static final AtomicInteger UNIQUE = new AtomicInteger();

  /**
   * New every JVM. The tables are wiped once per run but the blob directory under {@code target/}
   * is never wiped, so an image whose content repeated between runs would already be staged on disk
   * — a hit that silently subtracts one from every fetch count here.
   */
  private static final String RUN = java.util.UUID.randomUUID().toString().substring(0, 8);

  public static class ExpiredOnArrival implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.artifacts.oci.mirror.endpoint-override", StubOciRegistry.INSTANCE.baseUrl(),
          "qits.artifacts.oci.mirror.tag-ttl", "PT0S",
          "qits.artifacts.oci.mirror.manifest-timeout", "PT1S");
    }
  }

  @BeforeEach
  void registerUpstreamAndResetUpstreamState() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("slug", "quay"))
        .when()
        .put("/artifacts/api/mirror-upstreams/quay.io")
        .then()
        .statusCode(200);
    StubOciRegistry.INSTANCE.reset();
  }

  @Test
  void anExpiredTagIsRevalidatedByHeadRatherThanRefetched() {
    // The cheap half of the design. A registry HEAD returns Docker-Content-Digest and Docker Hub
    // does not count one against its anonymous pull limit, so an unchanged tag costs nothing —
    // which is what makes a short TTL affordable and keeps `jdk-25` current with zero curation.
    String image = image("stable");
    TinyImage upstream = TinyImage.of(image);
    StubOciRegistry.INSTANCE.hostImage(image, "jdk-25", upstream);

    get(image, "jdk-25").statusCode(200);
    assertEquals(1, StubOciRegistry.INSTANCE.manifestGets());

    get(image, "jdk-25").statusCode(200).header("Docker-Content-Digest", upstream.manifestDigest());
    assertEquals(1, StubOciRegistry.INSTANCE.manifestHeads(), "expiry costs one HEAD");
    assertEquals(1, StubOciRegistry.INSTANCE.manifestGets(), "and no second transfer");
  }

  @Test
  void aTagThatMovedUpstreamIsRefetchedAndThePointerFollows() {
    // The reason a tag has a TTL at all: jdk-25 and 9.6 move under toolchain and security updates,
    // and TTL-plus-revalidate is what keeps builds current without anyone maintaining a list.
    String image = image("moving");
    TinyImage first = TinyImage.of(image + "-v1");
    StubOciRegistry.INSTANCE.hostImage(image, "jdk-25", first);
    get(image, "jdk-25").statusCode(200).header("Docker-Content-Digest", first.manifestDigest());

    TinyImage second = TinyImage.of(image + "-v2");
    StubOciRegistry.INSTANCE.hostImage(image, "jdk-25", second);

    get(image, "jdk-25").statusCode(200).header("Docker-Content-Digest", second.manifestDigest());
    assertEquals(2, StubOciRegistry.INSTANCE.manifestGets(), "a moved digest costs one transfer");

    // And the superseded manifest is still reachable by digest — nothing here deletes.
    given()
        .urlEncodingEnabled(false)
        .when()
        .get("/v2/quay/" + image + "/manifests/" + first.manifestDigest())
        .then()
        .statusCode(200);
  }

  @Test
  void anUnreachableUpstreamServesTheStaleCopy() {
    String image = image("stale");
    TinyImage upstream = TinyImage.of(image);
    StubOciRegistry.INSTANCE.hostImage(image, "jdk-25", upstream);
    get(image, "jdk-25").statusCode(200);

    StubOciRegistry.INSTANCE.reachable(false);
    get(image, "jdk-25")
        .statusCode(200)
        .header("Docker-Content-Digest", upstream.manifestDigest());
  }

  @Test
  void aColdMissAgainstAnUnreachableUpstreamIsA502ThatSaysSo() {
    // Not a 404: nothing here knows whether the image exists, and saying "no such manifest" when
    // the truth is "I could not ask" sends a puller to debug the wrong registry. Not a 500 either —
    // a network miss is not this service failing.
    StubOciRegistry.INSTANCE.reachable(false);
    get(image("never-seen"), "jdk-25")
        .statusCode(502)
        .body("errors[0].message", containsString("is unreachable and this manifest is not cached"))
        .body("errors[0].detail.upstream", equalTo("quay.io"));
  }

  @Test
  void anUpstreamThatAcceptsAndThenGoesQuietIsBoundedByTheRequestTimeout() {
    // The blast-radius bound. A connection that is accepted and then never answered is the failure
    // a connect timeout does not catch, and the one that would pin a worker thread under every
    // service build once the FROM lines point here.
    StubOciRegistry.INSTANCE.stall(Duration.ofSeconds(20).toMillis());

    long startedAt = System.nanoTime();
    get(image("black-hole"), "jdk-25")
        .statusCode(502)
        .body("errors[0].detail.upstream", equalTo("quay.io"));
    Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);

    assertTrue(
        waited.compareTo(Duration.ofSeconds(10)) < 0,
        "the one-second manifest timeout must have ended this, not the stall — waited " + waited);
  }

  private static String image(String what) {
    return "quarkus/" + what + "-" + RUN + "-" + UNIQUE.incrementAndGet();
  }

  private static io.restassured.response.ValidatableResponse get(String image, String reference) {
    return given().when().get("/v2/quay/" + image + "/manifests/" + reference).then();
  }
}
