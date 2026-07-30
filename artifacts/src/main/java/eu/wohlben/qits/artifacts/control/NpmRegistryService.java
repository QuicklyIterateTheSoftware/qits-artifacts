package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.entity.ArtifactRepository;
import eu.wohlben.qits.artifacts.entity.NpmDistTag;
import eu.wohlben.qits.artifacts.entity.NpmProxyPackument;
import eu.wohlben.qits.artifacts.entity.NpmVersion;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.error.NpmException;
import eu.wohlben.qits.artifacts.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.artifacts.persistence.NpmDistTagRepository;
import eu.wohlben.qits.artifacts.persistence.NpmProxyPackumentRepository;
import eu.wohlben.qits.artifacts.persistence.NpmVersionRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The npm registry's database work, as complete units of work.
 *
 * <p>Every public method here is annotated, for the same load-bearing reason {@code
 * OciRegistryService}'s are: the callers are raw Vert.x route handlers, which run with <b>no CDI
 * request context and no transaction</b>. Drop an annotation and the npm routes fail with {@code
 * ContextNotActiveException} at runtime only, with a green {@code mvn verify} behind them.
 *
 * <p>Tarball bytes stay deliberately outside all of it — they are staged into {@code BlobStore} by
 * the route before anything here is called, so a slow publish or a slow upstream fetch cannot time
 * a transaction out.
 *
 * <p>Nothing here returns an entity. The route layer runs outside the persistence context, so a
 * lazily-materialised {@code @Lob} reached from there would fail; every accessor below copies what
 * it read into a record while the context is still active.
 */
@ApplicationScoped
public class NpmRegistryService {

  @Inject ArtifactRepositoryRepository repositories;
  @Inject NpmVersionRepository versions;
  @Inject NpmDistTagRepository distTags;
  @Inject NpmProxyPackumentRepository packuments;

  /** A stored version, flattened for packument assembly and for serving its tarball. */
  public record StoredVersion(
      String version, String tarballBlobId, String integrity, String shasum, String manifestJson) {}

  /** A cached upstream packument: the document verbatim, its validator, and when it arrived. */
  public record CachedPackument(String doc, String etag, Instant fetchedAt) {}

  /**
   * Resolves the first path segment after {@code /artifacts/npm/} to an npm-typed repository row.
   *
   * <p>Repositories are not created implicitly, exactly as on {@code /v2}: an unknown or
   * wrong-typed name is a 404 whose message names the ensure endpoint and the type to ask for. The
   * two seeded rows — {@code npm} (hosted) and {@code npmjs} (proxy) — mean a fresh deployment
   * needs no manual step for the platform's own convention; every other name still has to be asked
   * for, so a typo fails loudly rather than quietly minting a namespace.
   *
   * @return which of the two npm types this repository is, since almost every caller branches on it
   */
  @ActivateRequestContext
  public RepositoryType requireNpmRepository(String name) {
    ArtifactRepository repository = name == null ? null : repositories.findById(name);
    if (repository == null
        || (repository.type != RepositoryType.NPM_PACKAGES
            && repository.type != RepositoryType.NPM_PROXY)) {
      throw new NpmException(
          404,
          "no such npm repository '"
              + name
              + "'; create it with PUT /artifacts/api/repositories/"
              + name
              + " {\"type\":\"npm-packages\"} (or \"npm-proxy\")");
    }
    return repository.type;
  }

  @ActivateRequestContext
  public Optional<StoredVersion> findVersion(String repository, String packageName, String version) {
    return versions.findOne(repository, packageName, version).map(NpmRegistryService::flatten);
  }

  /** Every version of one package — the whole read side of packument assembly. */
  @ActivateRequestContext
  public List<StoredVersion> listVersions(String repository, String packageName) {
    return versions.listVersions(repository, packageName).stream()
        .map(NpmRegistryService::flatten)
        .toList();
  }

  /** The package's dist-tags, in a map whose iteration order is stable across requests. */
  @ActivateRequestContext
  public Map<String, String> distTags(String repository, String packageName) {
    Map<String, String> tags = new LinkedHashMap<>();
    for (NpmDistTag tag : distTags.listTags(repository, packageName)) {
      tags.put(tag.tag, tag.version);
    }
    return tags;
  }

  /**
   * Writes one published version and moves the dist-tags that named it.
   *
   * <p>The immutability check lives here rather than in the route because it has to be inside the
   * same transaction as the insert — checking outside it would make two concurrent publishes of the
   * same version a race that both sides win.
   *
   * @throws NpmException {@code 403} if the version already exists
   */
  @ActivateRequestContext
  @Transactional
  public void publish(
      String repository,
      String packageName,
      String version,
      String tarballBlobId,
      String integrity,
      String shasum,
      String manifestJson,
      Map<String, String> tagsToMove) {
    if (versions.findOne(repository, packageName, version).isPresent()) {
      throw new NpmException(
          403,
          "cannot publish over the existing "
              + packageName
              + "@"
              + version
              + " — published versions are immutable; bump the version");
    }
    versions.persist(
        row(repository, packageName, version, tarballBlobId, integrity, shasum, manifestJson));
    tagsToMove.forEach((tag, target) -> moveTag(repository, packageName, tag, target));
  }

  /**
   * Records a version the proxy just pulled through, if it is not already known.
   *
   * <p>Written lazily on the first tarball fetch rather than when a packument is cached, so the
   * tarball route is <b>one</b> code path for both repository types: look the version up, and if it
   * is missing and this is a proxy, go and get it. Idempotent, because two concurrent installs of
   * the same dependency are the normal case rather than the edge.
   */
  @ActivateRequestContext
  @Transactional
  public void recordProxiedVersion(
      String repository,
      String packageName,
      String version,
      String tarballBlobId,
      String integrity,
      String shasum,
      String manifestJson) {
    if (versions.findOne(repository, packageName, version).isPresent()) {
      return;
    }
    versions.persist(
        row(repository, packageName, version, tarballBlobId, integrity, shasum, manifestJson));
  }

  @ActivateRequestContext
  public Optional<CachedPackument> findProxyPackument(String repository, String packageName) {
    return packuments
        .findOne(repository, packageName)
        .map(cached -> new CachedPackument(cached.doc, cached.etag, cached.fetchedAt));
  }

  /** Stores or replaces a cached packument. Upstream's document goes in verbatim; see the entity. */
  @ActivateRequestContext
  @Transactional
  public void storeProxyPackument(
      String repository, String packageName, String doc, String etag, Instant fetchedAt) {
    NpmProxyPackument cached =
        packuments.findOne(repository, packageName).orElseGet(NpmProxyPackument::new);
    boolean fresh = cached.packageName == null;
    cached.repository = repository;
    cached.packageName = packageName;
    cached.doc = doc;
    cached.etag = etag;
    cached.fetchedAt = fetchedAt;
    if (fresh) {
      packuments.persist(cached);
    }
  }

  /**
   * Marks a cached packument as revalidated without rewriting its document — what a {@code 304} from
   * upstream means.
   *
   * <p>A bulk update rather than a load-and-mutate, and that is the point rather than an
   * optimisation detail: loading the row to move one timestamp would drag the whole packument CLOB
   * through the JVM on every TTL expiry, which for a popular package is a megabyte read to write
   * eight bytes. Separate from {@link #storeProxyPackument} for the same reason.
   */
  @ActivateRequestContext
  @Transactional
  public void touchProxyPackument(
      String repository, String packageName, String etag, Instant fetchedAt) {
    if (etag == null || etag.isBlank()) {
      packuments.update(
          "fetchedAt = ?1 where repository = ?2 and packageName = ?3",
          fetchedAt, repository, packageName);
      return;
    }
    packuments.update(
        "fetchedAt = ?1, etag = ?2 where repository = ?3 and packageName = ?4",
        fetchedAt, etag, repository, packageName);
  }

  private void moveTag(String repository, String packageName, String tag, String version) {
    NpmDistTag row = distTags.findOne(repository, packageName, tag).orElseGet(NpmDistTag::new);
    boolean fresh = row.tag == null;
    row.repository = repository;
    row.packageName = packageName;
    row.tag = tag;
    row.version = version;
    row.updatedAt = Instant.now();
    if (fresh) {
      distTags.persist(row);
    }
  }

  private static NpmVersion row(
      String repository,
      String packageName,
      String version,
      String tarballBlobId,
      String integrity,
      String shasum,
      String manifestJson) {
    NpmVersion row = new NpmVersion();
    row.repository = repository;
    row.packageName = packageName;
    row.version = version;
    row.tarballBlobId = tarballBlobId;
    row.integrity = integrity;
    row.shasum = shasum;
    row.manifestJson = manifestJson;
    row.createdAt = Instant.now();
    return row;
  }

  private static StoredVersion flatten(NpmVersion row) {
    return new StoredVersion(
        row.version, row.tarballBlobId, row.integrity, row.shasum, row.manifestJson);
  }
}
