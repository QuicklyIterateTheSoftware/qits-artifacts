package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.gc.dto.GcTypeConfiguration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The configured policy, read back as sentences — the report's configuration echo.
 *
 * <p>Each engine owns its own sentence ({@link CacheEvictionStrategy#rule}, {@link
 * OwnArtifactsStrategy#rule}), so the text a reviewer reads is written beside the code that acts on
 * it and cannot drift from it. This class only walks the types and asks.
 *
 * <p>A type whose configuration is missing or unusable is reported as such rather than skipped: a
 * type absent from the echo would read as "no policy", which is the one thing the echo exists to
 * make impossible to mistake.
 */
final class GcRules {

  static final String EXCLUDED =
      "excluded: no engine is configured for this type, so nothing of it is ever deleted. A"
          + " decision, not a gap — the rule it will eventually get is named in its strategy class.";

  private GcRules() {}

  /** One line per repository type, in the enum's own order. */
  static List<GcTypeConfiguration> echo(GcTypeConfig config) {
    List<GcTypeConfiguration> lines = new ArrayList<>();
    for (RepositoryType type : RepositoryType.values()) {
      lines.add(line(config, type));
    }
    return List.copyOf(lines);
  }

  private static GcTypeConfiguration line(GcTypeConfig config, RepositoryType type) {
    GcPolicy policy;
    try {
      policy = config.of(type).strategy();
    } catch (RuntimeException unconfigured) {
      return new GcTypeConfiguration(type, null, null, unconfigured.getMessage());
    }
    if (policy == GcPolicy.EXCLUDED) {
      return new GcTypeConfiguration(type, name(policy), null, EXCLUDED);
    }
    Duration window;
    try {
      window = config.requireWindow(type);
    } catch (RuntimeException unconfigured) {
      return new GcTypeConfiguration(type, name(policy), null, unconfigured.getMessage());
    }
    String rule =
        policy == GcPolicy.CACHE
            ? CacheEvictionStrategy.rule(window)
            : OwnArtifactsStrategy.rule(window);
    return new GcTypeConfiguration(type, name(policy), GcPlanner.iso(window), rule);
  }

  /** The wire spelling of a policy: what a deployment writes in its configuration. */
  private static String name(GcPolicy policy) {
    return policy.name().toLowerCase(Locale.ROOT);
  }
}
