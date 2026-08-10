package eu.wohlben.qits.artifacts.persistence;

import eu.wohlben.qits.artifacts.entity.NpmVersion;
import eu.wohlben.qits.artifacts.entity.NpmVersionId;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Panache DAO for {@link NpmVersion}, keyed by {@code (repository, packageName, version)}. */
@ApplicationScoped
public class NpmVersionRepository implements PanacheRepositoryBase<NpmVersion, NpmVersionId> {

  public Optional<NpmVersion> findOne(String repository, String packageName, String version) {
    return findByIdOptional(new NpmVersionId(repository, packageName, version));
  }

  /**
   * Every version of one package, which is the whole of what packument assembly reads. Ordered by
   * version string rather than by semver: a packument's {@code versions} is a JSON object and its
   * order carries no meaning to any client, but a stable order makes the document reproducible.
   */
  public List<NpmVersion> listVersions(String repository, String packageName) {
    return find(
            "repository = ?1 and packageName = ?2",
            Sort.ascending("version"),
            repository,
            packageName)
        .list();
  }

  /**
   * The packages a repository holds, lexically — the leading-column scan of {@code (repository,
   * package_name)}.
   *
   * <p>This is the enumeration for a <b>proxy</b> repository too, and it is deliberately not {@code
   * npm_proxy_packument}: that table's only index is its primary key, so listing it is a full scan
   * of CLOBs. The cost is that a package whose packument was cached but whose tarball was never
   * pulled has no row here and is missing from the list — a cached document with no cached bytes,
   * which is the honest thing for a store view to omit.
   */
  public List<String> listPackageNames(String repository) {
    return getEntityManager()
        .createQuery(
            "select distinct v.packageName from NpmVersion v where v.repository = :repository"
                + " order by v.packageName",
            String.class)
        .setParameter("repository", repository)
        .getResultList();
  }

  public long countPackages(String repository) {
    return getEntityManager()
        .createQuery(
            "select count(distinct v.packageName) from NpmVersion v"
                + " where v.repository = :repository",
            Long.class)
        .setParameter("repository", repository)
        .getSingleResult();
  }

  public long countVersions(String repository, String packageName) {
    return count("repository = ?1 and packageName = ?2", repository, packageName);
  }

  /**
   * {@code (version, tarballBlobId, createdAt, accessedAt)} for one package.
   *
   * <p>A projection rather than the entities, and that is the point: {@code manifest_json} is a
   * {@code @Lob}, so listing rows to read four short columns would drag every version's whole
   * manifest through the JVM.
   */
  public List<Object[]> listVersionRows(String repository, String packageName) {
    return getEntityManager()
        .createQuery(
            "select v.version, v.tarballBlobId, v.createdAt, v.accessedAt from NpmVersion v"
                + " where v.repository = :repository and v.packageName = :packageName"
                + " order by v.version",
            Object[].class)
        .setParameter("repository", repository)
        .setParameter("packageName", packageName)
        .getResultList();
  }

  /**
   * Moves {@code accessed_at} onto one version, but only if the stored value is older than {@code
   * cutoff} — the coalescing, expressed as a predicate rather than as a read-then-write.
   *
   * <p>A bulk update for {@link NpmVersionRepository}'s own reason as much as for the write budget:
   * {@code manifest_json} is a {@code @Lob}, so loading the row to move eight bytes would drag a
   * whole version manifest through the JVM on the hottest read path npm has.
   */
  public long touch(
      String repository, String packageName, String version, Instant cutoff, Instant now) {
    return update(
        "accessedAt = ?1 where repository = ?2 and packageName = ?3 and version = ?4"
            + " and (accessedAt is null or accessedAt <= ?5)",
        now, repository, packageName, version, cutoff);
  }

  /** The distinct tarball blobs a set of repositories references — the npm half of a size union. */
  public List<String> listTarballBlobIds(Collection<String> repositories) {
    if (repositories.isEmpty()) {
      return List.of();
    }
    return getEntityManager()
        .createQuery(
            "select distinct v.tarballBlobId from NpmVersion v"
                + " where v.repository in :repositories",
            String.class)
        .setParameter("repositories", repositories)
        .getResultList();
  }
}
