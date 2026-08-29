package eu.wohlben.qits.stories.npm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.PackagedProcessIT;
import eu.wohlben.qits.npm.NpmClient;
import eu.wohlben.qits.stories.support.Cli;
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
import java.net.URI;
import java.net.URL;
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
    story
        .happened(
            "a release pipeline",
            "qits-artifacts",
            "npm publish " + PACKAGE + "@" + VERSION + " -> 201")
        .as("publish-recorded");

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
    ReportAssertions.assertInteraction(
        CATEGORY,
        SLUG,
        "a release pipeline",
        "qits-artifacts",
        "npm publish " + PACKAGE + "@" + VERSION + " -> 201");
    // The whole bundle, as bytes: the token must be nowhere in it, including the .npmrc dump.
    ReportAssertions.assertNotLeaked(CATEGORY, SLUG, TOKEN);
  }
}
