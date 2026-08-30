package eu.wohlben.qits.stories.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.PackagedProcessIT;
import eu.wohlben.qits.maven.MavenClient;
import eu.wohlben.qits.stories.support.AccessLogSource;
import eu.wohlben.qits.stories.support.Cli;
import eu.wohlben.qits.stories.support.StoryMedia;
import eu.wohlben.qits.stories.support.StoryTarget;
import eu.wohlben.qits.userflows.Commands;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.UserflowContext;
import eu.wohlben.qits.userflows.UserflowPrecondition;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * The consuming half of the maven chain: a build that resolves what the pipeline deployed.
 *
 * <p>It is the half that proves the repository is <b>usable</b> rather than merely writable — the
 * coordinate resolving for a consumer at all, the jar landing in a local repository under its
 * proper path, and the platform repository serving those same bytes together with a checksum it
 * derived itself. The subject is handed over through the {@link UserflowContext} rather than
 * re-declared here, so this story resolves the exact version {@link MavenDeployIT} put in the store.
 *
 * <h2>Where the remoteness claim actually comes from</h2>
 *
 * <p>This story deliberately does <b>not</b> claim that {@code dependency:get} went to the network.
 * It shares the surrounding build's local repository (see {@link MavenDeployIT#localRepository()}),
 * because a private one would hold no plugins and turn every run into a download of the maven
 * toolchain rather than a resolve of this coordinate — so on a machine that has run this chain
 * before, {@code get} may legitimately answer out of the cache. That is what a real consuming build
 * does too, and a step whose truth depended on the cache being cold would be a step that quietly
 * stopped meaning anything on the second run.
 *
 * <p>So the wire round trip is made <b>explicit</b> instead of assumed: {@code bytes-verified}
 * fetches the jar from the platform repository over HTTP and compares three things that must all
 * agree — the bytes the pipeline deployed, the bytes now in the local repository, and the bytes the
 * repository serves under the derived {@code .sha256} it computes from what it stored. Every
 * recorded claim then holds on a cold cache and on a warm one alike.
 */
@QuarkusIntegrationTest
@TestProfile(PackagedProcessIT.TargetDirState.class)
@EnabledIf("eu.wohlben.qits.stories.support.Cli#mvnPresent")
public class MavenResolveIT {

  static final String CATEGORY = "maven";
  static final String SLUG = "a-build-resolves-the-deployed-jar-from-the-platform-repository";

  /** How the diagram names the initiator of everything this story sends. */
  static final String ACTOR = "a build";

  @TestHTTPResource("/")
  URL root;

  @UserStory(
      value = "A build resolves the deployed jar from the platform repository",
      category = "maven")
  @UserflowPrecondition(MavenDeployIT.class)
  @UserStoryDescription(
      """
      The other end of a deploy. A consuming build names a coordinate and a repository URL and gets
      the artifact — resolved by the real maven launcher, landing in a local repository under the
      path every maven client derives the same way. What arrived is then read in Java and compared
      against the platform repository's own answer, because "maven exited zero" and "the bytes the
      pipeline deployed are the bytes a consumer gets" are two different claims, and only the
      second one is worth writing down.
      """)
  void aBuildResolvesTheDeployedJar(
      Interactions story, Commands commands, UserflowContext context) throws Exception {
    StoryTarget target = new StoryTarget(root);
    String coordinate = context.require("story.maven.coordinate", String.class);
    String version = context.require("story.maven.version", String.class);
    String deployed = context.require("story.maven.jar-sha256", String.class);

    // Whose traffic the access log's next lines are, and what kind. Read at drain time, and the
    // actor is reset at every story border, so this never inherits the deploy story's pipeline.
    AccessLogSource.attribute(ACTOR, NetworkEdge.PACKAGE);

    // The scratch is taken so the story owns a directory even though maven writes into the shared
    // local repository rather than here — the transcript still lands beside every other story's.
    commands.workDir();

    commands
        .run(
            "{} -B -ntp -Dmaven.repo.local={}"
                + " org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get"
                + " -Dartifact={} -DremoteRepositories={}",
            Cli.mvn(),
            MavenDeployIT.localRepository(),
            coordinate + ":" + version,
            target.mavenRepository())
        .as("dependency-resolved");

    // Where every maven client puts it, derived rather than parsed out of the tool's output: the
    // group's dots become directories, then the artifact, then the version, then the file.
    Path resolved = MavenDeployIT.localRepository().resolve(MavenDeployIT.JAR_PATH);
    assertTrue(
        Files.isRegularFile(resolved), () -> "maven resolved nothing to " + resolved);
    assertEquals(
        deployed,
        StoryMedia.sha256Hex(resolved),
        "the jar in the local repository must be the bytes the pipeline deployed");

    // And the wire, explicitly — the round trip this story's remoteness claim rests on. The
    // .sha256 beside it is DERIVED by the repository from the bytes it stored; no client ever
    // uploaded one, so an agreement here is the store's own arithmetic over its own copy.
    try (MavenClient maven = new MavenClient(URI.create(root.toString()))) {
      HttpResponse<byte[]> served = maven.get("maven", MavenDeployIT.JAR_PATH);
      assertEquals(200, served.statusCode(), "the jar's status on the wire");
      assertEquals(
          deployed,
          StoryMedia.sha256Hex(served.body()),
          "the platform repository must serve the exact bytes that were deployed");
      HttpResponse<String> checksum = maven.getText("maven", MavenDeployIT.JAR_PATH + ".sha256");
      assertEquals(200, checksum.statusCode(), checksum.body());
      assertEquals(
          deployed,
          checksum.body().strip(),
          "the derived checksum is computed from the stored bytes, not echoed from an upload");
    }
    story
        .note(
            "the local repository, the deployed bytes and the repository's derived checksum are"
                + " one digest")
        .as("bytes-verified");

    // The narrative half. The edge below it is the launched process' own record of the fetch, which
    // is what makes the remoteness claim in the class javadoc evidence rather than an inference:
    // the jar was on the wire in THIS run, warm local repository or not.
    story
        .note("the jar and its derived checksum came off the platform repository in this run")
        .as("resolve-recorded");
    AccessLogSource.awaitLogged(
        "GET " + StoryTarget.MAVEN_PATH + "/" + MavenDeployIT.JAR_PATH + ".sha256");
  }

  @AfterAll
  static void storyReportIsComplete() {
    if (!Cli.mvnPresent()) {
      return;
    }
    ReportAssertions.assertComplete(CATEGORY, SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "dependency-resolved");
    ReportAssertions.assertCommand(CATEGORY, SLUG, "maven-dependency-plugin", 0);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "bytes-verified");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "resolve-recorded");
    // Both halves of the story's own claim, observed in the access log: the jar, and the checksum
    // the repository derived from its stored copy. No count, for the reason MavenDeployIT states —
    // how many requests a resolve is belongs to the client, not to this repository.
    ReportAssertions.assertEdge(
        CATEGORY,
        SLUG,
        NetworkEdge.PACKAGE,
        ACTOR,
        AccessLogSource.SERVICE,
        "GET " + StoryTarget.MAVEN_PATH + "/" + MavenDeployIT.JAR_PATH + " -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        SLUG,
        NetworkEdge.PACKAGE,
        ACTOR,
        AccessLogSource.SERVICE,
        "GET " + StoryTarget.MAVEN_PATH + "/" + MavenDeployIT.JAR_PATH + ".sha256 -> 200");
    // What the missing count can still say: whatever else the forked maven asked for, it asked as
    // this build and as nobody else.
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY, SLUG, List.of(ACTOR));
  }
}
