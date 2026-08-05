package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.control.NpmRegistryCollection;
import eu.wohlben.qits.artifacts.control.NpmSemver;
import eu.wohlben.qits.artifacts.entity.ArtifactRepository;
import eu.wohlben.qits.artifacts.entity.NpmDistTag;
import eu.wohlben.qits.artifacts.entity.NpmVersion;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import eu.wohlben.qits.artifacts.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.artifacts.persistence.NpmDistTagRepository;
import eu.wohlben.qits.artifacts.persistence.NpmVersionRepository;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The platform's own published packages, as facts: what a coordinate is, what a release is, which of
 * two releases is newer, what a dist-tag holds, and how a row goes.
 *
 * <h2>What a release is here</h2>
 *
 * <p>A version with <b>no prerelease part</b>. Consumers pin ranges, so {@code ^2026.801.85149} has
 * to keep resolving — including to the pre-calver {@code 0.0.x} line — and the belt is the last two
 * of them per package. An older release survives on <em>use</em>: a lockfile or a range that still
 * installs it moves {@code npm_version.accessed_at}, which is how a version stays alive by being
 * wanted rather than by policy.
 *
 * <p>Newer is <b>semver precedence</b> ({@link NpmSemver}), not a row timestamp and not insertion
 * order: {@code 2026.801.85149} outranks {@code 2026.801.63140} whichever was published first, and a
 * republish-after-collection could not reorder the belt if it tried.
 *
 * <p>A version that does not parse as semver cannot be ordered and is therefore never a release. It
 * is not thereby condemned — it ages out on access like any other non-release coordinate, which is a
 * narrower answer than the old unmodelled-means-keep-forever and a wider one than deleting it for
 * being unrecognised.
 *
 * <h2>The dist-tag belt</h2>
 *
 * <p>{@link #pinnedBy} keeps anything a dist-tag currently names. It is a pin in the exact sense the
 * keep-class means: a live pointer something outside this rule resolves through, and a packument
 * whose {@code dist-tags} names a version its {@code versions} does not list is a broken package to
 * every npm client. Today it changes no outcome — {@code latest} names a release and {@code main}
 * names a build published minutes ago — and that is precisely when a backstop is worth having.
 *
 * <h2>Scope, and the one mistake this type can make</h2>
 *
 * <p>{@code npm_version} holds hosted and proxied rows in one table, so the enumeration filters by
 * the <b>repository row's type</b>. Getting that wrong would put upstream's cached content under the
 * platform's release protection, or the platform's own packages under a cache's eviction; both
 * suites assert the scope from their own side.
 *
 * <p>Deletion goes through {@code NpmRegistryCollection.collect}, which writes the republish
 * tombstone <b>in the same transaction</b> and refuses a version a dist-tag still names. Both
 * guarantees are the mechanism's, so no path around them exists to forget.
 */
@Singleton
public class NpmPackagesGcAdapter implements GcTypeAdapter {

  /** The belt-and-braces keep, naming the tag so a reviewer can see which pointer saved a version. */
  static String keptByDistTag(String tag) {
    return "the " + tag + " dist-tag names this version";
  }

  @Inject ArtifactRepositoryRepository repositories;
  @Inject NpmVersionRepository versions;
  @Inject NpmDistTagRepository distTags;
  @Inject NpmRegistryCollection npm;

  @Override
  public RepositoryType type() {
    return RepositoryType.NPM_PACKAGES;
  }

  @Override
  public List<GcCandidate> enumerate() {
    List<GcCandidate> candidates = new ArrayList<>();
    for (ArtifactRepository repository : repositories.listAll()) {
      if (repository.type != RepositoryType.NPM_PACKAGES) {
        continue;
      }
      for (String packageName : versions.listPackageNames(repository.name)) {
        for (Object[] row : versions.listVersionRows(repository.name, packageName)) {
          String version = (String) row[0];
          candidates.add(
              new GcCandidate(
                  repository.name,
                  packageName + "@" + version,
                  // The belt counts per package of one repository: two packages must not spend each
                  // other's slots, and neither must two registries holding the same name.
                  repository.name + "/" + packageName,
                  NpmSemver.parse(version).filter(parsed -> !parsed.isPrerelease()).isPresent(),
                  latest((Instant) row[2], (Instant) row[3]),
                  Set.of((String) row[1])));
        }
      }
    }
    return List.copyOf(candidates);
  }

  /**
   * Anything a dist-tag currently names, under the tag's own name.
   *
   * <p>Read once per run over the packages the enumeration touched, rather than per candidate: a
   * plan judged against two readings of {@code npm_dist_tag} could condemn a version the second
   * reading had just tagged.
   */
  @Override
  public GcPinned pinnedBy(List<GcCandidate> candidates, GcPins pins) {
    Map<String, String> tagged = new HashMap<>();
    for (String group : groupsOf(candidates)) {
      // A repository name cannot contain a slash and a scoped package name can, so the FIRST one is
      // the boundary the group was built with.
      int slash = group.indexOf('/');
      String repository = group.substring(0, slash);
      String packageName = group.substring(slash + 1);
      for (NpmDistTag tag : distTags.listTags(repository, packageName)) {
        tagged.putIfAbsent(repository + "/" + packageName + "@" + tag.version, tag.tag);
      }
    }
    return candidate -> {
      String tag = tagged.get(candidate.group() + "@" + versionOf(candidate.identity()));
      return tag == null ? null : keptByDistTag(tag);
    };
  }

  /**
   * Oldest release first, by semver precedence; ties on the identity so a report is stable across
   * runs.
   *
   * <p>Only released candidates reach this comparator, and a release parses by definition — but it
   * stays total anyway: a version that cannot be ordered sorts below every version that can, so an
   * unorderable coordinate can never displace a real release off the belt.
   */
  @Override
  public Comparator<GcCandidate> byAge() {
    return Comparator.comparing(
            (GcCandidate candidate) -> versionOf(candidate.identity()), BY_PRECEDENCE)
        .thenComparing(GcCandidate::identity);
  }

  /**
   * Each dead version through {@code NpmRegistryCollection.collect}, one transaction per row so a
   * refusal takes down its own identity and nothing else.
   */
  @Override
  public GcStrategy.Applied delete(GcStrategy.Plan plan, GcStrategy.GraceWindow grace) {
    List<GcIdentity> deleted = new ArrayList<>();
    List<GcIdentity> withheld = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    for (GcIdentity dead : plan.dead()) {
      // A scoped package starts with '@', so the LAST '@' is the separator; a version has none.
      int at = dead.identity().lastIndexOf('@');
      String packageName = dead.identity().substring(0, at);
      String version = dead.identity().substring(at + 1);
      try {
        NpmVersion row = versions.findOne(dead.repository(), packageName, version).orElse(null);
        if (row == null) {
          errors.add(dead.identity() + ": no such version row — the store moved since planning");
          continue;
        }
        if (grace.withinGrace(row.tarballBlobId)) {
          withheld.add(dead);
          continue;
        }
        npm.collect(dead.repository(), packageName, version);
        deleted.add(dead);
      } catch (RuntimeException failed) {
        errors.add(dead.identity() + ": " + failed.getMessage());
      }
    }
    return new GcStrategy.Applied(deleted, withheld, errors);
  }

  /** Every identity group the enumeration touched, in encounter order. */
  private static List<String> groupsOf(List<GcCandidate> candidates) {
    Map<String, Boolean> groups = new LinkedHashMap<>();
    for (GcCandidate candidate : candidates) {
      groups.putIfAbsent(candidate.group(), true);
    }
    return List.copyOf(groups.keySet());
  }

  /** A scoped package starts with {@code @}, so the LAST one separates it from the version. */
  private static String versionOf(String identity) {
    return identity.substring(identity.lastIndexOf('@') + 1);
  }

  /** Publication counts as the first access, so a version published minutes ago reads as young. */
  private static Instant latest(Instant created, Instant accessed) {
    return accessed == null || accessed.isBefore(created) ? created : accessed;
  }

  /** Semver precedence, lowest first, with anything unparseable below everything that parses. */
  static final Comparator<String> BY_PRECEDENCE =
      (left, right) -> {
        Optional<NpmSemver> parsedLeft = NpmSemver.parse(left);
        Optional<NpmSemver> parsedRight = NpmSemver.parse(right);
        if (parsedLeft.isEmpty() || parsedRight.isEmpty()) {
          return parsedLeft.isPresent() == parsedRight.isPresent()
              ? left.compareTo(right)
              : (parsedLeft.isPresent() ? 1 : -1);
        }
        return parsedLeft.get().compareTo(parsedRight.get());
      };
}
