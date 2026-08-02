package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.entity.ArtifactRepository;
import eu.wohlben.qits.artifacts.entity.MavenArtifact;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.error.MavenException;
import eu.wohlben.qits.artifacts.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.artifacts.persistence.MavenArtifactRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The maven repository's database work, as complete units of work.
 *
 * <p>Every public method here is annotated, for the same load-bearing reason {@code
 * NpmRegistryService}'s are: the callers are raw Vert.x route handlers, which run with <b>no CDI
 * request context and no transaction</b>. Drop an annotation and the maven routes fail with {@code
 * ContextNotActiveException} at runtime only, with a green {@code mvn verify} behind them.
 *
 * <p>Artifact bytes stay deliberately outside all of it — they are staged into {@code BlobStore} by
 * the route before anything here is called, so a slow deploy cannot time a transaction out. And
 * nothing here assembles a document: {@code maven-metadata.xml} is derived state, assembled per
 * request from these rows in the wire package, so it can never become a second source of truth.
 */
@ApplicationScoped
public class MavenRegistryService {

  @Inject ArtifactRepositoryRepository repositories;
  @Inject MavenArtifactRepository artifacts;

  /** A stored file, flattened for the serve path. */
  public record StoredArtifact(String path, String blobId, long sizeBytes) {}

  /** One row under a metadata prefix: the path and when it landed. */
  public record StoredPath(String path, Instant createdAt) {}

  /**
   * Resolves the first path segment after {@code /artifacts/maven/} to a maven-typed repository
   * row.
   *
   * <p>Repositories are not created implicitly, exactly as on {@code /v2} and {@code
   * /artifacts/npm}: an unknown or wrong-typed name is a 404 whose message names the ensure
   * endpoint and the type to ask for. The seeded {@code maven} row means a fresh deployment needs
   * no manual step for the platform's own convention; every other name still has to be asked for,
   * so a typo fails loudly rather than quietly minting a namespace.
   */
  @ActivateRequestContext
  public RepositoryType requireMavenRepository(String name) {
    ArtifactRepository repository = name == null ? null : repositories.findById(name);
    if (repository == null || repository.type != RepositoryType.MAVEN_PACKAGES) {
      throw new MavenException(
          404,
          "no such maven repository '"
              + name
              + "'; create it with PUT /artifacts/api/repositories/"
              + name
              + " {\"type\":\"maven-packages\"}");
    }
    return repository.type;
  }

  @ActivateRequestContext
  public Optional<StoredArtifact> findArtifact(String repository, String path) {
    return artifacts
        .findOne(repository, path)
        .map(row -> new StoredArtifact(row.path, row.blobId, row.sizeBytes));
  }

  /**
   * Writes one deployed file.
   *
   * <p>The immutability check lives here rather than in the route because it has to be inside the
   * same transaction as the insert — checking outside it would make two concurrent deploys of the
   * same path a race that both sides win. The path space has three classes (maven-repository-plan.md
   * §3.6) and each gets the honest rule:
   *
   * <ul>
   *   <li><b>Release paths</b> are immutable: a re-deploy of identical bytes is an idempotent
   *       no-op (deploy retries are normal, and content addressing makes the retry free); a
   *       re-deploy of different bytes is {@code 403}, naming the version and the rule — a
   *       coordinate that resolved to two different jars over its lifetime is the mutability this
   *       registry exists to refuse.
   *   <li><b>Timestamped snapshot files</b> are unique by construction — one deploy, one filename —
   *       so they take the release rule: identical is a no-op, different bytes at the same
   *       timestamped name is a {@code 403} that means the client's clock or build counter
   *       collided, which is worth saying loudly rather than absorbing.
   *   <li><b>Literal {@code -SNAPSHOT} filenames</b> are mutable: the coordinate is a moving target
   *       by definition, and a {@code 403} here would break a legitimate redeploy while buying
   *       nothing — the timestamped form is what every modern client sends, so this class exists
   *       for compatibility, not as the platform's own convention.
   * </ul>
   *
   * @throws MavenException {@code 403} on a re-deploy of an immutable path with different bytes
   */
  @ActivateRequestContext
  @Transactional
  public void deploy(
      String repository, MavenLayout.ArtifactPath parsed, String blobId, long sizeBytes) {
    Optional<MavenArtifact> existing = artifacts.findOne(repository, parsed.path());
    if (existing.isEmpty()) {
      MavenArtifact row = new MavenArtifact();
      row.repository = repository;
      row.path = parsed.path();
      row.blobId = blobId;
      row.sizeBytes = sizeBytes;
      row.createdAt = Instant.now();
      artifacts.persist(row);
      return;
    }
    MavenArtifact row = existing.get();
    if (row.blobId.equals(blobId)) {
      return;
    }
    if (MavenLayout.parseTimestampedSnapshot(parsed.artifactId(), parsed.version(), parsed.file())
        != null) {
      throw new MavenException(
          403,
          "cannot deploy over the existing "
              + parsed.path()
              + " — a timestamped snapshot name is unique by construction; different bytes at the"
              + " same name means the client's clock or build counter collided");
    }
    if (!MavenLayout.isMutablePath(parsed)) {
      throw new MavenException(
          403,
          "cannot deploy over the existing "
              + parsed.path()
              + " — version "
              + parsed.version()
              + " is immutable here; bump the version");
    }
    // The one mutable class: a literal -SNAPSHOT filename is a moving target by definition.
    row.blobId = blobId;
    row.sizeBytes = sizeBytes;
    row.createdAt = Instant.now();
  }

  /**
   * Every row under a metadata prefix, for the derived document.
   *
   * <p>A prefix scan on the primary key's leading columns — an index read at this store's scale
   * (the platform holds dozens of artifacts, not Maven Central's millions).
   */
  @ActivateRequestContext
  public List<StoredPath> listUnder(String repository, String prefix) {
    return artifacts.listPathsAndCreatedAtStartingWith(repository, prefix).stream()
        .map(row -> new StoredPath((String) row[0], (Instant) row[1]))
        .toList();
  }
}
