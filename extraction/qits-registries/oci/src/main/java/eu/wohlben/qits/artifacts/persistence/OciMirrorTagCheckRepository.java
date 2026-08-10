package eu.wohlben.qits.artifacts.persistence;

import eu.wohlben.qits.artifacts.entity.OciMirrorTagCheck;
import eu.wohlben.qits.artifacts.entity.OciMirrorTagCheckId;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

/** Panache DAO for {@link OciMirrorTagCheck}, keyed by {@code (repository, imageName, tag)}. */
@ApplicationScoped
public class OciMirrorTagCheckRepository
    implements PanacheRepositoryBase<OciMirrorTagCheck, OciMirrorTagCheckId> {

  public Optional<OciMirrorTagCheck> findOne(String repository, String imageName, String tag) {
    return findByIdOptional(new OciMirrorTagCheckId(repository, imageName, tag));
  }
}
