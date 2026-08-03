package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.entity.ArtifactRepository;
import eu.wohlben.qits.artifacts.entity.DaemonBinary;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.error.DaemonException;
import eu.wohlben.qits.artifacts.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.artifacts.persistence.DaemonBinaryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The daemon-binaries registry's database work, as complete units of work.
 *
 * <p>Every public method here is annotated, for the same load-bearing reason {@code
 * NpmRegistryService}'s and {@code MavenRegistryService}'s are: the callers are raw Vert.x route
 * handlers, which run with <b>no CDI request context and no transaction</b>. Drop an annotation and
 * the daemon routes fail with {@code ContextNotActiveException} at runtime only, with a green {@code
 * mvn verify} behind them.
 *
 * <p>Binary bytes stay deliberately outside all of it — they are staged into {@code BlobStore} by
 * the route before {@link #publish} is called, so a 43 MB upload cannot time a transaction out.
 *
 * <p><b>The row write IS the publish</b> (daemon-artifact-identity-plan.md §2.2). There is no path
 * that stores a daemon's bytes without an identity, which is precisely the hole this type was
 * created to close, and it is why no backfill code exists here: the three orphans already on the
 * live volume are adopted by an ops action (§5), not by anything in the service.
 */
@ApplicationScoped
public class DaemonRegistryService {

  @Inject ArtifactRepositoryRepository repositories;
  @Inject DaemonBinaryRepository binaries;

  /** A published binary, flattened for the serve path. */
  public record StoredBinary(
      String name, String version, String blobId, long sizeBytes, Instant publishedAt) {}

  /**
   * Resolves the daemon repository row.
   *
   * <p>Repositories are not created implicitly, exactly as on {@code /v2}, {@code /artifacts/npm}
   * and {@code /artifacts/maven}: a missing or wrong-typed row is a 404 whose message names the
   * ensure endpoint and the type to ask for. The seeded {@code daemons} row means a fresh deployment
   * needs no manual step.
   */
  @ActivateRequestContext
  public RepositoryType requireDaemonRepository(String name) {
    ArtifactRepository repository = name == null ? null : repositories.findById(name);
    if (repository == null || repository.type != RepositoryType.DAEMON_BINARIES) {
      throw new DaemonException(
          404,
          "no such daemon repository '"
              + name
              + "'; create it with PUT /artifacts/api/repositories/"
              + name
              + " {\"type\":\"daemon-binaries\"}");
    }
    return repository.type;
  }

  @ActivateRequestContext
  public Optional<StoredBinary> find(String repository, String name, String version) {
    return binaries.findOne(repository, name, version).map(DaemonRegistryService::flatten);
  }

  @ActivateRequestContext
  public List<StoredBinary> listVersions(String repository, String name) {
    return binaries.listVersions(repository, name).stream()
        .map(DaemonRegistryService::flatten)
        .toList();
  }

  /**
   * Writes one published binary — the whole of a publish, once the bytes are promoted.
   *
   * <p>The immutability check lives here rather than in the route because it has to be inside the
   * same transaction as the insert: checking outside it would make two concurrent publishes of the
   * same version a race that both sides win.
   *
   * <p>A re-publish is {@code 409} <b>even for identical bytes</b>, and that differs from maven's
   * idempotent-retry rule on purpose. A maven deploy sends one file per request and retries are
   * routine, so absorbing an identical re-PUT costs nothing; a daemon publish is one request from
   * one release pipeline, and a second one at the same version means either the version was reused
   * or the release ran twice — both worth saying loudly. 409 rather than npm's 403 because the
   * conflict is with an existing resource, which is what the status means and what a pipeline can
   * read without a body.
   *
   * @throws DaemonException {@code 409} when this (name, version) is already published
   */
  @ActivateRequestContext
  @Transactional
  public StoredBinary publish(
      String repository, String name, String version, String blobId, long sizeBytes) {
    Optional<DaemonBinary> existing = binaries.findOne(repository, name, version);
    if (existing.isPresent()) {
      throw new DaemonException(
          409,
          "version "
              + version
              + " of "
              + name
              + " is already published (sha256:"
              + existing.get().blobId
              + "); daemon versions are immutable — publish a new version");
    }
    DaemonBinary row = new DaemonBinary();
    row.repository = repository;
    row.name = name;
    row.version = version;
    row.blobId = blobId;
    row.sizeBytes = sizeBytes;
    row.publishedAt = Instant.now();
    binaries.persist(row);
    return flatten(row);
  }

  private static StoredBinary flatten(DaemonBinary row) {
    return new StoredBinary(row.name, row.version, row.blobId, row.sizeBytes, row.publishedAt);
  }
}
