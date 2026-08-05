package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.control.LiveBlobCensus;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * npm's rule: every release stays forever, one main build stays per package, and the superseded main
 * builds go.
 *
 * <p><b>This strategy shares no policy code with docker's, and the resemblance is a coincidence to
 * leave alone.</b> Both fit in the sentence "releases stay, keep the newest build" and nothing below
 * that sentence matches. Here a release is a <em>version string with no prerelease part</em> rather
 * than a tag beside another tag; "newest" is semver precedence rather than a row's timestamp; and
 * deleting the wrong one breaks an {@code npm install} of a pinned range immediately and re-opens a
 * version name for republishing forever — which is why this type has a tombstone ({@code
 * NpmRegistryCollection.collect}) and docker has nothing like one. A base class over the two would make
 * each system's edge case the other's silent bug.
 *
 * <h2>What is kept, and the rule each keep is reported under</h2>
 *
 * <ol>
 *   <li><b>Every unsuffixed version, forever.</b> A version with no prerelease part is a release,
 *       and consumers pin ranges — {@code ^2026.801.85149} has to keep resolving, including to the
 *       pre-calver {@code 0.0.x} line. Nothing about a release makes it eligible, ever: not age, not
 *       a newer release beside it, not the absence of a dist-tag naming it.
 *   <li><b>The newest main build per package.</b> The platform publishes one prerelease shape,
 *       {@code <version>-main.g<sha7>}, one per push to main, and the newest of them is what {@code
 *       npm install <pkg>@main} resolves to. Newest is decided by semver precedence
 *       ({@link NpmSemver}), not by insertion order: {@code 2026.801.85149-main.gd43d710} outranks
 *       {@code 2026.801.85149-main.g21655ba} because the sha identifiers compare as ASCII, and both
 *       outrank anything on a lower core version.
 *   <li><b>Anything a dist-tag currently names</b> — belt and braces. Today it changes no outcome
 *       ({@code main} names the newest build, {@code latest} a release), and that is exactly when a
 *       backstop is worth having: a packument whose {@code dist-tags} names a version its {@code
 *       versions} does not list is a broken package to every npm client, and no ordering rule should
 *       be the only thing standing between here and there.
 *   <li><b>Prereleases this registry's conventions do not model</b>, such as an {@code -rc.1}. Only
 *       main builds are ever condemned. A prerelease of an unrecognised shape is something nobody
 *       modelled, and the answer to an unmodelled coordinate is to keep it and say so.
 *   <li><b>Versions that are not semver at all.</b> A version that cannot be ordered cannot be
 *       proved superseded, and this is the same strictness {@code requireLatestMayMoveTo} already
 *       applies to the {@code latest} guard.
 * </ol>
 *
 * <h2>What dies</h2>
 *
 * <p>Main builds that are neither their package's newest nor named by a dist-tag. Nothing else — no
 * manifest closure, no second pass, because an npm version has exactly one blob and the packument is
 * assembled from the surviving rows at read time, so removing a row removes the version from the
 * document with nothing left to rewrite.
 *
 * <h2>Scope, and what it deliberately excludes</h2>
 *
 * <p>{@code npm-packages} only. The proxy repositories share the {@code npm_version} table and are
 * <b>not</b> touched: their content is a cache of upstream, so its policy is eviction rather than
 * retention and the plan parks it. The planner's "no strategy registered for npm-proxy" line is the
 * honest report of that, and claiming the type here to say "nothing to do" would replace a real
 * decision with a shrug.
 *
 * <p>This strategy needs no census: the census carries blobs, and the rules are about versions. It
 * reads {@code npm_version} and {@code npm_dist_tag} directly and answers in the census's vocabulary
 * — tarball blob ids — which is what the substrate reconciles over.
 *
 * <p>{@link #plan} still deletes nothing — a report reads it. Deletion is {@link #apply}, invoked
 * only by the executor behind {@code POST /artifacts/api/gc/sweep}, on a plan computed in the same
 * request. Every row goes through {@code NpmRegistryCollection.collect}, which writes the republish
 * tombstone in the same transaction and refuses a version a dist-tag still names — both guarantees
 * are the mechanism's, so no path around them exists to forget. An identity whose tarball is still
 * inside the grace window is withheld whole, row intact, because a deleted row over an in-grace
 * blob would strand the blob as row-less and untouchable.
 *
 * <p>{@code @Singleton} rather than {@code @ApplicationScoped}, for the report's sake: a
 * normal-scoped bean answers {@code getClass().getSimpleName()} through its client proxy. {@link
 * GcPlanner} unwraps proxies now, so this is belt and braces too — one instance, no proxy, and the
 * name in the report is this class's.
 */
@Singleton
public class NpmPackagesGcStrategy implements GcStrategy {

  /**
   * The main-build suffix, as its two parsed prerelease identifiers: {@code main} and an
   * abbreviated commit sha. The convention publishes seven hex characters; the range runs to a full
   * sha so a longer abbreviation is still recognised as the build coordinate it is rather than kept
   * as a mystery.
   */
  private static final String MAIN = "main";

  private static final Pattern BUILD_SHA = Pattern.compile("g[0-9a-f]{7,40}");

  static final String KEPT_RELEASE =
      "release version — no prerelease part, so consumers' ranges resolve to it; releases are never"
          + " eligible";
  static final String KEPT_NEWEST_BUILD =
      "this package's newest main build by semver precedence — what @main resolves to";
  static final String KEPT_UNMODELLED =
      "a prerelease shape this registry does not model; an unmodelled coordinate is kept, not"
          + " guessed at";
  static final String KEPT_UNORDERABLE =
      "not a semver version, so it cannot be proved superseded";
  static final String DEAD_BUILD =
      "superseded main build: a newer one exists and no dist-tag names it";

  /** The belt-and-braces keep, naming the tag so a reviewer can see which pointer saved it. */
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

  /**
   * The execute half: each dead version through {@code NpmRegistryCollection.collect}, one
   * transaction per row so a refusal takes down its own identity and nothing else.
   *
   * <p>The identity is parsed back out of the spelling this class produced minutes ago — {@code
   * <package>@<version>}. A scoped package starts with {@code @}, so the <em>last</em> {@code @}
   * is the separator; the version cannot contain one.
   */
  @Override
  public Applied apply(Plan plan, GraceWindow grace) {
    List<GcIdentity> deleted = new ArrayList<>();
    List<GcIdentity> withheld = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    for (GcIdentity dead : plan.dead()) {
      int at = dead.identity().lastIndexOf('@');
      String packageName = dead.identity().substring(0, at);
      String version = dead.identity().substring(at + 1);
      try {
        NpmVersion row =
            versions.findOne(dead.repository(), packageName, version).orElse(null);
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
    return new Applied(deleted, withheld, errors);
  }

  @Override
  public Plan plan(LiveBlobCensus.Census census) {
    List<GcIdentity> dead = new ArrayList<>();
    List<GcIdentity> kept = new ArrayList<>();
    Set<String> released = new HashSet<>();
    Set<String> retained = new HashSet<>();

    for (ArtifactRepository repository : repositories.listAll()) {
      if (repository.type != RepositoryType.NPM_PACKAGES) {
        continue;
      }
      for (String packageName : versions.listPackageNames(repository.name)) {
        collect(repository.name, packageName, dead, kept, released, retained);
      }
    }

    dead.sort(BY_IDENTITY);
    kept.sort(BY_IDENTITY);
    return dead.isEmpty()
        ? Plan.nothingDies(kept, retained)
        : new Plan(dead, kept, released, retained);
  }

  /** One package: classify every version, then let the newest main build save itself. */
  private void collect(
      String repository,
      String packageName,
      List<GcIdentity> dead,
      List<GcIdentity> kept,
      Set<String> released,
      Set<String> retained) {
    // Version to tarball blob, in the order the projection returns them (by version string), which
    // is what makes a report reproducible across runs.
    Map<String, String> tarballs = new LinkedHashMap<>();
    for (Object[] row : versions.listVersionRows(repository, packageName)) {
      tarballs.put((String) row[0], (String) row[1]);
    }
    Map<String, String> tagged = tagsByVersion(repository, packageName);
    String newestBuild = newestMainBuild(tarballs.keySet());

    tarballs.forEach(
        (version, blobId) -> {
          String rule = keepRule(version, newestBuild, tagged);
          String identity = packageName + "@" + version;
          if (rule == null) {
            dead.add(new GcIdentity(repository, identity, DEAD_BUILD));
            released.add(blobId);
          } else {
            kept.add(new GcIdentity(repository, identity, rule));
            retained.add(blobId);
          }
        });
  }

  /** The keeping rule's name, or null when the version is a superseded main build. */
  private static String keepRule(String version, String newestBuild, Map<String, String> tagged) {
    Optional<NpmSemver> parsed = NpmSemver.parse(version);
    if (parsed.isEmpty()) {
      return KEPT_UNORDERABLE;
    }
    if (!parsed.get().isPrerelease()) {
      return KEPT_RELEASE;
    }
    if (!isMainBuild(parsed.get())) {
      return KEPT_UNMODELLED;
    }
    if (version.equals(newestBuild)) {
      return KEPT_NEWEST_BUILD;
    }
    // Structural first, then the backstop: a report reads better when it names the rule that was
    // meant to save a version, and falls back to the pointer that saved it anyway.
    return tagged.containsKey(version) ? keptByDistTag(tagged.get(version)) : null;
  }

  /** {@code -main.g<sha>}, as the two identifiers the parser produces. */
  private static boolean isMainBuild(NpmSemver version) {
    List<String> prerelease = version.prerelease();
    return prerelease.size() == 2
        && MAIN.equals(prerelease.get(0))
        && BUILD_SHA.matcher(prerelease.get(1)).matches();
  }

  /**
   * The package's highest main build by semver precedence, or null when it has none.
   *
   * <p>Ties break on the version string. Two versions can tie only by differing in build metadata,
   * which the spec says has no precedence — so something has to choose, and choosing by name keeps
   * a report stable across runs rather than dependent on row order.
   */
  private static String newestMainBuild(Set<String> versions) {
    return versions.stream()
        .filter(version -> NpmSemver.parse(version).filter(NpmPackagesGcStrategy::isMainBuild).isPresent())
        .max(
            Comparator.comparing((String version) -> NpmSemver.parse(version).orElseThrow())
                .thenComparing(Comparator.naturalOrder()))
        .orElse(null);
  }

  /**
   * Version to the dist-tag naming it. Several tags can name one version; the first by tag name
   * wins the report line, and the keep is the same either way.
   */
  private Map<String, String> tagsByVersion(String repository, String packageName) {
    Map<String, String> tagged = new LinkedHashMap<>();
    for (NpmDistTag tag : distTags.listTags(repository, packageName)) {
      tagged.putIfAbsent(tag.version, tag.tag);
    }
    return tagged;
  }

  private static final Comparator<GcIdentity> BY_IDENTITY =
      Comparator.comparing(GcIdentity::repository).thenComparing(GcIdentity::identity);
}
