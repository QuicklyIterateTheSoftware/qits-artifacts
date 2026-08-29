package eu.wohlben.qits.stories.daemons;

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
 * The daemon category's browser story — and the only way to answer "what daemons are in there?" at
 * all.
 *
 * <p>The wire under this listing has <b>no enumeration of its own</b>: every route below {@code
 * /artifacts/daemons} is version-addressed, so what exists is only askable through the explorer.
 * That makes this screen the daemon plane's index rather than a convenience over one.
 *
 * <p>The browse endpoint behind it reads {@code /artifacts/api/repositories/daemons/daemons} — the
 * doubled segment is correct and not a typo: the wire carries no repository segment, while the
 * explorer's subject is a repository throughout, so the seeded {@code daemons} row is both the
 * repository and the listing under it.
 */
@QuarkusIntegrationTest
@TestProfile(PackagedProcessIT.TargetDirState.class)
@EnabledIf("eu.wohlben.qits.stories.support.Cli#curlPresent")
public class DaemonExploreIT {

  static final String CATEGORY = "daemons";
  static final String SLUG = "an-operator-finds-a-published-daemon-and-its-versions";

  @TestHTTPResource("/")
  URL root;

  @UserStory(value = "An operator finds a published daemon and its versions", category = "daemons")
  @UserflowPrecondition(DaemonPublishIT.class)
  @UserflowRunsAfter(DaemonDownloadIT.class)
  @UserStoryDescription(
      """
      An operator asking what the platform would boot. The daemon wire is version-addressed from
      end to end and lists nothing, so this screen is the only place the question can be asked: the
      store's repositories, the daemons repository one click in, and one daemon's versions with the
      exact sha256 each one is — the string a deployment pin is written from.
      """)
  void anOperatorFindsAPublishedDaemon(Flow flow, Interactions story, UserflowContext context) {
    String daemon = context.require("story.daemon.name", String.class);
    String version = context.require("story.daemon.version", String.class);

    StoryBrowser.asOperator(flow);
    story.note("the daemon wire lists nothing of its own: this screen is the only enumeration");

    flow.navigate("{}", root);
    flow.waitFor("table tbody th a");
    flow.screenshot("the store's repositories").as("repositories-listed");

    // :text-is is exact, so the `daemon-binaries` type badge in the row's second cell cannot
    // shadow the repository link in its header.
    flow.click("tbody th a:text-is('daemons')");
    flow.waitFor("tbody th[scope=\"row\"] a:text-is('" + daemon + "')");
    flow.screenshot("the daemons repository's binaries").as("daemon-listed");

    // A version's link leaves this application entirely — it is the wire download at
    // /artifacts/daemons/<name>/<version> — so the page is reached by clicking the daemon, never
    // by clicking a version.
    flow.click("tbody th[scope=\"row\"] a:text-is('" + daemon + "')");
    // The version is waited for on its ANCHOR, not on the row header around it: `:text-is()`
    // matches the SMALLEST element carrying the text, so on a page where the version cell is a
    // download link `th:text-is(…)` matches nothing at all — which is exactly the difference
    // between this table and the npm package page's, where the version is the header's own text.
    flow.waitFor("tbody th[scope=\"row\"] a:text-is('" + version + "')");
    flow.screenshot("the daemon's versions and their digests").as("versions-shown");
  }

  @AfterAll
  static void storyReportIsComplete() {
    if (!Cli.curlPresent()) {
      return;
    }
    ReportAssertions.assertComplete(CATEGORY, SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "repositories-listed");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "daemon-listed");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "versions-shown");
  }
}
