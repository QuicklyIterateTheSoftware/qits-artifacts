package eu.wohlben.qits.maven;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.control.LiveBlobCensus;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.gc.CacheEvictionStrategy;
import eu.wohlben.qits.artifacts.gc.GcPinned;
import eu.wohlben.qits.artifacts.gc.GcStrategy;
import eu.wohlben.qits.artifacts.gc.MavenProxyGcAdapter;
import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import eu.wohlben.qits.artifacts.persistence.MavenArtifactRepository;
import eu.wohlben.qits.artifacts.persistence.MavenProxyMetadataRepository;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The maven pull-through cache, against an in-process upstream.
 *
 * <p>Never against repo1.maven.org: this repo's suite runs from a bare clone with no network, so a
 * test that reached the real thing would fail offline and pass in CI for reasons that have nothing
 * to do with this code. {@link StubMavenRepository} is also the only way to <em>count</em> upstream
 * requests, which is what every caching claim here actually rests on.
 *
 * <p><b>Fixture content is unique per RUN, not merely per test.</b> Nothing wipes {@code
 * target/artifacts-svc-test-blobs} between runs and blobs dedupe globally, so reusing an earlier
 * run's bytes makes a fetch a blob-store hit and the count comes out one short with nothing in the
 * failure to say why — the mirror suites' {@code RUN} salt, here for the same reason.
 *
 * <p>The metadata TTL is left at its shipped default, so these are the cache-<b>hit</b> cases;
 * {@code MavenProxyMetadataTest} runs the same stack with the TTL at zero and covers expiry.
 */
@QuarkusTest
@TestProfile(MavenProxyTest.ProxiedUpstream.class)
class MavenProxyTest {

  private static final AtomicInteger UNIQUE = new AtomicInteger();
  private static final String RUN = Long.toHexString(System.nanoTime());

  private static final String PROXY = "central";
  private static final String GROUP_PATH = "org/example";
  private static final String GROUP_ID = "org.example";

  /**
   * Points the proxy at the stub. The stub's port is only knowable at runtime, which is why it is a
   * process-wide singleton: this method runs while Quarkus is starting, before any {@code @BeforeAll}.
   */
  public static class ProxiedUpstream implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.artifacts.maven.proxy.upstream", StubMavenRepository.INSTANCE.baseUrl());
    }
  }

  @Inject MavenArtifactRepository artifacts;
  @Inject MavenProxyMetadataRepository metadata;
  @Inject MavenProxyGcAdapter proxyAdapter;
  @Inject LiveBlobCensus census;

  @TestHTTPResource("/")
  URL root;

  @BeforeEach
  void ensureRepositoriesAndUpstream() {
    ensure(PROXY, "maven-proxy");
    ensure("maven", "maven-packages");
    StubMavenRepository.INSTANCE.reset();
  }

  // --- immutable paths --------------------------------------------------------------------------

  @Test
  void anArtifactIsFetchedOnceAndThenServedFromDisk() {
    Coordinate subject = upstreamArtifact();

    try (MavenClient maven = client()) {
      HttpResponse<byte[]> first = maven.get(PROXY, subject.jarPath());
      assertEquals(200, first.statusCode());
      assertArrayEquals(subject.jar(), first.body());
      assertEquals(1, StubMavenRepository.INSTANCE.fileRequests());

      // Every path but maven-metadata.xml is immutable, so the second resolve is a sendFile off
      // local disk — the whole point of the cache, and what makes a build stop paying Central per
      // run.
      assertArrayEquals(subject.jar(), maven.get(PROXY, subject.jarPath()).body());
      assertEquals(
          1,
          StubMavenRepository.INSTANCE.fileRequests(),
          "a cached artifact must never be refetched");
    }
  }

  @Test
  void upstreamsOwnChecksumFileIsCachedRatherThanRecomputedHere() {
    // The end-to-end verification argument, proved the only way it can be: upstream hosts a .sha1
    // that does NOT match its jar, and the proxy hands it over unchanged. A proxy that derived the
    // checksum locally would answer the jar's real hash here — agreeing with itself whatever
    // arrived, which removes the client's check while looking like it kept it.
    Coordinate subject = upstreamArtifact();
    String wrong = "0".repeat(40);
    StubMavenRepository.INSTANCE.hostFile(
        subject.jarPath() + ".sha1", wrong.getBytes(StandardCharsets.UTF_8));

    try (MavenClient maven = client()) {
      HttpResponse<String> served = maven.getText(PROXY, subject.jarPath() + ".sha1");
      assertEquals(200, served.statusCode());
      assertEquals(wrong, served.body().trim(), "upstream's own checksum, byte for byte");
      assertFalse(
          TinyArtifact.hex(subject.jar(), "SHA-1").equals(served.body().trim()),
          "the fixture only means anything while the two differ");
    }
  }

  @Test
  void anArtifactUpstreamDoesNotHaveIs404AndNotA502() {
    // 404 means "upstream was asked and has no such thing", 502 means "upstream could not be asked".
    // Collapsing them sends whoever is debugging a failed build to the wrong repository.
    try (MavenClient maven = client()) {
      assertEquals(
          404, maven.get(PROXY, GROUP_PATH + "/nothing/1.0.0/nothing-1.0.0.jar").statusCode());
    }
  }

  @Test
  void anUncachedArtifactFromADeadUpstreamIsA502RatherThanA500() {
    Coordinate subject = upstreamArtifact();
    StubMavenRepository.INSTANCE.reachable(false);

    try (MavenClient maven = client()) {
      assertEquals(
          502, maven.get(PROXY, subject.jarPath()).statusCode(), "upstream's failure says so");
    }
  }

  @Test
  void aCachedArtifactSurvivesAnUpstreamOutageEntirely() {
    Coordinate subject = upstreamArtifact();

    try (MavenClient maven = client()) {
      assertEquals(200, maven.get(PROXY, subject.jarPath()).statusCode());

      StubMavenRepository.INSTANCE.reachable(false);
      HttpResponse<byte[]> served = maven.get(PROXY, subject.jarPath());
      assertEquals(200, served.statusCode(), "a cached artifact needs no upstream at all");
      assertArrayEquals(subject.jar(), served.body());
    }
  }

  // --- the mutable document ---------------------------------------------------------------------

  @Test
  void metadataIsFetchedOnceAndThenServedFromCache() {
    Coordinate subject = upstreamArtifact();
    StubMavenRepository.INSTANCE.hostMetadata(
        subject.metadataPath(),
        StubMavenRepository.metadataDocument(GROUP_ID, subject.artifactId(), "1.0.0"));

    try (MavenClient maven = client()) {
      HttpResponse<String> first = maven.getText(PROXY, subject.metadataPath());
      assertEquals(200, first.statusCode());
      assertTrue(first.body().contains("<version>1.0.0</version>"), first.body());
      assertEquals(1, StubMavenRepository.INSTANCE.metadataRequests());

      maven.getText(PROXY, subject.metadataPath());
      assertEquals(
          1,
          StubMavenRepository.INSTANCE.metadataRequests(),
          "a document within its TTL must not reach upstream at all");
    }
  }

  @Test
  void theMetadataChecksumIsDerivedFromTheCachedDocumentRatherThanProxied() {
    // The one hash this proxy computes, and the reason it must. Upstream's own
    // maven-metadata.xml.sha1 is a hash of whatever its document says NOW — a different document
    // from the one inside our TTL the moment a version is released — so proxying it would hand
    // every client a checksum that does not match the bytes beside it. Upstream here hosts a
    // deliberately wrong one; the answer must be the cached document's own hash, and the .sha1 path
    // must never be requested at all.
    Coordinate subject = upstreamArtifact();
    String document =
        StubMavenRepository.metadataDocument(GROUP_ID, subject.artifactId(), "1.0.0");
    StubMavenRepository.INSTANCE.hostMetadata(subject.metadataPath(), document);
    StubMavenRepository.INSTANCE.hostFile(
        subject.metadataPath() + ".sha1", "0".repeat(40).getBytes(StandardCharsets.UTF_8));

    try (MavenClient maven = client()) {
      String served = maven.getText(PROXY, subject.metadataPath()).body();
      HttpResponse<String> checksum = maven.getText(PROXY, subject.metadataPath() + ".sha1");

      assertEquals(200, checksum.statusCode());
      assertEquals(
          TinyArtifact.hex(served.getBytes(StandardCharsets.UTF_8), "SHA-1"),
          checksum.body().trim(),
          "the checksum is of the bytes served beside it, so the two cannot disagree");
      assertEquals(
          0,
          StubMavenRepository.INSTANCE.fileRequests(),
          "upstream's copy of that checksum is never even asked for");
    }
  }

  // --- what the cache is refused ----------------------------------------------------------------

  @Test
  void deployingToTheProxyIsRefusedBeforeAnythingIsFetched() {
    Coordinate subject = new Coordinate("refused-" + RUN + "-" + UNIQUE.incrementAndGet());
    try (MavenClient maven = client()) {
      HttpResponse<String> refused = maven.put(PROXY, subject.jarPath(), subject.jar());
      assertEquals(405, refused.statusCode());
      assertTrue(refused.body().contains("pull-through cache"), refused.body());
      assertEquals(
          0,
          StubMavenRepository.INSTANCE.fileRequests() + StubMavenRepository.INSTANCE.metadataRequests(),
          "the type check must happen before any upstream work");
    }
  }

  // --- what the collector sees ------------------------------------------------------------------

  @Test
  void theCensusAttributesACachedFileToTheProxyTypeAndNotToTheHostedOne() {
    // One table holds both maven types, so attribution by the repository row's TYPE is the whole
    // safety property here — a leak either way would put the platform's own published library under
    // a cache's eviction rule, or a cached jar under the release belt.
    Coordinate subject = upstreamArtifact();
    try (MavenClient maven = client()) {
      assertEquals(200, maven.get(PROXY, subject.jarPath()).statusCode());
    }
    String blobId = artifacts.findOne(PROXY, subject.jarPath()).orElseThrow().blobId;

    LiveBlobCensus.Census taken = census.take();
    assertTrue(taken.live(RepositoryType.MAVEN_PROXY).containsKey(blobId), "cached is live");
    assertFalse(
        taken.live(RepositoryType.MAVEN_PACKAGES).containsKey(blobId),
        "and it is not the hosted repository's");
    assertFalse(taken.rowless().contains(blobId), "a cached jar is never an orphan");
  }

  @Test
  void aResolveMovesTheAccessTimeTheEvictionWindowIsMeasuredAgainst() {
    // Without this the cache engine reads every cached file as last wanted when it was fetched, and
    // a dependency every build resolves ages out on schedule.
    Coordinate subject = upstreamArtifact();
    try (MavenClient maven = client()) {
      assertEquals(200, maven.get(PROXY, subject.jarPath()).statusCode());
      artifacts.getEntityManager().clear();
      Instant first = artifacts.findOne(PROXY, subject.jarPath()).orElseThrow().accessedAt;
      assertNotNull(first, "the fetch that writes the row is also its first access");

      assertEquals(200, maven.get(PROXY, subject.jarPath()).statusCode());
      artifacts.getEntityManager().clear();
      assertEquals(
          first,
          artifacts.findOne(PROXY, subject.jarPath()).orElseThrow().accessedAt,
          "writes are coalesced to one per row per hour, cached resolve included");
    }
  }

  @Test
  void anEvictedArtifactIsPulledThroughAgainAndReCached() {
    // Garbage collection's whole claim about this type, proved by COUNTING upstream requests. The
    // artifact and its document are cached, evicted through the real doors, and the next resolve
    // pays upstream exactly once more for each and lands back in the cache. A collector that broke
    // the re-fetch — a row half-removed, a refusal from the wrong door — shows up here as a 404 or
    // as a count that never moved.
    Coordinate subject = upstreamArtifact();
    StubMavenRepository.INSTANCE.hostMetadata(
        subject.metadataPath(),
        StubMavenRepository.metadataDocument(GROUP_ID, subject.artifactId(), "1.0.0"));

    try (MavenClient maven = client()) {
      assertEquals(200, maven.get(PROXY, subject.jarPath()).statusCode());
      assertEquals(200, maven.getText(PROXY, subject.metadataPath()).statusCode());
      assertEquals(1, StubMavenRepository.INSTANCE.fileRequests());
      assertEquals(1, StubMavenRepository.INSTANCE.metadataRequests());

      evict(subject);

      artifacts.getEntityManager().clear();
      assertTrue(artifacts.findOne(PROXY, subject.jarPath()).isEmpty(), "the row is gone");
      assertTrue(
          metadata.findOne(PROXY, subject.metadataPath()).isEmpty(), "the document is gone");

      assertArrayEquals(subject.jar(), maven.get(PROXY, subject.jarPath()).body());
      assertEquals(200, maven.getText(PROXY, subject.metadataPath()).statusCode());
      assertEquals(
          2,
          StubMavenRepository.INSTANCE.fileRequests(),
          "the evicted bytes are fetched again — the whole proof that eviction healed");
      assertEquals(2, StubMavenRepository.INSTANCE.metadataRequests());

      artifacts.getEntityManager().clear();
      assertTrue(
          artifacts.findOne(PROXY, subject.jarPath()).isPresent(),
          "re-cached, not merely served");
      assertTrue(metadata.findOne(PROXY, subject.metadataPath()).isPresent());
    }
  }

  // --- fixture ----------------------------------------------------------------------------------

  /**
   * Evicts one cached artifact the way a sweep does: the real engine over the real adapter, narrowed
   * to this case's own paths so the rest of the suite's cache is left alone.
   *
   * <p>The window is zero and the clock is a minute ahead, which is how a case gets a shipped P90D
   * rule to condemn something cached seconds ago without reconfiguring the deployment's policy. The
   * grace window answers "nothing is young" for the same reason: what is on trial here is the
   * re-fetch, and the window has its own cases in the gc module.
   */
  private void evict(Coordinate subject) {
    GcStrategy.Plan everything =
        new CacheEvictionStrategy()
            .plan(proxyAdapter, Duration.ZERO, Instant.now().plusSeconds(60), GcPinned.NONE);
    List<GcIdentity> mine =
        everything.dead().stream()
            .filter(
                dead ->
                    dead.identity().equals(subject.jarPath())
                        || dead.identity()
                            .equals(subject.metadataPath() + MavenProxyGcAdapter.METADATA))
            .toList();
    assertEquals(2, mine.size(), "the file and its document are both condemned");
    GcStrategy.Applied applied =
        proxyAdapter.delete(
            new GcStrategy.Plan(mine, List.of(), Set.of(), Set.of()), blobId -> false);
    assertEquals(List.of(), applied.errors(), "eviction goes through the proxy door cleanly");
    assertEquals(2, applied.deleted().size());
  }

  /** One artifact hosted upstream, with content nothing else in this run or any earlier one shares. */
  private Coordinate upstreamArtifact() {
    Coordinate subject = new Coordinate("lib-" + RUN + "-" + UNIQUE.incrementAndGet());
    StubMavenRepository.INSTANCE.hostFile(subject.jarPath(), subject.jar());
    return subject;
  }

  /** The three paths one test artifact is addressed by, and its bytes. */
  private record Coordinate(String artifactId) {

    byte[] jar() {
      return TinyArtifact.jar(artifactId);
    }

    String jarPath() {
      return GROUP_PATH + "/" + artifactId + "/1.0.0/" + artifactId + "-1.0.0.jar";
    }

    String metadataPath() {
      return GROUP_PATH + "/" + artifactId + "/maven-metadata.xml";
    }
  }

  private static void ensure(String repository, String type) {
    given()
        .contentType("application/json")
        .body("{\"type\":\"" + type + "\"}")
        .when()
        .put("/artifacts/api/repositories/" + repository)
        .then()
        .statusCode(200);
  }

  private MavenClient client() {
    return new MavenClient(URI.create(root.toString()));
  }
}
