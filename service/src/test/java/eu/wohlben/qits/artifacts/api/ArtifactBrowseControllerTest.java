package eu.wohlben.qits.artifacts.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

import eu.wohlben.qits.artifacts.control.ArtifactRepositoryService;
import eu.wohlben.qits.artifacts.control.BlobStore;
import eu.wohlben.qits.artifacts.control.OciMediaTypes;
import eu.wohlben.qits.artifacts.entity.NpmDistTag;
import eu.wohlben.qits.artifacts.entity.NpmVersion;
import eu.wohlben.qits.artifacts.entity.OciManifest;
import eu.wohlben.qits.artifacts.entity.OciTag;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.persistence.NpmDistTagRepository;
import eu.wohlben.qits.artifacts.persistence.NpmVersionRepository;
import eu.wohlben.qits.artifacts.persistence.OciManifestRepository;
import eu.wohlben.qits.artifacts.persistence.OciTagRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The six browse endpoints on the wire: the two ways a repository can be the wrong subject, and the
 * two names in this service that contain a slash.
 *
 * <p>Those names are the reason this suite exists at all. An OCI image name may have slashes in it
 * ({@code qits/build-images/ci-base} is repository {@code qits}, image {@code
 * build-images/ci-base}), and so does every scoped npm package. A browser sends them
 * percent-encoded and a person pastes them literally, and both have to reach the same row — which is
 * a property of the path templates and of nothing else, so it needs a request to prove.
 *
 * <p>RestAssured re-encodes a path by default, which would make every assertion below about
 * RestAssured rather than about the routes; {@code urlEncodingEnabled(false)} is what sends the
 * spelling the test names. The same rule the registry and npm suites carry.
 */
@QuarkusTest
class ArtifactBrowseControllerTest {

  private static final String IMAGE = "build-images/browse-it";
  private static final String PACKAGE = "@qits/browse-it";

  @Inject ArtifactRepositoryService repositoryService;
  @Inject BlobStore blobStore;
  @Inject OciManifestRepository manifests;
  @Inject OciTagRepository tags;
  @Inject NpmVersionRepository versions;
  @Inject NpmDistTagRepository distTags;

  @BeforeEach
  void seed() {
    repositoryService.ensure("qits", RepositoryType.OCI_IMAGES);
    repositoryService.ensure("npm", RepositoryType.NPM_PACKAGES);

    byte[] config = filled(5, (byte) 7);
    String configDigest = store(config);
    byte[] document =
        ("{\"schemaVersion\":2,\"mediaType\":\""
                + OciMediaTypes.OCI_MANIFEST_V1
                + "\",\"config\":{\"mediaType\":\"application/vnd.oci.image.config.v1+json\","
                + "\"digest\":\"sha256:"
                + configDigest
                + "\",\"size\":5},\"layers\":[]}")
            .getBytes(StandardCharsets.UTF_8);
    String manifestDigest = store(document);
    String tarball = store(filled(42, (byte) 8));

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              if (manifests.findOne("qits", IMAGE, manifestDigest).isEmpty()) {
                OciManifest manifest = new OciManifest();
                manifest.repository = "qits";
                manifest.imageName = IMAGE;
                manifest.digest = manifestDigest;
                manifest.mediaType = OciMediaTypes.OCI_MANIFEST_V1;
                manifest.size = document.length;
                manifest.createdAt = Instant.now();
                manifests.persist(manifest);
              }
              if (tags.findOne("qits", IMAGE, "v1").isEmpty()) {
                OciTag tag = new OciTag();
                tag.repository = "qits";
                tag.imageName = IMAGE;
                tag.tag = "v1";
                tag.manifestDigest = manifestDigest;
                tag.updatedAt = Instant.now();
                tags.persist(tag);
              }
              if (versions.findOne("npm", PACKAGE, "1.0.0").isEmpty()) {
                NpmVersion version = new NpmVersion();
                version.repository = "npm";
                version.packageName = PACKAGE;
                version.version = "1.0.0";
                version.tarballBlobId = tarball;
                version.manifestJson = "{}";
                version.createdAt = Instant.now();
                versions.persist(version);
              }
              if (distTags.findOne("npm", PACKAGE, "latest").isEmpty()) {
                NpmDistTag tag = new NpmDistTag();
                tag.repository = "npm";
                tag.packageName = PACKAGE;
                tag.tag = "latest";
                tag.version = "1.0.0";
                tag.updatedAt = Instant.now();
                distTags.persist(tag);
              }
            });
  }

  @Test
  void theRepositoryListCarriesATypeACountAndASize() {
    // The endpoint that already existed, extended. `type` was always there; the two numbers are new
    // and are what makes the listing a landing page rather than a set of names.
    given()
        .when()
        .get("/artifacts/api/repositories")
        .then()
        .statusCode(200)
        .body("repositories.name", hasItem("qits"))
        .body("repositories.find { it.name == 'qits' }.type", is("oci-images"))
        .body("repositories.find { it.name == 'qits' }.itemCount", greaterThanOrEqualTo(1))
        .body("repositories.find { it.name == 'qits' }.sizeBytes", notNullValue())
        .body("repositories.itemCount", everyItem(greaterThanOrEqualTo(0)));
  }

  @Test
  void imagesAreListedForAnImageRepository() {
    given()
        .when()
        .get("/artifacts/api/repositories/qits/images")
        .then()
        .statusCode(200)
        .body("images.name", hasItem(IMAGE))
        .body("images.find { it.name == '" + IMAGE + "' }.tagCount", is(1))
        .body("images.find { it.name == '" + IMAGE + "' }.manifestCount", is(1));
  }

  @Test
  void anImageNameWithASlashResolvesEncodedAndLiteralAlike() {
    // Half of why the template is `.+` rather than a single segment. The size is the manifest plus
    // its 5-byte config, which also proves the footprint reached the stored document.
    for (String spelling : new String[] {IMAGE.replace("/", "%2F"), IMAGE}) {
      given()
          .urlEncodingEnabled(false)
          .when()
          .get("/artifacts/api/repositories/qits/images/" + spelling + "/tags")
          .then()
          .statusCode(200)
          .body("tags", hasSize(1))
          .body("tags[0].tag", is("v1"))
          .body("tags[0].digest", startsWith("sha256:"))
          .body("tags[0].sizeBytes", greaterThanOrEqualTo(5))
          .body("tags[0].createdAt", notNullValue());
    }
  }

  @Test
  void manifestsExposeUntaggedReachabilityAndNullableAccess() {
    byte[] untaggedDocument =
        ("{\"schemaVersion\":2,\"mediaType\":\"" + OciMediaTypes.OCI_INDEX_V1 + "\",\"manifests\":[]}")
            .getBytes(StandardCharsets.UTF_8);
    String untaggedDigest = store(untaggedDocument);
    QuarkusTransaction.requiringNew().run(() -> {
      OciManifest row = new OciManifest();
      row.repository = "qits";
      row.imageName = IMAGE;
      row.digest = untaggedDigest;
      row.mediaType = OciMediaTypes.OCI_INDEX_V1;
      row.size = untaggedDocument.length;
      row.createdAt = Instant.now();
      manifests.persist(row);
    });
    given()
        .urlEncodingEnabled(false)
        .queryParam("never-accessed", "true")
        .when()
        .get("/artifacts/api/repositories/qits/images/" + IMAGE + "/manifests")
        .then()
        .statusCode(200)
        .body("manifests", hasSize(greaterThanOrEqualTo(2)))
        .body("manifests.digest", hasItem("sha256:" + untaggedDigest))
        .body(
            "manifests.find { it.digest == 'sha256:" + untaggedDigest + "' }.tags",
            hasSize(0))
        .body("manifests.mediaType", everyItem(notNullValue()))
        .body("manifests.createdAt", everyItem(notNullValue()));
  }

  @Test
  void packagesAreListedForAnNpmRepository() {
    given()
        .when()
        .get("/artifacts/api/repositories/npm/packages")
        .then()
        .statusCode(200)
        .body("packages.name", hasItem(PACKAGE))
        .body("packages.find { it.name == '" + PACKAGE + "' }.versionCount", is(1))
        .body("packages.find { it.name == '" + PACKAGE + "' }.latest", is("1.0.0"));
  }

  @Test
  void aScopedPackageNameResolvesEncodedAndLiteralAlike() {
    // The other half. npm itself sends the encoded spelling for a packument and the literal one in
    // the tarball urls it follows, so a browse API that accepted only one would be a third rule.
    for (String spelling : new String[] {PACKAGE.replace("/", "%2F"), PACKAGE}) {
      given()
          .urlEncodingEnabled(false)
          .when()
          .get("/artifacts/api/repositories/npm/packages/" + spelling + "/versions")
          .then()
          .statusCode(200)
          .body("versions", hasSize(1))
          .body("versions[0].version", is("1.0.0"))
          .body("versions[0].tarballSizeBytes", is(42))
          .body("versions[0].distTags", is(java.util.List.of("latest")))
          .body("versions[0].publishedAt", notNullValue());
    }
  }

  @Test
  void anUnknownRepositoryIsFourOhFourOnBothBrowseSurfaces() {
    given().when().get("/artifacts/api/repositories/no-such-repo/images").then().statusCode(404);
    given().when().get("/artifacts/api/repositories/no-such-repo/packages").then().statusCode(404);
    given()
        .when()
        .get("/artifacts/api/repositories/no-such-repo/images/anything/tags")
        .then()
        .statusCode(404);
  }

  @Test
  void aWrongTypedRepositoryIsFourHundredRatherThanAnEmptyList() {
    // The distinction that matters to a client: an npm repository has no images and never will, so
    // an empty list would read as an image registry that lost its images.
    given().when().get("/artifacts/api/repositories/npm/images").then().statusCode(400);
    given().when().get("/artifacts/api/repositories/qits/packages").then().statusCode(400);
  }

  @Test
  void theStoreSummaryNamesEveryFigure() {
    // Values depend on whatever else this JVM's suite has pushed, so the assertion is the contract
    // rather than the arithmetic — that lives in the artifacts module, over a fixture it owns.
    given()
        .when()
        .get("/artifacts/api/store/summary")
        .then()
        .statusCode(200)
        .body("ociPerImageSumBytes", greaterThanOrEqualTo(0))
        .body("ociUnionBytes", greaterThanOrEqualTo(0))
        .body("orphanBytes", greaterThanOrEqualTo(0))
        .body("npmPublishedBytes", greaterThanOrEqualTo(0))
        .body("npmProxyTarballBytes", greaterThanOrEqualTo(0))
        .body("npmProxyPackumentBytes", greaterThanOrEqualTo(0))
        .body("mavenPublishedBytes", greaterThanOrEqualTo(0))
        .body("daemonBinaryBytes", greaterThanOrEqualTo(0))
        .body("diskTotalBytes", greaterThanOrEqualTo(0));
  }

  private String store(byte[] bytes) {
    BlobStore.StagedBlob staged = blobStore.stage(new ByteArrayInputStream(bytes), Long.MAX_VALUE);
    blobStore.promote(staged);
    return staged.sha256();
  }

  private static byte[] filled(int length, byte value) {
    byte[] bytes = new byte[length];
    Arrays.fill(bytes, value);
    return bytes;
  }
}
