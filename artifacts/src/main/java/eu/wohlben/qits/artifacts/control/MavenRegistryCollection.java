package eu.wohlben.qits.artifacts.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The maven repository's collection door, opened exactly wide enough for the {@code gc} module and
 * no wider — the fifth of {@link BlobReclaim}, {@link OciRegistryCollection}, {@link
 * NpmRegistryCollection} and {@link DaemonRegistryCollection}.
 *
 * <p><b>A narrow facade rather than a widened method.</b> {@code MavenRegistryService.collect} stays
 * package-private because it is the only way a {@code maven_artifact} row ever leaves this service,
 * and there is no client-facing {@code DELETE} on {@code /artifacts/maven} for it to become one. A
 * {@code public} {@code collect} would put the removal of a published jar within reach of every
 * route in the application to serve one module. This delegates to it instead.
 *
 * <p><b>No tombstone, for maven's own reason.</b> npm needs one because a deleted version re-opens
 * its name for a publish with different bytes; a maven release path that has been collected is a
 * coordinate the repository no longer serves at all, and a re-deploy at that path is a fresh deploy
 * of the same release rather than a mutation of a live one. The immutability check is a row lookup
 * either way.
 *
 * <p><b>The owner is {@code MavenPackagesGcAdapter.delete} and nothing else.</b> Which coordinates
 * die is the own engine's rule; this only knows how a row is removed.
 */
@ApplicationScoped
public class MavenRegistryCollection {

  @Inject MavenRegistryService maven;

  /**
   * Deletes one deployed file. The caller removes a whole coordinate's set — see {@code
   * MavenRegistryService.collect} for why the split is where it is.
   *
   * @throws IllegalStateException no such row — the store moved since the plan was computed
   */
  public void collect(String repository, String path) {
    maven.collect(repository, path);
  }
}
