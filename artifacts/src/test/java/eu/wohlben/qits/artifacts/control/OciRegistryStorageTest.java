package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.entity.OciManifest;
import eu.wohlben.qits.artifacts.entity.OciTag;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.error.BadRequestException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The registry's storage layer: the third repository type, and the two tables V2 adds. Everything
 * here is about persistence — the protocol lives in {@code eu.wohlben.qits.registry}.
 */
@QuarkusTest
class OciRegistryStorageTest extends ArtifactsTestSupport {

  @Inject ArtifactRepositoryService repositoryService;

  private static final String DIGEST_A = "a".repeat(64);
  private static final String DIGEST_B = "b".repeat(64);

  @Test
  void anOciRepositoryIsEnsuredLikeAnyOtherAndItsTypeIsStillImmutable() {
    // The V2 migration widens artifact_repository's check constraint; without it this insert fails
    // at the database rather than at validation. Nothing else about ensure() changes — including
    // that a type is immutable once chosen.
    assertEquals(RepositoryType.OCI_IMAGES, repositoryService.ensure("qits", RepositoryType.OCI_IMAGES).type);
    assertEquals(RepositoryType.OCI_IMAGES, repositoryService.ensure("qits", RepositoryType.OCI_IMAGES).type);
    assertThrows(
        BadRequestException.class, () -> repositoryService.ensure("qits", RepositoryType.CI_VIDEOS));
  }

  @Test
  void theOciProfileAcceptsNothingThroughTheValidatingUploadPath() {
    // Why a zero cap is safe: BlobService checks accepts() before it ever reads maxBytes(), and the
    // profile accepts nothing, so a stray POST to the JSON blob API cannot reach the cap at all.
    RepositoryType type = RepositoryType.OCI_IMAGES;
    assertTrue(type.allowedMediaTypes().isEmpty());
    assertTrue(type.requiredMetadataKeys().isEmpty());
    assertEquals(0L, type.maxBytes());
    assertFalse(type.accepts("application/vnd.oci.image.layer.v1.tar+gzip"));
    assertEquals("oci-images", type.wireName());
    assertEquals(RepositoryType.OCI_IMAGES, RepositoryType.fromWire("oci-images"));
  }

  @Test
  void aManifestIsScopedToOneRepositoryAndImage() {
    // The reason oci_manifest exists at all: blobs dedupe globally, so without a per-name row a
    // manifest pushed to one repository would be servable out of every other one's namespace.
    repositoryService.ensure("qits", RepositoryType.OCI_IMAGES);
    repositoryService.ensure("other", RepositoryType.OCI_IMAGES);
    QuarkusTransaction.requiringNew()
        .run(() -> ociManifests.persist(manifest("qits", "alpine", DIGEST_A)));

    assertTrue(ociManifests.exists("qits", "alpine", DIGEST_A));
    assertFalse(ociManifests.exists("other", "alpine", DIGEST_A), "must not leak across repositories");
    assertFalse(ociManifests.exists("qits", "busybox", DIGEST_A), "nor across images");
    assertEquals(
        "application/vnd.oci.image.manifest.v1+json",
        ociManifests.findOne("qits", "alpine", DIGEST_A).orElseThrow().mediaType);
  }

  @Test
  void retaggingMovesThePointerAndLeavesTheOldManifestReachable() {
    // A tag is the registry's only mutable state. Everything else is content-addressed and
    // append-only, so the manifest a tag used to name stays resolvable by digest afterwards.
    repositoryService.ensure("qits", RepositoryType.OCI_IMAGES);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ociManifests.persist(manifest("qits", "alpine", DIGEST_A));
              ociManifests.persist(manifest("qits", "alpine", DIGEST_B));
              ociTags.persist(tag("qits", "alpine", "latest", DIGEST_A));
            });

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              OciTag latest = ociTags.findOne("qits", "alpine", "latest").orElseThrow();
              latest.manifestDigest = DIGEST_B;
              latest.updatedAt = Instant.now();
            });

    assertEquals(DIGEST_B, ociTags.findOne("qits", "alpine", "latest").orElseThrow().manifestDigest);
    assertEquals(1, ociTags.count(), "a re-tag updates the row, it does not add one");
    assertTrue(ociManifests.exists("qits", "alpine", DIGEST_A), "the displaced manifest survives");
  }

  @Test
  void tagsAreListedLexicallyAndPageFromACursor() {
    repositoryService.ensure("qits", RepositoryType.OCI_IMAGES);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ociManifests.persist(manifest("qits", "alpine", DIGEST_A));
              for (String name : List.of("v3", "latest", "v1", "v2")) {
                ociTags.persist(tag("qits", "alpine", name, DIGEST_A));
              }
              ociTags.persist(tag("qits", "busybox", "latest", DIGEST_A));
            });

    assertEquals(
        List.of("latest", "v1", "v2", "v3"),
        ociTags.listTagNames("qits", "alpine", null, 100),
        "the spec requires lexical order");
    assertEquals(List.of("latest", "v1"), ociTags.listTagNames("qits", "alpine", null, 2));
    assertEquals(
        List.of("v2", "v3"),
        ociTags.listTagNames("qits", "alpine", "v1", 100),
        "?last= is a strict cursor, not an offset");
    assertEquals(List.of("latest"), ociTags.listTagNames("qits", "busybox", null, 100));
  }

  private static OciManifest manifest(String repository, String image, String digest) {
    OciManifest manifest = new OciManifest();
    manifest.repository = repository;
    manifest.imageName = image;
    manifest.digest = digest;
    manifest.mediaType = "application/vnd.oci.image.manifest.v1+json";
    manifest.size = 123;
    manifest.createdAt = Instant.now();
    return manifest;
  }

  private static OciTag tag(String repository, String image, String name, String digest) {
    OciTag tag = new OciTag();
    tag.repository = repository;
    tag.imageName = image;
    tag.tag = name;
    tag.manifestDigest = digest;
    tag.updatedAt = Instant.now();
    return tag;
  }
}
