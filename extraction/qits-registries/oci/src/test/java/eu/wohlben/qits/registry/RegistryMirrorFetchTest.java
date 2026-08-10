package eu.wohlben.qits.registry;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.artifacts.control.OciMirrorUpstreams;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The miss path, end to end: fetch, verify, bind, serve — and then never again.
 *
 * <p>Every claim this cache makes is a claim about <b>upstream request counts</b>, so almost every
 * assertion here is one. A test that only checked the bytes came back would pass just as well
 * against a proxy that cached nothing, which is the failure mode worth catching.
 *
 * <p>The shipped TTL is in force (one hour), so a tag fetched in a test is fresh for the rest of it.
 * Expiry is {@code RegistryMirrorRevalidationTest}'s subject, for the reason the npm suite splits
 * the same way: proving expiry against the shipped hour would mean an hour-long test.
 *
 * <p>Image names are unique per test <b>and per run</b>, and both halves are load-bearing. The
 * upstream is reset before each test, but the registry under test is not: {@code clean-at-start}
 * wipes the tables once per run, and the blob directory under {@code target/} is not wiped at all.
 * So a name reused between tests would let one test serve another's cache, and content reused
 * between runs would leave yesterday's layer on disk — which is a blob-store <em>hit</em>, and
 * turns "the mirror fetched three things" into "the mirror fetched two things" with nothing in the
 * failure to say why. It cost an hour once; the {@link #RUN} salt is what stops it costing another.
 */
@QuarkusTest
@TestProfile(RegistryMirrorFetchTest.AgainstTheStubUpstream.class)
class RegistryMirrorFetchTest {

  private static final AtomicInteger UNIQUE = new AtomicInteger();

  /** New every JVM, so no image built here has ever been staged in {@code target/} before. */
  private static final String RUN = java.util.UUID.randomUUID().toString().substring(0, 8);

  public static class AgainstTheStubUpstream implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      // Every upstream — quay.io, docker.io, Red Hat — is dialled at the in-process stub. The
      // suite's default for this key is a closed port; opting in is deliberate and explicit.
      return Map.of(
          "qits.artifacts.oci.mirror.endpoint-override", StubOciRegistry.INSTANCE.baseUrl());
    }
  }

  @TestHTTPResource("/")
  URL root;

  @Inject OciMirrorUpstreams upstreams;

  @BeforeEach
  void registerUpstreamsAndResetUpstreamState() {
    register("quay.io", "quay");
    register("docker.io", "hub");
    StubOciRegistry.INSTANCE.reset();
  }

  @Test
  void aTagMissIsFetchedVerifiedBoundAndServed_andTheNextPullTouchesNoUpstream() {
    String image = "quarkus/builder-" + unique();
    TinyImage upstream = TinyImage.of(image);
    StubOciRegistry.INSTANCE.hostImage(image, "jdk-25", upstream);

    byte[] served =
        given()
            .when()
            .get("/v2/quay/" + image + "/manifests/jdk-25")
            .then()
            .statusCode(200)
            .header("Docker-Content-Digest", upstream.manifestDigest())
            .header("Content-Type", upstream.manifestMediaType())
            .extract()
            .asByteArray();

    // Byte-for-byte, because a manifest's digest covers its literal whitespace: a mirror that
    // re-serialised would serve a document nobody can address.
    assertArrayEquals(upstream.manifest(), served);
    assertEquals(1, StubOciRegistry.INSTANCE.manifestGets(), "the miss costs exactly one fetch");

    given().when().get("/v2/quay/" + image + "/manifests/jdk-25").then().statusCode(200);
    assertEquals(
        1,
        StubOciRegistry.INSTANCE.manifestGets(),
        "a tag inside its TTL is served from disk with no upstream traffic at all");
    assertEquals(0, StubOciRegistry.INSTANCE.manifestHeads(), "and not even a revalidation");
  }

  @Test
  void aWholeImageRoundTripsThroughTheMirrorAndTheSecondPullFetchesNothing() {
    // The feature, in one assertion pair: three upstream requests the first time — the manifest,
    // the config blob and the layer — and none at all the second. This is the same shape as the
    // live `docker pull` / `docker rmi` / `docker pull` proof, driven by a client that re-verifies
    // every digest it is handed.
    String image = "quarkus/roundtrip-" + unique();
    TinyImage upstream = TinyImage.of(image);
    StubOciRegistry.INSTANCE.hostImage(image, "jdk-25", upstream);

    try (OciClient client = new OciClient(URI.create(root.toString()))) {
      TinyImage pulled = client.pull("quay/" + image, "jdk-25");
      assertArrayEquals(upstream.manifest(), pulled.manifest());
      assertArrayEquals(upstream.layer().bytes(), pulled.layer().bytes());
      assertArrayEquals(upstream.config().bytes(), pulled.config().bytes());
      assertEquals(3, StubOciRegistry.INSTANCE.fetches(), "manifest + config + layer, once each");

      TinyImage again = client.pull("quay/" + image, "jdk-25");
      assertArrayEquals(upstream.manifest(), again.manifest());
      assertEquals(
          3,
          StubOciRegistry.INSTANCE.fetches(),
          "everything the second pull needs is already on this disk");
    }
  }

  @Test
  void aManifestAskedForByDigestIsFetchedOnceAndRevalidatedNever() {
    // The immutability half of the design: a digest cannot come to mean other bytes, so there is
    // nothing for a TTL to protect and no HEAD worth sending, ever.
    String image = "quarkus/by-digest-" + unique();
    TinyImage upstream = TinyImage.of(image);
    StubOciRegistry.INSTANCE.hostImage(image, "jdk-25", upstream);

    for (int attempt = 0; attempt < 2; attempt++) {
      given()
          .urlEncodingEnabled(false)
          .when()
          .get("/v2/quay/" + image + "/manifests/" + upstream.manifestDigest())
          .then()
          .statusCode(200)
          .header("Docker-Content-Digest", upstream.manifestDigest());
    }
    assertEquals(1, StubOciRegistry.INSTANCE.manifestGets());
    assertEquals(0, StubOciRegistry.INSTANCE.manifestHeads());
  }

  @Test
  void aBlobWhoseStreamDoesNotHashToItsDigestIsRefusedAndNothingIsStored() {
    // The one property that makes trusting an upstream unnecessary. BlobStore hashes while it
    // streams, so this costs nothing and is never skipped — and the refusal must leave no trace,
    // or the mirror would serve the corruption from cache for ever after.
    String image = "quarkus/corrupt-" + unique();
    TinyImage upstream = TinyImage.of(image);
    StubOciRegistry.INSTANCE.hostImage(image, "jdk-25", upstream);
    StubOciRegistry.INSTANCE.corrupt(upstream.layer().digest());

    given()
        .urlEncodingEnabled(false)
        .when()
        .get("/v2/quay/" + image + "/blobs/" + upstream.layer().digest())
        .then()
        .statusCode(502)
        .body("errors[0].code", equalTo("DIGEST_INVALID"))
        .body("errors[0].message", containsString("do not hash to the digest requested"))
        .body("errors[0].detail.expected", equalTo(upstream.layer().digest()));

    assertEquals(1, StubOciRegistry.INSTANCE.blobGets());
    given()
        .urlEncodingEnabled(false)
        .when()
        .get("/v2/quay/" + image + "/blobs/" + upstream.layer().digest())
        .then()
        .statusCode(502);
    assertEquals(
        2,
        StubOciRegistry.INSTANCE.blobGets(),
        "the second request had to ask upstream again — nothing was kept from the first");
  }

  @Test
  void anIndexIsCachedBeforeAnyChildIsFetchedAndAChildNobodyAsksForIsNeverPaidFor() {
    // The lazy child order, which is also the rate-limit-correct one: a multi-arch pull costs one
    // upstream request per architecture actually fetched. The push path's requireReferencesExist is
    // deliberately not applied here, so an index binds with no child present — the normal state of
    // a lazily pulled image, and the state BW's census was made to tolerate.
    String image = "quarkus/multiarch-" + unique();
    TinyImage amd64 = TinyImage.of(image + "-amd64");
    TinyImage arm64 = TinyImage.of(image + "-arm64");
    byte[] index = TinyImage.index(amd64, arm64);
    StubOciRegistry.INSTANCE.hostIndex(image, "jdk-25", index, amd64, arm64);

    given()
        .when()
        .get("/v2/quay/" + image + "/manifests/jdk-25")
        .then()
        .statusCode(200)
        .header("Docker-Content-Digest", TinyImage.digest(index))
        .header("Content-Type", TinyImage.INDEX_TYPE);
    assertEquals(1, StubOciRegistry.INSTANCE.manifestGets(), "the index, and nothing under it");

    given()
        .urlEncodingEnabled(false)
        .when()
        .get("/v2/quay/" + image + "/manifests/" + amd64.manifestDigest())
        .then()
        .statusCode(200)
        .header("Docker-Content-Digest", amd64.manifestDigest());
    assertEquals(2, StubOciRegistry.INSTANCE.manifestGets(), "one child, on demand");
    assertEquals(0, StubOciRegistry.INSTANCE.blobGets(), "and still none of its layers");

    // The index itself is now a hit, and the architecture nobody asked for was never fetched.
    given().when().get("/v2/quay/" + image + "/manifests/jdk-25").then().statusCode(200);
    assertEquals(2, StubOciRegistry.INSTANCE.manifestGets());
  }

  @Test
  void aBearerChallengeIsAnsweredAnonymouslyAndTheTokenIsReusedAcrossTheWholePull() {
    // Docker Hub demands the token dance even for a public image; quay and Red Hat mostly do not.
    // One client covers both because it sends bare first and only pays the hop when challenged —
    // and caches per scope, or a twelve-layer pull would cost thirteen token requests.
    String image = "library/challenged-" + unique();
    TinyImage upstream = TinyImage.of(image);
    StubOciRegistry.INSTANCE.hostImage(image, "latest", upstream);
    StubOciRegistry.INSTANCE.requireAuth(true);

    try (OciClient client = new OciClient(URI.create(root.toString()))) {
      TinyImage pulled = client.pull("hub/" + image, "latest");
      assertArrayEquals(upstream.manifest(), pulled.manifest());
    }
    assertEquals(3, StubOciRegistry.INSTANCE.fetches(), "manifest + config + layer");
    assertEquals(
        1,
        StubOciRegistry.INSTANCE.tokenRequests(),
        "one token for the whole pull: the scope is the repository, not the object");
  }

  @Test
  void anUpstreamThatHasNoSuchManifestIsA404NamingTheRegistryThatWasAsked() {
    // Not a 502 — the upstream answered perfectly well. Naming it is what stops a puller debugging
    // this mirror for something the mirror asked about and was told does not exist.
    given()
        .when()
        .get("/v2/quay/quarkus/never-published-" + UNIQUE.incrementAndGet() + "/manifests/jdk-25")
        .then()
        .statusCode(404)
        .body("errors[0].code", equalTo("MANIFEST_UNKNOWN"))
        .body("errors[0].message", containsString("quay.io has no such manifest"))
        .body("errors[0].detail.upstream", equalTo("quay.io"))
        .body("errors[0].detail.namespace", equalTo("quay"));
  }

  @Test
  void allThreeHubSpellingsShareOneCacheEntryAndThereforeOneUpstreamFetch() {
    // `hub/alpine`, `hub/library/alpine` and the bare `library/alpine` a registry-mirrors-configured
    // daemon asks for are one image. If they were not, the first pull of each would pay its own
    // fetch and store the same bytes under three names.
    String suffix = "alpine-" + unique();
    TinyImage upstream = TinyImage.of(suffix);
    StubOciRegistry.INSTANCE.hostImage("library/" + suffix, "latest", upstream);

    for (String spelling :
        new String[] {"hub/" + suffix, "hub/library/" + suffix, "library/" + suffix}) {
      given()
          .when()
          .get("/v2/" + spelling + "/manifests/latest")
          .then()
          .statusCode(200)
          .header("Docker-Content-Digest", upstream.manifestDigest());
    }
    assertEquals(
        1,
        StubOciRegistry.INSTANCE.manifestGets(),
        "three spellings, one image, one upstream request");
  }

  /** A name no other test, and no other run of this suite, has ever used. */
  private static String unique() {
    return RUN + "-" + UNIQUE.incrementAndGet();
  }

  /**
   * The upstream pairing, through the bean the JSON admin endpoint is thin over — that endpoint is
   * a service's surface, not this lib's.
   */
  private void register(String domain, String slug) {
    upstreams.ensure(domain, slug);
  }
}
