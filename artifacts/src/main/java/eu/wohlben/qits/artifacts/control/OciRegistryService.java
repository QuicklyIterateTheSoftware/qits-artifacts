package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.entity.ArtifactRepository;
import eu.wohlben.qits.artifacts.entity.OciManifest;
import eu.wohlben.qits.artifacts.entity.OciTag;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.error.OciCode;
import eu.wohlben.qits.artifacts.error.OciException;
import eu.wohlben.qits.artifacts.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.artifacts.persistence.OciManifestRepository;
import eu.wohlben.qits.artifacts.persistence.OciTagRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The registry's database work, as complete units of work.
 *
 * <p>Every public method here is annotated, and that is load-bearing rather than cargo cult: the
 * callers are raw Vert.x route handlers, which run with <b>no CDI request context and no
 * transaction</b>. {@code GitHostRoutes} is no precedent for this — it touches no database at all.
 * The precedents are {@code ArtifactsRepositorySeeder}, which carries {@code @ActivateRequestContext}
 * for exactly this reason, and {@code BlobService.upload}'s explicit {@code
 * QuarkusTransaction.requiringNew()}. Drop an annotation here and the manifest routes fail with
 * {@code ContextNotActiveException} at runtime only.
 *
 * <p>Blob streaming stays deliberately outside all of it. The bytes never enter a transaction, so a
 * slow gigabyte upload cannot time one out — and OCI blobs get no row at all, so there is nothing
 * for one to protect.
 */
@ApplicationScoped
public class OciRegistryService {

  @Inject ArtifactRepositoryRepository repositories;
  @Inject OciManifestRepository manifests;
  @Inject OciTagRepository tags;
  @Inject BlobStore blobStore;

  /** A manifest resolved for serving: what to read, how big it is, and what to call it. */
  public record StoredManifest(String digest, String mediaType, long size) {}

  /**
   * Resolves an OCI {@code <name>} to the repository that must already hold it.
   *
   * <p>Repositories are not created implicitly. A push to an unknown first segment is {@code
   * NAME_UNKNOWN}, and an operator creates it with the ordinary {@code PUT
   * /artifacts/api/repositories/<name>} carrying {@code {"type":"oci-images"}} — the same endpoint,
   * the same token guard and the same immutable-type rule as every other repository. A typo
   * therefore fails loudly instead of quietly minting a namespace.
   *
   * <p>The one exception to "an operator creates it" is {@code qits}, the repository the platform's
   * own publish convention uses: {@link ArtifactsRepositorySeeder} seeds that row at startup, so a
   * fresh deployment accepts {@code qits/<application>:<sha>} with no manual step. Every other
   * namespace still has to be asked for.
   */
  @ActivateRequestContext
  public OciImageName requireOciRepository(String name) {
    OciImageName parsed = OciImageName.parse(name);
    ArtifactRepository repository = repositories.findById(parsed.repository());
    if (repository == null || repository.type != RepositoryType.OCI_IMAGES) {
      throw new OciException(
          OciCode.NAME_UNKNOWN,
          "no such image repository; create it with PUT /artifacts/api/repositories/"
              + parsed.repository()
              + " {\"type\":\"oci-images\"}",
          Map.of("name", parsed.full()));
    }
    return parsed;
  }

  /**
   * Binds an already-promoted manifest to this name, and to a tag if the reference was one.
   *
   * @param digest the manifest's own digest, in bare hex, recomputed from the received bytes
   */
  @ActivateRequestContext
  @Transactional
  public void bindManifest(
      OciImageName name, String reference, String digest, String mediaType, long size) {
    OciManifest manifest =
        manifests.findOne(name.repository(), name.image(), digest).orElseGet(OciManifest::new);
    manifest.repository = name.repository();
    manifest.imageName = name.image();
    manifest.digest = digest;
    manifest.mediaType = mediaType;
    manifest.size = size;
    if (manifest.createdAt == null) {
      manifest.createdAt = Instant.now();
      manifests.persist(manifest);
    }

    if (!OciDigest.isDigest(reference)) {
      OciTag tag =
          tags.findOne(name.repository(), name.image(), reference).orElseGet(OciTag::new);
      boolean fresh = tag.tag == null;
      tag.repository = name.repository();
      tag.imageName = name.image();
      tag.tag = reference;
      tag.manifestDigest = digest;
      tag.updatedAt = Instant.now();
      if (fresh) {
        tags.persist(tag);
      }
    }
  }

  /**
   * Resolves a {@code <ref>} — a tag or a digest — to the manifest to serve.
   *
   * <p>The digest branch goes through {@code oci_manifest} rather than straight to the blob store,
   * which is the whole point of that table: the store dedupes globally, so a digest lookup that
   * skipped it would happily serve a manifest that was only ever pushed to some other repository.
   */
  @ActivateRequestContext
  public Optional<StoredManifest> resolveManifest(OciImageName name, String reference) {
    String digest =
        OciDigest.isDigest(reference)
            ? OciDigest.hexOrNull(reference)
            : tags.findOne(name.repository(), name.image(), reference)
                .map(tag -> tag.manifestDigest)
                .orElse(null);
    if (digest == null) {
      return Optional.empty();
    }
    return manifests
        .findOne(name.repository(), name.image(), digest)
        .map(manifest -> new StoredManifest(manifest.digest, manifest.mediaType, manifest.size));
  }

  /**
   * Checks every digest a manifest references, before anything is bound.
   *
   * <p>An index's children must be manifests already known to this {@code (repository, image)} — a
   * blob with the right bytes is not enough, because that is exactly the cross-repository leak the
   * manifest table exists to prevent. An image manifest's references are ordinary blobs.
   */
  @ActivateRequestContext
  public void requireReferencesExist(OciImageName name, boolean index, List<String> references) {
    for (String digest : references) {
      boolean present =
          index
              ? manifests.exists(name.repository(), name.image(), digest)
              : blobStore.exists(digest);
      if (!present) {
        throw new OciException(
            index ? OciCode.MANIFEST_UNKNOWN : OciCode.MANIFEST_BLOB_UNKNOWN,
            index
                ? "the index references a manifest this image does not have"
                : "the manifest references a blob that has not been uploaded",
            Map.of("digest", OciDigest.wire(digest)));
      }
    }
  }

  /** Tag names for {@code tags/list}, lexically ordered and paged by the {@code ?last=} cursor. */
  @ActivateRequestContext
  public List<String> listTags(OciImageName name, String after, int limit) {
    return tags.listTagNames(name.repository(), name.image(), after, limit);
  }
}
