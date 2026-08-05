package eu.wohlben.qits.artifacts.persistence;

import eu.wohlben.qits.artifacts.entity.NpmProxyPackument;
import eu.wohlben.qits.artifacts.entity.NpmProxyPackumentId;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Panache DAO for {@link NpmProxyPackument}, keyed by {@code (repository, packageName)}. */
@ApplicationScoped
public class NpmProxyPackumentRepository
    implements PanacheRepositoryBase<NpmProxyPackument, NpmProxyPackumentId> {

  public Optional<NpmProxyPackument> findOne(String repository, String packageName) {
    return findByIdOptional(new NpmProxyPackumentId(repository, packageName));
  }

  /**
   * {@code (packageName, fetchedAt, docLength)} for every document one proxy holds — the eviction
   * plan's enumeration.
   *
   * <p>A projection with {@code length()} in it rather than the entities, for {@link
   * #totalDocLength}'s reason at row granularity: the documents are CLOBs and a popular package's
   * is a megabyte, so listing rows to read a timestamp and a size would pull the whole cache
   * through the JVM. This is still a full scan — the table's only index is its primary key — which
   * is affordable exactly once per garbage collection run and would not be on a request path.
   */
  public List<Object[]> listCached(String repository) {
    return getEntityManager()
        .createQuery(
            "select p.packageName, p.fetchedAt, length(p.doc) from NpmProxyPackument p"
                + " where p.repository = :repository order by p.packageName",
            Object[].class)
        .setParameter("repository", repository)
        .getResultList();
  }

  /**
   * How much the cached documents cost, summed in the database rather than in the JVM.
   *
   * <p>It is the store's largest single number after the image layers, and the one no view of the
   * blob directory can see: the documents are CLOBs in H2, not files. {@code length()} counts
   * <b>characters</b>; a packument is ASCII JSON, so the two agree for every document this proxy has
   * ever cached, and summing in SQL is what keeps reading the figure from pulling hundreds of
   * megabytes of text across the wire.
   */
  public long totalDocLength(Collection<String> repositories) {
    if (repositories.isEmpty()) {
      return 0L;
    }
    Long total =
        getEntityManager()
            .createQuery(
                "select coalesce(sum(length(p.doc)), 0) from NpmProxyPackument p"
                    + " where p.repository in :repositories",
                Long.class)
            .setParameter("repositories", repositories)
            .getSingleResult();
    return total == null ? 0L : total;
  }
}
