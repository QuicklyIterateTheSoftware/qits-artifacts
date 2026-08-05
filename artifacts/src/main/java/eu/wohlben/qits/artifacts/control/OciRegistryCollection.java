package eu.wohlben.qits.artifacts.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The OCI registry's collection door, opened exactly wide enough for the {@code gc} module and no
 * wider — the twin of {@link BlobReclaim} and {@link NpmRegistryCollection}.
 *
 * <p><b>A narrow facade rather than two widened methods.</b> {@code
 * OciRegistryService.collectTag}/{@code collectManifest} stay package-private because their
 * guarantees are the mechanism's rather than the policy's: they are the only way a tag or manifest
 * row ever leaves this registry, each runs in its own transaction, and {@code collectManifest}
 * refuses a manifest a tag still names. Making them {@code public} would put a row deletion within
 * reach of every route in the application to serve one module; this delegates to them instead.
 *
 * <p><b>The owner is {@code OciImageGcStrategy.apply} and nothing else.</b> Which identities die is
 * the strategy's rule; this only knows how a row is removed. The {@code 405} on {@code /v2} client
 * deletes stays exactly as it is.
 */
@ApplicationScoped
public class OciRegistryCollection {

  @Inject OciRegistryService registry;

  /**
   * Deletes one tag row. See {@code OciRegistryService.collectTag}.
   *
   * @throws IllegalStateException no such tag row — the store moved since the plan was computed
   */
  public void collectTag(String repository, String imageName, String tag) {
    registry.collectTag(repository, imageName, tag);
  }

  /**
   * Deletes one manifest row, refusing one a tag still names. See {@code
   * OciRegistryService.collectManifest}.
   *
   * @throws IllegalStateException no such manifest row, or a tag still names it
   */
  public void collectManifest(String repository, String imageName, String digest) {
    registry.collectManifest(repository, imageName, digest);
  }
}
