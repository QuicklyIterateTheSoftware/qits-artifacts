package eu.wohlben.qits.stories.maven;

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
 * The maven category's <b>browser</b> story: headless Chromium over the explorer SPA, against the
 * same packaged process the two command-line stories drove.
 *
 * <p>It is also the only story in this repository that uses the explorer's <b>search box</b>, and
 * the maven listing is where a search stops being decoration: a repository holds one row per
 * {@code groupId:artifactId} and a platform's coordinates run to hundreds, so finding one by
 * scrolling is not the workflow anybody has. The filter is client-side over the rows already
 * fetched, which is why the story fills it and screenshots the result rather than waiting for a
 * request.
 *
 * <p>Two selector facts, both learned the expensive way. The coordinate cell is a {@code th.mono}
 * with an anchor inside it — not the {@code th[scope="row"]} the daemons and docs listings use — so
 * a selector copied from those tables matches nothing here. And on the coordinate page the version
 * is the header's <b>own text</b> rather than a link, so {@code th:text-is(…)} is right there and
 * would be wrong on the daemon page, where the same-looking cell wraps a download anchor.
 */
@QuarkusIntegrationTest
@TestProfile(PackagedProcessIT.TargetDirState.class)
@EnabledIf("eu.wohlben.qits.stories.support.Cli#mvnPresent")
public class MavenExploreIT {

  static final String CATEGORY = "maven";
  static final String SLUG = "an-operator-finds-a-deployed-coordinate-and-its-files";

  @TestHTTPResource("/")
  URL root;

  @UserStory(value = "An operator finds a deployed coordinate and its files", category = "maven")
  @UserflowPrecondition(MavenDeployIT.class)
  @UserflowRunsAfter(MavenResolveIT.class)
  @UserStoryDescription(
      """
      The repository's own web UI, served by the same process that stores the bytes. An operator
      opens the store's repositories at the host root, steps into the maven repository, narrows a
      list of coordinates down to the one they want, and opens it — arriving at the screen that
      answers the question a maven client cannot be asked: not "is this version there?", which a
      resolve answers, but "what files IS this version?", which is the jar, the pom and every
      checksum sidecar a deploy uploaded, with the size each one occupies.
      """)
  void anOperatorFindsADeployedCoordinate(
      Flow flow, Interactions story, UserflowContext context) {
    String coordinate = context.require("story.maven.coordinate", String.class);
    String version = context.require("story.maven.version", String.class);

    // The gateway's job, played for the browser, before the first navigate.
    StoryBrowser.asOperator(flow);

    story.note("the coordinate this operator is looking for was deployed over the wire, not seeded");

    // The template form keeps the definition hash stable although the launched process sits on a
    // random port: the fingerprint records `navigate {}`, the display the real URL.
    flow.navigate("{}", root);
    flow.waitFor("table tbody th a");
    flow.screenshot("the store's repositories").as("repositories-listed");

    // :text-is is exact, so the `maven-packages` type badge in the row's second cell cannot shadow
    // the repository link in its header.
    flow.click("tbody th a:text-is('maven')");
    flow.waitFor("tbody th.mono a:text-is('" + coordinate + "')");

    // The search box: the maven section renders exactly one, and only when the coordinate listing
    // has loaded — which the wait above has already established.
    flow.fill("label.search input[type=\"search\"]", MavenDeployIT.ARTIFACT_ID);
    flow.waitFor("tbody th.mono a:text-is('" + coordinate + "')");
    flow.screenshot("the maven repository's coordinates, filtered").as("coordinate-listed");

    flow.click("tbody th.mono a:text-is('" + coordinate + "')");
    // The version is the header cell's own text here, so :text-is matches the th itself.
    flow.waitFor("tbody th.mono:text-is('" + version + "')");
    // The files column, which is the whole reason this page exists rather than a version list.
    flow.expectText("tbody td.subtle", MavenDeployIT.ARTIFACT_ID + "-" + version + ".jar");
    flow.expectText("tbody td.subtle", MavenDeployIT.ARTIFACT_ID + "-" + version + ".pom");
    flow.screenshot("the coordinate's versions and the files behind one").as("files-shown");
  }

  @AfterAll
  static void storyReportIsComplete() {
    if (!Cli.mvnPresent()) {
      return;
    }
    ReportAssertions.assertComplete(CATEGORY, SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "repositories-listed");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "coordinate-listed");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "files-shown");
  }
}
