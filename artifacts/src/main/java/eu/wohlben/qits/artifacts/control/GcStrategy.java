package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.dto.GcIdentity;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import java.util.List;
import java.util.Set;

/**
 * One repository type's garbage collection: which of <b>its</b> identities are dead, and which blobs
 * that changes.
 *
 * <p>The seam exists so the substrate can stay policy-free, and it is drawn where it is for one
 * reason: an identity — a tag, a version, a record row — has a meaning only inside its type, while a
 * blob has none anywhere. So a strategy answers only about identities and the blobs they name, and
 * <b>never</b> decides that a blob may be unlinked. Which blobs lost their last reference across all
 * types is {@link BlobSweep}'s answer, and only its answer.
 *
 * <p><b>One strategy per type, sharing no policy code.</b> Docker's and npm's rules look alike today
 * — releases stay, keep the newest prerelease — and that is a coincidence, not an identity: what a
 * release is, what "newest" is, and what deleting one breaks are different in the two systems. Each
 * implementation owns its rule end to end. This interface is the only thing they share, there is no
 * base class, and a retention-rule framework growing between them would be the mistake this seam is
 * shaped to prevent. Registering one is a CDI bean of this type and nothing else.
 *
 * <p><b>Failing is a supported answer, and it is fail-closed.</b> A strategy whose keep-set depends
 * on something outside this service — the OCI rule reads qits-cd's live deployment pins at plan time
 * — must throw rather than plan without it. The planner catches it, reports the type as failed, and
 * treats every blob the census attributes to that type as live, so an unreachable dependency
 * reclaims nothing instead of reclaiming something it cannot vouch for.
 */
public interface GcStrategy {

  /** The type this strategy collects. Exactly one strategy may claim a type. */
  RepositoryType type();

  /**
   * Reads the census and says what would die.
   *
   * <p>Pure and side-effect free: this is called for a dry-run report, and a report that changed the
   * store would be the one thing nobody could review.
   *
   * @param census the store as it stands, including this type's live blob set
   * @throws RuntimeException the keep-set cannot be established safely — the type is reported as
   *     failed and nothing of it is planned
   */
  Plan plan(LiveBlobCensus.Census census);

  /**
   * What a strategy hands back: the identities, and the two blob sets the reconciliation needs.
   *
   * <p>The two sets may overlap, and that overlap is the point of asking for both. A layer under a
   * dying tag and a surviving one is released <em>and</em> retained; subtracting is the substrate's
   * job, so a strategy never has to reason about the store beyond its own type.
   *
   * @param dead the identities this run would delete, each naming the rule that condemned it
   * @param kept the identities it would keep, each naming the rule that saved it — the half of a
   *     dry-run report that makes the other half reviewable
   * @param blobsReleased every blob the dead identities reference
   * @param blobsRetained every blob this type still references once the dead ones are gone —
   *     the type's live set <em>after</em> the plan, not the delta
   */
  record Plan(
      List<GcIdentity> dead,
      List<GcIdentity> kept,
      Set<String> blobsReleased,
      Set<String> blobsRetained) {

    public Plan {
      dead = List.copyOf(dead);
      kept = List.copyOf(kept);
      blobsReleased = Set.copyOf(blobsReleased);
      blobsRetained = Set.copyOf(blobsRetained);
    }

    /** A strategy with nothing to do — the honest answer for a type whose rules exist but match no row. */
    public static Plan nothingDies(List<GcIdentity> kept, Set<String> live) {
      return new Plan(List.of(), kept, Set.of(), live);
    }
  }
}
