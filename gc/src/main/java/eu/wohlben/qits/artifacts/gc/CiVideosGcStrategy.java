package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.control.LiveBlobCensus;
import eu.wohlben.qits.artifacts.entity.ArtifactRepository;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.persistence.ArtifactRecordRepository;
import eu.wohlben.qits.artifacts.persistence.ArtifactRepositoryRepository;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Set;

/**
 * The videos stub: its rule in writing, and a refusal to run until there is anything to run on.
 *
 * <p>The golden-diff loop has never produced a video — zero {@code artifact_record} rows — so the
 * honest plan is {@code nothingDies} under a note that says why. The intended rule is written down
 * so the type keeps its own policy when the loop wakes up, and it is <b>not</b> screenshots' rule:
 * videos are orders of magnitude larger per record, so retention here is <b>byte-budgeted</b> —
 * keep the newest N per userflow with N sized in bytes, not in record count
 * (artifacts-gc-plan.md&nbsp;4.5).
 *
 * <p>Rows appearing is the gate flipping, and this stub <b>fails closed</b> at it: a plan over rows
 * it has no implemented rule for is a guess, and the planner reports the refusal while keeping
 * every blob of the type. Implementing the rule above is what replaces the throw.
 *
 * <p><b>Deliberately its own class beside {@link CiScreenshotsGcStrategy}</b>, however alike the
 * two look today. Writing them as one strategy would be the exact unification mistake the plan
 * forbids, at the cheapest place to demonstrate the discipline — their rules already diverge in
 * kind, branch-scoped against byte-budgeted, and a shared base would make one system's edge case
 * the other's silent bug.
 *
 * <p>{@code @Singleton} rather than {@code @ApplicationScoped}, for the report's sake: the planner
 * names a strategy by its class's simple name, and a pseudo-scope has no client proxy to get in the
 * way of that.
 */
@Singleton
public class CiVideosGcStrategy implements GcStrategy {

  static final String NOTE =
      "stub: the golden-diff loop has never produced a video. The intended rule is byte-budgeted —"
          + " keep the newest N per userflow, with N sized in bytes rather than record count,"
          + " because one video outweighs a branch of screenshots — and it is implemented when"
          + " rows exist to plan over, not before.";

  @Inject ArtifactRepositoryRepository repositories;
  @Inject ArtifactRecordRepository records;

  @Override
  public RepositoryType type() {
    return RepositoryType.CI_VIDEOS;
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
          "ci-videos holds "
              + rows
              + " record rows now, and this strategy is a stub — refusing to plan them."
              + " Implement the byte-budgeted rule (keep the newest N per userflow, N sized in"
              + " bytes) before this type is collected.");
    }
    // The type's whole live set, verbatim from the census — empty today, and honest either way.
    return Plan.nothingDies(List.of(), Set.copyOf(census.live(RepositoryType.CI_VIDEOS).keySet()));
  }

  private long rowCount() {
    long rows = 0;
    for (ArtifactRepository repository : repositories.listAll()) {
      if (repository.type == RepositoryType.CI_VIDEOS) {
        rows += records.countByRepository(repository.name);
      }
    }
    return rows;
  }
}
