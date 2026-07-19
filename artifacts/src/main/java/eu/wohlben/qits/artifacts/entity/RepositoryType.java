package eu.wohlben.qits.artifacts.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;
import java.util.Set;

/**
 * A repository's <b>type</b> = its validation/convention profile over the shared blob core: which
 * media types it accepts, which metadata keys it requires, and its per-upload size cap.
 * Deliberately thin — the deferred protocol types (maven/npm/docker) slot in as new constants
 * without touching the core.
 *
 * <p>This feature ships the two media types that serve the golden-diff loop. Required keys are the
 * pairing/comparison keys the future diff UI needs (branch, commit, flow name + hash, display name,
 * diff hash) plus the media resolution each type can express.
 */
public enum RepositoryType {

  /** Golden screenshots, diffed by branch. */
  CI_SCREENSHOTS(
      Set.of("image/png", "image/jpeg", "image/svg+xml"),
      Set.of(
          "git.branch.name",
          "git.commit.hash",
          "qits.userflow.name",
          "qits.userflow.hash",
          "qits.display.name",
          "qits.diff.hash",
          "media.resolution.width",
          "media.resolution.height"),
      25L * 1024 * 1024),

  /** Golden videos, diffed by branch. */
  CI_VIDEOS(
      Set.of("video/mp4", "video/webm"),
      Set.of(
          "git.branch.name",
          "git.commit.hash",
          "qits.userflow.name",
          "qits.userflow.hash",
          "qits.display.name",
          "qits.diff.hash",
          "media.resolution.length"),
      // 64 MB: generous for a short compressed golden clip, matched to the global HTTP body ceiling
      // (see service application.properties + docs/issues on the max-body-size tradeoff).
      64L * 1024 * 1024);

  private final Set<String> allowedMediaTypes;
  private final Set<String> requiredMetadataKeys;
  private final long maxBytes;

  RepositoryType(Set<String> allowedMediaTypes, Set<String> requiredMetadataKeys, long maxBytes) {
    this.allowedMediaTypes = allowedMediaTypes;
    this.requiredMetadataKeys = requiredMetadataKeys;
    this.maxBytes = maxBytes;
  }

  public Set<String> allowedMediaTypes() {
    return allowedMediaTypes;
  }

  public Set<String> requiredMetadataKeys() {
    return requiredMetadataKeys;
  }

  public long maxBytes() {
    return maxBytes;
  }

  public boolean accepts(String mediatype) {
    return allowedMediaTypes.contains(mediatype);
  }

  /**
   * The wire form (kebab-case, e.g. {@code ci-screenshots}) — the enum name isn't the API contract.
   */
  @JsonValue
  public String wireName() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  /** Parses the kebab wire form; also tolerant of the raw enum name. */
  @JsonCreator
  public static RepositoryType fromWire(String value) {
    if (value == null) {
      return null;
    }
    return RepositoryType.valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
  }
}
