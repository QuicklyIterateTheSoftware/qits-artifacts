package eu.wohlben.qits.artifacts.persistence;

import eu.wohlben.qits.artifacts.entity.OciMirrorUpstream;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/**
 * Panache DAO for {@link OciMirrorUpstream}, keyed by domain.
 *
 * <p>Two lookups, because the table is read from two directions: by domain (the CRUD API's key) and
 * by slug (the pull path, which starts from an image name's first segment).
 */
@ApplicationScoped
public class OciMirrorUpstreamRepository
    implements PanacheRepositoryBase<OciMirrorUpstream, String> {

  /** Every upstream, by slug — the order the API lists them in, stable across requests. */
  public List<OciMirrorUpstream> listBySlug() {
    return findAll(Sort.ascending("slug")).list();
  }

  public Optional<OciMirrorUpstream> findByDomain(String domain) {
    return domain == null ? Optional.empty() : findByIdOptional(domain);
  }

  public Optional<OciMirrorUpstream> findBySlug(String slug) {
    return slug == null ? Optional.empty() : find("slug", slug).firstResultOptional();
  }
}
