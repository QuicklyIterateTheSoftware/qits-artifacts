package eu.wohlben.qits.artifacts.api;

import eu.wohlben.qits.artifacts.control.GcPlanner;
import eu.wohlben.qits.artifacts.dto.GcPlanReport;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * What garbage collection would delete — the whole of the GC surface, on purpose.
 *
 * <p>There is <b>no execute endpoint</b>, and its absence is the feature: this platform has never
 * deleted a byte, and the first deletion happens after a person has read these plans, not because a
 * URL existed. Collection, when it is switched on, is an internal process behind a config flag; it
 * never becomes an API. The registries' {@code 405} on client deletes is unaffected by any of this.
 *
 * <p>A read, so unguarded like its neighbours — {@code ArtifactsTokenFilter} covers write methods
 * only. The {@code gc} prefix is named in that filter's set anyway, so that the day something here
 * writes, it is guarded by default rather than by remembering.
 */
@Path("/gc")
@Produces(MediaType.APPLICATION_JSON)
public class GcPlanController {

  @Inject GcPlanner planner;

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
}
