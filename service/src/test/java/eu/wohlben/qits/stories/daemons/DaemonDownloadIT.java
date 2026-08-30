package eu.wohlben.qits.stories.daemons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.PackagedProcessIT;
import eu.wohlben.qits.stories.support.AccessLogSource;
import eu.wohlben.qits.stories.support.Cli;
import eu.wohlben.qits.stories.support.StoryMedia;
import eu.wohlben.qits.stories.support.StoryTarget;
import eu.wohlben.qits.userflows.Commands;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.UserflowContext;
import eu.wohlben.qits.userflows.UserflowPrecondition;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * The consuming half of the daemon chain, and the reason {@code /artifacts} is in {@code
 * quarkus.quinoa.ignored-path-prefixes}.
 *
 * <p>A cold machine bootstraps by {@code curl}ing exactly this URL and {@code exec}ing what comes
 * back. There is no client, no retry library and no content negotiation in that path — so if the
 * SPA's catch-all ever swallowed {@code /artifacts/daemons}, the answer would be {@code 200
 * text/html} and the bootstrap would execute a web page. This story is that {@code curl}, run
 * against the packaged process with the SPA really mounted at the host root.
 *
 * <p>It verifies the bytes twice over: the digest the publish receipt promised, recomputed over
 * what arrived, and the {@code Docker-Content-Digest} header the version-addressed route echoes —
 * the two strings a deployment pin is checked against.
 */
@QuarkusIntegrationTest
@TestProfile(PackagedProcessIT.TargetDirState.class)
@EnabledIf("eu.wohlben.qits.stories.support.Cli#curlPresent")
public class DaemonDownloadIT {

  static final String CATEGORY = "daemons";
  static final String SLUG = "a-runner-downloads-the-daemon-version-its-pin-names";

  /** How the diagram names the initiator of everything this story sends. */
  static final String ACTOR = "a runner";

  @TestHTTPResource("/")
  URL root;

  @UserStory(value = "A runner downloads the daemon version its pin names", category = "daemons")
  @UserflowPrecondition(DaemonPublishIT.class)
  @UserStoryDescription(
      """
      What a cold machine does at boot: read the version its pin names, curl that one URL, and run
      whatever comes back. The route is version-addressed and carries no repository segment — the
      daemon wire serves the platform's own binaries and nothing else — and the response echoes the
      digest, which is what turns "I downloaded something" into "I downloaded the bytes that were
      pinned". Nothing in that path can tell an HTML error page from an executable, which is why
      this URL must never be allowed to fall through to the SPA.
      """)
  void aRunnerDownloadsTheDaemonVersionItsPinNames(
      Interactions story, Commands commands, UserflowContext context) throws IOException {
    StoryTarget target = new StoryTarget(root);
    String daemon = context.require("story.daemon.name", String.class);
    String version = context.require("story.daemon.version", String.class);
    String pinned = context.require("story.daemon.digest", String.class);

    // Whose traffic the access log's next line is, and what kind. Read at drain time, and the actor
    // is reset at every story border, so this never inherits the publish story's pipeline.
    AccessLogSource.attribute(ACTOR, NetworkEdge.PACKAGE);

    Path work = commands.workDir();
    commands
        .run(
            "{} -sS -f -D {} -o {} -w %{http_code} {}",
            Cli.curl(),
            "response-headers.txt",
            daemon,
            target.daemonBase() + "/" + daemon + "/" + version)
        .as("binary-downloaded");
    assertEquals("200", commands.lastOutput().strip(), "the download status");

    // The bytes, hashed the way the store hashes them.
    Path downloaded = work.resolve(daemon);
    assertEquals(
        pinned,
        "sha256:" + StoryMedia.sha256Hex(downloaded),
        "the bytes that arrived must hash to the digest the publish receipt promised");

    // And the header, which is the string a bootstrap script compares its pin against without ever
    // hashing anything itself.
    String headers = Files.readString(work.resolve("response-headers.txt"));
    Optional<String> echoed = headerValue(headers, "docker-content-digest");
    assertTrue(echoed.isPresent(), () -> "no Docker-Content-Digest in:\n" + headers);
    assertEquals(pinned, echoed.orElseThrow(), "the digest the version-addressed route echoes");
    story.note("the downloaded bytes and the echoed digest both match the pin").as("digest-verified");

    // The narrative the wire cannot carry: this one URL is the whole bootstrap, and what comes back
    // is executed — so a 200 that was HTML rather than a binary would be the failure this documents.
    story
        .note("one URL is the whole bootstrap, and whatever it answers is what the machine executes")
        .as("download-recorded");
    AccessLogSource.awaitLogged("GET " + DaemonPublishIT.BINARY_PATH);
  }

  /** The first value of {@code name} in a raw {@code curl -D} dump, case-insensitively. */
  private static Optional<String> headerValue(String dump, String name) {
    return dump.lines()
        .filter(line -> line.toLowerCase(Locale.ROOT).startsWith(name + ":"))
        .map(line -> line.substring(line.indexOf(':') + 1).strip())
        .findFirst();
  }

  @AfterAll
  static void storyReportIsComplete() {
    if (!Cli.curlPresent()) {
      return;
    }
    ReportAssertions.assertComplete(CATEGORY, SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "binary-downloaded");
    ReportAssertions.assertCommand(CATEGORY, SLUG, DaemonPublishIT.DAEMON, 0);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "digest-verified");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "download-recorded");
    // Observed, and counted: a cold bootstrap is exactly one request, so a second edge here would
    // mean the path a machine actually takes is not the one this story describes.
    ReportAssertions.assertEdge(
        CATEGORY,
        SLUG,
        NetworkEdge.PACKAGE,
        ACTOR,
        AccessLogSource.SERVICE,
        "GET " + DaemonPublishIT.BINARY_PATH + " -> 200");
    ReportAssertions.assertEdgeCount(CATEGORY, SLUG, 1);
  }
}
