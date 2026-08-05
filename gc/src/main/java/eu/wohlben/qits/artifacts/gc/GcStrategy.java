package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.control.LiveBlobCensus;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
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
   * A standing sentence the reports carry beside this strategy's line, or null.
   *
   * <p>Exists for the strategies whose whole plan needs a caption — the CI stubs use it to name the
   * rule they intend and the fact that nothing has ever produced their content. A strategy with
   * rules that speak for themselves returns null, which is what the default does.
   */
  default String note() {
    return null;
  }

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
   * Deletes the identity rows of a plan this strategy produced <b>moments ago</b> — the execute
   * half of the seam, called only by {@code GcSweepExecutor} and only on a plan computed in the
   * same request. A stored or hand-edited plan must never reach this method: the store moves, and
   * applying a stale plan is how the wrong identity dies.
   *
   * <p><b>The grace window gates identities here, not just blobs at the unlink.</b> The reason is
   * structural and worth keeping in view: a blob may only be swept by <em>losing</em> its last
   * identity row, so deleting a row while the blob's file is still inside the grace window would
   * strand the blob forever — row-less, therefore untouchable, and never reclaimed. An identity
   * whose released blobs include one still in grace is therefore <b>withheld whole</b>: its rows
   * stay, the next run re-plans it, and the run after the window matures deletes row and file
   * together.
   *
   * <p>Failures are collected per identity rather than thrown, because a half-applied type must
   * report what it did — the rows already deleted are deleted, and hiding them behind an exception
   * would leave their blobs unswept with nothing in the report to say why.
   *
   * <p>The default refuses a plan that condemns anything: a strategy whose rules can kill must own
   * its deletion mechanics by overriding this. For the strategies that never condemn — the mirror,
   * the CI stubs — the default is the correct implementation, applied to the empty set.
   *
   * @param plan this strategy's own freshly computed plan
   * @param grace answers whether a blob's file is still inside the grace window
   */
  default Applied apply(Plan plan, GraceWindow grace) {
    if (!plan.dead().isEmpty()) {
      throw new IllegalStateException(
          getClass().getSimpleName()
              + " condemned "
              + plan.dead().size()
              + " identities but does not implement apply(); a strategy that can kill must own its"
              + " deletion mechanics");
    }
    return Applied.nothing();
  }

  /** Whether a blob's file is younger than the sweep's grace window. Supplied by the executor. */
  @FunctionalInterface
  interface GraceWindow {
    boolean withinGrace(String blobId);
  }

  /**
   * What {@link #apply} actually did, identity by identity.
   *
   * @param deleted the identities whose rows are gone now
   * @param withheldByGraceWindow identities left whole because a blob they release is still inside
   *     the grace window — not lost, re-planned next run
   * @param errors identities that could not be applied, each with its reason; the rest of the plan
   *     was still applied
   */
  record Applied(
      List<GcIdentity> deleted, List<GcIdentity> withheldByGraceWindow, List<String> errors) {

    public Applied {
      deleted = List.copyOf(deleted);
      withheldByGraceWindow = List.copyOf(withheldByGraceWindow);
      errors = List.copyOf(errors);
    }

    public static Applied nothing() {
      return new Applied(List.of(), List.of(), List.of());
    }
  }

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
