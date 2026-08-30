package eu.wohlben.qits.stories.oci;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.PackagedProcessIT;
import eu.wohlben.qits.registry.TinyImage;
import eu.wohlben.qits.stories.support.AccessLogSource;
import eu.wohlben.qits.stories.support.Cli;
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
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * The consuming half of the image chain: what a deployment does when it is told which image to run.
 *
 * <p>A pull is the half that proves the registry is <b>usable</b> rather than merely writable, and
 * the property it turns on is the one a deploy plan is built from: the bytes are addressed by their
 * own digest end to end. The tag resolves to a manifest, the manifest names its layers by digest,
 * and every blob that arrives hashes to the name it was fetched under — so "run this image" means
 * exactly one set of bytes and can never quietly mean another.
 *
 * <p>The verification is done in <b>Java over the pulled layout</b> rather than by reading skopeo's
 * output. What is asserted is what a runtime would find on disk: the layout's {@code index.json}
 * naming the same manifest digest the registry served the push story, and the layer blob under it
 * being byte-identical to the one the pipeline built. {@link TinyImage#of} is deterministic per
 * salt, which is what lets the expected bytes be rebuilt here rather than carried between stories.
 */
@QuarkusIntegrationTest
@TestProfile(PackagedProcessIT.TargetDirState.class)
@EnabledIf("eu.wohlben.qits.stories.support.Cli#skopeoPresent")
public class OciPullIT {

  static final String CATEGORY = "qits";
  static final String SLUG = "a-deployment-pulls-the-image-it-was-told-to-run";

  /** How the diagram names the initiator of everything this story sends. */
  static final String ACTOR = "a deployment";

  private static final ObjectMapper JSON = new ObjectMapper();

  @TestHTTPResource("/")
  URL root;

  @UserStory(value = "A deployment pulls the image it was told to run", category = "qits")
  @UserflowPrecondition(OciPushIT.class)
  @UserStoryDescription(
      """
      The other end of a push. A deployment knows a name and a tag and nothing else; it copies the
      image out of the platform registry and ends up with a layout on disk. What lands there is
      then read directly — the manifest digest the registry served, and the layer bytes the
      pipeline built — because "skopeo exited zero" and "the bytes that were pushed are the bytes
      that will run" are two different claims, and only the second one is what a deploy plan pins.
      """)
  void aDeploymentPullsTheImage(Interactions story, Commands commands, UserflowContext context)
      throws IOException {
    StoryTarget target = new StoryTarget(root);
    String image = context.require("story.qits.image", String.class);
    String tag = context.require("story.qits.tag", String.class);
    String pushed = context.require("story.qits.manifest-digest", String.class);

    // Whose traffic the access log's next lines are, and what kind. Read at drain time, and the
    // actor is reset at every story border, so this never inherits the push story's pipeline.
    AccessLogSource.attribute(ACTOR, NetworkEdge.PACKAGE);

    Path work = commands.workDir();
    Path pulled = work.resolve("pulled");

    // --src-tls-verify=false here where the push needed --dest-: the insecure end is whichever one
    // is this registry, and skopeo names the two independently.
    commands
        .run(
            "{} --insecure-policy copy --src-tls-verify=false docker://{}/{}/{}:{} oci:{}:{}",
            Cli.skopeo(),
            target.ociHost(),
            OciPushIT.REPOSITORY,
            image,
            tag,
            pulled.toAbsolutePath(),
            OciPushIT.LAYOUT_REF)
        .as("image-pulled");

    // The layout's index names the manifest by digest — the same string the registry echoed on the
    // push. Equality here is what says the client and the registry agree on which bytes this tag is.
    JsonNode index = JSON.readTree(Files.readString(pulled.resolve("index.json")));
    JsonNode descriptor = index.path("manifests").get(0);
    assertEquals(
        pushed,
        descriptor.path("digest").asText(),
        "the pulled manifest must be the one the registry served under this tag");

    // And the layer itself, byte for byte against the image the pipeline built. A manifest could
    // agree while the bytes behind it did not; only this closes that gap.
    JsonNode manifest = JSON.readTree(blob(pulled, descriptor.path("digest").asText()));
    byte[] layer = blob(pulled, manifest.path("layers").get(0).path("digest").asText());
    assertArrayEquals(
        TinyImage.of(OciPushIT.SALT).layer().bytes(),
        layer,
        "the layer that arrived must be the layer that was built");
    story
        .note("the pulled manifest digest and the layer bytes are the ones that were pushed")
        .as("digest-verified");

    // The narrative half: a pull is the reverse order of a push — the tag first, then every blob
    // the manifest names, each fetched by the digest it must hash to.
    story
        .note("a pull resolves the tag first and then fetches each blob by its own digest")
        .as("pull-recorded");
    AccessLogSource.awaitLogged("GET " + OciPushIT.MANIFEST_PATH);
  }

  /** One blob out of a pulled layout, addressed the way the layout addresses it. */
  private static byte[] blob(Path layout, String digest) throws IOException {
    return Files.readAllBytes(
        layout
            .resolve("blobs")
            .resolve("sha256")
            .resolve(digest.substring(digest.indexOf(':') + 1)));
  }

  @AfterAll
  static void storyReportIsComplete() {
    if (!Cli.skopeoPresent()) {
      return;
    }
    ReportAssertions.assertComplete(CATEGORY, SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "image-pulled");
    ReportAssertions.assertCommand(CATEGORY, SLUG, "copy --src-tls-verify=false", 0);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "digest-verified");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "pull-recorded");
    // The tag resolution, observed. The blob fetches beside it are digest-addressed, so their
    // labels scrub to `{digest}` and pinning one would pin the scrubber rather than the pull; the
    // bytes are proved in the story body, against the layer the pipeline built.
    ReportAssertions.assertEdge(
        CATEGORY,
        SLUG,
        NetworkEdge.PACKAGE,
        ACTOR,
        AccessLogSource.SERVICE,
        "GET " + OciPushIT.MANIFEST_PATH + " -> 200");
    // The digest-addressed blob fetches are unpinned by name, but not unclaimed: they were this
    // deployment's, and no other actor may appear beside them.
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY, SLUG, List.of(ACTOR));
  }
}
