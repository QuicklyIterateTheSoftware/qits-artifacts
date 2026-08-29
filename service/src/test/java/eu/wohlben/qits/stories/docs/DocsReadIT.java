package eu.wohlben.qits.stories.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * The consuming half of the docs chain: finding the version you want, and reading it.
 *
 * <p>Two properties of the read surface are what make published documentation usable at all, and
 * both are asserted here rather than described. The version list takes {@code ?meta.<key>=<value>}
 * predicates, so "the docs for main" is a query rather than a naming convention somebody has to
 * keep — and a filter that matches nothing answers an empty list with a {@code 200}, because "no
 * version was published from that branch" is an answer and not an error. And a served file carries
 * its blob digest as its {@code ETag} under an immutable cache header, which makes a revalidation
 * free and makes two versions sharing an asset visibly share it.
 */
@QuarkusIntegrationTest
@TestProfile(PackagedProcessIT.TargetDirState.class)
@EnabledIf("eu.wohlben.qits.stories.support.Cli#curlAndTarPresent")
public class DocsReadIT {

  static final String CATEGORY = "docs";
  static final String SLUG = "a-reader-opens-the-published-documentation-by-version";

  private static final ObjectMapper JSON = new ObjectMapper();

  @TestHTTPResource("/")
  URL root;

  @UserStory(value = "A reader opens the published documentation by version", category = "docs")
  @UserflowPrecondition(DocsPublishIT.class)
  @UserStoryDescription(
      """
      A reader who knows what they are looking for. They ask the site what versions it has, narrow
      that by the branch a version was built from, and open the page — a plain GET that answers the
      exact bytes the bundle was published with, tagged with their own digest and marked immutable
      so nothing between here and the reader ever has to ask again.
      """)
  void aReaderOpensThePublishedDocumentation(
      Interactions story, Commands commands, UserflowContext context) throws IOException {
    StoryTarget target = new StoryTarget(root);
    String site = context.require("story.docs.site", String.class);
    String version = context.require("story.docs.version", String.class);
    String siteUrl = target.docsBase() + "/docs/" + site;

    Path work = commands.workDir();

    commands
        .run("{} -sS -o {} -w %{http_code} {}", Cli.curl(), "versions.json", siteUrl)
        .as("versions-listed");
    assertEquals("200", commands.lastOutput().strip(), "the version listing's status");
    JsonNode listed = JSON.readTree(Files.readString(work.resolve("versions.json")));
    assertEquals(site, listed.path("name").asText(), "the site the listing is about");
    assertEquals(1, listed.path("versions").size(), "one version has been published");
    assertEquals(
        version, listed.path("versions").get(0).path("version").asText(), "the version listed");

    // The metadata filter, in both directions. A predicate that matches is the useful half; a
    // predicate that matches nothing answering 200 with an empty list — rather than a 404 — is the
    // half that makes it safe to build a "docs for this branch" link that may not exist yet.
    commands
        .run(
            "{} -sS -o {} -w %{http_code} {}",
            Cli.curl(),
            "on-main.json",
            siteUrl + "?meta.git.branch.name=" + DocsPublishIT.BRANCH)
        .as("branch-filtered");
    JsonNode onMain = JSON.readTree(Files.readString(work.resolve("on-main.json")));
    assertEquals(1, onMain.path("versions").size(), "the version was published from main");

    commands.run(
        "{} -sS -o {} -w %{http_code} {}",
        Cli.curl(),
        "elsewhere.json",
        siteUrl + "?meta.git.branch.name=elsewhere");
    JsonNode elsewhere = JSON.readTree(Files.readString(work.resolve("elsewhere.json")));
    assertEquals("200", commands.lastOutput().strip(), "an unmatched filter is still an answer");
    assertEquals(0, elsewhere.path("versions").size(), "nothing was published from that branch");

    commands
        .run(
            "{} -sS -D {} -o {} -w %{http_code} {}",
            Cli.curl(),
            "response-headers.txt",
            "index.html",
            siteUrl + "/-/" + version + "/index.html")
        .as("page-served");
    assertEquals("200", commands.lastOutput().strip(), "the page's status");

    Path page = work.resolve("index.html");
    String headers = Files.readString(work.resolve("response-headers.txt"));
    Optional<String> etag = headerValue(headers, "etag");
    assertTrue(etag.isPresent(), () -> "no ETag on the served page:\n" + headers);
    assertEquals(
        "\"" + StoryMedia.sha256Hex(page) + "\"",
        etag.orElseThrow(),
        "the ETag is the digest of the blob behind this path, so a revalidation costs nothing");
    assertTrue(
        headerValue(headers, "cache-control").orElse("").contains("immutable"),
        () -> "a published version never changes and the response must say so:\n" + headers);

    story
        .happened(
            "a reader",
            "qits-artifacts",
            "GET /artifacts/docs/docs/" + DocsPublishIT.SITE + "/-/" + DocsPublishIT.VERSION
                + "/index.html -> 200")
        .as("read-recorded");
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
    if (!Cli.curlAndTarPresent()) {
      return;
    }
    ReportAssertions.assertComplete(CATEGORY, SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "versions-listed");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "branch-filtered");
    ReportAssertions.assertCommandOutputContains(CATEGORY, SLUG, "on-main.json", "200");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "page-served");
    ReportAssertions.assertCommand(CATEGORY, SLUG, "index.html", 0);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "read-recorded");
    ReportAssertions.assertInteraction(
        CATEGORY,
        SLUG,
        "a reader",
        "qits-artifacts",
        "GET /artifacts/docs/docs/" + DocsPublishIT.SITE + "/-/" + DocsPublishIT.VERSION
            + "/index.html -> 200");
  }
}
