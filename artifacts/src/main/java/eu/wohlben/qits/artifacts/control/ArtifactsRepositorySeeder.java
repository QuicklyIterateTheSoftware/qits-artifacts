package eu.wohlben.qits.artifacts.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

/**
 * Idempotently ensures the default repository rows exist: the two CI types ({@code ci-screenshots},
 * {@code ci-videos}), the platform image repository ({@code qits}, type {@code oci-images}), the
 * hosted npm root ({@code npm}), the hosted maven root ({@code maven}), the daemon root ({@code
 * daemons}) and the docs root ({@code docs}). Invoked by the service-side startup gate ({@code
 * ArtifactsStartupSeed}); also usable directly.
 *
 * <p><b>Hosted types only.</b> The pull-through caches — {@code npmjs}, {@code central} and the
 * three OCI mirror namespaces with their upstream rows — went to qits-platform-mirror with the code
 * that serves them (byte-plane-split-plan.md phase 4). They are not merely unseeded here but
 * unwritable: the cache profiles that ride in on the qits-registries jars are excluded from bean
 * discovery, so an attempt to ensure one is a 400 naming the types that ARE registered.
 *
 * <p>Purely additive — re-running is a no-op via {@link ArtifactRepositoryService#ensure}, which
 * also makes a repository's type immutable, so a name that somehow arrived as the wrong type is an
 * error rather than a silent conversion.
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
   * shared {@code qits.artifacts.image-repository} key that qits-ci and qits-platform-deployments
   * ship.
   */
  public static final String IMAGES = "qits";

  /**
   * The hosted npm registry, at {@code /artifacts/npm/npm/}. Same argument as {@link #IMAGES}: a
   * pipeline's {@code npm publish} step names this root by env alone, so the one namespace nobody
   * chooses must not also be a manual step. Third-party packages come from qits-platform-mirror's
   * {@code npmjs} root, which the client's scoped-registry config points at.
   */
  public static final String NPM = "npm";

  /**
   * The hosted maven repository, at {@code /artifacts/maven/maven/}. Same argument as {@link #NPM}:
   * a pipeline's {@code mvn deploy} names this root by its {@code distributionManagement} url alone.
   */
  public static final String MAVEN = "maven";

  /**
   * The platform's daemon binaries, at {@code /artifacts/daemons/}. Same argument as {@link #NPM}
   * with one more edge: this is the namespace a <b>cold bootstrap</b> publishes into before the
   * platform has any CI to run a release pipeline with, so it must exist on the very first boot or
   * the first daemon upload 404s and the fresh platform has nothing to launch its builds with.
   */
  public static final String DAEMONS = "daemons";

  /**
   * The published documentation sites, at {@code /artifacts/docs/}. Same argument as {@link #NPM}: a
   * release pipeline's docs publish names this root from environment alone, and qits-docs, being
   * stateless, has no place of its own to fall back on if the row is missing.
   */
  public static final String DOCS = "docs";

  @Inject ArtifactRepositoryService repositoryService;

  @ActivateRequestContext
  public void ensureDefaults() {
    repositoryService.ensure(CI_SCREENSHOTS, CiScreenshotsProfile.KEY);
    repositoryService.ensure(CI_VIDEOS, CiVideosProfile.KEY);
    repositoryService.ensure(IMAGES, OciImagesProfile.KEY);
    repositoryService.ensure(NPM, NpmPackagesProfile.KEY);
    repositoryService.ensure(MAVEN, MavenPackagesProfile.KEY);
    repositoryService.ensure(DAEMONS, DaemonBinariesProfile.KEY);
    repositoryService.ensure(DOCS, DocsProfile.KEY);
  }
}
