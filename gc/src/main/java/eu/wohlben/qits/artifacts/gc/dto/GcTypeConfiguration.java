package eu.wohlben.qits.artifacts.gc.dto;


/**
 * One line of the report's configuration echo: what this deployment has told the collector to do
 * with a repository type, and what that means in a sentence.
 *
 * <p>The echo exists because the settlement moved the policy into configuration, and configuration
 * is the half of a plan a reviewer cannot see from the dead and kept lists. A report that showed
 * only outcomes would be unreviewable the moment a window was wrong: "nothing died" reads the same
 * whether the rule is right or the window is a year.
 *
 * @param type the repository type, in its wire spelling
 * @param strategy the configured engine — {@code cache}, {@code own} or {@code excluded}
 * @param window the configured window, ISO-8601, or null for a type nobody collects
 * @param rule the effective rule, spelled out with this type's own window in it
 */
public record GcTypeConfiguration(
    String type, String strategy, String window, String rule) {}
