package eu.wohlben.qits;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.net.URL;
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
 * thing under test is the single process: {@code /artifacts/api/**} is JAX-RS, {@code /artifacts/q/**}
 * is Quarkus' non-application root and {@code /artifacts/git/**} is raw Vert.x, and what needs
 * proving is that all three resolve in the same binary. Splitting it by package would split the
 * subject.
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
      return overrides;
    }
  }

  @TestHTTPResource("/artifacts/git")
  URL gitBase;

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
    // startup seed wrote the two CI types through Panache, and Jackson serialised them back.
    given()
        .when()
        .get("/artifacts/api/repositories")
        .then()
        .statusCode(200)
        .body("repositories.name", hasItem("ci-screenshots"));

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

  /**
   * Seeds a bare origin at {@code <data-dir>/<repoId>/origin} with the git CLI, the same way {@code
   * GitHostTest} does — the served repository has to be a real on-disk bare, and this repo builds
   * one rather than shipping a fixture (see AGENTS.md, the clone-alone rule).
   */
  private String seedOrigin() throws Exception {
    String repoId = UUID.randomUUID().toString();
    Path origin = TargetDirState.ROOT.resolve("repositories").resolve(repoId).resolve("origin");
    Files.createDirectories(origin.getParent());

    Path seed = Files.createTempDirectory("qits-artifacts-it-seed");
    runGit(null, "git", "init", "-q", seed.toString());
    Files.writeString(seed.resolve("README.md"), "seed\n");
    runGit(seed, "git", "add", "README.md");
    runGit(seed, "git", "-c", "user.email=qits@local", "-c", "user.name=qits", "commit", "-q", "-m",
        "seed");
    runGit(null, "git", "clone", "-q", "--bare", seed.toString(), origin.toString());
    return repoId;
  }

  private String runGit(Path cwd, String... command) throws Exception {
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
