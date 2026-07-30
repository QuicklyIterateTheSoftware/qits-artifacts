package eu.wohlben.qits.artifacts.persistence;

import eu.wohlben.qits.artifacts.entity.NpmVersion;
import eu.wohlben.qits.artifacts.entity.NpmVersionId;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/** Panache DAO for {@link NpmVersion}, keyed by {@code (repository, packageName, version)}. */
@ApplicationScoped
public class NpmVersionRepository implements PanacheRepositoryBase<NpmVersion, NpmVersionId> {

  public Optional<NpmVersion> findOne(String repository, String packageName, String version) {
    return findByIdOptional(new NpmVersionId(repository, packageName, version));
  }

  /**
   * Every version of one package, which is the whole of what packument assembly reads. Ordered by
   * version string rather than by semver: a packument's {@code versions} is a JSON object and its
   * order carries no meaning to any client, but a stable order makes the document reproducible.
   */
  public List<NpmVersion> listVersions(String repository, String packageName) {
    return find(
            "repository = ?1 and packageName = ?2",
            Sort.ascending("version"),
            repository,
            packageName)
        .list();
  }
}
