package eu.wohlben.qits.artifacts.control;

import java.util.Set;

/** The manifest media types this registry serves, and the layer types it must not require. */
public final class OciMediaTypes {

  public static final String DOCKER_MANIFEST_V2 =
      "application/vnd.docker.distribution.manifest.v2+json";
  public static final String OCI_MANIFEST_V1 = "application/vnd.oci.image.manifest.v1+json";
  public static final String DOCKER_INDEX_V2 =
      "application/vnd.docker.distribution.manifest.list.v2+json";
  public static final String OCI_INDEX_V1 = "application/vnd.oci.image.index.v1+json";

  /** A single image: one config blob and a list of layers. */
  private static final Set<String> IMAGE_MANIFESTS =
      Set.of(DOCKER_MANIFEST_V2, OCI_MANIFEST_V1);

  /**
   * A multi-arch index: a list of child manifests, not of blobs. {@code docker buildx} and {@code
   * podman} push these by default, so they are not an exotic case.
   */
  private static final Set<String> INDEXES = Set.of(DOCKER_INDEX_V2, OCI_INDEX_V1);

  /**
   * Layer types whose bytes are never uploaded to a registry — Windows base layers and anything
   * marked non-distributable, which clients fetch from their original URLs instead.
   *
   * <p>Requiring these to exist is a real trap rather than a theoretical one: it makes every Windows
   * base image, and a handful of others, fail to push with a {@code BLOB_UNKNOWN} for a blob the
   * client was never going to send.
   */
  private static final Set<String> FOREIGN_LAYERS =
      Set.of(
          "application/vnd.docker.image.rootfs.foreign.diff.tar.gzip",
          "application/vnd.oci.image.layer.nondistributable.v1.tar",
          "application/vnd.oci.image.layer.nondistributable.v1.tar+gzip",
          "application/vnd.oci.image.layer.nondistributable.v1.tar+zstd");

  private OciMediaTypes() {}

  // Every predicate null-guards before the lookup: a request may well arrive with no Content-Type,
  // and Set.of() throws NullPointerException on contains(null) rather than answering false.

  public static boolean isSupportedManifest(String mediaType) {
    return isImageManifest(mediaType) || isIndex(mediaType);
  }

  public static boolean isImageManifest(String mediaType) {
    return mediaType != null && IMAGE_MANIFESTS.contains(mediaType);
  }

  public static boolean isIndex(String mediaType) {
    return mediaType != null && INDEXES.contains(mediaType);
  }

  public static boolean isForeignLayer(String mediaType) {
    return mediaType != null && FOREIGN_LAYERS.contains(mediaType);
  }

  /** Every manifest type, for the {@code Accept} header a pull should advertise. */
  public static Set<String> allManifestTypes() {
    return Set.of(DOCKER_MANIFEST_V2, OCI_MANIFEST_V1, DOCKER_INDEX_V2, OCI_INDEX_V1);
  }
}
