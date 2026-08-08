package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.entity.RepositoryType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

/**
 * Idempotently ensures the default repository rows exist: the two CI types ({@code ci-screenshots},
 * {@code ci-videos}), the platform image repository ({@code qits}, type {@code oci-images}), the
 * two npm roots ({@code npm} hosted, {@code npmjs} proxy), the two maven roots ({@code maven}
 * hosted, {@code central} proxy),
 * the daemon root ({@code daemons}, type {@code daemon-binaries}), the docs root ({@code docs},
 * type {@code docs})
 * and the three OCI mirror namespaces ({@code hub}, {@code quay}, {@code redhat}, each paired with
 * its upstream row). Invoked by the
 * service-side startup gate
 * ({@code ArtifactsStartupSeed}); also usable directly (e.g. the standalone deployable's own boot).
 * Purely additive — re-running is a no-op via {@link ArtifactRepositoryService#ensure}.
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
   * shared {@code qits.artifacts.image-repository} key that qits-ci and qits-platform-deployments ship.
   */
  public static final String IMAGES = "qits";

  /**
   * The hosted npm registry, at {@code /artifacts/npm/npm/}. Same argument as {@link #IMAGES}: a
   * pipeline's {@code npm publish} step names this root by env alone, so the one namespace nobody
   * chooses must not also be a manual step.
   */
  public static final String NPM = "npm";

  /**
   * The pull-through cache of npmjs, at {@code /artifacts/npm/npmjs/}. Separate row and separate
   * type from {@link #NPM} on purpose — cached upstream content and published content never share a
   * namespace, and a {@code PUT} here is refused by type.
   */
  public static final String NPM_PROXY = "npmjs";

  /**
   * The hosted maven repository, at {@code /artifacts/maven/maven/}. Same argument as {@link #NPM}:
   * a pipeline's {@code mvn deploy} names this root by its {@code distributionManagement} url alone,
   * so the one namespace nobody chooses must not also be a manual step.
   */
  public static final String MAVEN = "maven";

  /**
   * The pull-through cache of Maven Central, at {@code /artifacts/maven/central/}. Separate row and
   * separate type from {@link #MAVEN} on purpose — cached upstream content and deployed content
   * never share a namespace, and a {@code PUT} here is refused by type. Named after what it fronts,
   * the way {@link #NPM_PROXY} is.
   */
  public static final String MAVEN_CENTRAL = "central";

  /**
   * The platform's daemon binaries, at {@code /artifacts/daemons/}. Same argument as {@link #NPM}
   * with one more edge: this is the namespace a <b>cold bootstrap</b> publishes into before the
   * platform has any CI to run a release pipeline with, so it must exist on the very first boot or
   * the first daemon upload 404s and the fresh platform has nothing to launch its builds with.
   */
  public static final String DAEMONS = "daemons";

  /**
   * The published documentation sites, at {@code /artifacts/docs/}. Same argument as {@link #NPM}: a
   * release pipeline's docs publish names this root from environment alone, so the one namespace
   * nobody chooses must not also be a manual step — and qits-docs, being stateless, has no place of
   * its own to fall back on if the row is missing.
   */
  public static final String DOCS = "docs";

  @Inject ArtifactRepositoryService repositoryService;

  @Inject OciMirrorUpstreams mirrorUpstreams;

  @ActivateRequestContext
  public void ensureDefaults() {
    repositoryService.ensure(CI_SCREENSHOTS, RepositoryType.CI_SCREENSHOTS);
    repositoryService.ensure(CI_VIDEOS, RepositoryType.CI_VIDEOS);
    repositoryService.ensure(IMAGES, RepositoryType.OCI_IMAGES);
    repositoryService.ensure(NPM, RepositoryType.NPM_PACKAGES);
    repositoryService.ensure(NPM_PROXY, RepositoryType.NPM_PROXY);
    repositoryService.ensure(MAVEN, RepositoryType.MAVEN_PACKAGES);
    repositoryService.ensure(MAVEN_CENTRAL, RepositoryType.MAVEN_PROXY);
    repositoryService.ensure(DAEMONS, RepositoryType.DAEMON_BINARIES);
    repositoryService.ensure(DOCS, RepositoryType.DOCS);
    // The mirror namespaces (hub, quay, redhat) and the upstream row each of them fronts. Written
    // as a PAIR by OciMirrorUpstreams, which is why they are not five more lines above: a
    // repository row with no upstream is a namespace nothing can be fetched into, and an upstream
    // row with no repository is a namespace nothing resolves to. The migration prefills both; this
    // re-ensures them, so a deployment that lost one gets it back on the next boot.
    mirrorUpstreams.ensureDefaults();
  }
}
