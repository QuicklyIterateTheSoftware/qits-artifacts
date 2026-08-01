package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.entity.ArtifactRepository;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.persistence.ArtifactRecordRepository;
import eu.wohlben.qits.artifacts.persistence.ArtifactRepositoryRepository;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Set;

/**
 * The screenshots stub: its rule in writing, and a refusal to run until there is anything to run on.
 *
 * <p>The golden-diff loop has never produced a screenshot — zero {@code artifact_record} rows —
 * so the honest plan is {@code nothingDies} under a note that says why, and the class exists so the
 * report reads "a decision was taken" rather than "no strategy registered". The intended rule is
 * written down here so the type is never silently absorbed into "misc" when the loop wakes up:
 * <b>branch-scoped</b> — keep the newest record per {@code (git.branch.name, qits.userflow.name)}
 * while the branch exists, delete records of deleted branches
 * (artifacts-gc-plan.md&nbsp;4.4).
 *
 * <p>Rows appearing is the gate flipping, and this stub <b>fails closed</b> at it: a plan over rows
 * it has no implemented rule for is a guess, and the planner reports the refusal while keeping
 * every blob of the type. Implementing the rule above is what replaces the throw.
 *
 * <p><b>Deliberately not shared with {@link CiVideosGcStrategy}</b>, however alike the two look
 * today. Their intended rules already diverge — branch-scoped here, byte-budgeted there — and the
 * two-stubs split is the plan's coincidental-similarity rule demonstrated at the cheapest possible
 * place. A base class between them would be the exact unification the plan forbids.
 *
 * <p>{@code @Singleton} rather than {@code @ApplicationScoped}, for the report's sake: the planner
 * names a strategy by its class's simple name, and a pseudo-scope has no client proxy to get in the
 * way of that.
 */
@Singleton
public class CiScreenshotsGcStrategy implements GcStrategy {

  static final String NOTE =
      "stub: the golden-diff loop has never produced a screenshot. The intended rule is"
          + " branch-scoped — keep the newest record per (git.branch.name, qits.userflow.name)"
          + " while the branch exists, delete records of deleted branches — and it is implemented"
          + " when rows exist to plan over, not before.";

  @Inject ArtifactRepositoryRepository repositories;
  @Inject ArtifactRecordRepository records;

  @Override
  public RepositoryType type() {
    return RepositoryType.CI_SCREENSHOTS;
  }

  @Override
  public String note() {
    return NOTE;
  }

  @Override
  public Plan plan(LiveBlobCensus.Census census) {
    long rows = rowCount();
    if (rows > 0) {
      throw new IllegalStateException(
          "ci-screenshots holds "
              + rows
              + " record rows now, and this strategy is a stub — refusing to plan them."
              + " Implement the branch-scoped rule (keep the newest record per"
              + " (git.branch.name, qits.userflow.name) while the branch exists) before this type"
              + " is collected.");
    }
    // The type's whole live set, verbatim from the census — empty today, and honest either way.
    return Plan.nothingDies(
        List.of(), Set.copyOf(census.live(RepositoryType.CI_SCREENSHOTS).keySet()));
  }

  private long rowCount() {
    long rows = 0;
    for (ArtifactRepository repository : repositories.listAll()) {
      if (repository.type == RepositoryType.CI_SCREENSHOTS) {
        rows += records.countByRepository(repository.name);
      }
    }
    return rows;
  }
}
