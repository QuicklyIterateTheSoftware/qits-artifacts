package eu.wohlben.qits.artifacts.api;

import eu.wohlben.qits.artifacts.control.OciMirrorUpstreams;
import eu.wohlben.qits.artifacts.dto.MirrorUpstreamSummary;
import eu.wohlben.qits.artifacts.entity.OciMirrorUpstream;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * Which upstream registries this one mirrors, and under which namespace.
 *
 * <p>The discoverability the user ruled ⚖1 for: an operator (and the explorer's management panel,
 * workstream CA) can read and edit the upstream list instead of having to know a deployment's
 * config. Thin over {@code OciMirrorUpstreams}, which owns the pairing invariant — an upstream row
 * and the {@code oci-mirror} repository row named by its slug are written together or not at all.
 *
 * <p><b>Writes are token-guarded, reads are open</b>, exactly like every neighbour here: {@code
 * ArtifactsTokenFilter} names the {@code mirror-upstreams} prefix in its guarded set, which is a
 * line that has to be added rather than inherited — a resource served outside those prefixes ships
 * unguarded, and this one accepts writes.
 *
 * <p>{@code PUT} rather than {@code POST} for the create, mirroring {@code RepositoryController}:
 * the domain is the key, so the idempotent shape is the natural one, and re-running a provisioning
 * script is a no-op rather than a duplicate.
 */
@Path("/mirror-upstreams")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MirrorUpstreamController {

  @Inject OciMirrorUpstreams upstreams;

  public record ListUpstreamsResponse(List<MirrorUpstreamSummary> upstreams) {}

  /** Every registered upstream, by namespace. A read — open, like its neighbours. */
  @GET
  @Operation(hidden = true)
  public ListUpstreamsResponse list() {
    return new ListUpstreamsResponse(upstreams.list());
  }

  public record UpstreamResponse(MirrorUpstreamSummary upstream) {}

  /** One upstream by domain. 404 if nothing mirrors it. */
  @GET
  @Path("/{domain}")
  @Operation(hidden = true)
  public UpstreamResponse get(@PathParam("domain") String domain) {
    return new UpstreamResponse(upstreams.get(domain));
  }

  public record RegisterUpstreamRequest(@NotNull String slug) {}

  /**
   * Registers an upstream under a namespace, or returns the one already registered.
   *
   * <p>Write path — token-guarded. Creating one also creates its {@code oci-mirror} repository row,
   * in the same transaction. Changing an existing upstream's slug is a 400: content is cached under
   * the old namespace, so moving the name would strand it.
   */
  @PUT
  @Path("/{domain}")
  @Operation(hidden = true)
  public UpstreamResponse register(
      @PathParam("domain") String domain, @Valid RegisterUpstreamRequest request) {
    OciMirrorUpstream upstream = upstreams.ensure(domain, request.slug());
    return new UpstreamResponse(upstreams.get(upstream.domain));
  }

  /**
   * Stops mirroring an upstream.
   *
   * <p>Write path — token-guarded. <b>The cache stays.</b> The repository row and every manifest,
   * tag and blob under the namespace are left exactly as they are (⚖2, append-only); what ends is
   * the ability to fetch anything new into it. A caller that wants the bytes gone is asking for
   * deletion, which this service does not do.
   */
  @DELETE
  @Path("/{domain}")
  @Operation(hidden = true)
  public Response delete(@PathParam("domain") String domain) {
    upstreams.delete(domain);
    return Response.noContent().build();
  }
}
