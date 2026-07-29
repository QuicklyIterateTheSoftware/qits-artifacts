package eu.wohlben.qits.artifacts.persistence;

import eu.wohlben.qits.artifacts.entity.OciTag;
import eu.wohlben.qits.artifacts.entity.OciTagId;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/** Panache DAO for {@link OciTag}, keyed by {@code (repository, imageName, tag)}. */
@ApplicationScoped
public class OciTagRepository implements PanacheRepositoryBase<OciTag, OciTagId> {

  public Optional<OciTag> findOne(String repository, String imageName, String tag) {
    return findByIdOptional(new OciTagId(repository, imageName, tag));
  }

  /**
   * Tag names in lexical order, which the Distribution spec requires of {@code tags/list}.
   *
   * @param after the {@code ?last=} cursor — return only tags ordering strictly after it; null or
   *     blank starts at the beginning
   * @param limit the {@code ?n=} page size
   */
  public List<String> listTagNames(String repository, String imageName, String after, int limit) {
    String query = "repository = ?1 and imageName = ?2";
    Object[] params;
    if (after == null || after.isBlank()) {
      params = new Object[] {repository, imageName};
    } else {
      query += " and tag > ?3";
      params = new Object[] {repository, imageName, after};
    }
    return find(query, Sort.ascending("tag"), params).page(0, limit).list().stream()
        .map(tag -> tag.tag)
        .toList();
  }
}
