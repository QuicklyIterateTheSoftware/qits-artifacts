package eu.wohlben.qits.artifacts.gc;

import java.util.List;

/**
 * What qits-cd is currently holding on to: the deployment rows, newest-first, exactly as cd reports
 * them.
 *
 * <p><b>Transport only, no policy.</b> This port fetches rows and nothing else — which rows count as
 * rollback-relevant is {@link OciImageGcStrategy}'s rule and lives there, because a keep-set assembled
 * outside the class that acts on it is a place for the two to drift, and drift here deletes images.
 *
 * <p><b>Absent is not a supported configuration, and that is the exception to this repository's
 * usual port rule.</b> Everywhere else a missing collaborator has a documented fallback; here there
 * is none, because the fallback would be "plan the docker keep-set without knowing what is running".
 * An implementation that cannot answer must throw, the strategy lets the throw out, and the planner
 * reports the type as failed with its whole census set kept. Reclaiming nothing is the correct
 * outcome of an unreachable qits-cd; reclaiming something is never.
 */
@FunctionalInterface
public interface CdDeploymentPins {

  /**
   * One deployment row, reduced to the four fields a keep-set needs.
   *
   * @param applicationId cd's own key for the application, which is what rows are grouped by — an
   *     application is scoped to an environment, so two environments running the same service are
   *     two applications with one name
   * @param application the application's name, which is also the image name: cd derives every pull
   *     as {@code <repository>/<application>:<sha>}, so this is the join to an {@code oci_tag}
   * @param commitSha the sha the container was created from — the tag a restart pulls again
   * @param status cd's lifecycle state, spelled as cd spells it. Read rather than interpreted here;
   *     which states matter is the strategy's decision
   */
  record Deployment(String applicationId, String application, String commitSha, String status) {}

  /**
   * Every deployment row cd knows, newest-first within each application.
   *
   * <p>The order is load-bearing: "the previous distinct sha" is read off it, and a list in another
   * order silently keeps the wrong rollback target.
   *
   * @throws RuntimeException cd could not be reached or could not be parsed — fail-closed, never an
   *     empty list, because an empty list reads as "nothing is deployed" and condemns every tag
   */
  List<Deployment> deployments();
}
