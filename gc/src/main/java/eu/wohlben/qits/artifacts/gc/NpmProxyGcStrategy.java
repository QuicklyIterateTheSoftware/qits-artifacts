package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.entity.ArtifactRepository;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.artifacts.persistence.NpmProxyPackumentRepository;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;

/**
 * The npm proxy's rule, live: <b>everything unaccessed past the configured window is evicted</b> —
 * cached versions and the cached packuments beside them.
 *
 * <p>This type used to be <em>unclaimed</em>, and the planner's "no strategy registered for
 * npm-proxy" was the honest report of a decision nobody had taken (⚖1). The settlement took it:
 * npm-proxy is a {@code cache}, so the question dissolved into the same rule the mirror runs. The
 * report line changes from a decision's absence to a decision's outcome, which is the point of the
 * whole workstream.
 *
 * <p>The rule is {@link CacheEvictionStrategy}'s, the wiring {@link CacheGcStrategy}'s, and the
 * facts — two kinds of cached identity, their staleness, and eviction <b>without a tombstone</b> —
 * are {@link NpmProxyGcAdapter}'s.
 *
 * <p><b>The note is an honesty line about H2, and it belongs on every report.</b> Evicting a
 * packument frees no disk: the documents are CLOBs inside the database file, so their characters
 * leave the live set while the file stays the size it was. Only a maintenance restart running
 * {@code SHUTDOWN COMPACT} shrinks it (README, "Reclaiming the H2 file after a sweep"), and nothing
 * in this service runs that — a compaction closes the database, which is not something a GC route
 * may do to a running platform. Without this line a reviewer reads {@code reclaimableBytes: 0}
 * beside a hundred condemned packuments and concludes the collector is broken.
 *
 * <p>{@code @Singleton} rather than {@code @ApplicationScoped}, for the report's sake: a
 * normal-scoped bean answers {@code getClass().getSimpleName()} through its client proxy.
 */
@Singleton
public class NpmProxyGcStrategy extends CacheGcStrategy {

  @Inject NpmProxyGcAdapter proxy;
  @Inject ArtifactRepositoryRepository repositories;
  @Inject NpmProxyPackumentRepository packuments;

  @Override
  GcTypeAdapter adapter() {
    return proxy;
  }

  /**
   * The H2 honesty line, with the figure as it stands when the report is produced — on a plan, what
   * the cached documents cost right now; on a sweep receipt, what is left after the eviction, since
   * the receipt is written after the rows are gone. Computed fresh rather than remembered, so it can
   * never be a number from a run somebody else made.
   */
  @Override
  public String note() {
    return "cached packuments are H2 CLOBs, not files: "
        + packuments.totalDocLength(proxyRepositories())
        + " characters are cached as this report was produced. Evicting one removes its characters"
        + " from the database's live set and reclaims 0 bytes on disk — H2 reuses the freed pages"
        + " inside its own file, which shrinks only when a maintenance restart runs SHUTDOWN COMPACT"
        + " (README, \"Reclaiming the H2 file after a sweep\"). Nothing here runs it, so the"
        + " reclaimable bytes on this line count tarball blobs and nothing else.";
  }

  private List<String> proxyRepositories() {
    List<String> names = new ArrayList<>();
    for (ArtifactRepository repository : repositories.listAll()) {
      if (repository.type == RepositoryType.NPM_PROXY) {
        names.add(repository.name);
      }
    }
    return names;
  }
}
