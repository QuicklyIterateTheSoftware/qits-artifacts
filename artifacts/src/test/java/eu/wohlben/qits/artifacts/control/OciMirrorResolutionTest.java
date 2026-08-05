package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.error.OciCode;
import eu.wohlben.qits.artifacts.error.OciException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * How a {@code /v2} name becomes rows: the table read the pull path does, and the refusal the push
 * path does.
 *
 * <p>Resolution is <b>table-driven</b> rather than configured, which is what the user's ⚖1 ruling
 * bought — an upstream is a row an operator can see and change, so the namespaces this registry
 * answers under are data, not a deployment's env. The three answers below are the whole precedence
 * rule, and their order is load-bearing: an existing repository always wins.
 */
@QuarkusTest
class OciMirrorResolutionTest extends SeededStoreFixture {

  @Inject OciRegistryService registry;
  @Inject OciMirrorUpstreams upstreams;

  @Test
  void aRegisteredNamespaceResolvesIntoItsMirrorRepository() {
    upstreams.ensure("quay.io", "quay");

    OciRegistryService.PullTarget target =
        registry.resolveForPull("quay/quarkus/ubi9-quarkus-mandrel-builder-image");

    assertTrue(target.mirror());
    assertEquals("quay", target.name().repository());
    assertEquals("quarkus/ubi9-quarkus-mandrel-builder-image", target.name().image());
    assertEquals("quay.io", target.upstreamDomain(), "the miss path needs to know who to dial");
  }

  @Test
  void aHostedRepositoryIsUnchangedAndAlwaysWinsTheFirstSegment() {
    // The seed's sentence, still true: the hit path for qits/* is the existing code. It is asserted
    // beside a configured Hub upstream precisely because the remap below is what could take it.
    repositoryService.ensure("qits", RepositoryType.OCI_IMAGES);
    upstreams.ensure("docker.io", "hub");

    OciRegistryService.PullTarget target = registry.resolveForPull("qits/qits-artifacts");

    assertFalse(target.mirror());
    assertEquals(RepositoryType.OCI_IMAGES, target.type());
    assertNull(target.upstreamDomain());
  }

  @Test
  void anUnknownFirstSegmentIsStillNameUnknownWhenNoHubUpstreamIsRegistered() {
    // Nothing is created implicitly, and a mirror does not change that: a typo must fail loudly
    // rather than mint a namespace.
    OciException refused =
        assertThrows(OciException.class, () -> registry.resolveForPull("typo/alpine"));
    assertEquals(OciCode.NAME_UNKNOWN, refused.code());
    assertEquals(404, refused.statusCode());
  }

  @Test
  void aSingleComponentImageUnderHubMeansLibrary() {
    // The docker daemon's own expansion, not an invention here: `alpine` is `library/alpine`. Keyed
    // on the DOMAIN rather than on the slug, because the slug is whatever an operator chose and the
    // normalisation is a property of the registry.
    upstreams.ensure("docker.io", "hub");

    OciRegistryService.PullTarget target = registry.resolveForPull("hub/alpine");

    assertEquals("library/alpine", target.name().image());
    assertEquals("hub/library/alpine", target.name().full());
  }

  @Test
  void aMirrorNamespaceThatIsNotHubIsSpelledExactlyAsItArrived() {
    upstreams.ensure("quay.io", "quay");

    assertEquals("prometheus", registry.resolveForPull("quay/prometheus").name().image());
  }

  @Test
  void anUnknownFirstSegmentRemapsIntoHubWhenOneIsRegistered() {
    // The registry-mirrors footnote: a daemon configured to mirror Docker Hub asks for bare Hub
    // names, so `/v2/library/alpine/…` has to land somewhere. It lands where a `hub/library/alpine`
    // pull would put it, so the two spellings share one cache entry.
    upstreams.ensure("docker.io", "hub");

    OciRegistryService.PullTarget target = registry.resolveForPull("library/alpine");

    assertTrue(target.mirror());
    assertEquals("hub", target.name().repository());
    assertEquals("library/alpine", target.name().image());
  }

  @Test
  void aPushToAMirrorNamespaceIsRefusedByTypeWith405() {
    // By TYPE, not by configuration: no deployment can set its way past this, and no repository can
    // drift from one meaning to the other, because a type is immutable. Cached upstream content and
    // pushed content never share a namespace — the same rule the npm proxy carries.
    upstreams.ensure("quay.io", "quay");

    OciException refused =
        assertThrows(OciException.class, () -> registry.requireOciRepository("quay/anything"));

    assertEquals(405, refused.statusCode());
    assertEquals(OciCode.UNSUPPORTED, refused.code());
    assertTrue(refused.getMessage().contains("pull-through cache"), refused.getMessage());
    assertEquals("oci-mirror", refused.detail().get("type"));
  }

  @Test
  void aPushToAHostedRepositoryIsUnaffected() {
    repositoryService.ensure("qits", RepositoryType.OCI_IMAGES);

    assertEquals("qits", registry.requireOciRepository("qits/thing").repository());
  }

  @Test
  void aCachedManifestResolvesThroughTheNamespaceItWasCachedUnder() throws Exception {
    // Resolution and storage are the same rows: what the mirror cached is served by the ordinary
    // pull path, with no mirror-specific read anywhere.
    MirrorStore mirror = seedMirror();
    upstreams.ensure("quay.io", MIRROR_REPO);

    OciRegistryService.PullTarget target =
        registry.resolveForPull(MIRROR_REPO + "/" + MIRROR_IMAGE);

    assertEquals(
        mirror.index(),
        registry.resolveManifest(target.name(), "jdk-25").orElseThrow().digest());
  }
}
