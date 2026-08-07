package eu.wohlben.qits.artifacts.persistence;

import eu.wohlben.qits.artifacts.entity.DocsSite;
import eu.wohlben.qits.artifacts.entity.DocsSiteId;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Panache DAO for {@link DocsSite}, keyed by {@code (repository, name, version)}. */
@ApplicationScoped
public class DocsSiteRepository implements PanacheRepositoryBase<DocsSite, DocsSiteId> {

  public Optional<DocsSite> findOne(String repository, String name, String version) {
    return findByIdOptional(new DocsSiteId(repository, name, version));
  }

  /** Every version of one site, newest first — what a version list and a `latest` resolve read. */
  public List<DocsSite> listVersions(String repository, String name) {
    return list("repository = ?1 and name = ?2 order by publishedAt desc", repository, name);
  }

  /** Every site in one repository, by name — the catalog qits-platform-docs lists. */
  public List<String> listNames(String repository) {
    return getEntityManager()
        .createQuery(
            "select distinct s.name from DocsSite s where s.repository = :repository"
                + " order by s.name",
            String.class)
        .setParameter("repository", repository)
        .getResultList();
  }

  /**
   * Every published version of every site in one repository, by site name then newest first — one
   * query behind the whole catalog.
   *
   * <p>Rows rather than a {@code group by}, and that is a size judgement rather than a shortcut: a
   * docs store holds sites times a handful of retained versions, so the whole table is small and
   * grouping it in Java costs nothing — while a {@code group by} would answer counts and a maximum
   * timestamp but not the newest version's <em>name</em>, which is the one thing the catalog exists
   * to show. Revisit if a store ever holds thousands of sites.
   */
  public List<DocsSite> listAllByRepository(String repository) {
    return list("repository = ?1 order by name, publishedAt desc", repository);
  }

  /**
   * Moves {@code accessed_at} onto one published version, but only if the stored value is older than
   * {@code cutoff} — the coalescing, expressed as a predicate rather than as a read-then-write.
   */
  public long touch(
      String repository, String name, String version, Instant cutoff, Instant now) {
    return update(
        "accessedAt = ?1 where repository = ?2 and name = ?3 and version = ?4"
            + " and (accessedAt is null or accessedAt <= ?5)",
        now, repository, name, version, cutoff);
  }

  /** The published versions of one repository — the docs meaning of the explorer's one count. */
  public long countByRepository(String repository) {
    return count("repository = ?1", repository);
  }
}
