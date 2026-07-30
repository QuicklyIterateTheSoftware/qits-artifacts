package eu.wohlben.qits.artifacts.persistence;

import eu.wohlben.qits.artifacts.entity.NpmProxyPackument;
import eu.wohlben.qits.artifacts.entity.NpmProxyPackumentId;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

/** Panache DAO for {@link NpmProxyPackument}, keyed by {@code (repository, packageName)}. */
@ApplicationScoped
public class NpmProxyPackumentRepository
    implements PanacheRepositoryBase<NpmProxyPackument, NpmProxyPackumentId> {

  public Optional<NpmProxyPackument> findOne(String repository, String packageName) {
    return findByIdOptional(new NpmProxyPackumentId(repository, packageName));
  }
}
