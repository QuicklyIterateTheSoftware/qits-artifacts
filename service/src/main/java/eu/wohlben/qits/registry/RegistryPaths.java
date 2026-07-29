package eu.wohlben.qits.registry;

/**
 * The route grammar for {@code /v2}.
 *
 * <p>These are regexes rather than Vert.x path params for one unavoidable reason: an OCI {@code
 * <name>} may contain slashes ({@code qits/build-images/ci-base}), and {@code :param} never spans a
 * {@code /}. The {@link #NAME} group is <b>greedy</b>, which does the hard part for free — it takes
 * as much as it can while still leaving a literal {@code /blobs/…} or {@code /manifests/…} behind,
 * i.e. it splits on the <em>last</em> occurrence. That is what makes an image genuinely named {@code
 * foo/manifests} work.
 *
 * <p><b>Every group here is either {@code (?<name>…)} or {@code (?:…)}, never a bare {@code (…)}.</b>
 * vertx-web compares {@code Matcher.groupCount()} against the named groups it scraped out of the
 * pattern and silently falls back to positional {@code param0…paramN} when the two disagree — so one
 * stray capturing group breaks every {@code pathParam("name")} on that route, at runtime, with no
 * error anywhere.
 *
 * <p>Matching is against {@code normalizedPath()} and is a <em>full</em> match, so dot-segments are
 * already collapsed before the grammar is applied — the traversal defence is structural rather than
 * a check somebody has to remember.
 */
final class RegistryPaths {

  private RegistryPaths() {}

  static final String BASE = "/v2";

  /** One path component of a name, per the Distribution spec. Lowercase only. */
  private static final String COMPONENT = "[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*";

  /** {@code <name>} — one or more components joined by {@code /}. Greedy; see the class javadoc. */
  static final String NAME = "(?<name>" + COMPONENT + "(?:/" + COMPONENT + ")*)";

  /** A wire digest. Only sha256, because it is the only one we can verify. */
  static final String DIGEST = "(?<digest>sha256:[a-f0-9]{64})";

  /**
   * A {@code <ref>}: a tag or a digest. The two alternatives are disjoint — {@code :} is not in the
   * tag grammar — so there is no precedence rule to get wrong, and a reference matching neither
   * never reaches a handler at all.
   */
  static final String REF =
      "(?<ref>[a-zA-Z0-9_][a-zA-Z0-9._\\-]{0,127}|sha256:[a-f0-9]{64})";

  /**
   * An upload session id. Deliberately looser than a UUID: a malformed id should reach the handler
   * and be answered {@code BLOB_UPLOAD_UNKNOWN}, not miss the route and be answered {@code
   * NAME_UNKNOWN}, which would send a client looking in the wrong place. Bounded so it cannot itself
   * become a payload.
   */
  static final String SESSION = "(?<session>[0-9a-zA-Z\\-]{1,64})";

  static final String BLOB = route(NAME + "/blobs/" + DIGEST);
  static final String UPLOADS = route(NAME + "/blobs/uploads/?");
  static final String UPLOAD_SESSION = route(NAME + "/blobs/uploads/" + SESSION);
  static final String MANIFEST = route(NAME + "/manifests/" + REF);
  static final String TAGS_LIST = route(NAME + "/tags/list");

  /**
   * Builds a route regex under {@link #BASE}.
   *
   * <p>A method call rather than string concatenation, and that is not styling. A {@code static
   * final String} initialised from a constant expression is a compile-time constant, so javac
   * <em>inlines</em> it into every class that reads it — including {@code RegistryPathsTest}, which
   * would then keep asserting against whatever the value was when the test was last compiled. A
   * suite green against a pattern the router does not use is precisely the drift this repo refuses
   * to tolerate elsewhere in its config. Routing it through a method makes these ordinary fields,
   * read at runtime, so the test and the router can never disagree.
   */
  private static String route(String suffix) {
    return BASE + "/" + suffix;
  }
}
