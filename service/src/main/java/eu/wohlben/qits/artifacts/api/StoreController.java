package eu.wohlben.qits.artifacts.api;

import eu.wohlben.qits.artifacts.control.ArtifactExplorerService;
import eu.wohlben.qits.artifacts.dto.StoreSummary;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * The whole store, in seven numbers that do not add up.
 *
 * <p>The first resource this service serves outside {@code /repositories/}, which is worth knowing
 * for one reason: {@code ArtifactsTokenFilter} matches by path prefix, and it now names this segment
 * too, so a write added here would be guarded rather than quietly open. Nothing here writes.
 */
@Path("/store")
@Produces(MediaType.APPLICATION_JSON)
public class StoreController {

  @Inject ArtifactExplorerService explorer;

  @GET
  @Path("/summary")
  @Operation(hidden = true)
  public StoreSummary summary() {
    return explorer.storeSummary();
  }
}
