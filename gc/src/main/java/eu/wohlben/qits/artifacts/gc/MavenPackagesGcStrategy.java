package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.control.LiveBlobCensus;
import eu.wohlben.qits.artifacts.entity.ArtifactRepository;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import eu.wohlben.qits.artifacts.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.artifacts.persistence.MavenArtifactRepository;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * The maven repository's rule: nothing dies, said out loud — the mirror's shape, not the CI stubs'.
 *
 * <p><b>Releases are never eligible</b>, which is the purest form of the rule npm and docker both
 * reduce to: a maven release repository exists so that a coordinate, once resolved, resolves to the
 * same bytes forever. Timestamped snapshot builds <b>accumulate</b> — one file-set per snapshot
 * deploy — at a price stated up front: jar plus pom at the platform library's tens-of-kilobytes
 * scale is noise, and even a CI cadence of snapshot deploys is single-digit MiB per library per
 * year. The cleanup rule is named in {@link #NOTE} so the type is never silently absorbed into
 * "misc" when someone wants the bytes back; until it is implemented the posture is append-only, the
 * mirror's precedent exactly.
 *
 * <p>Why this shape rather than the CI stubs' fail-closed-at-first-row: that shape made sense for
 * types expected to stay empty. This type has rows from its first hour — that is its purpose — so a
 * stub would report {@code error} on every GC plan forever, training the reader to ignore the one
 * signal that means something.
 *
 * <p>The class exists at all for the mirror's reason: an unclaimed type reports "no strategy
 * registered", which is the honest report of a decision nobody has taken — and here one <em>has</em>
 * been taken (maven-repository-plan.md §3.8). {@code maven-proxy} is deliberately in the other
 * state, the {@code npm-proxy} line verbatim: its content is a re-fetchable cache of upstream, its
 * policy is eviction rather than retention, and eviction is access-based — {@code
 * artifact-access-tracking.md}'s territory.
 *
 * <p>Like the mirror's, this strategy reads the census for its retained set — with nothing
 * condemned, what this type retains is exactly what the census says it reaches — and depends on
 * nothing outside this service, so an {@code error} on its line means something is genuinely wrong.
 *
 * <p>{@code @Singleton} rather than {@code @ApplicationScoped}, for the report's sake: a
 * normal-scoped bean answers {@code getClass().getSimpleName()} through its client proxy.
 */
@Singleton
public class MavenPackagesGcStrategy implements GcStrategy {

  /** The rule every maven identity is kept under. */
  static final String KEPT_APPEND_ONLY =
      "append-only: releases are never eligible; timestamped snapshot builds accumulate";

  /** The cleanup rule, named so the type is never absorbed into "misc" when the bytes are wanted back. */
  static final String NOTE =
      "append-only pending snapshot cleanup — the intended rule is keep the newest N timestamped"
          + " builds per (group, artifact, snapshot version); releases never eligible.";

  @Inject ArtifactRepositoryRepository repositories;
  @Inject MavenArtifactRepository artifacts;

  @Override
  public RepositoryType type() {
    return RepositoryType.MAVEN_PACKAGES;
  }

  @Override
  public String note() {
    return NOTE;
  }

  @Override
  public Plan plan(LiveBlobCensus.Census census, GcPins pins) {
    List<GcIdentity> kept = new ArrayList<>();
    for (ArtifactRepository repository : repositories.listAll()) {
      if (repository.type != RepositoryType.MAVEN_PACKAGES) {
        continue;
      }
      for (String path : artifacts.listPaths(repository.name)) {
        kept.add(new GcIdentity(repository.name, path, KEPT_APPEND_ONLY));
      }
    }
    kept.sort(Comparator.comparing(GcIdentity::repository).thenComparing(GcIdentity::identity));
    // The type's whole live set, verbatim: with nothing condemned, what this type retains is exactly
    // what the census says it reaches. Recomputing it here would be a second answer to a question
    // that already has one.
    return Plan.nothingDies(kept, Set.copyOf(census.live(RepositoryType.MAVEN_PACKAGES).keySet()));
  }
}
