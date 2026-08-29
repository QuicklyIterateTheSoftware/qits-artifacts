package eu.wohlben.qits.stories.oci;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.PackagedProcessIT;
import eu.wohlben.qits.registry.TinyImage;
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

    story
        .happened(
            "a deployment",
            "qits-artifacts",
            "GET /v2/" + OciPushIT.NAME + "/manifests/" + OciPushIT.TAG + " -> 200")
        .as("pull-recorded");
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
    ReportAssertions.assertInteraction(
        CATEGORY,
        SLUG,
        "a deployment",
        "qits-artifacts",
        "GET /v2/" + OciPushIT.NAME + "/manifests/" + OciPushIT.TAG + " -> 200");
  }
}
