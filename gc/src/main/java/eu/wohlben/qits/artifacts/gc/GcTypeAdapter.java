package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.entity.RepositoryType;
import java.util.Comparator;
import java.util.List;

/**
 * Everything one repository type has to say about itself for a generic engine to collect it.
 *
 * <p>The settlement replaced six bespoke strategies with two engines and a per-type configuration,
 * and this is the seam that keeps that from becoming a retention-rule framework: the <b>rules</b>
 * live in {@link CacheEvictionStrategy} and {@link OwnArtifactsStrategy}, written once; the
 * <b>facts</b> live here, one implementation per type, sharing no policy at all. An adapter never
 * decides what dies — it answers what exists, what a release is, when something was last touched,
 * and how a row is removed.
 *
 * <p>The line between the two is worth stating, because it is the line the old design got wrong in
 * the other direction: "which of these is superseded" is a rule and belongs to an engine; "is
 * {@code 1.2.3-main.gab854a1} a release" is a fact about npm and can only be answered by npm's
 * adapter. Nothing in an engine may grow a {@code switch} on {@link RepositoryType}, and nothing in
 * an adapter may grow a window or a keep-count.
 */
public interface GcTypeAdapter {

  /** The type this adapter speaks for. */
  RepositoryType type();

  /**
   * Every identity of this type that exists right now, each with its effective access time.
   *
   * <p>Pure and side-effect free: an engine's plan is what a dry-run report prints, and a report
   * that changed the store would be the one report nobody could review.
   */
  List<GcCandidate> enumerate();

  /**
   * How this type orders two of its identities by age — <b>oldest first</b>.
   *
   * <p>Read only by {@link OwnArtifactsStrategy}, to find a group's last releases, and a per-type
   * fact for the same reason {@link GcCandidate#released()} is: "newest" is semver precedence in one
   * type and a row timestamp in another, and an engine that picked one would silently keep the wrong
   * version in the other.
   */
  Comparator<GcCandidate> byAge();

  /**
   * Deletes the identity rows an engine condemned, through this type's own collection funnel.
   *
   * <p>The execute half of the seam, and the mechanics stay per type because the funnels do: an npm
   * version goes through the tombstone-writing collect, an OCI tag through the registry's, a maven
   * path through none of them. The grace window gates identities here exactly as {@link
   * GcStrategy#apply} documents — a row deleted over a blob still inside the window would strand the
   * blob as row-less and therefore untouchable forever.
   *
   * @param plan the plan an engine produced from this adapter's own {@link #enumerate()}, moments
   *     ago and in the same request
   * @param grace answers whether a blob's file is still inside the grace window
   */
  GcStrategy.Applied delete(GcStrategy.Plan plan, GcStrategy.GraceWindow grace);
}
