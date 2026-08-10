package eu.wohlben.qits.artifacts.gc;

/**
 * Which engine collects a repository type — the settlement's whole policy vocabulary, narrowed to
 * the engines this service has.
 *
 * <p>The user's settlement (artifacts-gc-plan.md, 2026-08-05) replaced "one bespoke strategy per
 * type" with "generic strategies, configured per type". This enum is that decision spelled as
 * something a deployment can set.
 *
 * <p><b>{@code CACHE} is gone, and its absence is a refusal rather than an omission.</b> The
 * eviction engine went to qits-platform-mirror with the three types it collected, so a deployment
 * that writes {@code strategy=cache} here is asking for an engine this process does not have.
 * Without the constant that request fails while the configuration is being read, naming the two
 * values that exist; with it, the type would be accepted, planned by nothing, and reported as
 * uncollected — which reads as a rule that ran.
 *
 * <p><b>{@link #EXCLUDED} is a decision, not a gap.</b> It is what the report prints for a type
 * whose rule has been thought about and deliberately not implemented — the two CI types today — and
 * it is not the same fact as a type missing from the configuration altogether, which is a type
 * nobody has considered and which {@link GcTypeConfig} refuses to answer for.
 */
public enum GcPolicy {

  /**
   * The platform's own artifacts: the last released versions are kept whatever their age, and the
   * rest ages out. Own-ness is what earns version protection — a cache has no releases to protect,
   * which is why the other engine was never this one with a flag.
   */
  OWN,

  /** Not collected. Nothing of this type is ever deleted, and the report says so under this name. */
  EXCLUDED
}
