package eu.wohlben.qits.artifacts.persistence;

import eu.wohlben.qits.artifacts.entity.MavenProxyMetadata;
import eu.wohlben.qits.artifacts.entity.MavenProxyMetadataId;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Panache DAO for {@link MavenProxyMetadata}, keyed by {@code (repository, path)}. */
@ApplicationScoped
public class MavenProxyMetadataRepository
    implements PanacheRepositoryBase<MavenProxyMetadata, MavenProxyMetadataId> {

  public Optional<MavenProxyMetadata> findOne(String repository, String path) {
    return findByIdOptional(new MavenProxyMetadataId(repository, path));
  }

  /**
   * {@code (path, fetchedAt)} for every document one proxy holds — the eviction plan's enumeration.
   *
   * <p>A projection rather than the entities, for {@code NpmProxyPackumentRepository.listCached}'s
   * reason: the documents are CLOBs, so listing rows to read a timestamp would pull the whole cache
   * through the JVM. A full scan, affordable exactly once per garbage collection run and never on a
   * request path.
   */
  public List<Object[]> listCached(String repository) {
    return getEntityManager()
        .createQuery(
            "select m.path, m.fetchedAt from MavenProxyMetadata m"
                + " where m.repository = :repository order by m.path",
            Object[].class)
        .setParameter("repository", repository)
        .getResultList();
  }

  /**
   * How much the cached documents cost, summed in the database rather than in the JVM — the figure
   * the type's GC note prints so a reader knows what evicting one does and does not free.
   */
  public long totalDocLength(Collection<String> repositories) {
    if (repositories.isEmpty()) {
      return 0L;
    }
    Long total =
        getEntityManager()
            .createQuery(
                "select coalesce(sum(length(m.doc)), 0) from MavenProxyMetadata m"
                    + " where m.repository in :repositories",
                Long.class)
            .setParameter("repositories", repositories)
            .getSingleResult();
    return total == null ? 0L : total;
  }
}
