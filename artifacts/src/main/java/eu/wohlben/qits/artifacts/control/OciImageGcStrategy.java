package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.dto.GcIdentity;
import eu.wohlben.qits.artifacts.entity.ArtifactRepository;
import eu.wohlben.qits.artifacts.entity.OciManifest;
import eu.wohlben.qits.artifacts.entity.OciTag;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.artifacts.persistence.OciManifestRepository;
import eu.wohlben.qits.artifacts.persistence.OciTagRepository;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Docker's rule: releases stay, what qits-cd could pull stays, and every other build coordinate goes.
 *
 * <p><b>This strategy shares no policy code with npm's, and the resemblance between them is a
 * coincidence to be left alone.</b> Both reduce to "releases stay, keep the newest build" in one
 * sentence — but a release here is a calver <em>tag beside a sha tag</em> rather than a version
 * string, "newest" is an {@code oci_tag} row's timestamp rather than semver precedence, and deleting
 * the wrong one surfaces as {@code IMAGE_MISSING} on a container restart hours later rather than as a
 * failed install. A base class between the two would make one system's edge case the other's silent
 * bug.
 *
 * <h2>What is kept, and the rule each keep is reported under</h2>
 *
 * <ol>
 *   <li><b>Calver tags, forever.</b> The release coordinate. In docker there is no {@code
 *       -main.g<sha>} suffix — the sha tag <em>is</em> the prerelease coordinate and a release adds a
 *       version tag beside it — so "releases stay" means exactly "a tag shaped like a calver version
 *       is never eligible". It costs near nothing: a release manifest shares its layers with its sha
 *       twin.
 *   <li><b>Every sha an ACTIVE qits-cd deployment pins.</b> The container was created from {@code
 *       <repository>/<application>:<sha>} and a restart pulls that reference again.
 *   <li><b>The previous distinct sha per application.</b> Rollback on this platform is "redeploy the
 *       previous sha", so the rollback target has to survive to be a rollback target. It is the most
 *       recent row, older than the ACTIVE one, whose sha <em>differs</em> from it — a redeploy of the
 *       same sha is not a previous version, and reading it as one would keep a duplicate and drop
 *       the real rollback target.
 *   <li><b>The newest sha tag per image.</b> The next deploy's pull, and the whole safety net for an
 *       image with no deployment row at all — {@code qits-spa-home} has one tag and cd has never
 *       deployed it.
 *   <li><b>Belt and braces: any tag that is neither a calver version nor a build sha.</b> Only build
 *       coordinates are ever condemned here. A tag this rule cannot classify is something nobody
 *       modelled, and the answer to an unmodelled coordinate is to keep it and report why.
 * </ol>
 *
 * <p>Matching a pin to a tag is <b>exact string equality</b>, never a prefix. The store holds one
 * stray abbreviated tag ({@code qits-observability:2994a5e}, pushed once beside its full form) and it
 * classifies as a build sha and dies with them, which is what the GC plan asks for: nothing on this
 * platform pulls an abbreviated reference, so keeping it by prefix would keep a coordinate no
 * deployment can use.
 *
 * <h2>What dies</h2>
 *
 * <p>Build-sha tags outside the keep-set, and then every manifest row <b>no kept tag reaches</b>.
 * Reachability is the census's own closure ({@link OciManifestFootprints}, which walks an index's
 * children), so a child manifest of a kept index survives without being tagged. That second half is
 * what collects the store's 73 untagged manifests: a tag re-push moves the tag row and leaves the old
 * manifest row behind, reachable from no coordinate anyone uses.
 *
 * <h2>Why this is not pure, and why that is the seam working rather than the seam breaking</h2>
 *
 * <p>{@link #plan} performs an HTTP fetch: the pin list is read from qits-cd <em>at plan time</em>,
 * every time, because a cached one is a plan on stale facts and a plan on stale facts deletes a
 * running image. {@link CdDeploymentPins} is where that lives; when it throws, this method lets the
 * throw out, which is the interface's documented fail-closed answer — the planner reports the type as
 * failed and the sweep keeps every OCI blob the census found.
 *
 * <p>{@link #plan} still deletes nothing — a report reads it. Deletion is {@link #apply}, invoked
 * only by the executor behind {@code POST /artifacts/api/gc/sweep}, on a plan computed in the same
 * request. Tag rows go through {@code OciRegistryService.collectTag}, manifest rows through {@code
 * collectManifest} (which refuses a manifest a tag still names — the mechanism's own belt), and an
 * identity whose released blobs are still inside the grace window is withheld whole, rows intact,
 * because a deleted row over an in-grace blob would strand the blob as row-less and untouchable.
 *
 * <p>{@code @Singleton} rather than the {@code @ApplicationScoped} everything else here carries, and
 * the reason is the report: {@link GcPlanner} names the strategy by {@code
 * getClass().getSimpleName()}, and a normal-scoped bean answers that through its client proxy — the
 * type a reviewer would read is {@code OciImageGcStrategy_ClientProxy}. {@code @Singleton} is a
 * pseudo-scope, so there is no proxy and still exactly one instance. The name is asserted, so this
 * cannot quietly regress.
 */
@Singleton
public class OciImageGcStrategy implements GcStrategy {

  /**
   * The release coordinate: {@code <year>.<month><day>.<time>}, e.g. {@code 2026.801.85448}. Matched
   * before the sha shape, so a version can never be read as an abbreviated digest.
   */
  private static final Pattern CALVER = Pattern.compile("\\d{4}\\.\\d{1,4}\\.\\d+");

  /**
   * The per-build coordinate: a lowercase hex commit sha, full or abbreviated. post-receive pushes
   * {@code :$QITS_CI_SHA} per commit, which is the 40-hex form; the range starts at 7 so the one
   * stray short tag is classified as the build coordinate it is rather than kept as a mystery.
   */
  private static final Pattern BUILD_SHA = Pattern.compile("[0-9a-f]{7,40}");

  private static final String ACTIVE = "ACTIVE";

  static final String KEPT_RELEASE = "calver release tag — releases are never eligible";
  static final String KEPT_ACTIVE = "an ACTIVE qits-cd deployment pins this sha";
  static final String KEPT_ROLLBACK =
      "qits-cd's rollback target — the previous distinct sha for this application";
  static final String KEPT_NEWEST = "this image's newest build — the next deployment's pull target";
  static final String KEPT_UNCLASSIFIED =
      "neither a calver release nor a build sha; an unmodelled coordinate is kept, not guessed at";
  static final String DEAD_TAG =
      "build sha: no qits-cd deployment pins it and it is not this image's newest build";
  static final String DEAD_MANIFEST = "manifest reached by no kept tag";

  @Inject ArtifactRepositoryRepository repositories;
  @Inject OciTagRepository tags;
  @Inject OciManifestRepository manifests;
  @Inject OciManifestFootprints footprints;
  @Inject CdDeploymentPins pins;
  @Inject OciRegistryService registry;

  @Override
  public RepositoryType type() {
    return RepositoryType.OCI_IMAGES;
  }

  @Override
  public Plan plan(LiveBlobCensus.Census census) {
    // First, and unconditionally: an unreachable cd must abort a plan over an empty store exactly as
    // it aborts one over a full store, or the fail-closed posture depends on what happens to be
    // pushed.
    Pinned pinned = Pinned.from(pins.deployments());

    List<GcIdentity> dead = new ArrayList<>();
    List<GcIdentity> kept = new ArrayList<>();
    Set<String> released = new HashSet<>();
    Set<String> retained = new HashSet<>();

    for (ArtifactRepository repository : repositories.listAll()) {
      if (repository.type != RepositoryType.OCI_IMAGES) {
        continue;
      }
      for (String image : manifests.listImageNames(repository.name)) {
        collect(repository.name, image, pinned, dead, kept, released, retained);
      }
    }

    dead.sort(BY_IDENTITY);
    kept.sort(BY_IDENTITY);
    return dead.isEmpty() ? Plan.nothingDies(kept, retained) : new Plan(dead, kept, released, retained);
  }

  /**
   * The execute half: tag rows first, then manifest rows, both parsed back out of identities this
   * class spelled itself minutes ago — {@code image:tag} and {@code image@sha256:digest}, formats
   * {@link #collect} owns end to end.
   *
   * <p>Tags before manifests is load-bearing: a dead tag over a dead manifest must lose its row
   * before {@code collectManifest}'s a-tag-still-names-it belt looks. The two condemn the same
   * closure, so the grace gate always gives them the same answer — withheld together or deleted
   * together, never a tag left dangling over a removed manifest.
   */
  @Override
  public Applied apply(Plan plan, GraceWindow grace) {
    List<GcIdentity> deleted = new ArrayList<>();
    List<GcIdentity> withheld = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    for (GcIdentity dead : plan.dead()) {
      if (DEAD_TAG.equals(dead.rule())) {
        applyTag(dead, grace, deleted, withheld, errors);
      }
    }
    for (GcIdentity dead : plan.dead()) {
      if (DEAD_MANIFEST.equals(dead.rule())) {
        applyManifest(dead, grace, deleted, withheld, errors);
      } else if (!DEAD_TAG.equals(dead.rule())) {
        errors.add(dead.identity() + ": condemned under an unknown rule, refusing to apply it");
      }
    }
    return new Applied(deleted, withheld, errors);
  }

  private void applyTag(
      GcIdentity dead,
      GraceWindow grace,
      List<GcIdentity> deleted,
      List<GcIdentity> withheld,
      List<String> errors) {
    // An image name cannot contain a colon, so the last one separates image from tag.
    int colon = dead.identity().lastIndexOf(':');
    String image = dead.identity().substring(0, colon);
    String tagName = dead.identity().substring(colon + 1);
    try {
      OciTag row = tags.findOne(dead.repository(), image, tagName).orElse(null);
      if (row == null) {
        errors.add(dead.identity() + ": no such tag row — the store moved since planning");
        return;
      }
      if (anyWithinGrace(dead.repository(), image, row.manifestDigest, grace)) {
        withheld.add(dead);
        return;
      }
      registry.collectTag(dead.repository(), image, tagName);
      deleted.add(dead);
    } catch (RuntimeException failed) {
      errors.add(dead.identity() + ": " + failed.getMessage());
    }
  }

  private void applyManifest(
      GcIdentity dead,
      GraceWindow grace,
      List<GcIdentity> deleted,
      List<GcIdentity> withheld,
      List<String> errors) {
    int at = dead.identity().lastIndexOf("@sha256:");
    String image = dead.identity().substring(0, at);
    String digest = dead.identity().substring(at + "@sha256:".length());
    try {
      if (anyWithinGrace(dead.repository(), image, digest, grace)) {
        withheld.add(dead);
        return;
      }
      registry.collectManifest(dead.repository(), image, digest);
      deleted.add(dead);
    } catch (RuntimeException failed) {
      errors.add(dead.identity() + ": " + failed.getMessage());
    }
  }

  /**
   * Whether any blob the manifest's closure releases is still inside the grace window — the whole
   * gate on identity deletion. A manifest row that is already gone gates on nothing.
   */
  private boolean anyWithinGrace(
      String repository, String image, String digest, GraceWindow grace) {
    OciManifest manifest = manifests.findOne(repository, image, digest).orElse(null);
    if (manifest == null) {
      return false;
    }
    return footprints.of(manifest).keySet().stream().anyMatch(grace::withinGrace);
  }

  private void collect(
      String repository,
      String image,
      Pinned pinned,
      List<GcIdentity> dead,
      List<GcIdentity> kept,
      Set<String> released,
      Set<String> retained) {
    List<OciTag> imageTags = tags.listByImage(repository, image);
    Map<String, OciManifest> byDigest = new LinkedHashMap<>();
    for (OciManifest manifest : manifests.listByImage(repository, image)) {
      byDigest.put(manifest.digest, manifest);
    }

    String newestBuild = newestBuildTag(imageTags);
    List<OciTag> keptTags = new ArrayList<>();
    List<OciTag> deadTags = new ArrayList<>();
    for (OciTag tag : imageTags) {
      String rule = keepRule(tag.tag, image, newestBuild, pinned);
      if (rule == null) {
        deadTags.add(tag);
        dead.add(new GcIdentity(repository, image + ":" + tag.tag, DEAD_TAG));
      } else {
        keptTags.add(tag);
        kept.add(new GcIdentity(repository, image + ":" + tag.tag, rule));
      }
    }

    // Everything a kept tag reaches, index children included. This set is both the survivors' blob
    // set and the test for whether a manifest row survives: a row whose digest is in it is reached.
    Set<String> reachable = new HashSet<>();
    for (OciTag tag : keptTags) {
      OciManifest manifest = byDigest.get(tag.manifestDigest);
      if (manifest != null) {
        reachable.addAll(footprints.of(manifest).keySet());
      }
    }
    retained.addAll(reachable);

    for (OciManifest manifest : byDigest.values()) {
      if (reachable.contains(manifest.digest)) {
        continue;
      }
      dead.add(new GcIdentity(repository, image + "@sha256:" + manifest.digest, DEAD_MANIFEST));
      released.addAll(footprints.of(manifest).keySet());
    }
    // A dying tag releases what its manifest named even when that manifest survives on another tag.
    // Reporting both sides is the point of the seam: the substrate subtracts, this never does.
    for (OciTag tag : deadTags) {
      OciManifest manifest = byDigest.get(tag.manifestDigest);
      if (manifest != null) {
        released.addAll(footprints.of(manifest).keySet());
      }
    }
  }

  /** The keeping rule's name, or null when the tag is a build coordinate nothing needs. */
  private static String keepRule(String tag, String image, String newestBuild, Pinned pinned) {
    if (CALVER.matcher(tag).matches()) {
      return KEPT_RELEASE;
    }
    if (!BUILD_SHA.matcher(tag).matches()) {
      return KEPT_UNCLASSIFIED;
    }
    if (pinned.active(image).contains(tag)) {
      return KEPT_ACTIVE;
    }
    if (pinned.previous(image).contains(tag)) {
      return KEPT_ROLLBACK;
    }
    return tag.equals(newestBuild) ? KEPT_NEWEST : null;
  }

  /**
   * The image's most recently written build tag, by {@code oci_tag.updated_at} — the row a re-push
   * moves, which is what makes it the answer to "what would the next deploy pull". Ties break on the
   * tag name so a report is stable across runs rather than on row order.
   */
  private static String newestBuildTag(List<OciTag> imageTags) {
    return imageTags.stream()
        .filter(tag -> !CALVER.matcher(tag.tag).matches() && BUILD_SHA.matcher(tag.tag).matches())
        .max(Comparator.comparing((OciTag tag) -> tag.updatedAt).thenComparing(tag -> tag.tag))
        .map(tag -> tag.tag)
        .orElse(null);
  }

  private static final Comparator<GcIdentity> BY_IDENTITY =
      Comparator.comparing(GcIdentity::repository).thenComparing(GcIdentity::identity);

  /**
   * The two pin sets, per image name, read off cd's rows and off nothing else.
   *
   * <p>Grouped by cd's {@code applicationId} rather than by name: an application belongs to an
   * environment, so one service running in two environments is two applications sharing one image
   * name, and both of their ACTIVE shas pin that image.
   */
  private record Pinned(Map<String, Set<String>> active, Map<String, Set<String>> previous) {

    static Pinned from(List<CdDeploymentPins.Deployment> deployments) {
      Map<String, List<CdDeploymentPins.Deployment>> byApplication = new LinkedHashMap<>();
      for (CdDeploymentPins.Deployment row : deployments) {
        byApplication.computeIfAbsent(row.applicationId(), id -> new ArrayList<>()).add(row);
      }
      Map<String, Set<String>> active = new HashMap<>();
      Map<String, Set<String>> previous = new HashMap<>();
      byApplication.values().forEach(rows -> read(rows, active, previous));
      return new Pinned(active, previous);
    }

    /** One application's rows, newest-first: its ACTIVE sha and the newest different sha under it. */
    private static void read(
        List<CdDeploymentPins.Deployment> rows,
        Map<String, Set<String>> active,
        Map<String, Set<String>> previous) {
      int at = -1;
      for (int i = 0; i < rows.size() && at < 0; i++) {
        if (ACTIVE.equals(rows.get(i).status())) {
          at = i;
        }
      }
      if (at < 0) {
        // Nothing is serving this application, so nothing is pinned and nothing is a rollback
        // target. The newest-build rule is what keeps such an image reachable.
        return;
      }
      CdDeploymentPins.Deployment serving = rows.get(at);
      active.computeIfAbsent(serving.application(), image -> new HashSet<>()).add(serving.commitSha());
      for (CdDeploymentPins.Deployment older : rows.subList(at + 1, rows.size())) {
        if (!older.commitSha().equals(serving.commitSha())) {
          previous
              .computeIfAbsent(serving.application(), image -> new HashSet<>())
              .add(older.commitSha());
          return;
        }
      }
    }

    Set<String> active(String image) {
      return active.getOrDefault(image, Set.of());
    }

    Set<String> previous(String image) {
      return previous.getOrDefault(image, Set.of());
    }
  }
}
