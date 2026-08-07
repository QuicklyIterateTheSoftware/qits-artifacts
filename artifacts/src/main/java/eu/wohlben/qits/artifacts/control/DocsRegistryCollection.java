package eu.wohlben.qits.artifacts.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The docs registry's collection door, opened exactly wide enough for the {@code gc} module and no
 * wider — the fifth of {@link BlobReclaim}, {@link OciRegistryCollection}, {@link
 * NpmRegistryCollection} and {@link DaemonRegistryCollection}.
 *
 * <p><b>A narrow facade rather than a widened method.</b> {@code DocsRegistryService.collect} stays
 * package-private because it is the only way a {@code docs_site} row ever leaves this service, and
 * there is no client-facing delete on {@code /artifacts/docs} for it to become one. A {@code public}
 * {@code collect} would put the removal of a published documentation version — and, through the
 * cascade, every file in it — within reach of every route in the application to serve one module.
 *
 * <p><b>One door, and one coordinate: the version.</b> There is deliberately no per-file collect
 * here and no method that takes a path. The unit of eviction is the site row, the schema cascades
 * its files, and offering anything narrower would be offering a way to leave a version serving 404s
 * for its own assets.
 *
 * <p><b>The owner is {@code DocsGcAdapter.delete} and nothing else.</b> Which versions die is the own
 * engine's rule; this only knows how a version is removed.
 */
@ApplicationScoped
public class DocsRegistryCollection {

  @Inject DocsRegistryService docs;

  /**
   * Deletes one published docs version, and with it every file row it owns. See {@code
   * DocsRegistryService.collect}.
   *
   * @throws IllegalStateException no such row — the store moved since the plan was computed
   */
  public void collect(String repository, String name, String version) {
    docs.collect(repository, name, version);
  }
}
