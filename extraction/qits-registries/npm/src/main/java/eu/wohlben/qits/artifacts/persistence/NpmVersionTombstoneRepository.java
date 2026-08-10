package eu.wohlben.qits.artifacts.persistence;

import eu.wohlben.qits.artifacts.entity.NpmVersionTombstone;
import eu.wohlben.qits.artifacts.entity.NpmVersionTombstoneId;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

/**
 * Panache DAO for {@link NpmVersionTombstone}, keyed by {@code (repository, packageName, version)}.
 *
 * <p>One read shape and one write shape, deliberately: a publish asks whether this exact coordinate
 * was collected, and collection writes that it was. There is no list, because nothing serves
 * tombstones and a listing is the first step towards something that does.
 */
@ApplicationScoped
public class NpmVersionTombstoneRepository
    implements PanacheRepositoryBase<NpmVersionTombstone, NpmVersionTombstoneId> {

  public Optional<NpmVersionTombstone> findOne(
      String repository, String packageName, String version) {
    return findByIdOptional(new NpmVersionTombstoneId(repository, packageName, version));
  }
}
