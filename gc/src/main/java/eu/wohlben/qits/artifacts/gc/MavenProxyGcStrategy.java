package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.entity.ArtifactRepository;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.artifacts.persistence.MavenProxyMetadataRepository;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;

/**
 * The maven proxy's rule: <b>everything unaccessed past the configured window is evicted</b> —
 * cached files and the cached {@code maven-metadata.xml} documents beside them.
 *
 * <p>The rule is {@link CacheEvictionStrategy}'s, the wiring {@link CacheGcStrategy}'s, and the
 * facts — two kinds of cached identity, their staleness, and eviction through the proxy's own doors
 * — are {@link MavenProxyGcAdapter}'s. A third binder that is this short is the settlement working.
 *
 * <p>Its window is <b>P90D</b> rather than the other two caches' P30D, and that is the same sentence
 * {@code maven-packages} carries: a library is resolved when something builds against it, and that
 * cadence is not a month. A dependency nothing has built against in a quarter is what this evicts;
 * one behind a release train that runs twice a year survives on its next build and is re-fetched if
 * it does not.
 *
 * <p><b>The note is an honesty line about H2</b>, the npm proxy's verbatim: evicting a cached
 * document frees no disk, because the documents are CLOBs inside the database file rather than blob
 * files. Without it a reviewer reads {@code reclaimableBytes} unchanged beside a list of condemned
 * documents and concludes the collector is broken.
 *
 * <p>{@code @Singleton} rather than {@code @ApplicationScoped}, for the report's sake: a
 * normal-scoped bean answers {@code getClass().getSimpleName()} through its client proxy.
 */
@Singleton
public class MavenProxyGcStrategy extends CacheGcStrategy {

  @Inject MavenProxyGcAdapter proxy;
  @Inject ArtifactRepositoryRepository repositories;
  @Inject MavenProxyMetadataRepository metadata;

  @Override
  GcTypeAdapter adapter() {
    return proxy;
  }

  /**
   * The H2 honesty line, with the figure as it stands when the report is produced. Computed fresh
   * rather than remembered, so it can never be a number from a run somebody else made.
   */
  @Override
  public String note() {
    return "cached maven-metadata.xml documents are H2 CLOBs, not files: "
        + metadata.totalDocLength(proxyRepositories())
        + " characters are cached as this report was produced. Evicting one reclaims 0 bytes on"
        + " disk — H2 reuses the freed pages inside its own file, which shrinks only when a"
        + " maintenance restart runs SHUTDOWN COMPACT (README, \"Reclaiming the H2 file after a"
        + " sweep\"). The reclaimable bytes on this line count cached files and nothing else.";
  }

  private List<String> proxyRepositories() {
    List<String> names = new ArrayList<>();
    for (ArtifactRepository repository : repositories.listAll()) {
      if (repository.type == RepositoryType.MAVEN_PROXY) {
        names.add(repository.name);
      }
    }
    return names;
  }
}
