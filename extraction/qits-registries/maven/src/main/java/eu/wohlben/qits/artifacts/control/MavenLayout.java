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
   *
   * <p>The version directory may be a release version or end in {@code -SNAPSHOT}; both are
   * ordinary directories here. What differs is the file's rule underneath — see {@link
   * #isMutablePath(ArtifactPath)}.
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

  public static boolean isSnapshotVersion(String version) {
    return version.endsWith("-SNAPSHOT");
  }

  /**
   * A timestamped snapshot filename, parsed: {@code
   * <artifact>-<baseVersion>-<yyyyMMdd>.<HHmmss>-<buildNo>[-<classifier>].<ext>}.
   *
   * <p>These are what every modern client deploys for a {@code -SNAPSHOT} version (maven-3's
   * {@code uniqueVersion} default, and Gradle likewise): one deploy, one filename, unique by
   * construction. The server computes none of this — the client does — but it reads the names back
   * to derive the version-level {@code maven-metadata.xml} a resolver maps {@code 1.0.1-SNAPSHOT}
   * through.
   */
  public record SnapshotFileName(
      String extension, String classifier, String timestamp, int buildNumber) {

    /** The coordinate a resolver downloads: {@code 1.0.1-20260802.123456-3}. */
    public String value(String baseVersion) {
      return baseVersion + "-" + timestamp + "-" + buildNumber;
    }
  }

  private static final java.util.regex.Pattern TIMESTAMPED_SNAPSHOT =
      java.util.regex.Pattern.compile("(\\d{8}\\.\\d{6})-(\\d+)(?:-(.+))?\\.([A-Za-z0-9]+)");

  /**
   * Parses {@code file} as a timestamped snapshot of {@code artifactId:…:version}, or null when it
   * is not one — including when the version is not a {@code -SNAPSHOT} at all.
   */
  public static SnapshotFileName parseTimestampedSnapshot(
      String artifactId, String version, String file) {
    if (!isSnapshotVersion(version)) {
      return null;
    }
    String baseVersion = version.substring(0, version.length() - "-SNAPSHOT".length());
    String prefix = artifactId + "-" + baseVersion + "-";
    if (!file.startsWith(prefix)) {
      return null;
    }
    java.util.regex.Matcher matcher =
        TIMESTAMPED_SNAPSHOT.matcher(file.substring(prefix.length()));
    if (!matcher.matches()) {
      return null;
    }
    return new SnapshotFileName(
        matcher.group(4), matcher.group(3), matcher.group(1), Integer.parseInt(matcher.group(2)));
  }

  /**
   * The one mutable path class: a <b>literal</b> {@code -SNAPSHOT} filename ({@code
   * a-1.0.1-SNAPSHOT.jar}), what a client with {@code uniqueVersion=false} deploys.
   *
   * <p>The coordinate is a moving target by definition, so a redeploy rewrites the row — a {@code
   * 403} here would break a legitimate redeploy while buying nothing, since the timestamped form is
   * what every modern client sends. Release paths and timestamped snapshot files are both
   * immutable: the release rule, and uniqueness by construction.
   */
  public static boolean isMutablePath(ArtifactPath parsed) {
    return isSnapshotVersion(parsed.version())
        && parseTimestampedSnapshot(parsed.artifactId(), parsed.version(), parsed.file()) == null;
  }

  /** The same answer for a raw path, for the serve side which never needed the parse to resolve. */
  public static boolean isMutablePath(String path) {
    ArtifactPath parsed = parse(path);
    return parsed != null && isMutablePath(parsed);
  }
}
