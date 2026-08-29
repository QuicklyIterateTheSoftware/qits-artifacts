package eu.wohlben.qits.stories.docs;

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
 * The docs category's producer: a built site, packed the way a pipeline packs one, published at a
 * version that can never be re-cut.
 *
 * <p>Two things about the {@code tar} line are load-bearing and both are easy to "simplify" wrong.
 * {@code -C <dir> .} packs the site's <i>contents</i>; packing the directory itself would put a
 * {@code site/} component on every entry and therefore into every served URL, so the published
 * documentation would answer at {@code …/site/index.html} and nowhere a reader would look. And the
 * {@code ./} prefix that spelling produces on every entry is exactly what the publish path
 * normalises away — a fixture that avoided it would be testing a bundle no pipeline creates.
 *
 * <p>The publish URL spells its slashes <b>literally</b>. Unlike npm's, this grammar accepts no
 * percent-encoded separator: {@code @userflows/story-site} is one site name with a separator inside
 * it, and the wire wants it that way round. (The explorer's JSON API takes either spelling, which
 * is a different surface with a different reason.)
 */
@QuarkusIntegrationTest
@TestProfile(PackagedProcessIT.TargetDirState.class)
@EnabledIf("eu.wohlben.qits.stories.support.Cli#curlAndTarPresent")
public class DocsPublishIT {

  static final String CATEGORY = "docs";
  static final String SLUG = "a-release-pipeline-publishes-a-documentation-bundle";

  /** The subject the docs chain shares — one site name, separator and all. */
  public static final String SITE = "@userflows/story-site";

  public static final String VERSION = "2026.1.1";

  /** The three files {@link StoryMedia#siteTree} lays down: a page, an asset and a shared font. */
  private static final int FILES = 3;

  static final String BRANCH = "main";
  static final String COMMIT = "b".repeat(40);

  private static final ObjectMapper JSON = new ObjectMapper();

  @TestHTTPResource("/")
  URL root;

  @UserStory(value = "A release pipeline publishes a documentation bundle", category = "docs")
  @UserStoryDescription(
      """
      Documentation as an artifact rather than a deployment. A build produces a static site, the
      pipeline packs it into one tar.gz, and a single PUT publishes the whole thing at a version —
      with the branch and commit it came from riding along as metadata the store keeps but never
      verifies. A version is published whole, evicted whole and immutable, which is what makes a
      link to one safe to hand out: the bytes behind it never change meaning.
      """)
  void aReleasePipelinePublishesADocumentationBundle(
      Interactions story, Commands commands, UserflowContext context) throws IOException {
    StoryTarget target = new StoryTarget(root);

    // workDir() creates and wipes the scratch on first use, so it is taken before anything is
    // written into it.
    Path work = commands.workDir();
    StoryMedia.siteTree(work.resolve("site"));
    story.note("a documentation build produced a static site of three files").as("site-built");

    commands.run("{} -czf {} -C {} .", Cli.tar(), "site.tar.gz", "site").as("bundle-packed");

    // -H 'Expect:' for the reason the daemon publish carries it: Quarkus does not answer
    // 100-continue automatically and curl sends the header for any body over a kilobyte.
    commands
        .run(
            "{} -sS -X PUT -H 'Expect:' -H {} -H {} --data-binary @{} -o {} -w %{http_code} {}",
            Cli.curl(),
            "X-Artifacts-Meta-git.branch.name: " + BRANCH,
            "X-Artifacts-Meta-git.commit.hash: " + COMMIT,
            "site.tar.gz",
            "publish-response.json",
            target.docsBase() + "/docs/" + SITE + "/-/" + VERSION)
        .as("bundle-published");
    assertEquals("201", commands.lastOutput().strip(), "the publish status");

    JsonNode receipt = JSON.readTree(Files.readString(work.resolve("publish-response.json")));
    assertEquals(SITE, receipt.path("name").asText(), "the receipt's site name");
    assertEquals(VERSION, receipt.path("version").asText(), "the receipt's version");
    assertEquals(FILES, receipt.path("fileCount").asInt(), "every entry of the bundle was stored");
    assertEquals(
        BRANCH,
        receipt.path("metadata").path("git.branch.name").asText(),
        "the metadata the publisher declared, echoed back");
    story
        .happened(
            "a release pipeline",
            "qits-artifacts",
            "PUT /artifacts/docs/docs/" + SITE + "/-/" + VERSION + " -> 201")
        .as("publish-recorded");

    context.put("story.docs.site", SITE);
    context.put("story.docs.version", VERSION);
  }

  @AfterAll
  static void storyReportIsComplete() {
    if (!Cli.curlAndTarPresent()) {
      return;
    }
    ReportAssertions.assertComplete(CATEGORY, SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "site-built");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "bundle-packed");
    ReportAssertions.assertCommand(CATEGORY, SLUG, "-czf site.tar.gz", 0);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "bundle-published");
    ReportAssertions.assertCommandOutputContains(CATEGORY, SLUG, "-X PUT", "201");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "publish-recorded");
    ReportAssertions.assertInteraction(
        CATEGORY,
        SLUG,
        "a release pipeline",
        "qits-artifacts",
        "PUT /artifacts/docs/docs/" + SITE + "/-/" + VERSION + " -> 201");
  }
}
