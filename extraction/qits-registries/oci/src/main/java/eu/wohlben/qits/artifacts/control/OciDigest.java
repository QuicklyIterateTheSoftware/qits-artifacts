package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.error.OciCode;
import eu.wohlben.qits.artifacts.error.OciException;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The boundary between the wire's {@code sha256:<hex>} and the bare 64-hex id {@link BlobStore}
 * speaks.
 *
 * <p>Everything stored — {@code artifact_record.blob_id}, {@code oci_manifest.digest}, the on-disk
 * fan-out path — is bare hex, and everything on the wire carries the algorithm prefix. Converting in
 * exactly one place is what keeps a prefixed string from reaching {@code BlobStore}, where it would
 * fail the id-shape check and surface as a confusing 404 rather than the malformed-digest error it
 * actually is.
 *
 * <p>Only {@code sha256} is accepted. The spec allows other algorithms; no client in practice pushes
 * one, and silently accepting a digest we cannot verify would defeat the verification.
 */
public final class OciDigest {

  private static final Pattern SHA256_DIGEST = Pattern.compile("sha256:([0-9a-f]{64})");

  private OciDigest() {}

  /** The bare hex behind a wire digest, or null if it is not a well-formed {@code sha256:} one. */
  public static String hexOrNull(String wireDigest) {
    if (wireDigest == null) {
      return null;
    }
    var matcher = SHA256_DIGEST.matcher(wireDigest);
    return matcher.matches() ? matcher.group(1) : null;
  }

  /**
   * The bare hex behind a wire digest.
   *
   * @throws OciException {@code DIGEST_INVALID} if it is not a well-formed {@code sha256:} digest
   */
  public static String requireHex(String wireDigest) {
    String hex = hexOrNull(wireDigest);
    if (hex == null) {
      throw new OciException(
          OciCode.DIGEST_INVALID,
          "digest is not a well-formed sha256 digest",
          Map.of("digest", String.valueOf(wireDigest)));
    }
    return hex;
  }

  /** The wire form of a bare hex id. */
  public static String wire(String hex) {
    return "sha256:" + hex;
  }

  /** Whether a string is shaped like a digest at all — the tag-versus-digest discriminator. */
  public static boolean isDigest(String reference) {
    return hexOrNull(reference) != null;
  }
}
