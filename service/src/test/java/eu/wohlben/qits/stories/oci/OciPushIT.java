package eu.wohlben.qits.stories.oci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.PackagedProcessIT;
import eu.wohlben.qits.registry.OciClient;
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
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * The {@code qits} category's producer: a real image client — {@code skopeo} — pushing to the
 * launched process' OCI registry.
 *
 * <p>Every other registry suite in this repository drives a <b>synthesised</b> client ({@code
 * registry/OciClient} over the JDK's {@code HttpClient}), because {@code mvn verify} may not assume
 * docker, podman or skopeo is installed and the questions there are about protocol shape. This
 * story is the other half: the actual binary, doing the actual sequence — {@code HEAD} each blob,
 * {@code POST} an upload session, {@code PATCH} the bytes, {@code PUT} to close it, manifest last —
 * with the registry's answer read back by the synthetic client so the story asserts the store
 * rather than skopeo's exit code.
 *
 * <p><b>It skips wherever skopeo is absent</b>, which is most workstations and every plain {@code
 * mvn verify} here; CI's {@code userflows-base} carries one. A skipped story emits nothing, which
 * is the honest answer for "this machine has no image client" — and the reason the gate is
 * {@code @EnabledIf} at class level rather than an assumption inside the body.
 *
 * <h2>The two flags that are not optional</h2>
 *
 * <p>{@code --insecure-policy} is a <b>global</b> option and must precede the subcommand: without
 * it skopeo demands {@code /etc/containers/policy.json}, which a build image need not carry, and
 * fails before it opens a socket. {@code --dest-tls-verify=false} is what makes it speak plain HTTP
 * to a registry that has no certificate — there is no TLS inside qits-net either, so this is the
 * deployed posture rather than a test concession.
 *
 * <p>The layout the push reads is written by {@link TinyImage#writeOciLayout}: no tarball is
 * committed to this repository and no image is pulled from anywhere, so this story stays inside the
 * clone-alone rule while still handing a real client real bytes.
 */
@QuarkusIntegrationTest
@TestProfile(PackagedProcessIT.TargetDirState.class)
@EnabledIf("eu.wohlben.qits.stories.support.Cli#skopeoPresent")
public class OciPushIT {

  static final String CATEGORY = "qits";
  static final String SLUG = "a-release-pipeline-pushes-an-image-to-the-platform-registry";

  /** The seeded {@code oci-images} repository row — the first path segment of every image here. */
  public static final String REPOSITORY = "qits";

  public static final String IMAGE = "story-app";

  /** {@code <repository>/<image>} — the name the registry addresses under {@code /v2}. */
  public static final String NAME = REPOSITORY + "/" + IMAGE;

  public static final String TAG = "1.0.0";

  /**
   * The image's content, determined entirely by this salt — so the pull story can rebuild the same
   * bytes to compare against without either story keeping them.
   */
  public static final String SALT = "story-oci";

  /** The tag inside the on-disk layout, which is a layout annotation rather than a directory. */
  static final String LAYOUT_REF = "story";

  /** How the diagram names the initiator of everything this story sends. */
  static final String ACTOR = "a release pipeline";

  /** {@code /v2/<repository>/<image>/manifests/<tag>} — the wire path, as the access log spells it. */
  public static final String MANIFEST_PATH =
      StoryTarget.OCI_PATH + "/" + NAME + "/manifests/" + TAG;

  @TestHTTPResource("/")
  URL root;

  @UserStory(
      value = "A release pipeline pushes an image to the platform registry",
      category = "qits")
  @UserStoryDescription(
      """
      The registry as a release pipeline meets it: a built image on disk in the OCI layout every
      builder emits, one `skopeo copy` at a tag, and a manifest that exists from then on. The tool
      is a real image client rather than this repository's synthetic one — what a pipeline runs is
      the thing under test — and the manifest is read back over the wire afterwards so the story
      asserts what the registry now serves rather than what the client believes it sent.
      """)
  void aReleasePipelinePushesAnImage(
      Interactions story, Commands commands, UserflowContext context) {
    StoryTarget target = new StoryTarget(root);

    // Who the access log's next lines belong to, and what kind of traffic they are. Read at drain
    // time, so it is set before the first request — skopeo's and this story's alike.
    AccessLogSource.attribute(ACTOR, NetworkEdge.PACKAGE);

    // workDir() creates and wipes the scratch on first use, so it is taken before anything is
    // written into it.
    Path work = commands.workDir();
    TinyImage image = TinyImage.of(SALT);
    Path layout = work.resolve("layout");
    image.writeOciLayout(layout, LAYOUT_REF);
    story
        .note("a build produced a one-layer image in the OCI layout on disk")
        .as("image-built");

    // The registry's readiness probe, made in Java rather than by the tool. `skopeo inspect` takes
    // an IMAGE reference and cannot be pointed at /v2/ at all, so a command here would be a
    // different fact dressed as this one — while GET /v2/ is exactly the handshake every client
    // makes first.
    try (OciClient client = new OciClient(URI.create(root.toString()))) {
      assertEquals(200, client.versionProbe(), "the registry's version probe");
    }
    story
        .note("the registry answered the version probe every image client makes first")
        .as("registry-ready");

    // --insecure-policy is global and precedes the subcommand; the layout path is absolute because
    // skopeo resolves it, not this JVM. Both the path and the host ride as {} arguments, so the
    // fingerprint keeps the template and the definition hash survives the random port.
    commands
        .run(
            "{} --insecure-policy copy --dest-tls-verify=false oci:{}:{} docker://{}/{}:{}",
            Cli.skopeo(),
            layout.toAbsolutePath(),
            LAYOUT_REF,
            target.ociHost(),
            NAME,
            TAG)
        .as("image-pushed");

    // What the registry now serves, read with the synthetic client. The manifest's digest comes
    // from the registry's own Docker-Content-Digest rather than from the local bytes: a client is
    // permitted to convert a manifest on the way out, so the pull story must compare against what
    // was actually stored.
    String manifestDigest;
    try (OciClient client = new OciClient(URI.create(root.toString()))) {
      HttpResponse<byte[]> manifest = client.getManifest(NAME, TAG);
      assertEquals(200, manifest.statusCode(), "the pushed tag must resolve");
      manifestDigest = manifest.headers().firstValue("docker-content-digest").orElseThrow();
      assertEquals(
          manifestDigest,
          TinyImage.digest(manifest.body()),
          "the digest the registry echoes must be the digest of the bytes it served");
      assertTrue(
          client.blobExists(NAME, image.config().digest()),
          "the config blob the client uploaded must be in the store");
      assertTrue(
          client.blobExists(NAME, image.layer().digest()),
          "the layer blob the client uploaded must be in the store");
    }
    // The narrative the wire cannot carry: a push is finished when the MANIFEST lands, and the
    // digest a deploy plan pins is the one the registry echoed rather than the one skopeo held.
    story
        .note("the manifest is the last thing a push writes, and the tag resolves to it from now on")
        .as("push-recorded");
    AccessLogSource.awaitLogged("PUT " + MANIFEST_PATH);

    context.put("story.qits.image", IMAGE);
    context.put("story.qits.tag", TAG);
    context.put("story.qits.manifest-digest", manifestDigest);
  }

  @AfterAll
  static void storyReportIsComplete() {
    if (!Cli.skopeoPresent()) {
      return;
    }
    ReportAssertions.assertComplete(CATEGORY, SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "image-built");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "registry-ready");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "image-pushed");
    ReportAssertions.assertCommand(CATEGORY, SLUG, "copy --dest-tls-verify=false", 0);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "push-recorded");
    // Observed in the launched process' own access log — which is the only place a push by an
    // external binary exists at all, since nothing in this JVM is on that socket.
    //
    // No assertEdgeCount: a push is a conversation whose length is the CLIENT's — a blob already in
    // the store is a HEAD and no upload, and how a client chunks a PATCH is its own business. What
    // this repository owes a reader is the handshake and the manifest, and both are pinned exactly.
    ReportAssertions.assertEdge(
        CATEGORY,
        SLUG,
        NetworkEdge.PACKAGE,
        ACTOR,
        AccessLogSource.SERVICE,
        "GET " + StoryTarget.OCI_PATH + "/ -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        SLUG,
        NetworkEdge.PACKAGE,
        ACTOR,
        AccessLogSource.SERVICE,
        "PUT " + MANIFEST_PATH + " -> 201");
    // The negative half a count would have carried: however skopeo chose to chunk the blobs, every
    // request in that conversation was this pipeline's.
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY, SLUG, List.of(ACTOR));
  }
}
