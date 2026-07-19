package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.entity.RepositoryType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

/**
 * Idempotently ensures the two default CI repository types exist. Invoked by the service-side
 * startup gate ({@code ArtifactsStartupSeed}); also usable directly (e.g. the standalone
 * deployable's own boot). Purely additive — re-running is a no-op via {@link
 * ArtifactRepositoryService#ensure}.
 */
@ApplicationScoped
public class ArtifactsRepositorySeeder {

  public static final String CI_SCREENSHOTS = "ci-screenshots";
  public static final String CI_VIDEOS = "ci-videos";

  @Inject ArtifactRepositoryService repositoryService;

  @ActivateRequestContext
  public void ensureDefaults() {
    repositoryService.ensure(CI_SCREENSHOTS, RepositoryType.CI_SCREENSHOTS);
    repositoryService.ensure(CI_VIDEOS, RepositoryType.CI_VIDEOS);
  }
}
