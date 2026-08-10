package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.persistence.DocsSiteRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;

/**
 * The docs half of access tracking, coalesced on {@link ArtifactAccessTracker#WRITE_WINDOW} — {@link
 * DaemonAccessTracker}'s twin, and carved out for the same reason.
 *
 * <p>There is no per-file twin and no {@code docs_file} column to write: the site is what ages out,
 * so the site is what records being wanted. It also makes the coalescing matter more than anywhere
 * else — one page load is fifty requests against one row, and the one-hour window turns that into a
 * single update.
 */
@ApplicationScoped
public class DocsAccessTracker {

  @Inject DocsSiteRepository docsSites;

  /** One published docs version, reached by any file in it. */
  @Transactional
  public void touchDocsSite(String repository, String name, String version, Instant now) {
    docsSites.touch(repository, name, version, ArtifactAccessTracker.cutoff(now), now);
  }
}
