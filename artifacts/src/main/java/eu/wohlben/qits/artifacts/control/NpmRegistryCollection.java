package eu.wohlben.qits.artifacts.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The npm registry's collection door, opened exactly wide enough for the {@code gc} module and no
 * wider — the twin of {@link BlobReclaim} and {@link OciRegistryCollection}.
 *
 * <p><b>A narrow facade rather than a widened method.</b> {@code NpmRegistryService.collect} stays
 * package-private because both of its guarantees hold only while there is one way in: the version
 * row and its republish tombstone move in <em>one</em> transaction, and a version a dist-tag still
 * names is refused. A {@code public} {@code collect} would put an unpublish within reach of every
 * route in the application — which is exactly what the registry's {@code 405} refuses — to serve
 * one module. This delegates to it instead.
 *
 * <p><b>Two doors, because the two npm types owe their consumers opposite things.</b> {@link
 * #collect} removes a <em>published</em> version and writes the tombstone that spends its name
 * forever; {@link #evictProxiedVersion} and {@link #evictProxiedPackument} remove <em>cached</em>
 * rows and deliberately write none, because re-fetching the same version from upstream is what a
 * proxy is for. Which one applies is decided by the repository's type inside the service, not by the
 * caller — one table holds both, so a coordinate is all that separates them.
 *
 * <p><b>The owners are {@code NpmPackagesGcStrategy.apply} and {@code NpmProxyGcAdapter.delete},
 * and nothing else.</b> Which versions die is the strategy's rule; this only knows how a row is
 * removed and what has to travel with it.
 */
@ApplicationScoped
public class NpmRegistryCollection {

  @Inject NpmRegistryService npm;

  /**
   * Deletes one published version and writes its tombstone in the same transaction. See {@code
   * NpmRegistryService.collect}.
   *
   * @throws eu.wohlben.qits.artifacts.error.NpmException {@code 409} if a dist-tag still names the
   *     version, {@code 404} if there is no such row
   */
  public void collect(String repository, String packageName, String version) {
    npm.collect(repository, packageName, version);
  }

  /**
   * Evicts one cached version of a proxy repository, writing <b>no</b> tombstone. See {@code
   * NpmRegistryService.evictProxiedVersion} for why that is the point and how it is kept safe.
   *
   * @throws eu.wohlben.qits.artifacts.error.NpmException {@code 404} if there is no such row,
   *     {@code 409} if the repository is not an {@code npm-proxy}
   */
  public void evictProxiedVersion(String repository, String packageName, String version) {
    npm.evictProxiedVersion(repository, packageName, version);
  }

  /**
   * Evicts one cached packument document. See {@code NpmRegistryService.evictProxiedPackument}.
   *
   * @throws eu.wohlben.qits.artifacts.error.NpmException {@code 404} if nothing is cached, {@code
   *     409} if the repository is not an {@code npm-proxy}
   */
  public void evictProxiedPackument(String repository, String packageName) {
    npm.evictProxiedPackument(repository, packageName);
  }
}
