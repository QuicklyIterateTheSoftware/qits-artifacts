package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.control.MavenLayout;
import eu.wohlben.qits.artifacts.control.MavenRegistryCollection;
import eu.wohlben.qits.artifacts.control.MavenVersionOrder;
import eu.wohlben.qits.artifacts.entity.ArtifactRepository;
import eu.wohlben.qits.artifacts.entity.MavenArtifact;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import eu.wohlben.qits.artifacts.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.artifacts.persistence.MavenArtifactRepository;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The platform's own maven repository, as facts — and the one type whose identity is <b>not</b> the
 * row.
 *
 * <h2>A coordinate is the identity, not a path</h2>
 *
 * <p>A maven version is a <em>set</em> of files: a jar, its pom, sometimes sources beside them. Half
 * a version is not a smaller version, it is a broken resolve — a jar whose pom is gone fails every
 * build that reaches it — and the settled rule counts <b>versions</b> ("the last 2 release versions
 * per artifact"), which an engine counting paths could not express at all. So one identity here is
 * one resolvable coordinate, spelled the way a client spells it, and every file under it lives or
 * dies together:
 *
 * <ul>
 *   <li>{@code eu.wohlben.qits:qits-eventstream:1.0.0} — a release version directory. A
 *       <b>release</b>, so the belt counts it.
 *   <li>{@code eu.wohlben.qits:qits-eventstream:1.0.1-20260802.123456-3} — one timestamped snapshot
 *       deploy, exactly the coordinate the derived version-level metadata resolves
 *       {@code 1.0.1-SNAPSHOT} to. Not a release.
 *   <li>{@code eu.wohlben.qits:qits-eventstream:1.0.1-SNAPSHOT} — the literal-filename snapshot, the
 *       one mutable path class ({@code uniqueVersion=false}). Not a release.
 * </ul>
 *
 * <p>A path the layout cannot parse is its own identity under its own path spelling. Nothing can
 * deploy one — the wire refuses an unparseable path at the door — so this is the honest answer for
 * a row that predates a rule rather than a case that happens.
 *
 * <h2>The belt, and the belt maven needs beyond it</h2>
 *
 * <p>Releases: the last two per {@code (groupId, artifactId)}, by maven's own version order. Older
 * ones survive on use, which for a library means something built against it inside the window.
 *
 * <p>Snapshots get the belt maven's derived metadata forces: <b>the newest deployable set of every
 * snapshot version line is always kept</b> ({@link #pinnedBy}) — the newest timestamped set if the
 * line has any, else the literal {@code -SNAPSHOT} set. {@code maven-metadata.xml} is computed from
 * the surviving rows at read time, so a resolver asking for {@code 1.0.1-SNAPSHOT} is redirected to
 * whatever is newest; deleting that one would point the document at a file the store no longer has,
 * which is the single failure this type must not produce. Older timestamped sets are ordinary
 * candidates and age out at P90D.
 *
 * <p>What this deliberately does <b>not</b> do is keep a fixed number of snapshot builds. The plan
 * that named this type's cleanup ({@code maven-repository-plan.md} §3.6) never priced deletion, so
 * the conservative reading is taken: the settlement's window decides, and the only structural keep
 * is the one a resolver would break without.
 *
 * <h2>Access</h2>
 *
 * <p>A coordinate's effective access is the <b>newest</b> {@code max(created_at, accessed_at)} over
 * its files. A pom read is a resolve of the version, so one warm file keeps the set — the opposite
 * choice would let a jar nothing has re-downloaded drag its own pom out from under it. The derived
 * documents move nothing: metadata and checksums are computed per request and are no row's bytes.
 */
@Singleton
public class MavenPackagesGcAdapter implements GcTypeAdapter {

  /** The keep every snapshot version line gets, named so a report says which resolve it protects. */
  static final String KEPT_RESOLVABLE_SNAPSHOT =
      "the newest deployable set of this snapshot version — what the derived maven-metadata.xml"
          + " resolves to, so a resolver would 404 without it";

  @Inject ArtifactRepositoryRepository repositories;
  @Inject MavenArtifactRepository artifacts;
  @Inject MavenRegistryCollection maven;

  @Override
  public RepositoryType type() {
    return RepositoryType.MAVEN_PACKAGES;
  }

  @Override
  public List<GcCandidate> enumerate() {
    List<GcCandidate> candidates = new ArrayList<>();
    for (ArtifactRepository repository : repositories.listAll()) {
      if (repository.type != RepositoryType.MAVEN_PACKAGES) {
        continue;
      }
      units(repository.name).values().forEach(unit -> candidates.add(unit.candidate()));
    }
    return List.copyOf(candidates);
  }

  /** The newest deployable set of every snapshot version line — see the class javadoc. */
  @Override
  public GcPinned pinnedBy(List<GcCandidate> candidates, GcPins pins) {
    Map<String, String> newestPerLine = new HashMap<>();
    for (GcCandidate candidate : candidates) {
      // A snapshot line is exactly a group whose version directory ends in -SNAPSHOT, which is the
      // layout's own rule rather than a second reading of it. Releases and the unparseable rows
      // have no metadata redirect to protect.
      if (!candidate.group().endsWith("-SNAPSHOT")) {
        continue;
      }
      newestPerLine.merge(
          candidate.group(),
          candidate.identity(),
          (held, other) -> BY_SNAPSHOT_RECENCY.compare(held, other) >= 0 ? held : other);
    }
    return candidate ->
        candidate.identity().equals(newestPerLine.get(candidate.group()))
            ? KEPT_RESOLVABLE_SNAPSHOT
            : null;
  }

  /**
   * Oldest release first, by maven's own version order; ties on the identity so a report is stable
   * across runs.
   *
   * <p>{@link MavenVersionOrder} is total by construction — what it cannot read sorts last within
   * its own class and the document still serves — which is the property this comparator needs too.
   */
  @Override
  public Comparator<GcCandidate> byAge() {
    return Comparator.comparing(
            (GcCandidate candidate) -> versionOf(candidate.identity()), MavenVersionOrder.INSTANCE)
        .thenComparing(GcCandidate::identity);
  }

  /**
   * One coordinate at a time, every file of it, through {@code MavenRegistryCollection}.
   *
   * <p>The unit is re-derived from the rows rather than carried on the plan, for the reason every
   * {@code apply} here re-reads the store: a plan is applied moments after it was computed, and a
   * coordinate that gained a file in between must be removed whole or not at all.
   *
   * <p><b>The grace window gates the whole set.</b> One young file withholds the coordinate — its
   * rows all stay, and the next run past the window takes rows and files together. Deleting the
   * mature rows and keeping the young one would leave exactly the half-version this type's identity
   * model exists to prevent.
   */
  @Override
  public GcStrategy.Applied delete(GcStrategy.Plan plan, GcStrategy.GraceWindow grace) {
    List<GcIdentity> deleted = new ArrayList<>();
    List<GcIdentity> withheld = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    Map<String, Map<String, Unit>> byRepository = new HashMap<>();
    for (GcIdentity dead : plan.dead()) {
      Unit unit =
          byRepository
              .computeIfAbsent(dead.repository(), this::units)
              .get(dead.identity());
      if (unit == null) {
        errors.add(dead.identity() + ": no such coordinate — the store moved since planning");
        continue;
      }
      if (unit.blobs().stream().anyMatch(grace::withinGrace)) {
        withheld.add(dead);
        continue;
      }
      try {
        for (String path : unit.paths()) {
          maven.collect(dead.repository(), path);
        }
        deleted.add(dead);
      } catch (RuntimeException failed) {
        errors.add(dead.identity() + ": " + failed.getMessage());
      }
    }
    return new GcStrategy.Applied(deleted, withheld, errors);
  }

  /** One repository's rows, folded into coordinates. Keyed by identity, in path order. */
  private Map<String, Unit> units(String repository) {
    Map<String, Unit> units = new LinkedHashMap<>();
    for (MavenArtifact row : artifacts.<MavenArtifact>list("repository = ?1", repository)) {
      MavenLayout.ArtifactPath parsed = MavenLayout.parse(row.path);
      String identity = identityOf(parsed, row.path);
      units
          .computeIfAbsent(identity, key -> new Unit(repository, key, groupOf(repository, parsed)))
          .add(row);
    }
    return units;
  }

  /**
   * The coordinate a client would name this file's set by, or the raw path when the layout cannot
   * read it.
   */
  private static String identityOf(MavenLayout.ArtifactPath parsed, String path) {
    if (parsed == null) {
      return path;
    }
    String coordinate = parsed.groupId() + ":" + parsed.artifactId() + ":";
    MavenLayout.SnapshotFileName timestamped =
        MavenLayout.parseTimestampedSnapshot(parsed.artifactId(), parsed.version(), parsed.file());
    if (timestamped == null) {
      return coordinate + parsed.version();
    }
    return coordinate
        + timestamped.value(
            parsed.version().substring(0, parsed.version().length() - "-SNAPSHOT".length()));
  }

  /**
   * The group the belt counts within: the artifact for a release, the version line for a snapshot.
   *
   * <p>Two different questions wearing one field, and both are the settled ones: "the last 2 release
   * versions per artifact" needs the artifact, and "the newest deployable set of this snapshot
   * version" needs the version line. A release group can never collide with a snapshot group because
   * a version directory ending in {@code -SNAPSHOT} is what separates them.
   */
  private static String groupOf(String repository, MavenLayout.ArtifactPath parsed) {
    if (parsed == null) {
      return repository;
    }
    String artifact = repository + "/" + parsed.groupId() + ":" + parsed.artifactId();
    return MavenLayout.isSnapshotVersion(parsed.version())
        ? artifact + ":" + parsed.version()
        : artifact;
  }

  /** The version half of a {@code group:artifact:version} identity; a raw path answers whole. */
  private static String versionOf(String identity) {
    int colon = identity.lastIndexOf(':');
    return colon < 0 ? identity : identity.substring(colon + 1);
  }

  /**
   * Which of two snapshot coordinates a resolver would be sent to, newest last.
   *
   * <p>A literal {@code -SNAPSHOT} coordinate ranks below every timestamped one, so it is the newest
   * deployable set only on a line with no timestamped deploys at all — which is exactly when a
   * client resolves it by name. Timestamped coordinates order by their {@code yyyyMMdd.HHmmss}
   * stamp, which is lexical by construction, and then by <b>build number as a number</b>: two
   * deploys inside one second are the only case where that differs from a string compare, and
   * getting it wrong there would point the metadata at the earlier of the two.
   */
  static final Comparator<String> BY_SNAPSHOT_RECENCY =
      Comparator.<String, Boolean>comparing(identity -> !identity.endsWith("-SNAPSHOT"))
          .thenComparing(identity -> stampOf(versionOf(identity)))
          .thenComparingLong(identity -> buildNumberOf(versionOf(identity)));

  /** The {@code yyyyMMdd.HHmmss} stamp of a timestamped version, or the version itself. */
  private static String stampOf(String version) {
    int dash = version.lastIndexOf('-');
    return dash < 0 ? version : version.substring(0, dash);
  }

  /** The build number of a timestamped version, or zero when there is none to read. */
  private static long buildNumberOf(String version) {
    int dash = version.lastIndexOf('-');
    if (dash < 0) {
      return 0L;
    }
    try {
      return Long.parseLong(version.substring(dash + 1));
    } catch (NumberFormatException notANumber) {
      return 0L;
    }
  }

  /** One coordinate's rows, accumulated as the enumeration walks them. */
  private static final class Unit {

    private final String repository;
    private final String identity;
    private final String group;
    private final Set<String> paths = new TreeSet<>();
    private final Set<String> blobs = new TreeSet<>();
    private boolean released;
    private Instant lastAccessAt;

    private Unit(String repository, String identity, String group) {
      this.repository = repository;
      this.identity = identity;
      this.group = group;
    }

    private void add(MavenArtifact row) {
      paths.add(row.path);
      blobs.add(row.blobId);
      MavenLayout.ArtifactPath parsed = MavenLayout.parse(row.path);
      released = parsed != null && !MavenLayout.isSnapshotVersion(parsed.version());
      Instant access =
          row.accessedAt == null || row.accessedAt.isBefore(row.createdAt)
              ? row.createdAt
              : row.accessedAt;
      // The NEWEST access across the set: a pom read is a resolve of the version, and one warm file
      // keeps the coordinate whole rather than letting a cold sibling drag it out.
      if (lastAccessAt == null || lastAccessAt.isBefore(access)) {
        lastAccessAt = access;
      }
    }

    private Set<String> paths() {
      return paths;
    }

    private Set<String> blobs() {
      return blobs;
    }

    private GcCandidate candidate() {
      return new GcCandidate(repository, identity, group, released, lastAccessAt, blobs);
    }
  }
}
