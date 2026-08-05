package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.control.NpmRegistryCollection;
import eu.wohlben.qits.artifacts.entity.ArtifactRepository;
import eu.wohlben.qits.artifacts.entity.NpmVersion;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import eu.wohlben.qits.artifacts.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.artifacts.persistence.NpmProxyPackumentRepository;
import eu.wohlben.qits.artifacts.persistence.NpmVersionRepository;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The npm proxy's facts: two kinds of cached identity, when each was last wanted, and how a row goes
 * without a tombstone.
 *
 * <h2>Two identities, because the proxy caches two things</h2>
 *
 * <ul>
 *   <li>A <b>cached version</b> — an {@code npm_version} row with its tarball blob, spelled {@code
 *       <package>@<version>} exactly as npm spells it.
 *   <li>A <b>cached packument</b> — an {@code npm_proxy_packument} row, spelled {@code <package>
 *       (packument)} so it can never be mistaken for a version. It is the store's largest single
 *       cost after the image layers: 660 MB of CLOBs in a 747 MB H2 file, most of it documents for
 *       packages nothing has installed in months.
 * </ul>
 *
 * <h2>Only proxy rows, and the table is shared</h2>
 *
 * <p>{@code npm_version} holds hosted and proxied versions in one table, so the enumeration filters
 * by the <b>repository row's type</b> rather than by anything about a coordinate. Getting that wrong
 * would put the platform's own published packages under a cache's eviction rule, which is the one
 * mistake this type can make; {@code NpmProxyGcAdapterTest} asserts the scope over a store holding
 * both.
 *
 * <h2>Staleness</h2>
 *
 * <p>A version's effective access is {@code max(created_at, accessed_at)} — V11's column, moved by
 * every tarball GET, hosted or proxied, and creation counts as the first access so something pulled
 * through an hour ago is young rather than never-read.
 *
 * <p>A packument's is {@code max(fetched_at, the newest access among that package's cached
 * versions)}. Both halves are needed and neither alone is honest: {@code fetched_at} only says when
 * the <em>document</em> was last revalidated upstream, which a TTL moves on its own, while a package
 * whose tarballs are still being installed is plainly in use even if its document has sat inside its
 * TTL. Folding the versions in also makes the two die together: a packument outlives every version
 * it lists, so the document goes only in a run where all of them are cold, and the surviving
 * versions of a warm package always keep their document.
 *
 * <h2>Eviction writes no tombstone, and that is the point</h2>
 *
 * <p>{@code NpmRegistryCollection.collect} — the hosted door — deletes a version and writes the
 * republish tombstone that spends its name forever. Here that would be wrong in the exact direction
 * that breaks the cache: the version is upstream's, and re-fetching it is what the proxy exists for.
 * So eviction goes through {@code evictProxiedVersion}/{@code evictProxiedPackument}, which write
 * none and refuse any repository that is not an {@code npm-proxy} — the type check is what makes
 * "no tombstone" safe to say, given one table holds both kinds of row.
 */
@Singleton
public class NpmProxyGcAdapter implements GcTypeAdapter {

  /**
   * What distinguishes a packument identity from a version's. A space cannot appear in a package
   * name or a version, so no coordinate can collide with this spelling, and it reads as what it is
   * in a report.
   *
   * <p>Public because it is on the wire: a {@code GET /gc/plan} line spells an identity with it, so
   * anything reading that report — a test in another module included — has to name the same suffix
   * rather than a copy of it.
   */
  public static final String PACKUMENT = " (packument)";

  @Inject ArtifactRepositoryRepository repositories;
  @Inject NpmVersionRepository versions;
  @Inject NpmProxyPackumentRepository packuments;
  @Inject NpmRegistryCollection npm;

  @Override
  public RepositoryType type() {
    return RepositoryType.NPM_PROXY;
  }

  @Override
  public List<GcCandidate> enumerate() {
    List<GcCandidate> candidates = new ArrayList<>();
    for (ArtifactRepository repository : repositories.listAll()) {
      if (repository.type != RepositoryType.NPM_PROXY) {
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
   * Versions first, then packuments — a document whose versions are still being deleted is the
   * shape a reader expects, and the reverse would briefly leave a packument-less package with
   * tarballs still in it. Either order is correct: the next request re-fetches whatever is missing.
   */
  @Override
  public GcStrategy.Applied delete(GcStrategy.Plan plan, GcStrategy.GraceWindow grace) {
    List<GcIdentity> deleted = new ArrayList<>();
    List<GcIdentity> withheld = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    for (GcIdentity dead : plan.dead()) {
      if (!isPackument(dead)) {
        deleteVersion(dead, grace, deleted, withheld, errors);
      }
    }
    for (GcIdentity dead : plan.dead()) {
      if (isPackument(dead)) {
        deletePackument(dead, deleted, errors);
      }
    }
    return new GcStrategy.Applied(deleted, withheld, errors);
  }

  /** One proxy repository: every cached version, then every cached document. */
  private void collect(String repository, List<GcCandidate> candidates) {
    Map<String, Instant> newestVersionAccess = new HashMap<>();
    for (String packageName : versions.listPackageNames(repository)) {
      for (Object[] row : versions.listVersionRows(repository, packageName)) {
        String version = (String) row[0];
        String tarball = (String) row[1];
        Instant access = latest((Instant) row[2], (Instant) row[3]);
        newestVersionAccess.merge(packageName, access, NpmProxyGcAdapter::later);
        candidates.add(
            new GcCandidate(
                repository,
                packageName + "@" + version,
                packageName,
                // Upstream's release is not ours: a cache earns no version protection, which is the
                // rule the engine deliberately does not have.
                false,
                access,
                Set.of(tarball)));
      }
    }
    for (Object[] row : packuments.listCached(repository)) {
      String packageName = (String) row[0];
      Instant fetchedAt = (Instant) row[1];
      candidates.add(
          new GcCandidate(
              repository,
              packageName + PACKUMENT,
              packageName,
              false,
              later(fetchedAt, newestVersionAccess.get(packageName)),
              // No blob: a packument is an H2 CLOB, not a file. Releasing one frees no disk, which
              // is why NpmProxyGcStrategy's note says so on every report.
              Set.of()));
    }
  }

  private static boolean isPackument(GcIdentity identity) {
    return identity.identity().endsWith(PACKUMENT);
  }

  private void deleteVersion(
      GcIdentity dead,
      GcStrategy.GraceWindow grace,
      List<GcIdentity> deleted,
      List<GcIdentity> withheld,
      List<String> errors) {
    // A scoped package starts with '@', so the LAST '@' is the separator; a version has none.
    int at = dead.identity().lastIndexOf('@');
    String packageName = dead.identity().substring(0, at);
    String version = dead.identity().substring(at + 1);
    try {
      NpmVersion row = versions.findOne(dead.repository(), packageName, version).orElse(null);
      if (row == null) {
        errors.add(dead.identity() + ": no such version row — the store moved since planning");
        return;
      }
      if (grace.withinGrace(row.tarballBlobId)) {
        withheld.add(dead);
        return;
      }
      npm.evictProxiedVersion(dead.repository(), packageName, version);
      deleted.add(dead);
    } catch (RuntimeException failed) {
      errors.add(dead.identity() + ": " + failed.getMessage());
    }
  }

  /**
   * A packument names no blob, so nothing can be inside the grace window and it is never withheld.
   * The window exists to stop a row deletion from stranding a young file; there is no file.
   */
  private void deletePackument(
      GcIdentity dead, List<GcIdentity> deleted, List<String> errors) {
    String packageName =
        dead.identity().substring(0, dead.identity().length() - PACKUMENT.length());
    try {
      npm.evictProxiedPackument(dead.repository(), packageName);
      deleted.add(dead);
    } catch (RuntimeException failed) {
      errors.add(dead.identity() + ": " + failed.getMessage());
    }
  }

  /** Creation counts as the first access, so a version cached minutes ago reads as young. */
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
