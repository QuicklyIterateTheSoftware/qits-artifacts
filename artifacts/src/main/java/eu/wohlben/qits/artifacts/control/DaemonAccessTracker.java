package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.persistence.DaemonBinaryRepository;
import eu.wohlben.qits.blobstore.control.ArtifactAccessTracker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;

/**
 * The daemon half of access tracking, coalesced on {@link ArtifactAccessTracker#WRITE_WINDOW}.
 *
 * <p>One class per format, the shape the carve-out settled: the store's tracker injected every
 * format's tables, which is a dependency qits-blobstore cannot have. npm, maven and OCI took theirs
 * to qits-registries; this type is nobody's but this service's, so its half is here. The window and
 * its cutoff still come from the store, so every type coalesces identically.
 *
 * <p>There is no digest-addressed twin on purpose: that download is the {@code /v2} blob route,
 * which resolves an OCI repository and a globally deduplicated digest and therefore carries no
 * daemon identity — the same reason layer reads stay unattributed.
 */
@ApplicationScoped
public class DaemonAccessTracker {

  @Inject DaemonBinaryRepository daemonBinaries;

  /** One published daemon version, reached by the version-addressed route. */
  @Transactional
  public void touchDaemonBinary(String repository, String name, String version, Instant now) {
    daemonBinaries.touch(repository, name, version, ArtifactAccessTracker.cutoff(now), now);
  }
}
