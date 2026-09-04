package eu.wohlben.qits.artifacts.gc;

import java.util.List;

/**
 * Which container images the platform is <b>configured</b> to launch — the image-version entries
 * qits-configuration holds, resolved against their current values.
 *
 * <p><b>This is the pull nobody has made yet, and it is not the deployer's to answer.</b>
 * qits-platform-deployments names the images of containers that exist; these are the ones a service
 * will ask the registry for the next time it starts something — a workspace container, a project
 * agent, a refinement run. The version lives in a configuration entry rather than in a deployment,
 * so no deployment row names it and nothing in this store's timestamps implies it: a workspace image
 * released weeks ago and started by nobody since is cold by every clock here and is exactly what the
 * next {@code /workspaces} click pulls.
 *
 * <p>An entry with no stored value is simply absent from the answer — never released means nothing
 * to keep — and an empty list is therefore a real answer rather than a doubt. What is <b>not</b> an
 * answer is an unreachable service: it throws, {@link GcPinSources} records the failure, and the run
 * deletes nothing, exactly as {@link CdDeploymentPins} does.
 */
@FunctionalInterface
public interface ConfigurationImagePins {

  /**
   * One configured image version.
   *
   * @param image the full image name as the registry spells it, e.g. {@code qits/workspace}
   * @param version the configured version — the tag a launch would pull
   * @param application which application's configuration holds it, provenance for the receipt
   * @param key the configuration key it was read from, provenance for the same reason
   */
  record ImagePin(String image, String version, String application, String key) {}

  /**
   * Every configured image version the platform currently holds.
   *
   * @throws RuntimeException qits-configuration could not be reached or could not be parsed — never
   *     an empty list on doubt, because an empty list reads as "nothing is configured" and condemns
   *     the image the next launch pulls
   */
  List<ImagePin> pins();

  /** Where this implementation reads them from, for the report's pins section. */
  default String url() {
    return "(not reported by " + getClass().getSimpleName() + ")";
  }
}
