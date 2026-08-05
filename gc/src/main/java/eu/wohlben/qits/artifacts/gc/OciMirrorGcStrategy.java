package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.control.LiveBlobCensus;
import eu.wohlben.qits.artifacts.control.OciDigest;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The mirror's rule: nothing dies, and the report says why rather than saying nothing.
 *
 * <p><b>Append-only is a decision, not a gap</b> (proxy-pulling-normal-images.md ⚖2). A cache's
 * eviction is access-based — "which of these has nobody pulled in a year" — and this store tracks no
 * access, so the honest choices were an eviction rule computed from something that is not access, or
 * keeping everything and saying so at a recorded price. The price is recorded: an estimated 1.5–2.5
 * GiB one-time fill for the platform's real base images, plus low-GiB-per-year drift as upstreams
 * move mutable tags like {@code jdk-25} and strand the manifests they used to name. {@code
 * artifact-access-tracking.md} is where eviction lives when it lands; the npm proxy is already
 * waiting on the same feature, and the mirror is its second client.
 *
 * <p><b>Why a class exists at all for a policy of "no".</b> {@code GcPlanner} dispatches per type: an
 * unclaimed type reports "no strategy registered", which is the honest report of a decision nobody
 * has taken — and here a decision <em>has</em> been taken. Claiming the type is how the report
 * distinguishes the two. The npm proxy is deliberately in the other state, and the contrast between
 * the two lines in one report is the point of both.
 *
 * <p><b>Why a separate type rather than folding mirrors into {@code oci-images}.</b> Mirror tags —
 * {@code jdk-25}, {@code 9.6}, {@code latest} — are neither calver releases nor build shas, so
 * inside docker's rules every one of them would land on the unclassified-means-keep backstop. The
 * outcome would be the same and the report would be a lie: a backstop firing on every row reads as a
 * rule that nearly fired, and it would bury the one case that backstop exists to catch.
 *
 * <p>This is the one strategy that reads the census, and the interface's signature is why it can: it
 * has no rules of its own to compute a keep-set from, so the type's live set <em>is</em> its answer.
 * Nothing here can fail closed either — there is no outside dependency to be unable to reach, which
 * is what makes an {@code error} on this type's line mean something is genuinely wrong.
 *
 * <p>{@code @Singleton} rather than {@code @ApplicationScoped}, for the report's sake: a
 * normal-scoped bean answers {@code getClass().getSimpleName()} through its client proxy.
 */
@Singleton
public class OciMirrorGcStrategy implements GcStrategy {

  /** The rule every mirror identity is kept under, and the wording the plan settled. */
  static final String KEPT_APPEND_ONLY = "append-only pending access tracking";

  @Inject ArtifactRepositoryRepository repositories;
  @Inject OciTagRepository tags;
  @Inject OciManifestRepository manifests;

  @Override
  public RepositoryType type() {
    return RepositoryType.OCI_MIRROR;
  }

  @Override
  public Plan plan(LiveBlobCensus.Census census) {
    List<GcIdentity> kept = new ArrayList<>();
    for (ArtifactRepository repository : repositories.listAll()) {
      if (repository.type != RepositoryType.OCI_MIRROR) {
        continue;
      }
      collect(repository.name, kept);
    }
    kept.sort(Comparator.comparing(GcIdentity::repository).thenComparing(GcIdentity::identity));
    // The type's whole live set, verbatim: with nothing condemned, what this type retains is exactly
    // what the census says it reaches. Recomputing it here would be a second answer to a question
    // that already has one.
    return Plan.nothingDies(kept, Set.copyOf(census.live(RepositoryType.OCI_MIRROR).keySet()));
  }

  /**
   * One namespace's identities: every cached tag, and every manifest no tag names.
   *
   * <p>Both are listed because both are what a later eviction rule would act on — an untagged
   * manifest is usually a tag that moved upstream, which is the drift the recorded price is about,
   * and a report that showed only tags would hide the thing growing.
   */
  private void collect(String repository, List<GcIdentity> kept) {
    for (String image : manifests.listImageNames(repository)) {
      // Per image, because a manifest digest is scoped to one: the same bytes cached under two
      // images are two rows, and one of them being tagged says nothing about the other.
      Set<String> tagged = new HashSet<>();
      for (OciTag tag : tags.listByImage(repository, image)) {
        tagged.add(tag.manifestDigest);
        kept.add(new GcIdentity(repository, image + ":" + tag.tag, KEPT_APPEND_ONLY));
      }
      for (OciManifest manifest : manifests.listByImage(repository, image)) {
        if (!tagged.contains(manifest.digest)) {
          kept.add(
              new GcIdentity(
                  repository, image + "@" + OciDigest.wire(manifest.digest), KEPT_APPEND_ONLY));
        }
      }
    }
  }
}
