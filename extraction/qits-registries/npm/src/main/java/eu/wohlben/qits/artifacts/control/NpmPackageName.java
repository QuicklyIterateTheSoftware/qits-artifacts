package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.error.NpmException;
import java.util.regex.Pattern;

/**
 * An npm package name, and the two derived strings the routes need: the tarball's file name and the
 * version hiding inside it.
 *
 * <p>The name is the identity npm addresses everything by, and it has one property no other
 * identifier in this service has: <b>a scoped name contains a slash</b> ({@code @qits/angular}), so
 * on the wire it arrives percent-encoded as {@code @qits%2fangular} — Vert.x' {@code
 * normalizedPath()} decodes dot-segments but leaves {@code %2f} alone, so the router matches the
 * <em>encoded</em> form and this class is where it is turned back into a name. Both spellings are
 * accepted, because npm sends the encoded one for a packument and the unencoded one for whatever URL
 * we put in {@code dist.tarball}.
 *
 * <p>A tarball file name is {@code <unscoped>-<version>.tgz} — the <em>unscoped</em> half, which is
 * npmjs' own layout and the reason a scoped package's tarball path has no second {@code @} in it.
 * That convention is what makes {@link #versionOfTarball} possible at all: the package name is
 * already known from the path, so stripping it off the front leaves the version, unambiguously, no
 * matter how many hyphens either contains.
 */
public record NpmPackageName(String scope, String unscoped, String full) {

  /** npm's own cap; a name over it is not installable anywhere. */
  private static final int MAX_LENGTH = 214;

  /**
   * One name component. Deliberately permissive about case: new packages may not carry uppercase,
   * but {@code JSONStream} and friends predate that rule and the proxy has to be able to fetch them.
   */
  private static final Pattern COMPONENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._~-]*");

  /**
   * Percent-decodes the one escape that matters. Vert.x hands the router a path with its escapes
   * intact, and {@code %2f} is the only one npm relies on — decoding the whole segment with {@code
   * URLDecoder} would also turn {@code +} into a space, which is form encoding rather than path
   * encoding and would corrupt a legal name.
   */
  public static String decodePathSegment(String raw) {
    return raw == null ? null : raw.replace("%2f", "/").replace("%2F", "/");
  }

  /**
   * Parses a decoded package name.
   *
   * @throws NpmException {@code 400} if it is not a name npm would accept
   */
  public static NpmPackageName parse(String decoded) {
    if (decoded == null || decoded.isEmpty() || decoded.length() > MAX_LENGTH) {
      throw new NpmException(400, "not a valid npm package name: " + decoded);
    }
    if (!decoded.startsWith("@")) {
      requireComponent(decoded, decoded);
      return new NpmPackageName(null, decoded, decoded);
    }
    int slash = decoded.indexOf('/');
    if (slash < 2 || slash == decoded.length() - 1) {
      throw new NpmException(
          400, "a scoped package must be written @scope/name, not: " + decoded);
    }
    String scope = decoded.substring(1, slash);
    String unscoped = decoded.substring(slash + 1);
    requireComponent(scope, decoded);
    requireComponent(unscoped, decoded);
    return new NpmPackageName(scope, unscoped, decoded);
  }

  private static void requireComponent(String component, String whole) {
    if (!COMPONENT.matcher(component).matches()) {
      throw new NpmException(400, "not a valid npm package name: " + whole);
    }
  }

  /** {@code true} for {@code @scope/name}. */
  public boolean scoped() {
    return scope != null;
  }

  /** The file name a tarball is served under: {@code <unscoped>-<version>.tgz}. */
  public String tarballFile(String version) {
    return unscoped + "-" + version + ".tgz";
  }

  /**
   * The version a tarball file name encodes, or {@code null} if it does not belong to this package.
   *
   * <p>Case-insensitive on the name half only: npm's own registry serves a legacy package's tarball
   * under a lowercased file name in some documents, and answering 404 for a byte-identical tarball
   * because of a capital letter would be a confusing way to fail.
   */
  public String versionOfTarball(String file) {
    if (file == null || !file.endsWith(".tgz")) {
      return null;
    }
    String stem = file.substring(0, file.length() - ".tgz".length());
    String prefix = unscoped + "-";
    if (stem.length() <= prefix.length()
        || !stem.regionMatches(true, 0, prefix, 0, prefix.length())) {
      return null;
    }
    return stem.substring(prefix.length());
  }
}
