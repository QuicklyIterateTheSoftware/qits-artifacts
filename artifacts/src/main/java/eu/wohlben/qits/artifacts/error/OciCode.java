package eu.wohlben.qits.artifacts.error;

/**
 * The OCI Distribution spec's error codes, with the HTTP status each carries by default.
 *
 * <p>These are not decoration. Docker and podman surface {@code code} and {@code message} to the
 * user verbatim, so the code chosen here is the whole of what a person debugging a failed push sees
 * — which is why, for example, an unknown repository is {@code NAME_UNKNOWN} rather than a bare 404.
 */
public enum OciCode {

  /** The repository named by the first path segment has no row, or is not an OCI_IMAGES one. */
  NAME_UNKNOWN(404),
  /** The name does not parse: bad grammar, or a single segment with no {@code <repository>/}. */
  NAME_INVALID(400),
  /** A blob is not in the store — on a read, or as a manifest's unmet reference. */
  BLOB_UNKNOWN(404),
  /** An upload session id that is unknown, expired, or from before a restart. */
  BLOB_UPLOAD_UNKNOWN(404),
  /** A malformed upload: a finalize with no digest, a PATCH on a finished session. */
  BLOB_UPLOAD_INVALID(400),
  /** The content did not hash to the claimed digest, or the digest itself is malformed. */
  DIGEST_INVALID(400),
  /** A tag or digest not bound in this {@code (repository, image)}. */
  MANIFEST_UNKNOWN(404),
  /** Unparseable, schema v1, an unsupported media type, or the two disagreeing. */
  MANIFEST_INVALID(400),
  /** A manifest naming a layer or config blob that was never uploaded. */
  MANIFEST_BLOB_UNKNOWN(404),
  /** Past the configured layer cap. */
  SIZE_INVALID(413),
  /** A write with no valid credential. Always accompanied by a {@code WWW-Authenticate} header. */
  UNAUTHORIZED(401),
  /** Authenticated but not permitted. Unused today: this service authorizes nothing. */
  DENIED(403),
  /** An operation this registry deliberately does not implement — see the README on GC. */
  UNSUPPORTED(404);

  private final int status;

  OciCode(int status) {
    this.status = status;
  }

  public int status() {
    return status;
  }
}
