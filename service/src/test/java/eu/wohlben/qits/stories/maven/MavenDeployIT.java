package eu.wohlben.qits.stories.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.PackagedProcessIT;
import eu.wohlben.qits.maven.MavenClient;
import eu.wohlben.qits.maven.TinyArtifact;
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
import java.net.URI;
import java.net.URL;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * The maven category's producer: the real {@code mvn} launcher, deploying a jar to the launched
 * process.
 *
 * <p>Every other maven suite in this repository drives a <b>synthesised</b> client — {@code
 * maven/MavenClient} over the JDK's {@code HttpClient} — because {@code mvn verify} may not assume
 * a maven binary is reachable and the wire questions there are about path grammar rather than about
 * the tool. This story is the other half of that: what a release pipeline actually types, run by
 * the launcher maven itself was started with, with the derived {@code maven-metadata.xml} read back
 * afterwards so the story asserts the server's answer rather than the plugin's exit code alone.
 *
 * <p>Three details of the command line are load-bearing and each one costs a build to relearn.
 *
 * <ul>
 *   <li><b>The plugin version is pinned.</b> An unpinned {@code deploy-file} makes maven resolve
 *       LATEST from central on every run, which is a network call the coordinate itself does not
 *       need and a different plugin from one week to the next.
 *   <li><b>There is no {@code -s}.</b> The fork inherits whatever settings the surrounding build
 *       runs with, which is what a nested maven invocation does in a real pipeline. Maven's default
 *       {@code external:http:*} mirror blocker does <b>not</b> refuse the target URL: that pattern
 *       exempts {@code localhost} and {@code 127.0.0.1} by definition, and the launched process is
 *       always on {@code localhost}.
 *   <li><b>The local repository is the surrounding build's</b> — see {@link #localRepository()}.
 *       Isolation comes from the coordinate instead: {@code eu.wohlben.qits.stories:story-library}
 *       is published nowhere, so nothing can answer for it but this process.
 * </ul>
 *
 * <p>Browserless: {@link Interactions} and {@link Commands}, no {@link
 * eu.wohlben.qits.userflows.Flow}, so no Chromium is launched and the report is the transcript.
 */
@QuarkusIntegrationTest
@TestProfile(PackagedProcessIT.TargetDirState.class)
@EnabledIf("eu.wohlben.qits.stories.support.Cli#mvnPresent")
public class MavenDeployIT {

  static final String CATEGORY = "maven";
  static final String SLUG = "a-release-pipeline-deploys-a-jar-to-the-platform-repository";

  /** The subject the whole maven chain shares; the resolve and explore stories read it back. */
  public static final String GROUP_ID = "eu.wohlben.qits.stories";

  public static final String ARTIFACT_ID = "story-library";

  public static final String VERSION = "1.0.0";

  /** {@code groupId:artifactId} — the spelling the explorer's coordinate listing uses. */
  public static final String COORDINATE = GROUP_ID + ":" + ARTIFACT_ID;

  /** The repository-relative path of the deployed jar, which is also the wire path under it. */
  public static final String JAR_PATH =
      GROUP_ID.replace('.', '/')
          + "/"
          + ARTIFACT_ID
          + "/"
          + VERSION
          + "/"
          + ARTIFACT_ID
          + "-"
          + VERSION
          + ".jar";

  /**
   * The jar's content, and it is <b>fixed rather than salted</b> — the one place this category
   * departs from the run-uniqueness rule, deliberately. {@link TinyArtifact} stamps every zip entry
   * with the epoch, so a fixed string means byte-identical jars run after run; the resolve story's
   * local repository is the shared build cache, and a cached copy of this coordinate is then the
   * same bytes as the one just deployed rather than a stale impostor that would fail its digest
   * comparison for a reason nothing in the report could explain. Nothing here counts stored bytes,
   * so the deduplication that a fixed fixture invites costs this chain nothing.
   */
  private static final String CONTENT = "the maven userflow chain's library";

  @TestHTTPResource("/")
  URL root;

  @UserStory(value = "A release pipeline deploys a jar to the platform repository", category = "maven")
  @UserStoryDescription(
      """
      The maven repository as a release pipeline meets it: a built jar, one `deploy-file` against
      the platform URL, and a coordinate that exists from then on. The tool is the real maven
      launcher rather than this repository's synthetic client — what a pipeline types is the thing
      under test — and the `maven-metadata.xml` behind the coordinate is read back afterwards to
      show that the repository DERIVED it from the files it received, rather than storing whatever
      document the plugin happened to send.
      """)
  void aReleasePipelineDeploysAJar(
      Interactions story, Commands commands, UserflowContext context) throws IOException {
    StoryTarget target = new StoryTarget(root);

    // workDir() creates and wipes the scratch on first use, so it is taken before anything is
    // written into it.
    Path work = commands.workDir();
    byte[] jar = TinyArtifact.jar(CONTENT);
    Path jarFile = work.resolve(ARTIFACT_ID + "-" + VERSION + ".jar");
    Files.write(jarFile, jar);
    String expected = StoryMedia.sha256Hex(jar);
    story.note("a build produced the jar this release deploys").as("artifact-built");

    // -Dfile and -Dmaven.repo.local are ABSOLUTE: they are resolved by the forked maven, not by
    // this JVM, and an absolute value is the only spelling that cannot depend on where that fork
    // decided its own working directory is. Both ride as {} arguments, so the recorded fingerprint
    // keeps the template and the story's definition hash survives a different machine's paths.
    commands
        .run(
            "{} -B -ntp -Dmaven.repo.local={}"
                + " org.apache.maven.plugins:maven-deploy-plugin:3.1.4:deploy-file"
                + " -DrepositoryId=qits-story -Durl={} -Dfile={}"
                + " -DgroupId={} -DartifactId={} -Dversion={} -Dpackaging=jar",
            Cli.mvn(),
            localRepository(),
            target.mavenRepository(),
            jarFile.toAbsolutePath(),
            GROUP_ID,
            ARTIFACT_ID,
            VERSION)
        .as("artifact-deployed");

    // The repository's own answer, read with the synthetic client: the plugin reports what it sent,
    // and what is worth recording is what the store now serves. maven-metadata.xml is the sharpest
    // of those reads because the plugin PUT one of its own and the repository DERIVES the document
    // it serves from the files it holds — so this asserts the server rather than the echo.
    try (MavenClient maven = new MavenClient(URI.create(root.toString()))) {
      String coordinateDir = GROUP_ID.replace('.', '/') + "/" + ARTIFACT_ID;
      HttpResponse<String> metadata = maven.getText("maven", coordinateDir + "/maven-metadata.xml");
      assertEquals(200, metadata.statusCode(), metadata.body());
      assertTrue(
          metadata.body().contains("<release>" + VERSION + "</release>"),
          () -> "the derived metadata must name the released version:\n" + metadata.body());
      assertTrue(
          metadata.body().contains("<version>" + VERSION + "</version>"),
          () -> "the derived metadata must list the version:\n" + metadata.body());
    }
    story
        .happened(
            "a release pipeline",
            "qits-artifacts",
            "PUT /artifacts/maven/maven/" + JAR_PATH + " -> 201")
        .as("deploy-recorded");

    context.put("story.maven.coordinate", COORDINATE);
    context.put("story.maven.version", VERSION);
    context.put("story.maven.jar-sha256", expected);
  }

  /**
   * The local repository the forked maven must use — the surrounding build's, never a fresh one.
   *
   * <p>A story-private local repository would be the tidier isolation, and it does not work: an
   * empty one holds no {@code deploy-file} and no {@code dependency:get} plugin either, so the fork
   * would re-resolve both from central on every run. The build's own repository already has
   * everything a nested maven needs, which is exactly why a real pipeline shares it too.
   *
   * <p>Resolved in three steps because the value reaches this JVM by two different routes and may
   * reach it by neither. Surefire and failsafe forward the maven process' system properties into
   * the test JVM, so a build run with {@code -Dmaven.repo.local=…} on the command line is visible
   * here directly; a build that set it through {@code MAVEN_OPTS} instead put it on the maven JVM
   * only, so the environment variable is parsed as the second source. With neither, this returns
   * maven's own default, which makes passing the flag a no-op rather than a redirection — the
   * command shape stays the same on every machine, and so does the definition hash derived from it.
   */
  static Path localRepository() {
    String declared = System.getProperty("maven.repo.local");
    if (declared == null || declared.isBlank()) {
      declared = fromMavenOpts(System.getenv("MAVEN_OPTS"));
    }
    if (declared == null || declared.isBlank()) {
      return Path.of(System.getProperty("user.home"), ".m2", "repository").toAbsolutePath();
    }
    return Path.of(declared.strip()).toAbsolutePath();
  }

  /** {@code -Dmaven.repo.local=<value>} out of a {@code MAVEN_OPTS} string, if it carries one. */
  private static String fromMavenOpts(String mavenOpts) {
    if (mavenOpts == null) {
      return null;
    }
    for (String token : mavenOpts.split("\\s+")) {
      if (token.startsWith("-Dmaven.repo.local=")) {
        return token.substring("-Dmaven.repo.local=".length());
      }
    }
    return null;
  }

  @AfterAll
  static void storyReportIsComplete() {
    if (!Cli.mvnPresent()) {
      return;
    }
    ReportAssertions.assertComplete(CATEGORY, SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "artifact-built");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "artifact-deployed");
    ReportAssertions.assertCommand(CATEGORY, SLUG, "deploy-file", 0);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "deploy-recorded");
    ReportAssertions.assertInteraction(
        CATEGORY,
        SLUG,
        "a release pipeline",
        "qits-artifacts",
        "PUT /artifacts/maven/maven/" + JAR_PATH + " -> 201");
  }
}
