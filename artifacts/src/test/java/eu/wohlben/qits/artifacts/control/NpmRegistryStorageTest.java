package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.error.BadRequestException;
import eu.wohlben.qits.artifacts.error.NpmException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The npm side's storage layer: the two new repository types, and the three tables V3 adds.
 * Everything here is about persistence — the wire protocol lives in {@code eu.wohlben.qits.npm}.
 */
@QuarkusTest
class NpmRegistryStorageTest extends ArtifactsTestSupport {

  private static final String BLOB_A = "a".repeat(64);
  private static final String BLOB_B = "b".repeat(64);

  @Inject ArtifactRepositoryService repositoryService;

  @Inject NpmRegistryService npm;

  @Test
  void bothNpmTypesAreEnsuredLikeAnyOtherAndStayImmutable() {
    // V3 widens artifact_repository's check constraint; without it these inserts fail at the
    // database rather than at validation. And the two npm types are as immutable as every other —
    // which is what makes "a proxy rejects publishes" a property of the row rather than of config.
    assertEquals(
        RepositoryType.NPM_PACKAGES, repositoryService.ensure("npm", RepositoryType.NPM_PACKAGES).type);
    assertEquals(
        RepositoryType.NPM_PROXY, repositoryService.ensure("npmjs", RepositoryType.NPM_PROXY).type);
    assertThrows(
        BadRequestException.class, () -> repositoryService.ensure("npm", RepositoryType.NPM_PROXY));
  }

  @Test
  void theNpmProfilesAcceptNothingThroughTheValidatingUploadPath() {
    // Why a zero cap is safe on a protocol type, restated for npm: BlobService checks accepts()
    // before it ever reads maxBytes(), and both profiles accept nothing, so a stray POST to the
    // JSON blob API cannot reach the cap at all. The real cap is qits.artifacts.npm.max-publish-size.
    for (RepositoryType type : List.of(RepositoryType.NPM_PACKAGES, RepositoryType.NPM_PROXY)) {
      assertTrue(type.allowedMediaTypes().isEmpty());
      assertTrue(type.requiredMetadataKeys().isEmpty());
      assertEquals(0L, type.maxBytes());
      assertEquals(false, type.accepts("application/octet-stream"));
    }
  }

  @Test
  void theWireNamesAreTheKebabFormsTheApiAndTheSchemaUse() {
    assertEquals("npm-packages", RepositoryType.NPM_PACKAGES.wireName());
    assertEquals("npm-proxy", RepositoryType.NPM_PROXY.wireName());
    assertEquals(RepositoryType.NPM_PACKAGES, RepositoryType.fromWire("npm-packages"));
    assertEquals(RepositoryType.NPM_PROXY, RepositoryType.fromWire("npm-proxy"));
  }

  @Test
  void requiringARepositoryIsByTypeAndNotMerelyByExistence() {
    repositoryService.ensure("npm", RepositoryType.NPM_PACKAGES);
    repositoryService.ensure("qits", RepositoryType.OCI_IMAGES);

    assertEquals(RepositoryType.NPM_PACKAGES, npm.requireNpmRepository("npm"));
    assertThrows(NpmException.class, () -> npm.requireNpmRepository("qits"));
    assertThrows(NpmException.class, () -> npm.requireNpmRepository("no-such-row"));
    assertThrows(NpmException.class, () -> npm.requireNpmRepository(null));
  }

  @Test
  void publishingWritesTheVersionAndMovesTheTagsThatNameIt() {
    repositoryService.ensure("npm", RepositoryType.NPM_PACKAGES);
    npm.publish("npm", "@qits/angular", "1.0.0", BLOB_A, "sha512-aaa", "aaa", "{\"a\":1}",
        Map.of("latest", "1.0.0"));

    assertEquals(Map.of("latest", "1.0.0"), npm.distTags("npm", "@qits/angular"));
    NpmRegistryService.StoredVersion stored =
        npm.findVersion("npm", "@qits/angular", "1.0.0").orElseThrow();
    assertEquals(BLOB_A, stored.tarballBlobId());
    assertEquals("sha512-aaa", stored.integrity());
    assertEquals("{\"a\":1}", stored.manifestJson(), "the manifest survives the CLOB round trip");
  }

  @Test
  void aVersionIsImmutableButATagIsNot() {
    // The npm restatement of the registry's append-only stance: exactly one mutable table, and it
    // is the dist-tag one. Publishing over a version is refused; `latest` moving is the normal case.
    repositoryService.ensure("npm", RepositoryType.NPM_PACKAGES);
    npm.publish("npm", "left-pad", "1.0.0", BLOB_A, "sha512-a", "a", "{}", Map.of("latest", "1.0.0"));

    assertThrows(
        NpmException.class,
        () ->
            npm.publish(
                "npm", "left-pad", "1.0.0", BLOB_B, "sha512-b", "b", "{}", Map.of("latest", "1.0.0")));

    npm.publish("npm", "left-pad", "1.1.0", BLOB_B, "sha512-b", "b", "{}", Map.of("latest", "1.1.0"));
    assertEquals(Map.of("latest", "1.1.0"), npm.distTags("npm", "left-pad"));
    assertEquals(
        List.of("1.0.0", "1.1.0"),
        npm.listVersions("npm", "left-pad").stream()
            .map(NpmRegistryService.StoredVersion::version)
            .toList(),
        "the version latest used to name stays installable");
  }

  @Test
  void aProxiedVersionIsRecordedOnceAndIsIdempotentAfterThat() {
    // Two concurrent installs of the same dependency are the normal case rather than the edge.
    repositoryService.ensure("npmjs", RepositoryType.NPM_PROXY);
    npm.recordProxiedVersion("npmjs", "left-pad", "1.3.0", BLOB_A, "sha512-up", "up", "{}");
    npm.recordProxiedVersion("npmjs", "left-pad", "1.3.0", BLOB_B, "sha512-other", "other", "{}");

    NpmRegistryService.StoredVersion stored =
        npm.findVersion("npmjs", "left-pad", "1.3.0").orElseThrow();
    assertEquals(BLOB_A, stored.tarballBlobId(), "the first write wins; a tarball is immutable");
    assertEquals("sha512-up", stored.integrity(), "upstream's integrity, re-emitted unmodified");
    assertTrue(npm.distTags("npmjs", "left-pad").isEmpty(), "a proxy stores no dist-tags of its own");
  }

  @Test
  void aCachedPackumentIsStoredVerbatimAndRevalidationOnlyMovesItsClock() {
    repositoryService.ensure("npmjs", RepositoryType.NPM_PROXY);
    Instant fetched = Instant.now().minusSeconds(600);
    npm.storeProxyPackument("npmjs", "left-pad", "{\"name\":\"left-pad\"}", "\"v1\"", fetched);

    // A 304 means the document did not change, so the revalidation path moves the clock and the
    // validator without touching the CLOB at all.
    npm.touchProxyPackument("npmjs", "left-pad", "\"v2\"", Instant.now());

    // Read ONCE, and only after the write. A read before it would be served from the session bound
    // to this test's request context and would still hold the pre-update row — which says nothing
    // about this service, since a route handler activates a fresh context (and therefore a fresh
    // session) per call. Asserting after the touch is also the stronger claim: the document is
    // proved to have survived a revalidation rather than merely to have been stored.
    NpmRegistryService.CachedPackument after =
        npm.findProxyPackument("npmjs", "left-pad").orElseThrow();
    assertEquals("{\"name\":\"left-pad\"}", after.doc(), "revalidation must not rewrite the document");
    assertEquals("\"v2\"", after.etag());
    assertTrue(
        after.fetchedAt().isAfter(fetched.plusSeconds(300)),
        "revalidating must move the clock, or every later request revalidates again: "
            + after.fetchedAt());
  }
}
