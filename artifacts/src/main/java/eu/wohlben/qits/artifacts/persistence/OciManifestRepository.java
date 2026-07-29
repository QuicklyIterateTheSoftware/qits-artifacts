package eu.wohlben.qits.artifacts.persistence;

import eu.wohlben.qits.artifacts.entity.OciManifest;
import eu.wohlben.qits.artifacts.entity.OciManifestId;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

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
}
