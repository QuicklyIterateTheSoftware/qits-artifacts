package eu.wohlben.qits.stories.docs;

import eu.wohlben.qits.PackagedProcessIT;
import eu.wohlben.qits.stories.support.Cli;
import eu.wohlben.qits.stories.support.StoryBrowser;
import eu.wohlben.qits.userflows.Flow;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.UserflowContext;
import eu.wohlben.qits.userflows.UserflowPrecondition;
import eu.wohlben.qits.userflows.UserflowRunsAfter;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.net.URL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * The docs category's browser story: finding a published site without knowing its URL.
 *
 * <p>The site segment is where the two spellings of a name with a slash in it meet, and they are
 * not interchangeable. The SPA's router encodes {@code @userflows/story-site} to {@code %2F} on the
 * way into its route and decodes it back out, so the address bar carries the escape — while the
 * "Open" link on the page spells the slashes <b>literally</b>, because it leaves the application
 * for the docs wire, whose grammar accepts no percent-encoded separator. Asserting {@code %2F} on
 * that link would be asserting a 404.
 */
@QuarkusIntegrationTest
@TestProfile(PackagedProcessIT.TargetDirState.class)
@EnabledIf("eu.wohlben.qits.stories.support.Cli#curlAndTarPresent")
public class DocsExploreIT {

  static final String CATEGORY = "docs";
  static final String SLUG = "an-operator-finds-a-published-site-and-its-versions";

  @TestHTTPResource("/")
  URL root;

  @UserStory(value = "An operator finds a published site and its versions", category = "docs")
  @UserflowPrecondition(DocsPublishIT.class)
  @UserflowRunsAfter(DocsReadIT.class)
  @UserStoryDescription(
      """
      An operator who has a documentation site and no link to it. The store's repositories, the
      docs repository one click in, and the site's versions — each with the branch and commit it
      was published from printed beside it, and a way straight into the bundle. Those two columns
      are what the publisher declared on the upload rather than anything this store verified, which
      is why they are printed to match against a build and are deliberately not links.
      """)
  void anOperatorFindsAPublishedSite(Flow flow, Interactions story, UserflowContext context) {
    String site = context.require("story.docs.site", String.class);
    String version = context.require("story.docs.version", String.class);

    StoryBrowser.asOperator(flow);
    story.note("a site name may contain a slash: this is one site, not a scope and a name");

    flow.navigate("{}", root);
    flow.waitFor("table tbody th a");
    flow.screenshot("the store's repositories").as("repositories-listed");

    // :text-is is exact, so the `docs` type badge in the row's second cell cannot shadow the
    // repository link in its header.
    flow.click("tbody th a:text-is('docs')");
    flow.waitFor("tbody th[scope=\"row\"] a:text-is('" + site + "')");
    flow.screenshot("the docs repository's sites").as("site-listed");

    flow.click("tbody th[scope=\"row\"] a:text-is('" + site + "')");
    flow.waitFor("th:text-is('" + version + "')");
    // The branch/commit cell, which is the whole reason those two columns exist.
    flow.expectText("tbody tr td.mono.subtle", DocsPublishIT.BRANCH);
    // And the way out of the explorer and into the bundle itself.
    flow.waitFor("[data-testid=\"open-bundle\"]");
    flow.screenshot("the site's versions, with the build each came from").as("versions-shown");
  }

  @AfterAll
  static void storyReportIsComplete() {
    if (!Cli.curlAndTarPresent()) {
      return;
    }
    ReportAssertions.assertComplete(CATEGORY, SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "repositories-listed");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "site-listed");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "versions-shown");
  }
}
