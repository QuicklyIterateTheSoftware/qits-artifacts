package eu.wohlben.qits.daemon;

/**
 * The route grammar for {@code /artifacts/daemons}.
 *
 * <p>One regex with named {@code (?<name>…)} and {@code (?<version>…)} groups, the {@code
 * RegistryPaths}/{@code NpmPaths}/{@code MavenPaths} pattern.
 *
 * <p><b>Every group here is either {@code (?<name>…)} or {@code (?:…)}, never a bare {@code (…)}.</b>
 * vertx-web compares {@code Matcher.groupCount()} against the named groups it scraped out of the
 * pattern and silently falls back to positional {@code param0…paramN} when the two disagree — so one
 * stray capturing group breaks every {@code pathParam(…)} on that route, at runtime, with no error
 * anywhere.
 *
 * <p>Matching runs against {@code normalizedPath()}, which collapses dot-segments before routing —
 * so {@code ..} never reaches a handler.
 *
 * <p><b>There is no repository segment</b>, and that is the one place this grammar differs from npm's
 * and maven's. Those surfaces host many namespaces because their clients name a registry URL; this
 * one serves the platform's own daemons and nothing else, so the repository is the seeded {@code
 * daemons} row ({@link eu.wohlben.qits.artifacts.control.ArtifactsRepositorySeeder#DAEMONS}) and the
 * first segment after the base is the daemon's name. A second daemon namespace would be a design
 * decision, not a path someone can mint by typing one.
 */
final class DaemonPaths {

  private DaemonPaths() {}

  /**
   * The mount point — a literal in the code exactly as {@code /artifacts/git}, {@code /artifacts/npm}
   * and {@code /artifacts/maven} are. No config key moves it and no JAX-RS test would notice if it
   * drifted, which is why {@code DaemonRegistryTest} spells its paths out absolutely.
   */
  static final String BASE = "/artifacts/daemons";

  /** The daemon, e.g. {@code qits-ci-daemon}. */
  private static final String NAME = "(?<name>[a-z0-9][a-z0-9._-]{0,63})";

  /**
   * The version. Wide enough for calver, for a semver prerelease and for the 64-hex digest the
   * adopted rows use (⚖5) — shape is the handler's business, and the answer for a malformed one is a
   * {@code 400} there, not a {@code 404} here.
   */
  private static final String VERSION = "(?<version>[A-Za-z0-9][A-Za-z0-9._+-]{0,127})";

  /** {@code /artifacts/daemons/<name>/<version>} — publish and version-addressed download. */
  static final String BINARY = route(NAME + "/" + VERSION);

  /**
   * Builds a route regex under {@link #BASE}.
   *
   * <p>A method call rather than string concatenation, and that is not styling — the reason is
   * {@code MavenPaths.route}'s verbatim: a {@code static final String} initialised from a constant
   * expression is inlined by javac into every class that reads it, including the test, which would
   * then keep asserting against whatever the value was when it was last compiled.
   */
  private static String route(String suffix) {
    return BASE + "/" + suffix;
  }
}
