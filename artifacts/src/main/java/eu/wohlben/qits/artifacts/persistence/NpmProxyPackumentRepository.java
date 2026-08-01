package eu.wohlben.qits.artifacts.persistence;

import eu.wohlben.qits.artifacts.entity.NpmProxyPackument;
import eu.wohlben.qits.artifacts.entity.NpmProxyPackumentId;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.Optional;

/** Panache DAO for {@link NpmProxyPackument}, keyed by {@code (repository, packageName)}. */
@ApplicationScoped
public class NpmProxyPackumentRepository
    implements PanacheRepositoryBase<NpmProxyPackument, NpmProxyPackumentId> {

  public Optional<NpmProxyPackument> findOne(String repository, String packageName) {
    return findByIdOptional(new NpmProxyPackumentId(repository, packageName));
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
