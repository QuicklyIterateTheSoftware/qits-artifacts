package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.control.OciManifestFootprints;
import eu.wohlben.qits.artifacts.control.OciRegistryCollection;
import eu.wohlben.qits.artifacts.entity.ArtifactRepository;
import eu.wohlben.qits.artifacts.entity.OciManifest;
import eu.wohlben.qits.artifacts.entity.OciTag;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import eu.wohlben.qits.artifacts.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.artifacts.persistence.OciManifestRepository;
import eu.wohlben.qits.artifacts.persistence.OciTagRepository;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The mirror's facts: what a cached identity is, when it was last pulled, and how a row goes.
 *
 * <p><b>Identity is the OCI model the mirror already has</b> — a cached tag, and a manifest no tag
 * names. Both, because both are what upstream drift leaves behind: a mutable tag like {@code jdk-25}
 * moves upstream, the next pull binds the tag to new bytes, and the manifest it used to name stays
 * as a row nobody can reach by any coordinate. Enumerating only tags would hide exactly the thing
 * that grows.
 *
 * <p><b>A manifest a tag names is not a candidate of its own.</b> Its tag is its identity; the row
 * dies when the tag does, on a later run once it is untagged. Listing both would let the window
 * condemn a manifest under a live tag — which {@code collectManifest} refuses anyway, so the only
 * outcome would be an error column nobody can act on.
 *
 * <p><b>A child of a cached index is a candidate, and evicting one is not a corruption.</b> A pull
 * arrives index-first and fetches children lazily, one per architecture actually asked for, so a
 * mirror index referencing a child with no local row is the normal state of a partially-pulled
 * image. An architecture nobody has pulled in a month therefore ages out and is re-fetched on the
 * next miss, which is the whole cache bargain. The child's <em>bytes</em> outlive its row for as
 * long as the index needs them: the census reaches them through the index's closure and the
 * surviving tag retains them, so the sweep never unlinks a blob a live index still names.
 *
 * <p><b>Effective access is {@code max(created/updated, accessed_at)}</b>, from V9's columns on
 * {@code oci_tag} and {@code oci_manifest}. A tag's non-access timestamp is {@code updated_at},
 * which is what a re-push or an upstream rebind moves — the moment those bytes became this tag's,
 * and the honest floor for "how long has nobody wanted this".
 *
 * <p>Deletion runs through the registry's own funnel ({@code OciRegistryCollection}), which is where
 * the {@code oci_mirror_tag_check} freshness row is removed with its tag — see {@code
 * OciRegistryService.collectTag}. That row's cleanup belongs to the funnel rather than here for the
 * usual reason: a second caller cannot forget what has no second way in.
 */
@Singleton
public class OciMirrorGcAdapter implements GcTypeAdapter {

  /** The digest form the identities here are spelled with, matching {@code OciImageGcStrategy}. */
  private static final String DIGEST_PREFIX = "@sha256:";

  @Inject ArtifactRepositoryRepository repositories;
  @Inject OciTagRepository tags;
  @Inject OciManifestRepository manifests;
  @Inject OciManifestFootprints footprints;
  @Inject OciRegistryCollection registry;

  @Override
  public RepositoryType type() {
    return RepositoryType.OCI_MIRROR;
  }

  @Override
  public List<GcCandidate> enumerate() {
    List<GcCandidate> candidates = new ArrayList<>();
    for (ArtifactRepository repository : repositories.listAll()) {
      if (repository.type != RepositoryType.OCI_MIRROR) {
        continue;
      }
      for (String image : manifests.listImageNames(repository.name)) {
        collect(repository.name, image, candidates);
      }
    }
    return List.copyOf(candidates);
  }

  /**
   * Oldest first by effective access, ties on the identity so a report is stable across runs.
   *
   * <p>Unread today — only {@link OwnArtifactsStrategy} asks, and no cache is configured onto it —
   * but answered honestly rather than left to throw: "which of these two is older" has a real answer
   * for a cache, and a stub here would be a trap for whoever configures a type across engines.
   */
  @Override
  public Comparator<GcCandidate> byAge() {
    return Comparator.comparing(GcCandidate::lastAccessAt).thenComparing(GcCandidate::identity);
  }

  /**
   * Tags first, then manifests, for {@code OciImageGcStrategy}'s reason: a dead tag over a dead
   * manifest has to lose its row before {@code collectManifest}'s a-tag-still-names-it belt looks.
   * The two cannot both be condemned in one run here — a tagged manifest is never enumerated — but
   * the order costs nothing and removes the question.
   */
  @Override
  public GcStrategy.Applied delete(GcStrategy.Plan plan, GcStrategy.GraceWindow grace) {
    List<GcIdentity> deleted = new ArrayList<>();
    List<GcIdentity> withheld = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    for (GcIdentity dead : plan.dead()) {
      if (!isManifest(dead)) {
        deleteTag(dead, grace, deleted, withheld, errors);
      }
    }
    for (GcIdentity dead : plan.dead()) {
      if (isManifest(dead)) {
        deleteManifest(dead, grace, deleted, withheld, errors);
      }
    }
    return new GcStrategy.Applied(deleted, withheld, errors);
  }

  /** One namespace's image: every cached tag, then every manifest no tag names. */
  private void collect(String repository, String image, List<GcCandidate> candidates) {
    Map<String, OciManifest> byDigest = new LinkedHashMap<>();
    for (OciManifest manifest : manifests.listByImage(repository, image)) {
      byDigest.put(manifest.digest, manifest);
    }
    Set<String> tagged = new HashSet<>();
    for (OciTag tag : tags.listByImage(repository, image)) {
      tagged.add(tag.manifestDigest);
      candidates.add(
          new GcCandidate(
              repository,
              image + ":" + tag.tag,
              image,
              // Upstream's releases are not ours. A cache has no version protection to earn, and
              // saying so here rather than pattern-matching jdk-25 is what keeps the engine's "no
              // release rule" honest.
              false,
              latest(tag.updatedAt, tag.accessedAt),
              blobsOf(byDigest.get(tag.manifestDigest))));
    }
    for (OciManifest manifest : byDigest.values()) {
      if (tagged.contains(manifest.digest)) {
        continue;
      }
      candidates.add(
          new GcCandidate(
              repository,
              image + DIGEST_PREFIX + manifest.digest,
              image,
              false,
              latest(manifest.createdAt, manifest.accessedAt),
              footprints.of(manifest).keySet()));
    }
  }

  /** Everything a manifest reaches, or nothing when the row it named is already gone. */
  private Set<String> blobsOf(OciManifest manifest) {
    return manifest == null ? Set.of() : footprints.of(manifest).keySet();
  }

  /** Creation counts as the first access, so a tag cached minutes ago reads as young. */
  private static Instant latest(Instant created, Instant accessed) {
    return accessed == null || accessed.isBefore(created) ? created : accessed;
  }

  private static boolean isManifest(GcIdentity identity) {
    return identity.identity().contains(DIGEST_PREFIX);
  }

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
