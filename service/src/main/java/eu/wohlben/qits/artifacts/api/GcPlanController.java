package eu.wohlben.qits.artifacts.api;

import eu.wohlben.qits.artifacts.gc.GcPlanner;
import eu.wohlben.qits.artifacts.gc.GcSweepExecutor;
import eu.wohlben.qits.artifacts.gc.dto.GcPlanReport;
import eu.wohlben.qits.artifacts.gc.dto.GcRepositoriesPlanResponse;
import eu.wohlben.qits.artifacts.gc.dto.GcRepositoryPlanReport;
import eu.wohlben.qits.artifacts.gc.dto.GcSweepReport;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * The whole GC surface: the dry-run plan to read, and the sweep to invoke once it has been read.
 *
 * <p>The order of the two routes is the feature. {@code GET /gc/plan} stays the default and changes
 * nothing — a report that could not be reviewed without side effects would be the one report nobody
 * could review. {@code POST /gc/sweep} executes exactly what a plan at that moment says: identity
 * rows per type, then the blob unlinks, each gated by the grace window (identities included — a row
 * deleted over an in-grace blob would strand the blob as row-less and untouchable). It landed after
 * the user reviewed the dry-run, and nothing executes without the {@code POST}.
 *
 * <p><b>The same pair exists per repository</b>, under {@code /gc/repositories}: the listing's
 * figures, and one repository's plan in full. Scope is a <b>path segment</b> rather than a query
 * parameter, and that is a decision about the destructive half of the surface: a dropped or
 * mistyped {@code ?repository=} would silently widen a scoped call into a whole-store one, while a
 * mistyped segment is a 404 and can never widen into anything. Nothing about the rules changes with
 * scope — a repository's plan is its share of its type's one plan, reconciled against the same
 * census, never a second policy.
 *
 * <p><b>The guard, honestly.</b> The sweep is a write, and {@code gc} sits in {@code
 * ArtifactsTokenFilter}'s guarded prefix set, so the {@code POST} inherits the {@code
 * X-Artifacts-Token} check by construction. But the live deployment ships {@code
 * qits.artifacts.token} <b>blank</b>, which makes that filter a no-op — the guard is inert until
 * the platform's auth posture lands (the standing qits-idp direction; per the recorded
 * no-interim-token-schemes decision, nothing here invents one meanwhile). Until then the real
 * front door is the gateway's session policy, and the sweep's own safety is its content: on a
 * store younger than the grace window it deletes nothing, provably.
 *
 * <p>The registries' {@code 405} on client deletes is unaffected: no client gains deletion
 * semantics from any of this. The sweep is an operator invocation of an internal process, and its
 * receipt is the plan report's executed twin.
 */
@Path("/gc")
@Produces(MediaType.APPLICATION_JSON)
public class GcPlanController {

  @Inject GcPlanner planner;
  @Inject GcSweepExecutor executor;

  /**
   * Every repository type, its strategy's plan or the reason it has none, the cross-type sweep, and
   * the row-less pool no plan may touch. Costs one census — a disk walk and a pass over the rows.
   */
  @GET
  @Path("/plan")
  @Operation(hidden = true)
  public GcPlanReport plan() {
    return planner.plan();
  }

  /**
   * Every repository's expected cleanup, from <b>one</b> run of the same plan.
   *
   * <p>What the explorer's repository listing reads. It costs exactly what {@code /gc/plan} costs —
   * one census, one pin fetch — and is a stripped reading of that report rather than a second
   * computation of it: identity lists and blob digests are left out here and answered per
   * repository below. A route that answered one repository at a time would make a listing cost N
   * censuses and 2N cross-service calls, and would read the live pins N times in a run that is
   * supposed to read them once.
   */
  @GET
  @Path("/repositories")
  @Operation(hidden = true)
  public GcRepositoriesPlanResponse repositories() {
    return planner.planForRepositories();
  }

  /**
   * One repository's plan in full: the identities that would die and the ones that would not, each
   * with its rule, the two blob figures, the configuration echo, the pins provenance and the
   * row-less pool.
   *
   * <p>This is the review artifact for a scoped sweep, and it is a separate URL from the sweep for
   * the same reason the global pair is: a report that could not be read without side effects would
   * be the one report nobody could review. 404 for a repository that does not exist — a path
   * segment cannot be dropped the way a query parameter can, so a mistyped name here can never
   * silently widen into something else.
   */
  @GET
  @Path("/repositories/{repository}/plan")
  @Operation(hidden = true)
  public GcRepositoryPlanReport repositoryPlan(@PathParam("repository") String repository) {
    return planner.planForRepository(repository);
  }

  /**
   * Executes one sweep: a fresh plan, its identity deletions, the blob unlinks, and the receipt —
   * what was deleted per type, what was unlinked, what the grace window withheld, and the
   * untouchable pool restated.
   *
   * <p>A {@code POST} and nothing else, so a crawler or a probing {@code GET} can never delete a
   * byte. The plan it applies is computed inside this request; there is no way to submit one.
   */
  @POST
  @Path("/sweep")
  @Operation(hidden = true)
  public GcSweepReport sweep() {
    return executor.sweep();
  }
}
