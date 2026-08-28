package eu.wohlben.qits;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.npm.NpmClient;
import eu.wohlben.qits.npm.TinyPackage;
import eu.wohlben.qits.userflows.Flow;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.net.URI;
import java.net.URL;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;

/**
 * The first <b>browser</b> userflow: headless Chromium over the explorer SPA, against the packaged
 * process. The web UI is <b>embedded</b> — Quinoa builds src/main/webui into the artifact and the
 * process serves it at {@code /} — so the browser flow runs through the service itself, on the same
 * launched instance the wire cases drive; there is no separate frontend to point a browser at, and
 * no other suite that could see the UI at all (Quinoa is disabled under {@code @QuarkusTest}).
 *
 * <p>A <b>mixed</b> story: the {@link Interactions} half records the wire publish, the {@link Flow}
 * half drives the browser, and both land in one interleaved step log — with screenshots and a video
 * emitted under {@code target/userstories/}. Chromium comes from {@code PLAYWRIGHT_BROWSERS_PATH}
 * (baked into the workspace image and the userflows-base CI image); a bare machine downloads one.
 *
 * <p>{@code PackagedProcessIT}'s profile is reused deliberately: same launched app, one boot for
 * both classes, and this story needs exactly what that profile provides — the seeded repository
 * rows and no auth gate. The story publishes its own uniquely named package, so class order does
 * not matter.
 */
@QuarkusIntegrationTest
@TestProfile(PackagedProcessIT.TargetDirState.class)
public class ExplorerBrowseIT {

  static final String CATEGORY = "explorer";
  static final String SLUG = "a-published-npm-package-is-browsable-in-the-explorer";

  static final String PACKAGE = "@qits/story-browse";

  @TestHTTPResource("/")
  URL root;

  @UserStory(
      value = "A published npm package is browsable in the explorer",
      category = "explorer")
  @UserStoryDescription(
      """
      The registry's own web UI, served by the same process that stores the bytes: a release
      pipeline publishes an npm package over the wire, and a developer finds it in the explorer —
      the store's repositories at the host root, the npm repository's packages one click in, and
      the immutable version list one more.
      """)
  void aPublishedPackageIsBrowsableInTheExplorer(Flow flow, Interactions story) throws Exception {
    // The wire half. The same synthesised-in-memory publish the wire suites use — no npm binary,
    // no network beyond the launched process — recorded as the story's opening interaction.
    TinyPackage subject = TinyPackage.of(PACKAGE, "1.0.0");
    try (NpmClient npm = new NpmClient(URI.create(root.toString()))) {
      assertEquals(
          201,
          npm.publish("npm", "@qits%2fstory-browse", subject.publishDocument("latest"))
              .statusCode(),
          "the publish this story browses");
    }
    story
        .happened("a release pipeline", "qits-artifacts", "npm publish " + PACKAGE + "@1.0.0 -> 201")
        .as("published");

    // The browser half. First, the gateway's job, played for the browser: every JSON read the SPA
    // makes is @RolesAllowed("qits:admin"), answered from the X-Qits-User/X-Qits-Roles pair
    // qits-gateway injects for a signed-in operator — and the packaged process (LaunchMode NORMAL)
    // has no %test synthetic identity to answer instead, so without these headers every fetch is
    // an anonymous 401 and the page renders its error states. page() records nothing: this is
    // harness plumbing, not a story step.
    flow.page()
        .setExtraHTTPHeaders(Map.of("X-Qits-User", "story-operator", "X-Qits-Roles", "qits:admin"));

    // The template form keeps the definition hash stable although the launched process sits on a
    // random port: the fingerprint records `navigate {}`, the display the real URL.
    flow.navigate("{}", root);
    flow.waitFor("table tbody th a");
    flow.screenshot("the store's repositories").as("repositories-listed");

    // The repository rows are seeded at startup, so `npm` is a link whether or not any other test
    // has run. :text-is is exact, so the `npm-packages` type badge cannot shadow it.
    flow.click("tbody th a:text-is('npm')");
    flow.waitFor("tbody th a:text-is('" + PACKAGE + "')");
    flow.screenshot("the npm repository's packages").as("package-listed");

    flow.click("tbody th a:text-is('" + PACKAGE + "')");
    flow.waitFor("th:text-is('1.0.0')");
    flow.screenshot("the package's immutable versions").as("versions-shown");
  }

  @AfterAll
  static void storyReportIsComplete() {
    ReportAssertions.assertComplete(CATEGORY, SLUG, UserflowReport.PASSED);
    ReportAssertions.assertInteraction(
        CATEGORY, SLUG, "a release pipeline", "qits-artifacts",
        "npm publish " + PACKAGE + "@1.0.0 -> 201");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "published");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "repositories-listed");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "package-listed");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "versions-shown");
  }
}
