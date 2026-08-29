package eu.wohlben.qits.stories.daemons;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.PackagedProcessIT;
import eu.wohlben.qits.stories.support.Cli;
import eu.wohlben.qits.stories.support.StoryMedia;
import eu.wohlben.qits.stories.support.StoryTarget;
import eu.wohlben.qits.userflows.Commands;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.UserflowContext;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * The daemon category's producer, told the way the platform's own release actually does it: one
 * {@code curl -X PUT} of a binary at a version, and a receipt carrying the digest.
 *
 * <p>{@code curl} rather than a client library is the point. Nothing on this wire is a package
 * manager — a daemon is published by a pipeline step and downloaded by a bootstrap script, both of
 * which are shell — so the story that documents it has to be shell too, or it documents a client
 * nobody uses.
 *
 * <p>The publish surface is deliberately <b>unauthenticated</b> (the qits-net trust posture every
 * wire stack here keeps), and what stands in for write auth is immutability: a version is published
 * once, a republish is a {@code 409}, and a consumer pins the digest this receipt echoes.
 */
@QuarkusIntegrationTest
@TestProfile(PackagedProcessIT.TargetDirState.class)
@EnabledIf("eu.wohlben.qits.stories.support.Cli#curlPresent")
public class DaemonPublishIT {

  static final String CATEGORY = "daemons";
  static final String SLUG = "a-cold-bootstrap-publishes-the-platform-s-daemon-binary";

  /** The subject the daemon chain shares. Bounded to {@code [a-z0-9][a-z0-9._-]{0,63}} by the wire. */
  public static final String DAEMON = "qits-story-daemon";

  public static final String VERSION = "1.0.0";

  /** Small enough to be free, large enough that the transfer is a real chunked body. */
  private static final int SIZE = 4096;

  private static final ObjectMapper JSON = new ObjectMapper();

  @TestHTTPResource("/")
  URL root;

  @UserStory(value = "A cold bootstrap publishes the platform's daemon binary", category = "daemons")
  @UserStoryDescription(
      """
      How the platform's own executables get into the store. A build produces a binary, a pipeline
      step PUTs it at a name and a version, and the receipt comes back carrying the sha256 the
      store computed over what it received. That digest is the contract: it is what a deployment
      pins, what the download echoes back, and the reason an open publish surface is safe here — a
      version can be added and never changed.
      """)
  void aColdBootstrapPublishesTheDaemonBinary(
      Interactions story, Commands commands, UserflowContext context) throws IOException {
    StoryTarget target = new StoryTarget(root);

    // workDir() creates and wipes the scratch on first use, so it has to be taken before anything
    // is written into it.
    Path work = commands.workDir();
    byte[] binary = StoryMedia.daemonBinary(DAEMON + "-" + VERSION, SIZE);
    Files.write(work.resolve("qits-story-daemon"), binary);
    String expected = StoryMedia.sha256Hex(binary);
    story
        .note("a build produced the daemon binary this release publishes")
        .as("binary-built");

    // -H 'Expect:' because Quarkus does not answer 100-continue automatically, and curl sends the
    // header for any body over a kilobyte: without this every publish here would stall a second
    // waiting for a continuation that never comes. The body goes to a file and the status code to
    // stdout, so both are evidence rather than one being lost to the other.
    commands
        .run(
            "{} -sS -X PUT -H 'Expect:' --data-binary @{} -o {} -w %{http_code} {}",
            Cli.curl(),
            DAEMON,
            "publish-response.json",
            target.daemonBase() + "/" + DAEMON + "/" + VERSION)
        .as("binary-published");
    assertEquals("201", commands.lastOutput().strip(), "the publish status");

    JsonNode receipt = JSON.readTree(Files.readString(work.resolve("publish-response.json")));
    assertEquals(DAEMON, receipt.path("name").asText(), "the receipt's name");
    assertEquals(VERSION, receipt.path("version").asText(), "the receipt's version");
    assertEquals(SIZE, receipt.path("sizeBytes").asInt(), "the receipt's size");
    assertEquals(
        "sha256:" + expected,
        receipt.path("digest").asText(),
        "the digest the store computed over the bytes it received");
    story
        .note("the receipt's digest is the sha256 of the bytes the build produced")
        .as("digest-echoed");

    story
        .happened(
            "a release pipeline",
            "qits-artifacts",
            "PUT /artifacts/daemons/" + DAEMON + "/" + VERSION + " -> 201")
        .as("publish-recorded");

    context.put("story.daemon.name", DAEMON);
    context.put("story.daemon.version", VERSION);
    context.put("story.daemon.digest", "sha256:" + expected);
  }

  @AfterAll
  static void storyReportIsComplete() {
    if (!Cli.curlPresent()) {
      return;
    }
    ReportAssertions.assertComplete(CATEGORY, SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "binary-built");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "binary-published");
    ReportAssertions.assertCommand(CATEGORY, SLUG, "-X PUT", 0);
    ReportAssertions.assertCommandOutputContains(CATEGORY, SLUG, "-X PUT", "201");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "digest-echoed");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "publish-recorded");
    ReportAssertions.assertInteraction(
        CATEGORY,
        SLUG,
        "a release pipeline",
        "qits-artifacts",
        "PUT /artifacts/daemons/" + DAEMON + "/" + VERSION + " -> 201");
  }
}
