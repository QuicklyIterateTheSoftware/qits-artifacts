package eu.wohlben.qits.artifacts.persistence;

import eu.wohlben.qits.artifacts.entity.OciManifest;
import eu.wohlben.qits.artifacts.entity.OciManifestId;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import java.time.Instant;

/** Panache DAO for {@link OciManifest}, keyed by {@code (repository, imageName, digest)}. */
@ApplicationScoped
public class OciManifestRepository
    implements PanacheRepositoryBase<OciManifest, OciManifestId> {

  public Optional<OciManifest> findOne(String repository, String imageName, String digest) {
    return findByIdOptional(new OciManifestId(repository, imageName, digest));
  }

  /**
   * Whether this {@code (repository, image)} has ever been told about this manifest. Deliberately
   * narrower than {@code BlobStore.exists}: the bytes may well be in the store because some other
   * repository pushed them, and serving those would leak across namespaces.
   */
  public boolean exists(String repository, String imageName, String digest) {
    return count(
            "repository = ?1 and imageName = ?2 and digest = ?3", repository, imageName, digest)
        > 0;
  }

  /**
   * The images a repository holds, lexically. There is no image table — an image exists exactly as
   * long as a manifest names it — so this distinct scan <em>is</em> the enumeration, and it rides
   * {@code idx_oci_manifest_image}.
   */
  public List<String> listImageNames(String repository) {
    return getEntityManager()
        .createQuery(
            "select distinct m.imageName from OciManifest m where m.repository = :repository"
                + " order by m.imageName",
            String.class)
        .setParameter("repository", repository)
        .getResultList();
  }

  public long countImages(String repository) {
    return getEntityManager()
        .createQuery(
            "select count(distinct m.imageName) from OciManifest m where m.repository = :repository",
            Long.class)
        .setParameter("repository", repository)
        .getSingleResult();
  }

  public List<OciManifest> listByImage(String repository, String imageName) {
    return find(
            "repository = ?1 and imageName = ?2",
            Sort.ascending("digest"),
            repository,
            imageName)
        .list();
  }

  public List<OciManifest> listByRepository(String repository) {
    return find("repository = ?1", Sort.ascending("imageName", "digest"), repository).list();
  }

  public long touch(
      String repository, String imageName, String digest, Instant cutoff, Instant now) {
    return update(
        "accessedAt = ?1 where repository = ?2 and imageName = ?3 and digest = ?4"
            + " and (accessedAt is null or accessedAt <= ?5)",
        now, repository, imageName, digest, cutoff);
  }
}
