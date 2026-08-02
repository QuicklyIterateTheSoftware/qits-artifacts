package eu.wohlben.qits.artifacts.control;

import java.util.Arrays;
import java.util.List;

/**
 * The maven repository layout, as pure functions over repository-relative paths.
 *
 * <p>The server is a dumb path store: a path is {@code <group segments>/<artifact>/<version>/<file>}
 * and every rule the wire enforces is read out of that shape — the file must start with {@code
 * <artifact>-}, because the metadata derivation reads structure out of paths and a store that
 * accepts unparseable paths serves unanswerable metadata later.
 *
 * <p>Both derived documents are recognised <em>by name</em> here: {@code maven-metadata.xml} (and
 * its checksum siblings) at any depth routes to the metadata handler rather than to a row lookup,
 * and a checksum suffix routes to verification rather than to storage.
 */
public final class MavenLayout {

  /** The one filename that is derived state rather than a stored row, at any depth. */
  public static final String METADATA = "maven-metadata.xml";

  /** The four checksum siblings every stored file serves, in the order clients ask for them. */
  private static final List<String> CHECKSUM_SUFFIXES =
      List.of(".md5", ".sha1", ".sha256", ".sha512");

  private MavenLayout() {}

  /** A path parsed into its maven coordinates. {@link #path} is the full repository-relative form. */
  public record ArtifactPath(
      String path, String groupId, String artifactId, String version, String file) {}

  /** The file of a path: its last segment. */
  public static String fileOf(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? path : path.substring(slash + 1);
  }

  /** The directory of a path: everything before the last segment. */
  public static String directoryOf(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? "" : path.substring(0, slash);
  }

  public static boolean isMetadata(String file) {
    return METADATA.equals(file);
  }

  /**
   * The algorithm of a metadata checksum sibling ({@code maven-metadata.xml.sha1} → {@code sha1}),
   * or null. Metadata checksums are the one PUT accepted <em>without</em> verification: the
   * client's document is a merge of its own and our derived one, so the two legitimately differ.
   */
  public static String metadataChecksumAlgorithm(String file) {
    if (file.startsWith(METADATA + ".")) {
      return checksumAlgorithm(file.substring(METADATA.length()));
    }
    return null;
  }

  /**
   * The algorithm of a checksum filename ({@code x-1.0.0.jar.sha256} → {@code sha256}), or null.
   * Only the four algorithms a stored file serves are recognised.
   */
  public static String checksumAlgorithm(String file) {
    for (String suffix : CHECKSUM_SUFFIXES) {
      if (file.endsWith(suffix)) {
        return suffix.substring(1);
      }
    }
    return null;
  }

  /**
   * Parses a repository-relative path into its coordinates, or null when it is not {@code <group
   * segments>/<artifact>/<version>/<file>} with the file starting with {@code <artifact>-}.
   *
   * <p>A null here is a {@code 400} on a PUT, not a {@code 404}: a store that accepts unparseable
   * paths serves unanswerable metadata later, so the refusal happens at the door. A GET needs no
   * parse at all — an unknown path is simply a row that does not exist.
   */
  public static ArtifactPath parse(String path) {
    String[] segments = path.split("/");
    if (segments.length < 4) {
      return null;
    }
    String file = segments[segments.length - 1];
    String version = segments[segments.length - 2];
    String artifact = segments[segments.length - 3];
    if (artifact.isEmpty() || version.isEmpty() || file.isEmpty()) {
      return null;
    }
    if (!file.startsWith(artifact + "-")) {
      return null;
    }
    String[] group = Arrays.copyOf(segments, segments.length - 3);
    for (String segment : group) {
      if (segment.isEmpty()) {
        return null;
      }
    }
    return new ArtifactPath(path, String.join(".", group), artifact, version, file);
  }
}
