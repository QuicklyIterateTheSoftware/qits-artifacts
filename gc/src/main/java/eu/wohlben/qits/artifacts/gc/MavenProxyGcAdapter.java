package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.control.MavenLayout;
import eu.wohlben.qits.artifacts.control.MavenRegistryCollection;
import eu.wohlben.qits.artifacts.entity.ArtifactRepository;
import eu.wohlben.qits.artifacts.entity.MavenArtifact;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import eu.wohlben.qits.artifacts.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.artifacts.persistence.MavenArtifactRepository;
import eu.wohlben.qits.artifacts.persistence.MavenProxyMetadataRepository;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The maven proxy's facts: two kinds of cached identity, when each was last wanted, and how a row
 * goes.
 *
 * <h2>Two identities, because the proxy caches two things</h2>
 *
 * <ul>
 *   <li>A <b>cached file</b> — a {@code maven_artifact} row with its blob, spelled by its path. A
 *       jar, a pom, a sources jar, and upstream's own {@code .sha1}/{@code .md5} siblings, which are
 *       immutable paths like any other here.
 *   <li>A <b>cached metadata document</b> — a {@code maven_proxy_metadata} row, spelled {@code
 *       <path> (metadata)} so it can never be mistaken for a file. A path already ends in {@code
 *       maven-metadata.xml}, and no filename contains a space, so the two spellings cannot collide.
 * </ul>
 *
 * <h2>A PATH is the identity here, and that is the difference from {@code maven-packages}</h2>
 *
 * <p>{@code MavenPackagesGcAdapter} folds rows into coordinates because half a published version is
 * a broken resolve that nothing can repair: the bytes are gone. A cache repairs itself — the next
 * request for an evicted path fetches it through again — so there is no half-version to prevent, and
 * making a coordinate the unit here would only withhold files nothing has asked for in months
 * because one sibling is warm. The rule the settlement gives a cache is per candidate, and a
 * candidate here is a file.
 *
 * <h2>Only proxy rows, and the table is shared</h2>
 *
 * <p>{@code maven_artifact} holds deployed and cached files in one table, so the enumeration filters
 * by the <b>repository row's type</b> rather than by anything about a path. Getting that wrong would
 * put the platform's own published library under a cache's eviction rule, which is the one mistake
 * this type can make — the npm proxy's hazard verbatim, and {@code MavenProxyGcAdapterTest} asserts
 * the scope over a store holding both.
 *
 * <h2>Staleness</h2>
 *
 * <p>A file's effective access is {@code max(created_at, accessed_at)} — V11's column, moved by every
 * stored-file GET, and creation counts as the first access so something pulled through an hour ago
 * reads as young rather than never-read.
 *
 * <p>A document's is {@code max(fetched_at, the newest access among the files cached under its
 * directory)}. Both halves are needed and neither alone is honest, the npm packument's argument
 * restated: {@code fetched_at} only says when the document was last revalidated upstream, which a
 * TTL moves on its own, while an artifact whose jars are still being resolved is plainly in use.
 * Folding the files in also makes the two die together, so a warm artifact always keeps the document
 * a resolver reads its versions from.
 *
 * <h2>Eviction is not a collection</h2>
 *
 * <p>Rows leave through {@code MavenRegistryCollection.evictProxiedArtifact}/{@code
 * evictProxiedMetadata}, which refuse any repository that is not a {@code maven-proxy}. The hosted
 * door ({@code collect}) is the one whose caller must remove a whole coordinate; this one may not be
 * reached from here at all, which is what keeps one table's two meanings apart.
 */
@Singleton
public class MavenProxyGcAdapter implements GcTypeAdapter {

  /**
   * What distinguishes a cached document's identity from a cached file's. A space cannot appear in a
   * maven path segment this store accepts, so no path can collide with this spelling, and it reads
   * as what it is in a report.
   *
   * <p>Public because it is on the wire: a {@code GET /gc/plan} line spells an identity with it, so
   * anything reading that report — a test in another module included — names the same suffix rather
   * than a copy of it.
   */
  public static final String METADATA = " (metadata)";

  @Inject ArtifactRepositoryRepository repositories;
  @Inject MavenArtifactRepository artifacts;
  @Inject MavenProxyMetadataRepository metadata;
  @Inject MavenRegistryCollection maven;

  @Override
  public RepositoryType type() {
    return RepositoryType.MAVEN_PROXY;
  }

  @Override
  public List<GcCandidate> enumerate() {
    List<GcCandidate> candidates = new ArrayList<>();
    for (ArtifactRepository repository : repositories.listAll()) {
      if (repository.type != RepositoryType.MAVEN_PROXY) {
        continue;
      }
      collect(repository.name, candidates);
    }
    return List.copyOf(candidates);
  }

  /** Oldest first by effective access; ties on the identity, so a report is stable across runs. */
  @Override
  public Comparator<GcCandidate> byAge() {
    return Comparator.comparing(GcCandidate::lastAccessAt).thenComparing(GcCandidate::identity);
  }

  /**
   * Files first, then documents — a document whose files are still being deleted is the shape a
   * reader expects. Either order is correct: the next request re-fetches whatever is missing.
   */
  @Override
  public GcStrategy.Applied delete(GcStrategy.Plan plan, GcStrategy.GraceWindow grace) {
    List<GcIdentity> deleted = new ArrayList<>();
    List<GcIdentity> withheld = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    for (GcIdentity dead : plan.dead()) {
      if (!isMetadata(dead)) {
        deleteFile(dead, grace, deleted, withheld, errors);
      }
    }
    for (GcIdentity dead : plan.dead()) {
      if (isMetadata(dead)) {
        deleteMetadata(dead, deleted, errors);
      }
    }
    return new GcStrategy.Applied(deleted, withheld, errors);
  }

  /** One proxy repository: every cached file, then every cached document. */
  private void collect(String repository, List<GcCandidate> candidates) {
    // Sorted, so a document's directory prefix is a range rather than a scan of everything. A
    // Central cache is thousands of paths and a document per artifact, which is the one place in
    // this adapter where the naive shape would be quadratic.
    TreeMap<String, Instant> accessByPath = new TreeMap<>();
    for (MavenArtifact row : artifacts.<MavenArtifact>list("repository = ?1", repository)) {
      Instant access = latest(row.createdAt, row.accessedAt);
      accessByPath.put(row.path, access);
      candidates.add(
          new GcCandidate(
              repository,
              row.path,
              // The group is the artifact directory, so a scoped plan and a report read by
              // coordinate rather than by a flat list of paths. No rule of this engine uses it —
              // the cache engine is per candidate — but every report prints it.
              MavenLayout.directoryOf(MavenLayout.directoryOf(row.path)),
              // Upstream's release is not ours: a cache earns no version protection, which is the
              // rule the engine deliberately does not have.
              false,
              access,
              Set.of(row.blobId)));
    }
    for (Object[] row : metadata.listCached(repository)) {
      String path = (String) row[0];
      Instant fetchedAt = (Instant) row[1];
      candidates.add(
          new GcCandidate(
              repository,
              path + METADATA,
              MavenLayout.directoryOf(path),
              false,
              later(fetchedAt, newestUnder(accessByPath, MavenLayout.directoryOf(path))),
              // No blob: a metadata document is an H2 CLOB, not a file. Releasing one frees no disk,
              // which is why MavenProxyGcStrategy's note says so on every report.
              Set.of()));
    }
  }

  /**
   * The newest access among the files cached under a directory — a prefix range on the sorted map,
   * so this costs the entries it reads rather than the whole cache per document.
   */
  private static Instant newestUnder(TreeMap<String, Instant> accessByPath, String directory) {
    String prefix = directory.isEmpty() ? "" : directory + "/";
    Instant newest = null;
    for (Map.Entry<String, Instant> entry : accessByPath.tailMap(prefix, true).entrySet()) {
      if (!entry.getKey().startsWith(prefix)) {
        break;
      }
      newest = later(newest, entry.getValue());
    }
    return newest;
  }

  private static boolean isMetadata(GcIdentity identity) {
    return identity.identity().endsWith(METADATA);
  }

  private void deleteFile(
      GcIdentity dead,
      GcStrategy.GraceWindow grace,
      List<GcIdentity> deleted,
      List<GcIdentity> withheld,
      List<String> errors) {
    try {
      MavenArtifact row = artifacts.findOne(dead.repository(), dead.identity()).orElse(null);
      if (row == null) {
        errors.add(dead.identity() + ": no such cached row — the store moved since planning");
        return;
      }
      if (grace.withinGrace(row.blobId)) {
        withheld.add(dead);
        return;
      }
      maven.evictProxiedArtifact(dead.repository(), dead.identity());
      deleted.add(dead);
    } catch (RuntimeException failed) {
      errors.add(dead.identity() + ": " + failed.getMessage());
    }
  }

  /**
   * A document names no blob, so nothing can be inside the grace window and it is never withheld.
   * The window exists to stop a row deletion from stranding a young file; there is no file.
   */
  private void deleteMetadata(GcIdentity dead, List<GcIdentity> deleted, List<String> errors) {
    String path = dead.identity().substring(0, dead.identity().length() - METADATA.length());
    try {
      maven.evictProxiedMetadata(dead.repository(), path);
      deleted.add(dead);
    } catch (RuntimeException failed) {
      errors.add(dead.identity() + ": " + failed.getMessage());
    }
  }

  /** Creation counts as the first access, so a file cached minutes ago reads as young. */
  private static Instant latest(Instant created, Instant accessed) {
    return accessed == null || accessed.isBefore(created) ? created : accessed;
  }

  private static Instant later(Instant one, Instant other) {
    if (one == null) {
      return other;
    }
    return other == null || other.isBefore(one) ? one : other;
  }
}
