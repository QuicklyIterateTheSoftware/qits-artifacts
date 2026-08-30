package eu.wohlben.qits.stories.npm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.PackagedProcessIT;
import eu.wohlben.qits.npm.NpmClient;
import eu.wohlben.qits.stories.support.AccessLogSource;
import eu.wohlben.qits.stories.support.Cli;
import eu.wohlben.qits.stories.support.StoryTarget;
import eu.wohlben.qits.userflows.Commands;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.UserflowContext;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.net.URI;
import java.net.URL;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * The npm category's producer: the real npm CLI, publishing to the launched process.
 *
 * <p>Every other npm suite in this repository drives a <b>synthesised</b> client — {@code
 * npm/NpmClient} over the JDK's {@code HttpClient} — because {@code mvn verify} may not assume npm
 * is installed and the wire questions there are about path encoding rather than about the tool.
 * This story is the other half of that: what a release pipeline actually types, run by the actual
 * binary, with the packument read back afterwards by the synthetic client so the story asserts the
 * server's answer rather than npm's exit code alone.
 *
 * <p>Browserless: {@link Interactions} and {@link Commands}, no {@link
 * eu.wohlben.qits.userflows.Flow}, so no Chromium is launched and the report is the transcript.
 *
 * <p>The network diagram is <b>observed, never narrated</b>. npm talks to the launched process over
 * a socket this JVM is not on, so the observation is the server's own access log, read back by
 * {@link AccessLogSource}; the story names its initiator and the kind of traffic and asserts nothing
 * about the shape of an edge until the {@code @AfterAll}.
 */
@QuarkusIntegrationTest
@TestProfile(PackagedProcessIT.TargetDirState.class)
@EnabledIf("eu.wohlben.qits.stories.support.Cli#npmPresent")
public class NpmPublishIT {

  static final String CATEGORY = "npm";
  static final String SLUG = "a-release-pipeline-publishes-an-npm-package";

  /** The subject the whole npm chain shares; the install and explore stories read it back. */
  public static final String PACKAGE = "@qits/story-package";

  public static final String VERSION = "1.0.0";

  /** The percent-encoded spelling every npm client sends for a scoped packument read. */
  static final String ENCODED = "@qits%2fstory-package";

  /** How the diagram names the initiator of everything this story sends. */
  static final String ACTOR = "a release pipeline";

  /**
   * A publish credential. npm refuses to publish without one ({@code ENEEDAUTH}) and this registry
   * ignores it — every wire surface here is unauthenticated on qits-net by design — so the value is
   * a fixture rather than a secret. It is {@linkplain Commands#redact redacted} anyway: a token
   * written into a report is a habit, and the habit is what leaks a real one later.
   */
  static final String TOKEN = "story-npm-publish-token";

  @TestHTTPResource("/")
  URL root;

  @UserStory(value = "A release pipeline publishes an npm package", category = "npm")
  @UserStoryDescription(
      """
      The npm registry as a release pipeline meets it: a package directory with a manifest and a
      registry credential, one `npm publish`, and a version that exists from then on and can never
      be changed. The tool is the real npm CLI rather than this repository's synthetic client —
      what a pipeline types is the thing under test — and the packument is read back afterwards to
      show the registry, not the tool, is what accepted it.
      """)
  void aReleasePipelinePublishesAnNpmPackage(
      Interactions story, Commands commands, UserflowContext context) {
    StoryTarget target = new StoryTarget(root);
    commands.redact(TOKEN);

    // Who the access log's lines belong to, and what kind of traffic they are. Both are read when
    // the framework drains at story end, and the actor is reset at every story border — so this is
    // set before the first request rather than beside the assertion it shapes. `package` because
    // this exchange IS a package-manager publish, whatever HTTP carries it.
    AccessLogSource.attribute(ACTOR, NetworkEdge.PACKAGE);

    // npm wants a writable HOME and a cache directory, and a build agent's HOME is frequently
    // neither. Both go into the story's scratch directory, which is wiped per run — so a cached
    // packument from an earlier run can never stand in for the publish this story is about.
    commands.env("HOME", commands.workDir().toAbsolutePath().toString());
    commands.env(
        "npm_config_cache", commands.workDir().resolve(".npm-cache").toAbsolutePath().toString());

    // The package gets a directory of its own, BESIDE the cache rather than above it. `npm publish`
    // packs the whole working directory, so a cache inside it would ship npm's own debug log inside
    // the published tarball — and the tarball's file list is the evidence this story is about.
    commands.in("package");

    // The project .npmrc, which is where npm looks for a credential first. The key is the registry
    // URL with its scheme stripped — npm's "nerf dart" — and it must match the --registry flag
    // below exactly or the publish goes out anonymous.
    commands.file(".npmrc", "//{}:_authToken={}\n", target.npmRegistryAuthKey(), TOKEN);

    commands
        .file(
            "package.json",
            """
            {
              "name": "{}",
              "version": "{}",
              "description": "The subject of the npm userflow chain.",
              "license": "UNLICENSED",
              "main": "index.js"
            }
            """,
            PACKAGE,
            VERSION)
        .as("package-prepared");

    // BOTH registry flags, and neither is redundant. --registry points npm at this process;
    // --@qits:registry overrides the scoped key the workspace npm shim injects through the
    // environment, which would otherwise send this publish to the platform's own registry. npm
    // ranks the command line above the environment, so the command line is what wins.
    commands
        .run(
            "{} publish --registry {} --@qits:registry={}",
            Cli.npm(),
            target.npmRegistry(),
            target.npmRegistry())
        .as("package-published");

    // The registry's own answer, read with the synthetic client: npm reports what it sent, and what
    // is worth recording is what the store now serves.
    try (NpmClient npm = new NpmClient(URI.create(root.toString()))) {
      JsonNode packument = npm.packumentJson("npm", ENCODED);
      assertEquals(PACKAGE, packument.path("name").asText(), "the packument's name");
      assertEquals(
          VERSION,
          packument.path("dist-tags").path("latest").asText(),
          "a bare `npm publish` means --tag latest, and this is the first version");
    }
    // The narrative the access log cannot carry: WHICH package at WHICH version, in the vocabulary
    // a pipeline author uses. The edge itself is observed and drawn from the server's own record.
    story
        .note("the registry now holds " + PACKAGE + "@" + VERSION + ", published by the real npm CLI")
        .as("publish-recorded");
    // The receiver writes off the request thread, so the publish line can still be in flight while
    // this story finishes. Waiting for it here is what keeps the edge in THIS story's diagram.
    AccessLogSource.awaitLogged("PUT " + StoryTarget.NPM_PATH + ENCODED);

    context.put("story.npm.name", PACKAGE);
    context.put("story.npm.version", VERSION);
  }

  @AfterAll
  static void storyReportIsComplete() {
    if (!Cli.npmPresent()) {
      return;
    }
    ReportAssertions.assertComplete(CATEGORY, SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "package-prepared");
    ReportAssertions.assertWroteFile(CATEGORY, SLUG, ".npmrc");
    ReportAssertions.assertWroteFile(CATEGORY, SLUG, "package.json");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "package-published");
    ReportAssertions.assertCommand(CATEGORY, SLUG, "publish --registry", 0);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "publish-recorded");
    // The network, observed rather than claimed: these are the lines the launched process itself
    // wrote about what npm and this story sent it. The publish is a `package` edge because the
    // exchange IS a package-manager publish — the transport happens to be HTTP and says nothing
    // about what the traffic means.
    ReportAssertions.assertEdge(
        CATEGORY,
        SLUG,
        NetworkEdge.PACKAGE,
        ACTOR,
        AccessLogSource.SERVICE,
        "PUT " + StoryTarget.NPM_PATH + ENCODED + " -> 201");
    ReportAssertions.assertEdge(
        CATEGORY,
        SLUG,
        NetworkEdge.PACKAGE,
        ACTOR,
        AccessLogSource.SERVICE,
        "GET " + StoryTarget.NPM_PATH + ENCODED + " -> 200");
    // NO assertEdgeCount, and the reason is worth keeping because it is not obvious from the
    // transcript: a real npm also asks the configured registry for the package named `npm` — its
    // own update-notifier — so a third edge `GET …/npm/npm/npm` appears in this diagram that no
    // line of this story asked for. It was measured answering both 200 and 404 across two
    // invocations against the same store, so it is neither stable nor this repository's to promise.
    // Pinning a count here would pin the npm on the build machine. What IS asserted is the publish
    // and the read-back, exactly, and those are the story.
    //
    // The negative claim the count cannot make, though, still holds and is worth stating: however
    // many requests npm decided to send, EVERY one of them was this pipeline's. The set of
    // initiators is the story's promise even where the number is the client's — and a story that
    // forgot to name itself would leak the framework's default `a caller` into the diagram, which
    // is precisely what this catches.
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY, SLUG, List.of(ACTOR));
    // The whole bundle, as bytes: the token must be nowhere in it, including the .npmrc dump.
    ReportAssertions.assertNotLeaked(CATEGORY, SLUG, TOKEN);
  }
}
