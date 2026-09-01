package eu.wohlben.qits.artifacts.persistence;

import eu.wohlben.qits.artifacts.entity.SbomDocument;
import eu.wohlben.qits.artifacts.entity.SbomDocumentId;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Panache DAO for {@link SbomDocument}, keyed by {@code (repository, packageType, packageName,
 * version)}.
 */
@ApplicationScoped
public class SbomDocumentRepository
    implements PanacheRepositoryBase<SbomDocument, SbomDocumentId> {

  public Optional<SbomDocument> findOne(
      String repository, String packageType, String packageName, String version) {
    return findByIdOptional(new SbomDocumentId(repository, packageType, packageName, version));
  }

  /** Every stored document of one package, newest first — the listing a consumer reads. */
  public List<SbomDocument> listVersions(String repository, String packageType, String packageName) {
    return list(
        "repository = ?1 and packageType = ?2 and packageName = ?3 order by createdAt desc",
        repository,
        packageType,
        packageName);
  }

  /**
   * Moves {@code accessed_at} onto one stored document, but only if the stored value is older than
   * {@code cutoff} — the coalescing, expressed as a predicate rather than as a read-then-write.
   */
  public long touch(
      String repository,
      String packageType,
      String packageName,
      String version,
      Instant cutoff,
      Instant now) {
    return update(
        "accessedAt = ?1 where repository = ?2 and packageType = ?3 and packageName = ?4"
            + " and version = ?5 and (accessedAt is null or accessedAt <= ?6)",
        now, repository, packageType, packageName, version, cutoff);
  }

  /** The stored documents of one repository — the explorer's one count for this type. */
  public long countByRepository(String repository) {
    return count("repository = ?1", repository);
  }

  /**
   * The distinct blobs a repository references, with their sizes — the SBOM half of a size union.
   *
   * <p>{@code (blobId, sizeBytes)} pairs, sized from the row rather than from disk, exactly as
   * {@code daemon_binary}'s are: the size was free at stage time, so neither the census nor the
   * explorer needs a disk read here.
   */
  public List<Object[]> listDistinctBlobs(String repository) {
    return getEntityManager()
        .createQuery(
            "select distinct s.blobId, s.sizeBytes from SbomDocument s"
                + " where s.repository = :repository",
            Object[].class)
        .setParameter("repository", repository)
        .getResultList();
  }
}
