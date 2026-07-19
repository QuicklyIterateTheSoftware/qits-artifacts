package eu.wohlben.qits.artifactory.api;

import eu.wohlben.qits.artifactory.control.ArtifactRepositoryService;
import eu.wohlben.qits.artifactory.dto.ArtifactRepositoryDto;
import eu.wohlben.qits.artifactory.entity.RepositoryType;
import eu.wohlben.qits.artifactory.mapper.ArtifactRepositoryMapper;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * The artifactory repository lifecycle boundary (docs/epics/qits-artifactory/). Thin controllers
 * over the {@code artifactory} module's services; all paths are relative to {@code /api}. Hidden
 * from the OpenAPI document (a wire/system API — like the capture/OTLP receivers — so {@code
 * docs/openapi.yml} and the generated Angular client stay untouched).
 */
@Path("/artifactory/repositories")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RepositoryController {

  @Inject ArtifactRepositoryService repositoryService;

  @Inject ArtifactRepositoryMapper mapper;

  public record EnsureRepositoryRequest(@NotNull RepositoryType type) {
    public record Response(ArtifactRepositoryDto repository) {}
  }

  /**
   * Idempotently create/ensure a repository with a type. Write path — token-guarded in `service`.
   */
  @PUT
  @Path("/{repo}")
  @Operation(hidden = true)
  public EnsureRepositoryRequest.Response ensure(
      @PathParam("repo") String repo, @Valid EnsureRepositoryRequest request) {
    var entity = repositoryService.ensure(repo, request.type());
    return new EnsureRepositoryRequest.Response(mapper.toDto(entity));
  }

  public record ListRepositoriesResponse(List<ArtifactRepositoryDto> repositories) {}

  @GET
  @Operation(hidden = true)
  public ListRepositoriesResponse list() {
    return new ListRepositoriesResponse(
        repositoryService.list().stream().map(mapper::toDto).toList());
  }
}
