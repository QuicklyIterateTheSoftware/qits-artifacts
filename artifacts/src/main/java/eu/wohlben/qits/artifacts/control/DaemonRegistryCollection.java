package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.blobstore.control.BlobReclaim;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The daemon registry's collection door, opened exactly wide enough for the {@code gc} module and no
 * wider — the fourth of {@link BlobReclaim}, {@link OciRegistryCollection} and {@link
 * NpmRegistryCollection}.
 *
 * <p><b>A narrow facade rather than a widened method.</b> {@code DaemonRegistryService.collect}
 * stays package-private because it is the only way a {@code daemon_binary} row ever leaves this
 * service, and there is no client-facing delete on {@code /artifacts/daemons} for it to become one.
 * A {@code public} {@code collect} would put the removal of a platform executable within reach of
 * every route in the application to serve one module. This delegates to it instead.
 *
 * <p><b>One door, not two.</b> npm needs a pair because its two types owe their consumers opposite
 * things — a published version's name is spent forever, a cached one is re-fetchable. Every
 * {@code daemon_binaries} row is the platform's own release, so there is one meaning and no
 * tombstone; the reasoning is on {@code DaemonRegistryService.collect}.
 *
 * <p><b>The owner is {@code DaemonBinariesGcAdapter.delete} and nothing else.</b> Which versions die
 * is the own engine's rule; this only knows how a row is removed.
 */
@ApplicationScoped
public class DaemonRegistryCollection {

  @Inject DaemonRegistryService daemons;

  /**
   * Deletes one published daemon version. See {@code DaemonRegistryService.collect}.
   *
   * @throws IllegalStateException no such row — the store moved since the plan was computed
   */
  public void collect(String repository, String name, String version) {
    daemons.collect(repository, name, version);
  }
}
