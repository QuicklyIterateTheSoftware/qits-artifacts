package eu.wohlben.qits;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.maven.MavenClient;
import eu.wohlben.qits.maven.TinyArtifact;
import eu.wohlben.qits.npm.NpmClient;
import eu.wohlben.qits.npm.TinyPackage;
import eu.wohlben.qits.registry.OciClient;
import eu.wohlben.qits.registry.TinyImage;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Everything the {@code @QuarkusTest} suite cannot prove: that the <b>packaged process</b> serves
 * what the sources say it serves. Under {@code -Dnative} that process is {@code
 * service/target/qits-artifacts}, and this is the only place a GraalVM binary is exercised at all —
 * native-image resolves reflection, {@code ServiceLoader} and resource lookups at build time, so a
 * gap in any of them leaves the JVM suite green and fails only in the binary, at runtime.
 *
 * <p>Deliberately <b>one</b> class spanning both packages rather than one per context, because the
 * thing under test is the single process: {@code /artifacts/} is Quinoa's static SPA, {@code
 * /artifacts/api/**} is JAX-RS, {@code /artifacts/q/**} is Quarkus' non-application root, and
 * {@code /artifacts/git/**}, {@code /artifacts/npm/**}, {@code /artifacts/maven/**} and {@code
 * /v2/**} are four independent raw-Vert.x route stacks — and what needs proving is that all of
 * them resolve in the same binary.
 * Splitting it by package would split the subject.
 *
 * <p>The SPA is here rather than in the {@code @QuarkusTest} suite because it <b>cannot</b> be
 * there: Quinoa logs "Quinoa is disabled by default in tests" and registers neither the static
 * resources nor the SPA re-route, so a unit test asserting any of this would pass against a process
 * that has no web UI in it at all. This is the only suite that sees the real thing.
 *
 * <p>JGit is the reason this exists. It is not a Quarkus extension, so nothing registers its
 * {@code ServiceLoader} providers or its {@code JGitText} resource bundle for native-image on its
 * behalf; a clone/push round trip through {@code UploadPack}/{@code ReceivePack} is the only check
 * that the registrations in {@code application.properties} are actually sufficient.
 */
@QuarkusIntegrationTest
@TestProfile(PackagedProcessIT.TargetDirState.class)
class PackagedProcessIT {

  /**
   * Points the process' three on-disk locations under {@code target/} instead of the {@code
   * ~/.qits} home the shipped defaults name — a test must never write to the developer's real data
   * directory, and a stale H2 file there would make this suite order-dependent.
   *
   * <p>All four are runtime config, so they reach the already-built artifact as {@code -D} flags;
   * nothing here re-augments. The paths are absolute because the launched process' working
   * directory is not this JVM's contract.
   */
  public static class TargetDirState implements QuarkusTestProfile {

    static final Path ROOT = Path.of(System.getProperty("user.dir"), "target", "packaged-it");

    @Override
    public Map<String, String> getConfigOverrides() {
      Map<String, String> overrides = new LinkedHashMap<>();
      // A FILE H2, not the in-memory one the unit suite uses, and embedded exactly as the shipped
      // default is: the file/embedded shape is the one the deployment runs, and `;AUTO_SERVER=TRUE`
      // is precisely what a native binary cannot open (see the artifacts jar's
      // microprofile-config.properties). Only the location moves.
      overrides.put(
          "quarkus.datasource.artifacts.jdbc.url", "jdbc:h2:file:" + ROOT.resolve("h2/artifacts"));
      overrides.put("quarkus.flyway.artifacts.clean-at-start", "true");
      overrides.put("qits.artifacts.blobs-dir", ROOT.resolve("blobs").toString());
      overrides.put("qits.repositories.data-dir", ROOT.resolve("repositories").toString());
      // No CI intake in this repo; the notifier is fire-and-forget, so a closed port is the honest
      // posture here exactly as it is in the unit suite.
      overrides.put("qits.ci.intake-url", "http://localhost:1/post-receive");
      // No qits-cd here either, and the shipped default names it by its qits-net alias — which on a
      // build machine resolves to whatever the resolver feels like, or hangs. A closed port makes
      // the fail-closed path deterministic while still driving a real HttpClient inside the binary.
      overrides.put("qits.artifacts.gc.oci.cd-base-url", "http://localhost:1/cd/api");
      // And the same for the mirror's three upstreams, where the shipped defaults name real public
      // registries: this key redirects every one of them, so a closed port is what keeps an IT from
      // dialling quay.io or Docker Hub. It still drives the binary's outbound HttpClient for real,
      // which is the only reason the miss path can be observed here at all.
      overrides.put("qits.artifacts.oci.mirror.endpoint-override", "http://localhost:1");
      return overrides;
    }
  }

  @TestHTTPResource("/artifacts/git")
  URL gitBase;

  @TestHTTPResource("/")
  URL root;

  @Test
  void theSpaIsServedAtTheSegmentWithAMatchingBaseHref() {
    // Quinoa builds src/main/webui (the qits-spa-artifacts submodule) during augmentation and the
    // files ship inside the artifact. The base href is the client's half of the same coupling:
    // Quinoa mounts the files at quarkus.quinoa.ui-root-path, but only the baseHref baked in by
    // `ng build` makes the browser ask for them there. Asserting both together is what makes a
    // one-sided move fail here instead of in a browser.
    given()
        .when()
        .get("/artifacts/")
        .then()
        .statusCode(200)
        .contentType(containsString("text/html"))
        .body(containsString("<base href=\"/artifacts/\">"));
  }

  @Test
  void aDeepLinkFallsBackToTheSpaButTheOtherStacksAreNotSwallowed() {
    // quarkus.quinoa.enable-spa-routing puts a catch-all at /artifacts/* near the end of the route
    // order: a client route has no file behind it and must reach index.html, or every reload and
    // every pasted link 404s.
    given()
        .when()
        .get("/artifacts/some/client/route")
        .then()
        .statusCode(200)
        .body(containsString("<base href=\"/artifacts/\">"));

    // The other half, and the reason quarkus.quinoa.ignored-path-prefixes is spelled out rather
    // than left to Quinoa's derivation. That derivation reads quarkus.rest.path and
    // quarkus.http.non-application-root-path — so /api and /q would be covered — but NOTHING names
    // /artifacts/git, which GitHostRoutes carries as a literal. The git host's six routes match
    // ahead of the catch-all on their own; what does not is the base BETWEEN them, which is
    // precisely the url qits-ci and qits-workspace-daemon hold as a contract. Un-ignored it answers
    // 200 text/html, and a git client told 200 HTML reports anything but "no such repository".
    given().when().get("/artifacts/git/" + UUID.randomUUID()).then().statusCode(404);

    // The npm case verbatim, one segment over: no config key names /maven either (MavenRoutes
    // spells it as a literal), so without the ignore a mistyped artifact path would answer 200
    // text/html and a maven client would report a corrupt jar.
    given().when().get("/artifacts/maven/maven").then().statusCode(404);

    // Setting the key REPLACED the derivation, so the two it used to supply are re-asserted here.
    given().when().get("/artifacts/api/repositories").then().statusCode(200);
    given().when().get("/artifacts/q/health/ready").then().statusCode(200);
  }

  @Test
  void frameworkSurfaceIsServedUnderTheQSegment() {
    // quarkus.http.non-application-root-path, and swagger-ui only because always-include=true
    // survives into a packaged (non-dev) build — the setting exists for exactly this.
    given().when().get("/artifacts/q/openapi").then().statusCode(200);
    given().when().get("/artifacts/q/swagger-ui/").then().statusCode(200);
  }

  @Test
  void theBlobStoreBootsItsOwnSchemaAndServesBytes() {
    // Reaching a repository row at all means Flyway migrated this process' own H2 file, the
    // startup seed wrote its rows through Panache, and Jackson serialised them back. `qits`, `npm`,
    // `npmjs` and `maven` are in the set because a packaged process is the only thing here that runs
    // the seed for real: they are what let a fresh deployment take a `docker push`, an `npm publish`
    // and an `mvn deploy` with no manual ensure call.
    given()
        .when()
        .get("/artifacts/api/repositories")
        .then()
        .statusCode(200)
        .body(
            "repositories.name",
            hasItems("ci-screenshots", "ci-videos", "qits", "npm", "npmjs", "maven"));

    byte[] png = png(120, 80);
    String id =
        given()
            .contentType("image/png")
            .headers(screenshotHeaders("main", "packaged-it"))
            .body(png)
            .when()
            .post("/artifacts/api/repositories/ci-screenshots/blobs")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    byte[] served =
        given()
            .when()
            .get("/artifacts/api/repositories/ci-screenshots/blobs/" + id)
            .then()
            .statusCode(200)
            .contentType("image/png")
            .extract()
            .asByteArray();
    assertArrayEquals(png, served, "the packaged process should serve back the exact bytes");
  }

  @Test
  void anUnknownRepositoryIsRejectedByTheRepositoryLifecycle() {
    // The exception mapper is JAX-RS machinery that native-image has to have kept: an unknown
    // repository must surface as 404, not as a 500 from a missing reflective constructor.
    given()
        .when()
        .get("/artifacts/api/repositories/no-such-repo/blobs/deadbeef")
        .then()
        .statusCode(404);
  }

  @Test
  void theRegistryIsMountedAtTheHostRootNotUnderTheArtifactsSegment() {
    // /v2 is a literal in the route code, like /artifacts/git — no config key moves it, and docker
    // and podman hardcode the prefix at the ROOT, so a drift to /artifacts/v2 would be invisible
    // everywhere except here.
    given()
        .when()
        .get("/v2/")
        .then()
        .statusCode(200)
        .header("Docker-Distribution-Api-Version", "registry/2.0");
    given().when().get("/artifacts/v2/").then().statusCode(404);
  }

  @Test
  void anImageRoundTripsThroughTheBinary() {
    // The registry's whole path in one test, and the parts of it that only a binary can falsify:
    // a blob is served with HttpServerResponse.sendFile — through Vert.x' FileResolver and a Netty
    // file region, neither of which behaves the same in a native image as on the JVM — and the
    // upload arrives CHUNKED, which is the encoding docker uses and the one the global wire ceiling
    // does not gate.
    ensureOciRepository();

    try (OciClient client = new OciClient(URI.create(root.toString()))) {
      TinyImage subject = TinyImage.of("packaged-it");
      client.push("qits/it", "latest", subject);

      TinyImage pulled = client.pull("qits/it", "latest");
      assertArrayEquals(subject.manifest(), pulled.manifest());
      assertArrayEquals(
          subject.layer().bytes(),
          pulled.layer().bytes(),
          "sendFile must return the exact bytes from the binary");
    }
  }

  @Test
  void anUnknownBlobAnswersTheSpecErrorEnvelopeRatherThanA500() {
    // The UploadResult lesson, restated for a raw Vert.x route: nothing in the build sees a type
    // that is only serialised inside such a handler, so a DTO there 500s in the binary while the
    // JVM suite stays green. Asserting the envelope's SHAPE is what catches that — a bare 404 would
    // not. (The registry builds this with JsonObject precisely so it cannot happen.)
    //
    // The repository has to exist for this to be a BLOB_UNKNOWN rather than a NAME_UNKNOWN; both
    // would prove the envelope, but a test that asserts one and accepts the other depending on
    // method order is not asserting anything.
    ensureOciRepository();
    given()
        .urlEncodingEnabled(false)
        .when()
        .get("/v2/qits/it/blobs/sha256:" + "0".repeat(64))
        .then()
        .statusCode(404)
        .contentType(containsString("json"))
        .body("errors[0].code", equalTo("BLOB_UNKNOWN"));
  }

  @Test
  void anNpmPackageRoundTripsThroughTheBinary() {
    // The third raw-Vert.x route stack, proved to coexist with /v2 and /artifacts/git in one
    // binary — and the parts of it only a binary can falsify: a scoped name arrives percent-encoded
    // and is matched by a regex route, the packument is built as a Jackson tree with no bound type
    // anywhere (the dto/UploadResult lesson), and the tarball comes back through
    // HttpServerResponse.sendFile, which behaves differently under native-image than on the JVM.
    //
    // The seed has already written the `npm` row in a packaged process; this states its own
    // precondition anyway rather than resting on another test's subject.
    given()
        .contentType("application/json")
        .body("{\"type\":\"npm-packages\"}")
        .when()
        .put("/artifacts/api/repositories/npm")
        .then()
        .statusCode(200);

    TinyPackage subject = TinyPackage.of("@qits/packaged-it", "1.0.0");
    try (NpmClient npm = new NpmClient(URI.create(root.toString()))) {
      assertEquals(
          201,
          npm.publish("npm", "@qits%2fpackaged-it", subject.publishDocument("latest")).statusCode());

      JsonNode packument = npm.packumentJson("npm", "@qits%2fpackaged-it");
      assertEquals("1.0.0", packument.path("dist-tags").path("latest").asText());
      assertArrayEquals(
          subject.tarball(),
          npm.tarball(NpmClient.tarballUrl(packument, "1.0.0")).body(),
          "sendFile must return the exact bytes from the binary");
    }
  }

  @Test
  void anUnknownNpmPackageAnswersNpmsErrorShapeRatherThanA500() {
    // The UploadResult lesson once more, on a third stack: the error envelope is built with a
    // JsonObject precisely so nothing has to be registered for reflection, and asserting its SHAPE
    // is what would catch a DTO creeping in — a bare 404 would not.
    given()
        .when()
        .get("/artifacts/npm/npm/no-such-package")
        .then()
        .statusCode(404)
        .contentType(containsString("json"))
        .body("error", containsString("no such package"));
  }

  @Test
  void aMavenReleaseDeploysAndResolvesThroughTheBinary() {
    // The fourth raw-Vert.x route stack, proved to coexist with /v2, /artifacts/npm and
    // /artifacts/git in one binary — and the parts of it only a binary can falsify: the deploy PUT
    // streams through VertxInputStream, the metadata document and the checksums are derived per
    // request, and the jar comes back through HttpServerResponse.sendFile, which behaves
    // differently under native-image than on the JVM.
    //
    // The seed has already written the `maven` row in a packaged process; this states its own
    // precondition anyway rather than resting on another test's subject.
    given()
        .contentType("application/json")
        .body("{\"type\":\"maven-packages\"}")
        .when()
        .put("/artifacts/api/repositories/maven")
        .then()
        .statusCode(200);

    try (MavenClient maven = new MavenClient(URI.create(root.toString()))) {
      String ga = "eu/wohlben/qits/packaged-it";
      String base = ga + "/1.0.0/packaged-it-1.0.0";
      byte[] jar = TinyArtifact.jar("packaged-it release");
      assertEquals(201, maven.put("maven", base + ".jar", jar).statusCode());
      assertEquals(
          201,
          maven.put(
                  "maven", base + ".pom",
                  TinyArtifact.pom("eu.wohlben.qits", "packaged-it", "1.0.0"))
              .statusCode());
      // The deploy plugin's last steps: the checksum claim, verified; the client's metadata merge,
      // accepted and discarded.
      assertEquals(
          201,
          maven.put("maven", base + ".jar.sha1", TinyArtifact.hex(jar, "SHA-1").getBytes())
              .statusCode());
      assertEquals(
          201,
          maven.put("maven", ga + "/maven-metadata.xml", "<metadata/>".getBytes()).statusCode());

      assertArrayEquals(
          jar,
          maven.get("maven", base + ".jar").body(),
          "sendFile must return the exact bytes from the binary");

      HttpResponse<String> metadata = maven.getText("maven", ga + "/maven-metadata.xml");
      assertEquals(200, metadata.statusCode(), metadata.body());
      assertTrue(metadata.body().contains("<release>1.0.0</release>"), metadata.body());
      assertTrue(metadata.body().contains("<version>1.0.0</version>"), metadata.body());

      assertEquals(
          TinyArtifact.hex(jar, "SHA-256"),
          maven.getText("maven", base + ".jar.sha256").body(),
          "the checksum is derived from the stored bytes, in the binary");
    }
  }

  @Test
  void aMavenSnapshotDeploysAndItsVersionMetadataDerivesThroughTheBinary() {
    // The ⚖1 flow end to end in the packaged process: a timestamped deploy as ordinary files, and
    // the version-level document derived from the names — the whole of the server's snapshot
    // machinery.
    try (MavenClient maven = new MavenClient(URI.create(root.toString()))) {
      String dir = "eu/wohlben/qits/packaged-it-snap/1.0.1-SNAPSHOT";
      byte[] jar = TinyArtifact.jar("packaged-it snapshot");
      assertEquals(
          201,
          maven.put(
                  "maven",
                  dir + "/packaged-it-snap-1.0.1-20260802.123456-1.jar",
                  jar)
              .statusCode());

      HttpResponse<String> metadata = maven.getText("maven", dir + "/maven-metadata.xml");
      assertEquals(200, metadata.statusCode(), metadata.body());
      assertTrue(
          metadata.body().contains("<value>1.0.1-20260802.123456-1</value>"), metadata.body());
      assertTrue(metadata.body().contains("<buildNumber>1</buildNumber>"), metadata.body());
    }
  }

  @Test
  void gitSmartHttpAdvertisesRefsFromTheBinary() throws Exception {
    // JGit's UploadPack running inside the compiled binary. A 404 here would mean the route is
    // missing; a 500 would mean JGit itself did not survive the compile.
    String repoId = seedOrigin();
    given()
        .when()
        .get("/artifacts/git/" + repoId + "/info/refs?service=git-upload-pack")
        .then()
        .statusCode(200)
        .contentType(containsString("git-upload-pack-advertisement"));
  }

  @Test
  void unknownRepoIdIs404FromTheGitHostRatherThanTheRouter() {
    // The distinction that matters when reading this result: an unroutable path would 404 from
    // Vert.x with no body handling at all, whereas the git host answers 404 for a well-formed id
    // it cannot open — and 403 for a well-formed id asked over dumb HTTP. Asserting the 403 is
    // what proves the handler, not the router, produced the 404 above it.
    given()
        .when()
        .get("/artifacts/git/" + UUID.randomUUID() + "/info/refs?service=git-upload-pack")
        .then()
        .statusCode(404);
  }

  @Test
  void dumbHttpIsRefusedByTheHandler() throws Exception {
    String repoId = seedOrigin();
    given().when().get("/artifacts/git/" + repoId + "/info/refs").then().statusCode(403);
  }

  @Test
  void cloneAndPushRoundTripAgainstTheBinary() throws Exception {
    // The whole wire protocol, both directions, driven by the real git CLI: UploadPack builds a
    // packfile and ReceivePack applies one. Nothing short of this exercises JGit's pack machinery.
    String repoId = seedOrigin();
    Path origin = TargetDirState.ROOT.resolve("repositories").resolve(repoId).resolve("origin");
    Path clone = Files.createTempDirectory("qits-artifacts-it-clone");
    Files.delete(clone);

    runGit(null, "git", "clone", gitBase + "/" + repoId, clone.toString());
    assertTrue(Files.exists(clone.resolve(".git")), "clone should have produced a working copy");

    String branch = runGit(clone, "git", "rev-parse", "--abbrev-ref", "HEAD").trim();
    Files.writeString(clone.resolve("pushed.txt"), "from the packaged binary\n");
    runGit(clone, "git", "add", "pushed.txt");
    runGit(clone, "git", "-c", "user.email=qits@local", "-c", "user.name=qits", "commit", "-m", "p");
    String pushedSha = runGit(clone, "git", "rev-parse", "HEAD").trim();
    runGit(clone, "git", "push", "origin", branch);

    String originSha = runGit(origin, "git", "rev-parse", "refs/heads/" + branch).trim();
    assertEquals(pushedSha, originSha, "push should have advanced the origin's branch ref");
  }

  @Test
  void theShippedDefaultsLeaveTheDefaultBranchUnprotected() throws Exception {
    // The trap this feature is shaped around, asserted against the artifact that actually ships:
    // qits-artifacts is the git host that serves its own redeploy, so a protection default of TRUE
    // in the packaged binary could refuse the very push that fixes it. Nothing here overrides
    // qits.repositories.git.protect-default-branch — this is the shipped value, and the roughest
    // push there is must still go through untouched.
    String repoId = seedOrigin();
    Path origin = TargetDirState.ROOT.resolve("repositories").resolve(repoId).resolve("origin");
    Path clone = Files.createTempDirectory("qits-artifacts-it-inert");
    Files.delete(clone);
    runGit(null, "git", "clone", "-q", gitBase + "/" + repoId, clone.toString());

    runGit(clone, "git", "-c", "user.email=q@l", "-c", "user.name=q", "commit", "-q", "--amend",
        "-m", "rewritten");
    String rewritten = runGit(clone, "git", "rev-parse", "HEAD").trim();
    runGit(clone, "git", "push", "--force", "origin", "main");

    assertEquals(
        rewritten,
        runGit(origin, "git", "rev-parse", "refs/heads/main").trim(),
        "the shipped default must leave a force push to the default branch exactly as it was");
  }

  @Test
  void theBrowseEndpointsSurviveTheCompileOverContentPushedThroughTheWire() {
    // Six new response shapes, every one of them a Jackson-serialised record — which is the
    // dto/UploadResult trap restated: a bound type reaches the native build only through the
    // provider chain that discovers it, and a gap there is a green `mvn verify` and a 500 in the
    // binary. Asserting the FIELDS is what catches it; a status code would not.
    //
    // The subjects are pushed through the registry and the npm registry rather than written into
    // the database, because the sizes are the point: they are read out of the stored manifest
    // document and off the blob files, so a fixture that skipped the wire would not prove the
    // parse happens in the binary at all.
    ensureOciRepository();
    try (OciClient client = new OciClient(URI.create(root.toString()))) {
      client.push("qits/browse-it", "v1", TinyImage.of("browse-it"));
    }
    TinyPackage subject = TinyPackage.of("@qits/browse-it", "1.0.0");
    try (NpmClient npm = new NpmClient(URI.create(root.toString()))) {
      assertEquals(
          201, npm.publish("npm", "@qits%2fbrowse-it", subject.publishDocument("latest")).statusCode());
    }

    given()
        .when()
        .get("/artifacts/api/repositories")
        .then()
        .statusCode(200)
        .body("repositories.find { it.name == 'qits' }.type", equalTo("oci-images"))
        .body("repositories.find { it.name == 'qits' }.itemCount", greaterThanOrEqualTo(1))
        .body("repositories.find { it.name == 'qits' }.sizeBytes", greaterThan(0));

    given()
        .when()
        .get("/artifacts/api/repositories/qits/images")
        .then()
        .statusCode(200)
        .body("images.find { it.name == 'browse-it' }.tagCount", equalTo(1))
        .body("images.find { it.name == 'browse-it' }.sizeBytes", greaterThan(0));

    given()
        .when()
        .get("/artifacts/api/repositories/qits/images/browse-it/tags")
        .then()
        .statusCode(200)
        .body("tags.find { it.tag == 'v1' }.digest", containsString("sha256:"))
        .body("tags.find { it.tag == 'v1' }.sizeBytes", greaterThan(0))
        .body("tags.find { it.tag == 'v1' }.createdAt", notNullValue());

    // The percent-encoded scoped name, on the JAX-RS surface this time. The npm routes prove it for
    // a Vert.x regex route; a path template is different machinery and needs its own case.
    given()
        .urlEncodingEnabled(false)
        .when()
        .get("/artifacts/api/repositories/npm/packages/@qits%2fbrowse-it/versions")
        .then()
        .statusCode(200)
        .body("versions[0].version", equalTo("1.0.0"))
        .body("versions[0].tarballSizeBytes", greaterThan(0))
        .body("versions[0].distTags", hasItems("latest"));

    given()
        .when()
        .get("/artifacts/api/repositories/npm/packages")
        .then()
        .statusCode(200)
        .body("packages.find { it.name == '@qits/browse-it' }.latest", equalTo("1.0.0"));

    // The seven figures, from a process that has really walked its own blob directory.
    given()
        .when()
        .get("/artifacts/api/store/summary")
        .then()
        .statusCode(200)
        .body("ociUnionBytes", greaterThan(0))
        .body("diskTotalBytes", greaterThan(0))
        .body("npmPublishedBytes", greaterThan(0))
        .body("ociPerImageSumBytes", greaterThanOrEqualTo(0))
        .body("orphanBytes", greaterThanOrEqualTo(0))
        .body("npmProxyTarballBytes", greaterThanOrEqualTo(0))
        .body("npmProxyPackumentBytes", greaterThanOrEqualTo(0))
        .body("ociMirrorBytes", greaterThanOrEqualTo(0))
        .body("mavenPublishedBytes", greaterThanOrEqualTo(0))
        // Zero, not merely non-negative: no maven-proxy repository can exist before the
        // pull-through workstream lands the type.
        .body("mavenProxyBytes", equalTo(0));

    given().when().get("/artifacts/api/repositories/no-such-repo/images").then().statusCode(404);
    given().when().get("/artifacts/api/repositories/npm/images").then().statusCode(400);
  }

  @Test
  void theGcPlanSurvivesTheCompileAndReportsAnHonestEmptyState() {
    // Four more Jackson-serialised records, so the same trap as the browse shapes — and one thing a
    // JVM test cannot show: this process has really walked its own blob directory to decide what
    // nothing references. The store here holds blobs pushed by the cases above, so the row-less
    // figures are a real reading rather than a zero that would pass either way.
    //
    // One of the eight types has no strategy and must say so — daemon-binaries joins npm-proxy as
    // unclaimed. oci-images must name the one that
    // does — and, with no qits-cd to answer, must report the refusal rather than a plan. That
    // fail-closed path only exists in the binary if the JDK HttpClient survived the compile, so this
    // is the one assertion here that a JVM test cannot make on its behalf. npm-packages is the
    // opposite case, and worth its own line for it: its rules read rows this process really
    // published, run with no outbound call at all, and condemn nothing — both packages above are
    // releases, and releases are never eligible. The two CI stubs carry their captions.
    given()
        .when()
        .get("/artifacts/api/gc/plan")
        .then()
        .statusCode(200)
        .body("dryRun", equalTo(true))
        .body("graceWindow", equalTo("P7D"))
        .body("types", hasSize(8))
        .body("types.find { it.type == 'oci-images' }.strategy", equalTo("OciImageGcStrategy"))
        .body("types.find { it.type == 'oci-images' }.error", containsString("qits-cd"))
        .body("types.find { it.type == 'oci-images' }.dead", hasSize(0))
        .body(
            "types.find { it.type == 'npm-packages' }.strategy", equalTo("NpmPackagesGcStrategy"))
        .body("types.find { it.type == 'npm-packages' }.error", nullValue())
        .body("types.find { it.type == 'npm-packages' }.dead", hasSize(0))
        .body("types.find { it.type == 'npm-packages' }.reclaimableBytes", equalTo(0))
        .body(
            "types.find { it.type == 'npm-proxy' }.note", containsString("no strategy registered"))
        .body("types.find { it.type == 'npm-proxy' }.strategy", nullValue())
        .body(
            "types.find { it.type == 'daemon-binaries' }.note",
            containsString("no strategy registered"))
        .body("types.find { it.type == 'daemon-binaries' }.strategy", nullValue())
        .body(
            "types.find { it.type == 'maven-packages' }.strategy",
            equalTo("MavenPackagesGcStrategy"))
        .body("types.find { it.type == 'maven-packages' }.note", containsString("snapshot"))
        .body("types.find { it.type == 'maven-packages' }.error", nullValue())
        .body("types.find { it.type == 'maven-packages' }.dead", hasSize(0))
        .body("types.find { it.type == 'oci-mirror' }.strategy", equalTo("OciMirrorGcStrategy"))
        .body("types.find { it.type == 'oci-mirror' }.note", nullValue())
        .body("types.find { it.type == 'oci-mirror' }.dead", hasSize(0))
        .body(
            "types.find { it.type == 'ci-screenshots' }.strategy",
            equalTo("CiScreenshotsGcStrategy"))
        // The stub answers with its rule-naming note at zero rows and a fail-closed refusal once
        // the upload case above has run — both honest, and which one depends on method order, so
        // the assertion accepts the pair rather than betting on the order.
        .body(
            "types.find { it.type == 'ci-screenshots' }.note"
                + " ?: types.find { it.type == 'ci-screenshots' }.error",
            containsString("branch"))
        .body("types.find { it.type == 'ci-videos' }.strategy", equalTo("CiVideosGcStrategy"))
        .body(
            "types.find { it.type == 'ci-videos' }.note"
                + " ?: types.find { it.type == 'ci-videos' }.error",
            containsString("byte"))
        .body("sweep.blobCount", equalTo(0))
        .body("sweep.blobIds", hasSize(0))
        .body("untouchable.blobCount", greaterThanOrEqualTo(0))
        .body("untouchable.reason", containsString("LOSES its last row"));

    // Reading and executing stay two different URLs, asserted against the binary.
    given().when().post("/artifacts/api/gc/plan").then().statusCode(405);

    // And the execute surface itself, in the binary: one more Jackson-serialised record family, and
    // the receipt of a sweep over a store whose every blob this process wrote seconds ago — the
    // grace window withholds identities and files alike, so the honest answer is zeros with the
    // withheld figures carrying the story. The store summary above already proved the blobs exist;
    // this proves invoking the sweep did not touch them.
    given()
        .when()
        .post("/artifacts/api/gc/sweep")
        .then()
        .statusCode(200)
        .body("dryRun", equalTo(false))
        .body("graceWindow", equalTo("P7D"))
        .body("types", hasSize(8))
        .body("types.find { it.type == 'oci-images' }.error", containsString("qits-cd"))
        .body("types.find { it.type == 'npm-packages' }.deleted", hasSize(0))
        .body("sweep.blobsUnlinked", equalTo(0))
        .body("sweep.unlinkedBlobIds", hasSize(0))
        .body("untouchable.reason", containsString("LOSES its last row"));
  }

  @Test
  void theMirrorNamespacesAreSeededByARealBootAndTheMissPathSurvivesTheCompile() {
    // The startup seed only ever fires in a packaged or dev process — never under TEST — so this is
    // the one place the three prefilled upstreams are observed arriving on their own, from a
    // migration and a boot rather than from a test's setup.
    given()
        .when()
        .get("/artifacts/api/mirror-upstreams")
        .then()
        .statusCode(200)
        .body("upstreams.domain", hasItems("docker.io", "quay.io", "registry.access.redhat.com"))
        .body("upstreams.find { it.domain == 'quay.io' }.slug", equalTo("quay"))
        .body("upstreams.find { it.domain == 'quay.io' }.cachedImages", equalTo(0));

    // And what a pull through one does: it FETCHES. The upstream is pointed at a closed port here,
    // so what is observable is the miss path running to its honest end — a 502 naming the upstream
    // it could not reach, rather than a 500 or a hang. That is a small assertion carrying a large
    // one: the mirror's outbound HttpClient is the fourth in this process, it is built inside the
    // binary rather than frozen into the image at build time, and nothing on the path from a /v2
    // route to an upstream request needed reflection the builder was not told about. A green
    // `mvn verify` proves none of that — every unit test configures a reachable stub, and this key
    // ships BLANK, which is the shape that once failed the boot outright.
    given()
        .when()
        .get("/v2/hub/library/alpine/manifests/latest")
        .then()
        .statusCode(502)
        .body("errors[0].message", containsString("docker.io is unreachable"))
        .body("errors[0].detail.namespace", equalTo("hub"));

    given()
        .when()
        .post("/v2/quay/anything/blobs/uploads/")
        .then()
        .statusCode(405)
        .body("errors[0].detail.type", equalTo("oci-mirror"));
  }

  /**
   * Idempotent, like the endpoint itself — the registry never creates a repository implicitly. The
   * startup seed has already written this exact row in a packaged process; the call stays so the
   * registry cases state their own precondition instead of resting on another test's subject.
   */
  private void ensureOciRepository() {
    given()
        .contentType("application/json")
        .body("{\"type\":\"oci-images\"}")
        .when()
        .put("/artifacts/api/repositories/qits")
        .then()
        .statusCode(200);
  }

  /**
   * Seeds a bare origin at {@code <data-dir>/<repoId>/origin} with the git CLI, the same way {@code
   * GitHostSuite} does on the file backend — the served repository has to be a real on-disk bare,
   * and this repo builds one rather than shipping a fixture (see AGENTS.md, the clone-alone rule).
   *
   * <p>Static and package-private because {@link ProtectedGitHostIT} launches the same binary under
   * a different configuration and seeds the same way.
   */
  static String seedOrigin() throws Exception {
    String repoId = UUID.randomUUID().toString();
    Path origin = TargetDirState.ROOT.resolve("repositories").resolve(repoId).resolve("origin");
    Files.createDirectories(origin.getParent());

    Path seed = Files.createTempDirectory("qits-artifacts-it-seed");
    // The branch is pinned rather than left to the host's init.defaultBranch: the protected ref is
    // the bare's own HEAD, and the protection cases below have to know its name.
    runGit(null, "git", "init", "-q", "-b", "main", seed.toString());
    Files.writeString(seed.resolve("README.md"), "seed\n");
    runGit(seed, "git", "add", "README.md");
    runGit(seed, "git", "-c", "user.email=qits@local", "-c", "user.name=qits", "commit", "-q", "-m",
        "seed");
    runGit(null, "git", "clone", "-q", "--bare", seed.toString(), origin.toString());
    return repoId;
  }

  static String runGit(Path cwd, String... command) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(command);
    if (cwd != null) {
      pb.directory(cwd.toFile());
    }
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes());
    if (p.waitFor() != 0) {
      throw new RuntimeException("git " + String.join(" ", command) + " failed:\n" + out);
    }
    return out;
  }

  /**
   * A refused push is the subject of the protection cases, so its output is a value rather than an
   * exception: the message the pusher reads is exactly what is being asserted.
   */
  static String runGitExpectingFailure(Path cwd, String... command) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(command);
    if (cwd != null) {
      pb.directory(cwd.toFile());
    }
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes());
    if (p.waitFor() == 0) {
      throw new AssertionError(
          "git " + String.join(" ", command) + " unexpectedly succeeded:\n" + out);
    }
    return out;
  }

  /** The 33 bytes of a PNG header the media sniffer needs; the body is irrelevant to the store. */
  private static byte[] png(int width, int height) {
    byte[] b = new byte[33];
    System.arraycopy(
        new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'}, 0, b, 0, 8);
    b[11] = 13; // IHDR chunk length
    b[12] = 'I';
    b[13] = 'H';
    b[14] = 'D';
    b[15] = 'R';
    b[19] = (byte) width;
    b[23] = (byte) height;
    b[24] = 8; // bit depth
    b[25] = 6; // colour type
    return b;
  }

  private static Map<String, String> screenshotHeaders(String branch, String flow) {
    Map<String, String> h = new LinkedHashMap<>();
    h.put("X-Artifacts-Meta-git.branch.name", branch);
    h.put("X-Artifacts-Meta-git.commit.hash", "abc123");
    h.put("X-Artifacts-Meta-qits.userflow.name", flow);
    h.put("X-Artifacts-Meta-qits.userflow.hash", "flowhash");
    h.put("X-Artifacts-Meta-qits.display.name", "step 1");
    h.put("X-Artifacts-Meta-qits.diff.hash", "diffhash");
    h.put("X-Artifacts-Meta-media.resolution.width", "120");
    h.put("X-Artifacts-Meta-media.resolution.height", "80");
    return h;
  }
}
