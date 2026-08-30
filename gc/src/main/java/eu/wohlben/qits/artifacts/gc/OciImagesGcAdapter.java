package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.control.OciManifestFootprints;
import eu.wohlben.qits.artifacts.control.OciRegistryCollection;
import eu.wohlben.qits.blobstore.entity.ArtifactRepository;
import eu.wohlben.qits.artifacts.entity.OciManifest;
import eu.wohlben.qits.artifacts.entity.OciTag;
import eu.wohlben.qits.artifacts.control.OciImagesProfile;
import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import eu.wohlben.qits.blobstore.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.artifacts.persistence.OciManifestRepository;
import eu.wohlben.qits.artifacts.persistence.OciTagRepository;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The platform's own images, as facts: what a coordinate is, what a release is, which of two
 * releases is newer, what qits-platform-deployments would pull, and how a row goes.
 *
 * <h2>What a release is here</h2>
 *
 * <p>A <b>calver tag</b>. In docker there is no {@code -main.g<sha>} suffix — the sha tag <em>is</em>
 * the prerelease coordinate and a release adds a version tag beside it — so "the last 2 released
 * versions of every image" means exactly "the two newest tags shaped like a calver version". Newer
 * is the version's own order ({@link #BY_CALVER}) rather than a row timestamp, because a release
 * pulled last week is not thereby a newer release than one cut yesterday.
 *
 * <h2>The one belt this type derives itself: the newest build tag per image</h2>
 *
 * <p>{@link #pinnedBy} keeps every sha qits-platform-deployments names <b>and</b> each image's most recently written
 * build tag. The second is a pin in all but name — the pull the <em>next</em> deploy will make —
 * and it is here rather than in cd because cd cannot answer for a deployment that has not happened:
 * {@code qits-spa-home} has tags and not a single deployment row, and without this line its whole
 * image would be eligible and the next deploy would pull a tag this run deleted. That is the
 * measured {@code IMAGE_MISSING} hazard, and it is one line.
 *
 * <p>"Most recently written" is {@code oci_tag.updated_at}, the row a re-push moves — deliberately
 * <em>not</em> the candidate's effective access time, which a pull of an older sha would lift. A
 * cold, never-deployed image is exactly the case this belt exists for, so reading a pull into it
 * would disarm it precisely where it is needed.
 *
 * <h2>What is a candidate, and what is not</h2>
 *
 * <p>Every tag, and every manifest that <b>no tag names and no tagged manifest reaches</b>. The
 * second half is the mirror adapter's rule for the mirror adapter's reason: a tagged manifest's
 * identity is its tag, and a child of a tagged index is reached through that index's closure, so
 * neither is an identity of its own. What is left is the store's measured 73 untagged manifests — a
 * tag re-push moves the tag row to the new manifest and leaves the old row reachable from no
 * coordinate anyone uses — and they now age out on access like anything else.
 *
 * <p>A manifest under a tag this run condemns is therefore collected on the <em>next</em> run, once
 * it is untagged. One run, one class of identity; the alternative is a two-phase rule inside a
 * single plan, which is the shape the engine seam exists to refuse.
 *
 * <p><b>Effective access is {@code max(updated/created, accessed_at)}</b>, from V9's columns. A
 * tag's non-access timestamp is {@code updated_at} — the moment those bytes became this tag's — and
 * creation counting as a first access is what stops a tag pushed ten minutes ago from reading as
 * never-pulled.
 *
 * <p>Deletion runs through the registry's own funnel ({@code OciRegistryCollection}), tags before
 * manifests, exactly as {@code OciMirrorGcAdapter} does it: nothing in that funnel reads a
 * repository's type, so both OCI types have always come through the same door. The code is written
 * out twice rather than shared, which is this module's standing rule — two types may share an
 * engine the user picked for both, and they still own their own facts.
 */
@Singleton
public class OciImagesGcAdapter implements GcTypeAdapter {

  /**
   * The release coordinate: {@code <year>.<month><day>.<time>}, e.g. {@code 2026.801.85448}. Matched
   * before the sha shape, so a version can never be read as an abbreviated digest.
   */
  static final Pattern CALVER = Pattern.compile("\\d{4}\\.\\d{1,4}\\.\\d+");

  /**
   * The per-build coordinate: a lowercase hex commit sha, full or abbreviated. post-receive pushes
   * {@code :$QITS_CI_SHA} per commit, which is the 40-hex form; the range starts at 7 so the one
   * stray short tag ({@code qits-observability:2994a5e}) is classified as the build coordinate it is
   * rather than kept as a mystery.
   */
  static final Pattern BUILD_SHA = Pattern.compile("[0-9a-f]{7,40}");

  /** The digest form the untagged-manifest identities are spelled with. */
  static final String DIGEST_PREFIX = "@sha256:";

  /** The belt this type derives for itself, named so a report says which pull it protects. */
  static final String KEPT_NEWEST = "this image's newest build — the next deployment's pull target";

  @Inject ArtifactRepositoryRepository repositories;
  @Inject OciTagRepository tags;
  @Inject OciManifestRepository manifests;
  @Inject OciManifestFootprints footprints;
  @Inject OciRegistryCollection registry;

  @Override
  public String type() {
    return OciImagesProfile.KEY;
  }

  @Override
  public List<GcCandidate> enumerate() {
    List<GcCandidate> candidates = new ArrayList<>();
    for (ArtifactRepository repository : repositories.listAll()) {
      if (!OciImagesProfile.KEY.equals(repository.type)) {
        continue;
      }
      for (String image : manifests.listImageNames(repository.name)) {
        collect(repository.name, image, candidates);
      }
    }
    return List.copyOf(candidates);
  }

  /**
   * The two coordinate pins: every sha qits-platform-deployments holds, and each image's newest build tag.
   *
   * <p>The newest build is read off the tag rows rather than off the candidates, for the reason the
   * class javadoc gives — a candidate carries its access time, and a pull of an older sha must not
   * be able to move this belt.
   */
  @Override
  public GcPinned pinnedBy(List<GcCandidate> candidates, GcPins pins) {
    Map<String, String> newestBuild = new HashMap<>();
    for (String group : groupsOf(candidates)) {
      // A repository name cannot contain a slash and an image name can, so the FIRST one is the
      // boundary the group was built with.
      int slash = group.indexOf('/');
      String newest =
          newestBuildTag(tags.listByImage(group.substring(0, slash), group.substring(slash + 1)));
      if (newest != null) {
        newestBuild.put(group, newest);
      }
    }
    return candidate -> {
      if (isManifest(candidate.identity())) {
        return null;
      }
      int colon = candidate.identity().lastIndexOf(':');
      String image = candidate.identity().substring(0, colon);
      String tag = candidate.identity().substring(colon + 1);
      String byCd = pins.pinsImageTag(image, tag);
      if (byCd != null) {
        return byCd;
      }
      return tag.equals(newestBuild.get(candidate.group())) ? KEPT_NEWEST : null;
    };
  }

  /**
   * Oldest release first, by the calver version's own order; ties on the identity so a report is
   * stable across runs.
   *
   * <p>Only released candidates ever reach this comparator ({@link OwnArtifactsStrategy} sorts a
   * group's releases and nothing else), so it only has to be right about calver tags. It is total
   * anyway: anything it cannot read as a version sorts below everything it can, which keeps an
   * unreadable coordinate from displacing a real release off the belt.
   */
  @Override
  public Comparator<GcCandidate> byAge() {
    return Comparator.comparing(
            (GcCandidate candidate) -> tagOf(candidate.identity()), BY_CALVER)
        .thenComparing(GcCandidate::identity);
  }

  /**
   * Tags first, then manifests, and the order is load-bearing: a dead tag over a dead manifest must
   * lose its row before {@code collectManifest}'s a-tag-still-names-it belt looks. The two cannot
   * both be condemned in one run here — a tagged manifest is never a candidate — but the order costs
   * nothing and removes the question.
   */
  @Override
  public GcStrategy.Applied delete(GcStrategy.Plan plan, GcStrategy.GraceWindow grace) {
    List<GcIdentity> deleted = new ArrayList<>();
    List<GcIdentity> withheld = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    for (GcIdentity dead : plan.dead()) {
      if (!isManifest(dead.identity())) {
        deleteTag(dead, grace, deleted, withheld, errors);
      }
    }
    for (GcIdentity dead : plan.dead()) {
      if (isManifest(dead.identity())) {
        deleteManifest(dead, grace, deleted, withheld, errors);
      }
    }
    return new GcStrategy.Applied(deleted, withheld, errors);
  }

  /** One image: every tag, then every manifest no kept coordinate can reach. */
  private void collect(String repository, String image, List<GcCandidate> candidates) {
    Map<String, OciManifest> byDigest = new LinkedHashMap<>();
    for (OciManifest manifest : manifests.listByImage(repository, image)) {
      byDigest.put(manifest.digest, manifest);
    }
    Set<String> tagged = new HashSet<>();
    Set<String> reachableFromTags = new HashSet<>();
    for (OciTag tag : tags.listByImage(repository, image)) {
      tagged.add(tag.manifestDigest);
      Set<String> blobs = blobsOf(byDigest.get(tag.manifestDigest));
      reachableFromTags.addAll(blobs);
      candidates.add(
          new GcCandidate(
              repository,
              image + ":" + tag.tag,
              group(repository, image),
              CALVER.matcher(tag.tag).matches(),
              latest(tag.updatedAt, tag.accessedAt),
              blobs));
    }
    for (OciManifest manifest : byDigest.values()) {
      // A tagged manifest's identity is its tag; an index child's is the index that names it.
      // Neither is an identity of its own, and enumerating one would let the window condemn a
      // manifest a live coordinate reaches.
      if (tagged.contains(manifest.digest) || reachableFromTags.contains(manifest.digest)) {
        continue;
      }
      candidates.add(
          new GcCandidate(
              repository,
              image + DIGEST_PREFIX + manifest.digest,
              group(repository, image),
              false,
              latest(manifest.createdAt, manifest.accessedAt),
              footprints.of(manifest).keySet()));
    }
  }

  /**
   * The identity group the release belt counts within: one image of one repository.
   *
   * <p>Qualified by the repository because the belt is a count and two repositories holding the same
   * image name would otherwise spend each other's slots.
   */
  private static String group(String repository, String image) {
    return repository + "/" + image;
  }

  /** Every identity group the enumeration touched, in encounter order. */
  private static Set<String> groupsOf(List<GcCandidate> candidates) {
    Set<String> groups = new LinkedHashSet<>();
    for (GcCandidate candidate : candidates) {
      groups.add(candidate.group());
    }
    return groups;
  }

  /**
   * The image's most recently written build tag by {@code oci_tag.updated_at}, or null when it has
   * none. Ties break on the tag name so a report is stable across runs rather than on row order.
   */
  private static String newestBuildTag(List<OciTag> imageTags) {
    return imageTags.stream()
        .filter(tag -> !CALVER.matcher(tag.tag).matches() && BUILD_SHA.matcher(tag.tag).matches())
        .max(Comparator.comparing((OciTag tag) -> tag.updatedAt).thenComparing(tag -> tag.tag))
        .map(tag -> tag.tag)
        .orElse(null);
  }

  /** Everything a manifest reaches, or nothing when the row it named is already gone. */
  private Set<String> blobsOf(OciManifest manifest) {
    return manifest == null ? Set.of() : footprints.of(manifest).keySet();
  }

  /** Creation counts as the first access, so a tag pushed minutes ago reads as young. */
  private static Instant latest(Instant created, Instant accessed) {
    return accessed == null || accessed.isBefore(created) ? created : accessed;
  }

  private static boolean isManifest(String identity) {
    return identity.contains(DIGEST_PREFIX);
  }

  /** The tag half of an {@code image:tag} identity; a manifest identity has none and answers whole. */
  private static String tagOf(String identity) {
    return isManifest(identity) ? identity : identity.substring(identity.lastIndexOf(':') + 1);
  }

  /**
   * Calver order over the three numeric parts, oldest first — {@code 2026.801.85447} below {@code
   * 2026.801.85448} and {@code 2026.1201.5} above both, which a lexical comparison gets backwards.
   * Anything that is not a calver version sorts below every version that is.
   */
  static final Comparator<String> BY_CALVER =
      (left, right) -> {
        boolean leftIsVersion = CALVER.matcher(left).matches();
        boolean rightIsVersion = CALVER.matcher(right).matches();
        if (!leftIsVersion || !rightIsVersion) {
          return leftIsVersion == rightIsVersion
              ? left.compareTo(right)
              : (leftIsVersion ? 1 : -1);
        }
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        for (int part = 0; part < leftParts.length; part++) {
          int compared = Long.compare(Long.parseLong(leftParts[part]), Long.parseLong(rightParts[part]));
          if (compared != 0) {
            return compared;
          }
        }
        return 0;
      };

  private void deleteTag(
      GcIdentity dead,
      GcStrategy.GraceWindow grace,
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

  private void deleteManifest(
      GcIdentity dead,
      GcStrategy.GraceWindow grace,
      List<GcIdentity> deleted,
      List<GcIdentity> withheld,
      List<String> errors) {
    int at = dead.identity().lastIndexOf(DIGEST_PREFIX);
    String image = dead.identity().substring(0, at);
    String digest = dead.identity().substring(at + DIGEST_PREFIX.length());
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
   * Whether any blob the manifest's closure releases is still inside the grace window — the gate on
   * identity deletion, because a row deleted over a young file would strand that file row-less and
   * therefore untouchable forever. A manifest row already gone gates on nothing.
   */
  private boolean anyWithinGrace(
      String repository, String image, String digest, GcStrategy.GraceWindow grace) {
    OciManifest manifest = manifests.findOne(repository, image, digest).orElse(null);
    if (manifest == null) {
      return false;
    }
    return footprints.of(manifest).keySet().stream().anyMatch(grace::withinGrace);
  }
}
