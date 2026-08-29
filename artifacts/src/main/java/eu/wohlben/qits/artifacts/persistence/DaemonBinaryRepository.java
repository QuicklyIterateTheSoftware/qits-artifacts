package eu.wohlben.qits.artifacts.persistence;

import eu.wohlben.qits.artifacts.entity.DaemonBinary;
import eu.wohlben.qits.artifacts.entity.DaemonBinaryId;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Panache DAO for {@link DaemonBinary}, keyed by {@code (repository, name, version)}. */
@ApplicationScoped
public class DaemonBinaryRepository
    implements PanacheRepositoryBase<DaemonBinary, DaemonBinaryId> {

  public Optional<DaemonBinary> findOne(String repository, String name, String version) {
    return findByIdOptional(new DaemonBinaryId(repository, name, version));
  }

  /** Every version of one daemon, newest first — the listing a release surface reads. */
  public List<DaemonBinary> listVersions(String repository, String name) {
    return list("repository = ?1 and name = ?2 order by publishedAt desc", repository, name);
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

  /** The published binaries of one repository — the daemon meaning of the explorer's one count. */
  public long countByRepository(String repository) {
    return count("repository = ?1", repository);
  }

  /**
   * Every daemon this repository holds, by name — the explorer's enumeration.
   *
   * <p>A distinct scan rather than a table of its own, because there is no daemon table: a daemon
   * exists exactly as long as a row names it, the same way an image exists as long as a manifest
   * names it ({@code OciManifestRepository.listImageNames}). Ordered by name so the listing is
   * stable across calls without the caller sorting it.
   */
  public List<String> listNames(String repository) {
    return getEntityManager()
        .createQuery(
            "select distinct d.name from DaemonBinary d where d.repository = :repository"
                + " order by d.name",
            String.class)
        .setParameter("repository", repository)
        .getResultList();
  }

  /**
   * The distinct blobs a repository references, with their sizes — the daemon half of a size union.
   *
   * <p>{@code (blobId, sizeBytes)} pairs, sized from the row rather than from disk, exactly as
   * {@code maven_artifact} is: the size was free at stage time, so neither the census nor the
   * explorer needs a disk read or a nullable figure here.
   */
  public List<Object[]> listDistinctBlobs(String repository) {
    return getEntityManager()
        .createQuery(
            "select distinct d.blobId, d.sizeBytes from DaemonBinary d"
                + " where d.repository = :repository",
            Object[].class)
        .setParameter("repository", repository)
        .getResultList();
  }

  /**
   * The same union narrowed to one daemon — what a daemon's own size is.
   *
   * <p>Narrowed in the query rather than filtered in Java, because {@code distinct} has to run over
   * the narrowed set: two versions of <em>this</em> daemon built from identical bytes are one blob,
   * and a version of a <em>different</em> daemon naming those same bytes must not be added here at
   * all. Summing the per-daemon answers therefore over-counts against {@link
   * #listDistinctBlobs(String)}, which is the honest arithmetic and not a bug — the same
   * relationship a per-image union has to the store-wide one.
   */
  public List<Object[]> listDistinctBlobs(String repository, String name) {
    return getEntityManager()
        .createQuery(
            "select distinct d.blobId, d.sizeBytes from DaemonBinary d"
                + " where d.repository = :repository and d.name = :name",
            Object[].class)
        .setParameter("repository", repository)
        .setParameter("name", name)
        .getResultList();
  }
}
