package eu.wohlben.qits;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The <b>upstream</b> OCI distribution-spec conformance suite, driven against the packaged process.
 *
 * <p>{@code RegistryTest} and {@code PackagedProcessIT} prove the registry against a client this
 * repo wrote, which can only ever assert the reading of the spec that went into writing it. This
 * runs {@code opencontainers/distribution-spec}'s own suite instead — several hundred assertions
 * nobody here authored — so it is the only thing in this repo that can falsify that reading.
 *
 * <p><b>Opt-in, and manual.</b> The suite is a Go binary with no published release, so it cannot be
 * a build dependency without breaking the rule this repo exists for (a clone alone builds green,
 * docker-free, with no toolchain beyond a JDK). It therefore gates on {@code
 * -Doci.conformance-binary=<path>} and <b>skips</b> — never fails — when that is absent, because
 * {@code -Dnative} flips {@code skipITs} to false and a native build must not start needing Go.
 *
 * <pre>
 *   git clone https://github.com/opencontainers/distribution-spec.git
 *   cd distribution-spec/conformance &amp;&amp; go build -o conformance .    # needs Go &gt;= 1.24
 *
 *   ./mvnw verify -DskipITs=false \
 *       -Doci.conformance-binary=/abs/path/to/distribution-spec/conformance/conformance
 * </pre>
 *
 * <p>Add {@code -Dnative} to run it against the GraalVM binary instead of the fast-jar.
 *
 * <p><b>What the capability flags below mean.</b> The suite has no notion of "this registry chose
 * not to implement that"; every optional API is a flag the operator declares. Three of them are
 * declared {@code false} here, and each is a <i>design decision of this service</i> documented in
 * README.md, not a failing test being hidden — see the comments on {@link #env}. Everything the spec
 * makes mandatory is left on.
 */
@QuarkusIntegrationTest
@TestProfile(OciConformanceIT.TargetDirState.class)
class OciConformanceIT {

  /** {@code -Doci.conformance-binary=<path>}; absent means skip. */
  private static final String BINARY_PROPERTY = "oci.conformance-binary";

  /**
   * The repository half of {@code OCI_REPO1}/{@code OCI_REPO2}. An OCI {@code <name>} splits at its
   * <b>first</b> slash ({@code OciImageName.parse}), so {@code conformance/repo1} is repository
   * {@code conformance}, image {@code repo1} — one row covers both, and the suite's cross-repository
   * blob mount stays a mount between two images rather than needing a second ensure call.
   */
  private static final String REPOSITORY = "conformance";

  private static final String REPO1 = REPOSITORY + "/repo1";
  private static final String REPO2 = REPOSITORY + "/repo2";

  /** Its own root, not {@code PackagedProcessIT}'s: each IT class owns the state it writes. */
  public static class TargetDirState implements QuarkusTestProfile {

    static final Path ROOT = Path.of(System.getProperty("user.dir"), "target", "oci-conformance-it");

    @Override
    public Map<String, String> getConfigOverrides() {
      Map<String, String> overrides = new LinkedHashMap<>();
      // The deployed shape (file H2, embedded, no AUTO_SERVER), only relocated — same reasoning as
      // PackagedProcessIT. Runtime config, so it reaches the already-built artifact as -D flags.
      overrides.put(
          "quarkus.datasource.artifacts.jdbc.url", "jdbc:h2:file:" + ROOT.resolve("h2/artifacts"));
      overrides.put("quarkus.flyway.artifacts.clean-at-start", "true");
      overrides.put("qits.artifacts.blobs-dir", ROOT.resolve("blobs").toString());
      overrides.put("qits.ci.intake-url", "http://localhost:1/post-receive");
      return overrides;
    }
  }

  @TestHTTPResource("/")
  URL root;

  @Test
  void theRegistryPassesTheUpstreamConformanceSuite() throws Exception {
    Path binary = binaryOrSkip();

    Path work = TargetDirState.ROOT.resolve("conformance");
    Path results = work.resolve("results");
    deleteRecursively(work);
    Files.createDirectories(results);

    // Black box on purpose: an @QuarkusIntegrationTest talks to a separate process and gets no bean
    // injection, which is exactly the posture the suite itself has. The registry never creates a
    // repository implicitly — without this every push is 404 NAME_UNKNOWN.
    given()
        .contentType("application/json")
        .body("{\"type\":\"oci-images\"}")
        .when()
        .put("/artifacts/api/repositories/" + REPOSITORY)
        .then()
        .statusCode(200);

    Path log = work.resolve("conformance.log");
    ProcessBuilder pb = new ProcessBuilder(binary.toString());
    // A directory this test just emptied, because the binary picks up an `oci-conformance.yaml`
    // from its working directory if one is there — and a stray file would silently reconfigure
    // the run. (OCI_CONFIGURATION cannot express "no file": pointing it at a missing path is a
    // config error that makes the binary exit *before* it writes any report.)
    pb.directory(work.toFile());
    pb.environment().putAll(env(results));
    pb.redirectErrorStream(true);
    pb.redirectOutput(log.toFile());

    Process process = pb.start();
    if (!process.waitFor(10, TimeUnit.MINUTES)) {
      process.destroyForcibly();
      fail("the conformance suite did not finish within 10 minutes; output in " + log);
    }
    int exit = process.exitValue();

    Path junit = results.resolve("junit.xml");
    Path report = results.resolve("report.html");
    // Exit 1 does mean "a test failed", but exit 0 does NOT mean "all passed": the binary's main()
    // *returns* — status 0 — when the config fails to load or the runner cannot be built, having
    // written no report at all. So a missing junit.xml is the failure, not the exit code, and the
    // counts below are the source of truth.
    assertTrue(
        Files.exists(junit),
        "the conformance suite wrote no junit.xml (exit "
            + exit
            + "), so it never ran; output in "
            + log);

    Summary summary = parseJunit(junit);
    if (summary.failed() == 0 && summary.errored() == 0) {
      // Not an assertion, a guard: a run that somehow executed nothing would otherwise be green.
      assertTrue(
          summary.total() > 0,
          "the conformance suite reported zero tests; output in " + log + ", report " + report);
      return;
    }

    StringBuilder message = new StringBuilder();
    message
        .append("the OCI distribution-spec conformance suite reported ")
        .append(summary.failed())
        .append(" failure(s) and ")
        .append(summary.errored())
        .append(" error(s) out of ")
        .append(summary.total())
        .append(" (")
        .append(summary.skipped())
        .append(" skipped, ")
        .append(summary.disabled())
        .append(" disabled by the capability declarations in this test); exit ")
        .append(exit)
        .append("\n\nrequest/response detail for each: ")
        .append(report)
        .append("\nsuite output: ")
        .append(log)
        .append("\n\nfailing:");
    for (String name : summary.failing()) {
      message.append("\n  ").append(name);
    }
    fail(message.toString());
  }

  /**
   * The exact names the suite reads, and why each non-default value is what it is. Anything not
   * listed keeps the suite's own default, which is the strict one.
   */
  private Map<String, String> env(Path results) {
    Map<String, String> env = new LinkedHashMap<>();
    // host:port with NO scheme — the scheme comes from OCI_TLS, and this process serves plain HTTP.
    env.put("OCI_REGISTRY", root.getHost() + ":" + root.getPort());
    env.put("OCI_TLS", "disabled");
    env.put("OCI_REPO1", REPO1);
    env.put("OCI_REPO2", REPO2);
    // Reads are anonymous and the write token is blank in tests, so there is nothing to send.
    env.put("OCI_USERNAME", "");
    env.put("OCI_PASSWORD", "");
    env.put("OCI_API_PULL", "true");
    env.put("OCI_API_PUSH", "true");

    // --- capability declarations: what this service deliberately does not implement -------------
    //
    // DELETE is unimplemented BY DESIGN, and the reason is storage, not effort: the blob store is
    // append-only and there is no garbage collector, so deletion has no meaning here yet and the
    // routes answer 405 UNSUPPORTED precisely so nothing comes to depend on semantics that do not
    // exist (README, "Deliberately not implemented").
    //
    // These three hide NOTHING, which is worth knowing before anyone suspects they do: the suite
    // tracks an endpoint answering a valid "unsupported" status rather than failing it, and 405 is
    // exactly that. Turning all three on leaves the result at 430 pass / 2 fail and only relabels
    // 283 tests from Disabled to Skip. They are here as a declaration of intent, not as a shield.
    env.put("OCI_API_BLOBS_DELETE", "false");
    env.put("OCI_API_MANIFESTS_DELETE", "false");
    env.put("OCI_API_TAGS_DELETE", "false");
    // /v2/<name>/referrers/<digest> is likewise absent — a manifest's `subject` is parsed and
    // ignored. The spec makes this optional explicitly: a 404 is the defined "referrers API
    // unavailable" signal with a mandated client fallback to the referrers tag schema (spec.md,
    // "Unavailable Referrers API"), and the OCI-Subject response header is required only of "a
    // registry implementation that supports the referrers API".
    env.put("OCI_API_REFERRER", "false");
    // sha512 content. The blob store is SHA-256 throughout — the content id IS the sha256 — and a
    // sha512 digest is rejected with 400 DIGEST_INVALID, which is the spec's own SHOULD for "an
    // unsupported algorithm". sha256 is the required algorithm and stays fully exercised.
    env.put("OCI_DATA_SHA512", "false");
    // -------------------------------------------------------------------------------------------

    env.put("OCI_VERSION", "1.1");
    env.put("OCI_LOG", "warn");
    env.put("OCI_RESULTS_DIR", results.toString());
    return env;
  }

  private Path binaryOrSkip() {
    String configured = System.getProperty(BINARY_PROPERTY, "");
    assumeTrue(
        !configured.isBlank(),
        "skipped: build the upstream suite (git clone opencontainers/distribution-spec;"
            + " cd conformance && go build -o conformance .) and pass -D"
            + BINARY_PROPERTY
            + "=<path>");
    Path binary = Path.of(configured).toAbsolutePath();
    // Past the gate a wrong path is a real failure, not a skip: the developer asked for this run.
    assertTrue(Files.isExecutable(binary), BINARY_PROPERTY + " is not an executable file: " + binary);
    return binary;
  }

  private record Summary(
      int total, int failed, int errored, int skipped, int disabled, List<String> failing) {}

  /**
   * The suite's {@code junit.xml}. The counts live on the root {@code <testsuites>}; {@code errors}
   * and friends are omitted when zero, hence the defaults.
   */
  private static Summary parseJunit(Path junit) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    Document document = factory.newDocumentBuilder().parse(junit.toFile());
    Element root = document.getDocumentElement();

    List<String> failing = new ArrayList<>();
    NodeList cases = document.getElementsByTagName("testcase");
    for (int i = 0; i < cases.getLength(); i++) {
      Element testcase = (Element) cases.item(i);
      String status = testcase.getAttribute("status");
      if ("failure".equals(status) || "error".equals(status)) {
        String detail = firstLineOf(testcase, "failure".equals(status) ? "failure" : "error");
        failing.add(testcase.getAttribute("name") + (detail.isEmpty() ? "" : " — " + detail));
      }
    }
    return new Summary(
        intAttribute(root, "tests"),
        intAttribute(root, "failures"),
        intAttribute(root, "errors"),
        intAttribute(root, "skipped"),
        intAttribute(root, "disabled"),
        failing);
  }

  /**
   * The one-line "expected X, received Y" the suite records, so the failure message says what broke
   * without anyone opening the report first.
   */
  private static String firstLineOf(Element testcase, String tag) {
    NodeList nodes = testcase.getElementsByTagName(tag);
    for (int i = 0; i < nodes.getLength(); i++) {
      String text = nodes.item(i).getTextContent();
      if (text != null && !text.isBlank()) {
        return text.strip().lines().findFirst().orElse("");
      }
    }
    NodeList out = testcase.getElementsByTagName("system-out");
    if (out.getLength() > 0) {
      String text = out.item(0).getTextContent();
      if (text != null && !text.isBlank()) {
        return text.strip().lines().findFirst().orElse("");
      }
    }
    return "";
  }

  private static int intAttribute(Element element, String name) {
    String value = element.getAttribute(name);
    return value.isEmpty() ? 0 : Integer.parseInt(value);
  }

  private static void deleteRecursively(Path path) throws IOException {
    if (!Files.exists(path)) {
      return;
    }
    try (var walk = Files.walk(path)) {
      for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    }
  }
}
