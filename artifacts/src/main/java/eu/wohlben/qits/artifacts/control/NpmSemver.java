package eu.wohlben.qits.artifacts.control;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A semver version, ordered by the spec's own precedence rules (semver.org §11).
 *
 * <p>Written out here rather than pulled in, for the reason every dependency in this repo is
 * weighed against: the clone-alone rule and the native image both get cheaper the fewer libraries
 * the builder has to be told about, and precedence is forty lines. It is deliberately <b>only</b>
 * ordering — no ranges, no matching, no coercion of near-misses. A string that is not semver does
 * not parse, and the caller decides what that means.
 *
 * <p>Three rules are easy to get wrong and each has a test:
 *
 * <ul>
 *   <li><b>A prerelease sorts below its own release.</b> {@code 1.0.0-rc.1 < 1.0.0}, which is the
 *       whole reason this class exists: a {@code -main.g<sha>} build must never outrank the release
 *       it was built from.
 *   <li><b>Numeric identifiers compare numerically, alphanumeric ones as ASCII</b>, and a numeric
 *       identifier always sorts below an alphanumeric one — so {@code 1.0.0-2 < 1.0.0-alpha}.
 *   <li><b>Leading zeros are not allowed</b> in any numeric identifier, so {@code 1.0.0-01} is not
 *       a version at all rather than a synonym for {@code 1.0.0-1}. That rule is what lets the
 *       numeric comparison below be length-then-lexical on the digits: with no leading zeros, the
 *       longer digit string is always the larger number, and nothing has to fit in a {@code long}.
 * </ul>
 *
 * <p>Build metadata ({@code +…}) is parsed and then ignored, exactly as the spec says: two versions
 * differing only in build metadata have equal precedence.
 */
public record NpmSemver(String major, String minor, String patch, List<String> prerelease)
    implements Comparable<NpmSemver> {

  /** The spec's own suggested grammar, as one expression: core, optional prerelease, optional build. */
  private static final Pattern SEMVER =
      Pattern.compile(
          "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
              + "(?:-((?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*)"
              + "(?:\\.(?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*))*))?"
              + "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$");

  private static final Pattern NUMERIC = Pattern.compile("\\d+");

  /** Parses a version, or returns empty for anything that is not one. Never throws. */
  public static Optional<NpmSemver> parse(String version) {
    if (version == null) {
      return Optional.empty();
    }
    Matcher m = SEMVER.matcher(version);
    if (!m.matches()) {
      return Optional.empty();
    }
    List<String> prerelease =
        m.group(4) == null ? List.of() : List.of(m.group(4).split("\\.", -1));
    return Optional.of(new NpmSemver(m.group(1), m.group(2), m.group(3), prerelease));
  }

  /** {@code true} when this version carries a prerelease part, and so sorts below its release. */
  public boolean isPrerelease() {
    return !prerelease.isEmpty();
  }

  @Override
  public int compareTo(NpmSemver other) {
    int core = compareNumeric(major, other.major);
    if (core == 0) {
      core = compareNumeric(minor, other.minor);
    }
    if (core == 0) {
      core = compareNumeric(patch, other.patch);
    }
    if (core != 0) {
      return core;
    }
    if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
      // A release outranks any prerelease of the same core version; two releases are equal.
      return Boolean.compare(prerelease.isEmpty(), other.prerelease.isEmpty());
    }
    for (int i = 0; i < Math.min(prerelease.size(), other.prerelease.size()); i++) {
      int identifier = compareIdentifier(prerelease.get(i), other.prerelease.get(i));
      if (identifier != 0) {
        return identifier;
      }
    }
    // All the identifiers they share are equal, so the one with more of them is the larger.
    return Integer.compare(prerelease.size(), other.prerelease.size());
  }

  private static int compareIdentifier(String a, String b) {
    boolean numericA = NUMERIC.matcher(a).matches();
    boolean numericB = NUMERIC.matcher(b).matches();
    if (numericA && numericB) {
      return compareNumeric(a, b);
    }
    if (numericA != numericB) {
      // "Numeric identifiers always have lower precedence than alphanumeric identifiers."
      return numericA ? -1 : 1;
    }
    return a.compareTo(b);
  }

  /**
   * Compares two digit strings as numbers without converting them. Legal semver forbids leading
   * zeros, so the longer string is the larger number and equal lengths compare lexically — which
   * also means a sha-shaped identifier of any length can never overflow anything.
   */
  private static int compareNumeric(String a, String b) {
    return a.length() != b.length()
        ? Integer.compare(a.length(), b.length())
        : a.compareTo(b);
  }
}
