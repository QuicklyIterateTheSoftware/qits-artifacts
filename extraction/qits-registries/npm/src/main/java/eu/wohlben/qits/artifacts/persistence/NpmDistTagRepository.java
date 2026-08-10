package eu.wohlben.qits.artifacts.persistence;

import eu.wohlben.qits.artifacts.entity.NpmDistTag;
import eu.wohlben.qits.artifacts.entity.NpmDistTagId;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/** Panache DAO for {@link NpmDistTag}, keyed by {@code (repository, packageName, tag)}. */
@ApplicationScoped
public class NpmDistTagRepository implements PanacheRepositoryBase<NpmDistTag, NpmDistTagId> {

  public Optional<NpmDistTag> findOne(String repository, String packageName, String tag) {
    return findByIdOptional(new NpmDistTagId(repository, packageName, tag));
  }

  public List<NpmDistTag> listTags(String repository, String packageName) {
    return find("repository = ?1 and packageName = ?2", Sort.ascending("tag"), repository, packageName)
        .list();
  }
}
