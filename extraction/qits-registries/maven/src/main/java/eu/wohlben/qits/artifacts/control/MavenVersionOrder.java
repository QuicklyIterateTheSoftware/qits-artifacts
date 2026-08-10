package eu.wohlben.qits.artifacts.control;

import java.util.Comparator;

/**
 * Maven version ordering, the subset the platform's own versions exercise.
 *
 * <p>This is deliberately not {@code ComparableVersion}'s whole grammar. Dotted numeric tokens
 * compare numerically ({@code 1.0.10} above {@code 1.0.9}, calver {@code 2026.801.85448} above
 * {@code 2026.801.85447}), and a qualifier after the first dash ranks by the well-known ladder with
 * {@code SNAPSHOT} below a release of the same base. What it cannot order it does not guess at: an
 * unparseable token sorts <b>last</b>, deterministically, and the document still serves — a
 * metadata {@code GET} that 500s breaks every consumer of the repository at once, which is the
 * refusal-honesty npm's semver guard takes the other side of (npm refuses the publish; maven
 * answers the reader).
 */
public final class MavenVersionOrder implements Comparator<String> {

  public static final MavenVersionOrder INSTANCE = new MavenVersionOrder();

  private MavenVersionOrder() {}

  /**
   * The qualifier ladder, lowest first: an unknown qualifier ranks below every known one and above
   * nothing — it sorts last, with a lexical tie-break so the order stays total.
   */
  private static int qualifierRank(String qualifier) {
    return switch (qualifier.toLowerCase(java.util.Locale.ROOT)) {
      case "alpha", "a" -> 1;
      case "beta", "b" -> 2;
      case "milestone", "m" -> 3;
      case "rc", "cr" -> 4;
      case "snapshot" -> 5;
      case "" -> 6;
      case "sp" -> 7;
      default -> Integer.MAX_VALUE;
    };
  }

  @Override
  public int compare(String left, String right) {
    if (left.equals(right)) {
      return 0;
    }
    String[] leftParts = splitQualifier(left);
    String[] rightParts = splitQualifier(right);
    boolean leftNumeric = isNumericDotted(leftParts[0]);
    boolean rightNumeric = isNumericDotted(rightParts[0]);
    if (!leftNumeric || !rightNumeric) {
      // A token that cannot be ordered sorts LAST, never first, and the order stays total so the
      // derived document still serves.
      if (leftNumeric != rightNumeric) {
        return leftNumeric ? -1 : 1;
      }
      return left.compareTo(right);
    }
    int main = compareNumericDotted(leftParts[0], rightParts[0]);
    if (main != 0) {
      return main;
    }
    int leftRank = qualifierRank(leftParts[1]);
    int rightRank = qualifierRank(rightParts[1]);
    if (leftRank != rightRank) {
      return Integer.compare(leftRank, rightRank);
    }
    return leftParts[1].compareToIgnoreCase(rightParts[1]);
  }

  /** Splits at the first dash: {@code 1.0.1-SNAPSHOT} → {@code ["1.0.1", "SNAPSHOT"]}. */
  private static String[] splitQualifier(String version) {
    int dash = version.indexOf('-');
    return dash < 0
        ? new String[] {version, ""}
        : new String[] {version.substring(0, dash), version.substring(dash + 1)};
  }

  private static boolean isNumericDotted(String main) {
    if (main.isEmpty()) {
      return false;
    }
    for (String token : main.split("\\.")) {
      if (token.isEmpty() || !token.chars().allMatch(Character::isDigit)) {
        return false;
      }
    }
    return true;
  }

  /** Numeric token by numeric token; a missing token is zero, so {@code 1.0} equals {@code 1.0.0}. */
  private static int compareNumericDotted(String left, String right) {
    String[] leftTokens = left.split("\\.");
    String[] rightTokens = right.split("\\.");
    for (int i = 0; i < Math.max(leftTokens.length, rightTokens.length); i++) {
      long l = i < leftTokens.length ? Long.parseLong(leftTokens[i]) : 0L;
      long r = i < rightTokens.length ? Long.parseLong(rightTokens[i]) : 0L;
      if (l != r) {
        return Long.compare(l, r);
      }
    }
    return 0;
  }
}
