package eu.wohlben.qits.artifacts.gc;

/**
 * Which of the two engines collects a repository type — the settlement's whole policy vocabulary.
 *
 * <p>The user's settlement (artifacts-gc-plan.md, 2026-08-05) replaced "one bespoke strategy per
 * type" with "two generic strategies, configured per type". This enum is that decision spelled as
 * something a deployment can set: a type is a cache, or it holds the platform's own artifacts, or
 * nobody collects it yet.
 *
 * <p><b>{@link #EXCLUDED} is a decision, not a gap.</b> It is what the report prints for a type
 * whose rule has been thought about and deliberately not implemented — the two CI types today — and
 * it is not the same fact as a type missing from the configuration altogether, which is a type
 * nobody has considered and which {@link GcTypeConfig} refuses to answer for.
 */
public enum GcPolicy {

  /**
   * A pull-through cache of somebody else's content: everything unaccessed past the window goes,
   * because every byte of it is re-fetchable and the only cost of being wrong is a re-download.
   */
  CACHE,

  /**
   * The platform's own artifacts: the last released versions are kept whatever their age, and the
   * rest ages out. Own-ness is what earns version protection — a cache has no releases to protect.
   */
  OWN,

  /** Not collected. Nothing of this type is ever deleted, and the report says so under this name. */
  EXCLUDED
}
