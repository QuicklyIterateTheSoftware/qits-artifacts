package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.error.OciCode;
import eu.wohlben.qits.artifacts.error.OciException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The registry's parsing rules, as plain JUnit — no Quarkus, because none of this needs a container
 * and the cases are cheap enough to be exhaustive.
 */
class OciValueTypesTest {

  private static final String HEX = "a".repeat(64);

  @Nested
  class Names {

    @Test
    void theFirstSegmentIsTheRepositoryAndTheRestIsTheImage() {
      OciImageName simple = OciImageName.parse("qits/alpine");
      assertEquals("qits", simple.repository());
      assertEquals("alpine", simple.image());

      // The document's own example. A name may contain slashes and only the FIRST one splits.
      OciImageName nested = OciImageName.parse("qits/build-images/ci-base");
      assertEquals("qits", nested.repository());
      assertEquals("build-images/ci-base", nested.image());
      assertEquals("qits/build-images/ci-base", nested.full());

      assertEquals("a/b/c/d", OciImageName.parse("qits/a/b/c/d").image());
    }

    @Test
    void aSingleSegmentReferenceIsANamedFailure() {
      // `docker push <host>/alpine:latest` is a reference docker will happily emit, and here it has
      // no repository/image split at all. It must say so — a 404 would read as "registry broken".
      OciException thrown = assertThrows(OciException.class, () -> OciImageName.parse("alpine"));
      assertEquals(OciCode.NAME_INVALID, thrown.code());
      assertTrue(thrown.getMessage().contains("<repository>/<image>"));
    }

    @Test
    void malformedNamesAreRejectedRatherThanReachingTheFilesystem() {
      for (String bad :
          new String[] {
            null, "", "qits//alpine", "qits/alpine/", "/qits/alpine", "QITS/alpine", "Qits/Alpine",
            "../etc/passwd", "qits/../../x", "qits/alpine:latest", "qits/-alpine", "qits/alpine-"
          }) {
        assertThrows(OciException.class, () -> OciImageName.parse(bad), "should reject: " + bad);
      }
    }

    @Test
    void theSpecsPunctuationRulesHold() {
      // Separators are legal INSIDE a component but never at an edge, and the grammar allows the
      // doubled forms docker hub names use.
      assertEquals("my-image", OciImageName.parse("qits/my-image").image());
      assertEquals("my.image", OciImageName.parse("qits/my.image").image());
      assertEquals("my__image", OciImageName.parse("qits/my__image").image());
      assertEquals("my---image", OciImageName.parse("qits/my---image").image());
    }
  }

  @Nested
  class Digests {

    @Test
    void theWirePrefixIsStrippedForTheBlobStoreAndAddedBackForClients() {
      assertEquals(HEX, OciDigest.requireHex("sha256:" + HEX));
      assertEquals("sha256:" + HEX, OciDigest.wire(HEX));
    }

    @Test
    void anythingNotAWellFormedSha256DigestIsRejected() {
      assertNull(OciDigest.hexOrNull(null));
      assertNull(OciDigest.hexOrNull(HEX), "the bare hex is not a wire digest");
      assertNull(OciDigest.hexOrNull("sha256:" + "a".repeat(63)), "too short");
      assertNull(OciDigest.hexOrNull("sha256:" + "A".repeat(64)), "uppercase is not the wire form");
      assertNull(OciDigest.hexOrNull("sha512:" + HEX), "only sha256 is verifiable here");
      assertEquals(
          OciCode.DIGEST_INVALID,
          assertThrows(OciException.class, () -> OciDigest.requireHex("nonsense")).code());
    }

    @Test
    void tagsAndDigestsAreDisjointSoThereIsNoPrecedenceRuleToGetWrong() {
      // ':' is not in the tag grammar, so a <ref> is unambiguously one or the other.
      assertTrue(OciDigest.isDigest("sha256:" + HEX));
      assertFalse(OciDigest.isDigest("latest"));
      assertFalse(OciDigest.isDigest("sha256-" + HEX));
    }
  }

  @Nested
  class MediaTypes {

    @Test
    void allFourManifestFlavoursAreSupportedAndNothingElseIs() {
      assertTrue(OciMediaTypes.isImageManifest(OciMediaTypes.DOCKER_MANIFEST_V2));
      assertTrue(OciMediaTypes.isImageManifest(OciMediaTypes.OCI_MANIFEST_V1));
      assertTrue(OciMediaTypes.isIndex(OciMediaTypes.DOCKER_INDEX_V2));
      assertTrue(OciMediaTypes.isIndex(OciMediaTypes.OCI_INDEX_V1));
      assertEquals(4, OciMediaTypes.allManifestTypes().size());

      // Schema 1 is rejected by the parser, and its media type is not one we serve.
      assertFalse(
          OciMediaTypes.isSupportedManifest("application/vnd.docker.distribution.manifest.v1+json"));
      assertFalse(OciMediaTypes.isSupportedManifest("application/json"));
      assertFalse(OciMediaTypes.isSupportedManifest(null));
    }

    @Test
    void foreignLayersAreRecognisedSoTheyAreNotRequiredToExist() {
      // Requiring these would make every Windows base image unpushable — the client never sends
      // them, by design.
      assertTrue(
          OciMediaTypes.isForeignLayer("application/vnd.docker.image.rootfs.foreign.diff.tar.gzip"));
      assertTrue(
          OciMediaTypes.isForeignLayer(
              "application/vnd.oci.image.layer.nondistributable.v1.tar+gzip"));
      assertFalse(OciMediaTypes.isForeignLayer("application/vnd.oci.image.layer.v1.tar+gzip"));
      assertFalse(OciMediaTypes.isForeignLayer(null));
    }
  }
}
