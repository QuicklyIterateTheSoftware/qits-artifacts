package eu.wohlben.qits.artifacts.persistence;

import eu.wohlben.qits.artifacts.entity.MavenArtifact;
import eu.wohlben.qits.artifacts.entity.MavenArtifactId;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Panache DAO for {@link MavenArtifact}, keyed by {@code (repository, path)}. */
@ApplicationScoped
public class MavenArtifactRepository implements PanacheRepositoryBase<MavenArtifact, MavenArtifactId> {

  public Optional<MavenArtifact> findOne(String repository, String path) {
    return findByIdOptional(new MavenArtifactId(repository, path));
  }

  /** Every path a repository holds, lexically — the enumeration a GC report lists identities from. */
  public List<String> listPaths(String repository) {
    return getEntityManager()
        .createQuery(
            "select a.path from MavenArtifact a where a.repository = :repository order by a.path",
            String.class)
        .setParameter("repository", repository)
        .getResultList();
  }

  /**
   * Moves {@code accessed_at} onto one deployed path, but only if the stored value is older than
   * {@code cutoff} — the coalescing, expressed as a predicate rather than as a read-then-write.
   */
  public long touch(String repository, String path, Instant cutoff, Instant now) {
    return update(
        "accessedAt = ?1 where repository = ?2 and path = ?3"
            + " and (accessedAt is null or accessedAt <= ?4)",
        now, repository, path, cutoff);
  }

  /** The deployed files of one repository — the maven meaning of the explorer's one count. */
  public long countByRepository(String repository) {
    return count("repository = ?1", repository);
  }

  /**
   * The distinct blobs a repository references, with their sizes — the maven half of a size union.
   *
   * <p>{@code (blobId, sizeBytes)} pairs, sized from the row rather than from disk: {@code
   * maven_artifact} is the one protocol table whose size was free at stage time, so neither the
   * census nor the explorer needs a disk read or a nullable figure here.
   */
  public List<Object[]> listDistinctBlobs(String repository) {
    return getEntityManager()
        .createQuery(
            "select distinct a.blobId, a.sizeBytes from MavenArtifact a"
                + " where a.repository = :repository",
            Object[].class)
        .setParameter("repository", repository)
        .getResultList();
  }

  /**
   * {@code (path, createdAt)} for every row under a prefix — the whole of what the derived
   * {@code maven-metadata.xml} reads. A prefix scan on the primary key's leading columns.
   */
  public List<Object[]> listPathsAndCreatedAtStartingWith(String repository, String prefix) {
    return getEntityManager()
        .createQuery(
            "select a.path, a.createdAt from MavenArtifact a"
                + " where a.repository = :repository and a.path like :prefix",
            Object[].class)
        .setParameter("repository", repository)
        .setParameter("prefix", prefix + "/%")
        .getResultList();
  }
}
