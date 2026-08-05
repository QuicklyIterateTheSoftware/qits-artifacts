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
 * <p><b>The owner is {@code NpmPackagesGcStrategy.apply} and nothing else.</b> Which versions die
 * is the strategy's rule; this only knows how a row is removed and what has to travel with it.
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
}
