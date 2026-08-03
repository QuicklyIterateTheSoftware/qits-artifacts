package eu.wohlben.qits.artifacts.persistence;

import eu.wohlben.qits.artifacts.entity.DaemonBinary;
import eu.wohlben.qits.artifacts.entity.DaemonBinaryId;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
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

  /** The published binaries of one repository — the daemon meaning of the explorer's one count. */
  public long countByRepository(String repository) {
    return count("repository = ?1", repository);
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
}
