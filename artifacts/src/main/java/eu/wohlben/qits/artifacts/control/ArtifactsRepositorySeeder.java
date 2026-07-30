package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.entity.RepositoryType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

/**
 * Idempotently ensures the default repository rows exist: the two CI types ({@code ci-screenshots},
 * {@code ci-videos}) and the platform image repository ({@code qits}, type {@code oci-images}).
 * Invoked by the service-side startup gate ({@code ArtifactsStartupSeed}); also usable directly
 * (e.g. the standalone deployable's own boot). Purely additive — re-running is a no-op via {@link
 * ArtifactRepositoryService#ensure}.
 */
@ApplicationScoped
public class ArtifactsRepositorySeeder {

  public static final String CI_SCREENSHOTS = "ci-screenshots";
  public static final String CI_VIDEOS = "ci-videos";

  /**
   * The platform's own image repository. The registry creates nothing implicitly, and the
   * platform-wide publish convention pushes every image under {@code qits/<application>:<sha>} — so
   * without this row a fresh deployment answers the first {@code docker push} with {@code 404
   * NAME_UNKNOWN} until an operator runs the ensure endpoint by hand. Seeding it is what makes a
   * green pipeline able to publish with zero manual steps; the name matches the default of the
   * shared {@code qits.artifacts.image-repository} key that qits-ci and qits-cd ship.
   */
  public static final String IMAGES = "qits";

  @Inject ArtifactRepositoryService repositoryService;

  @ActivateRequestContext
  public void ensureDefaults() {
    repositoryService.ensure(CI_SCREENSHOTS, RepositoryType.CI_SCREENSHOTS);
    repositoryService.ensure(CI_VIDEOS, RepositoryType.CI_VIDEOS);
    repositoryService.ensure(IMAGES, RepositoryType.OCI_IMAGES);
  }
}
