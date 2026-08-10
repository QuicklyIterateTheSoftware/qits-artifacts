package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.entity.ArtifactRepository;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.error.BadRequestException;
import eu.wohlben.qits.artifacts.error.NotFoundException;
import eu.wohlben.qits.artifacts.persistence.ArtifactRepositoryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;

/** Lifecycle of the named, typed blob containers. */
@ApplicationScoped
public class ArtifactRepositoryService {

  @Inject ArtifactRepositoryRepository repositories;

  /**
   * Idempotently ensures a repository of the given type exists. Re-ensuring an existing repository
   * is a no-op that returns it; requesting a <em>different</em> type for an existing name is a 400
   * (a repository's type is immutable — its stored blobs were validated against it).
   */
  @Transactional
  public ArtifactRepository ensure(String name, RepositoryType type) {
    if (name == null || name.isBlank()) {
      throw new BadRequestException("repository name is required");
    }
    if (type == null) {
      throw new BadRequestException("repository type is required");
    }
    ArtifactRepository existing = repositories.findById(name);
    if (existing != null) {
      if (existing.type != type) {
        throw new BadRequestException(
            "Repository '" + name + "' already exists with type " + existing.type);
      }
      return existing;
    }
    ArtifactRepository repo = new ArtifactRepository();
    repo.name = name;
    repo.type = type;
    repo.createdAt = Instant.now();
    repositories.persist(repo);
    return repo;
  }

  public List<ArtifactRepository> list() {
    return repositories.listAll();
  }

  /** Resolves a repository or fails with 404 — the guard on every upload/query/serve path. */
  public ArtifactRepository require(String name) {
    ArtifactRepository repo = repositories.findById(name);
    if (repo == null) {
      throw new NotFoundException("No such artifacts repository: " + name);
    }
    return repo;
  }
}
