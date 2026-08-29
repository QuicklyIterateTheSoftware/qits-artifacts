package eu.wohlben.qits.stories.npm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.PackagedProcessIT;
import eu.wohlben.qits.stories.support.Cli;
import eu.wohlben.qits.stories.support.StoryTarget;
import eu.wohlben.qits.userflows.Commands;
import eu.wohlben.qits.userflows.Interactions;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * The consuming half of the npm chain: a build that installs what the pipeline published.
 *
 * <p>It is the half that proves the registry is <b>usable</b> rather than merely writable — the
 * packument npm resolves against, the absolute tarball URL it advertises, and the tarball actually
 * arriving — none of which a publish alone can show. The subject is handed over through the
 * {@link UserflowContext} rather than re-declared here, so this story installs the exact version
 * {@link NpmPublishIT} put in the store.
 */
@QuarkusIntegrationTest
@TestProfile(PackagedProcessIT.TargetDirState.class)
@EnabledIf("eu.wohlben.qits.stories.support.Cli#npmPresent")
public class NpmInstallIT {

  static final String CATEGORY = "npm";
  static final String SLUG = "a-build-installs-the-published-package-from-the-platform-registry";

  private static final ObjectMapper JSON = new ObjectMapper();

  @TestHTTPResource("/")
  URL root;

  @UserStory(
      value = "A build installs the published package from the platform registry",
      category = "npm")
  @UserflowPrecondition(NpmPublishIT.class)
  @UserStoryDescription(
      """
      The other end of a publish. A consuming build declares nothing but a dependency, points npm
      at the platform registry, and gets the bytes — the packument, the absolute tarball URL it
      advertises and the tarball behind it, all resolved by the real npm CLI. What lands in
      node_modules is then read in Java, because "npm exited zero" and "the right version is on
      disk" are two different claims.
      """)
  void aBuildInstallsThePublishedPackage(
      Interactions story, Commands commands, UserflowContext context) throws IOException {
    StoryTarget target = new StoryTarget(root);
    String name = context.require("story.npm.name", String.class);
    String version = context.require("story.npm.version", String.class);

    commands.env("HOME", commands.workDir().toAbsolutePath().toString());
    // A cache of its own, inside the scratch that is wiped per run: an install that answered from
    // a warm cache would pass with the registry switched off.
    commands.env(
        "npm_config_cache", commands.workDir().resolve(".npm-cache").toAbsolutePath().toString());

    // The consuming build gets a directory beside the cache rather than above it, the layout
    // NpmPublishIT explains — so node_modules holds what the registry served and nothing else.
    commands.in("consumer");

    commands
        .file(
            "package.json",
            """
            {
              "name": "story-consumer",
              "version": "0.0.0",
              "private": true,
              "description": "A build that consumes the platform registry."
            }
            """)
        .as("consumer-prepared");

    // Both registry flags again, for the reason NpmPublishIT spells out: --@qits:registry beats the
    // workspace npm shim's environment injection, which would otherwise resolve @qits/* against the
    // platform's own registry rather than the process under test.
    commands
        .run(
            "{} install {}@{} --registry {} --@qits:registry={} --no-audit --no-fund",
            Cli.npm(),
            name,
            version,
            target.npmRegistry(),
            target.npmRegistry())
        .as("package-installed");

    // What is on disk, read in Java. npm's exit code says the resolve succeeded; only this says the
    // right bytes arrived.
    Path installed =
        commands
            .workDir()
            .resolve("consumer")
            .resolve("node_modules")
            .resolve(name)
            .resolve("package.json");
    assertTrue(Files.isRegularFile(installed), () -> "npm installed nothing at " + installed);
    JsonNode manifest = JSON.readTree(Files.readString(installed));
    assertEquals(name, manifest.path("name").asText(), "the installed package's name");
    assertEquals(
        version, manifest.path("version").asText(), "the installed package's version");
    story
        .note("node_modules holds " + NpmPublishIT.PACKAGE + " at the version the pipeline published")
        .as("contents-verified");

    story
        .happened("a build", "qits-artifacts", "npm install " + NpmPublishIT.PACKAGE + " -> 200")
        .as("install-recorded");
  }

  @AfterAll
  static void storyReportIsComplete() {
    if (!Cli.npmPresent()) {
      return;
    }
    ReportAssertions.assertComplete(CATEGORY, SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "consumer-prepared");
    ReportAssertions.assertWroteFile(CATEGORY, SLUG, "package.json");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "package-installed");
    ReportAssertions.assertCommand(CATEGORY, SLUG, "install " + NpmPublishIT.PACKAGE, 0);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "contents-verified");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "install-recorded");
    ReportAssertions.assertInteraction(
        CATEGORY,
        SLUG,
        "a build",
        "qits-artifacts",
        "npm install " + NpmPublishIT.PACKAGE + " -> 200");
  }
}
