package eu.wohlben.qits.artifacts.gc;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Every live pin one run holds, read once at its start and never cached.
 *
 * <p>Two services answer it: qits-cd names the image shas a restart or a rollback would pull,
 * qits-ci names the daemon versions its ladder would launch. Both are facts no timestamp in this
 * store implies — a container running untouched for months still pulls its sha the moment it
 * restarts — which is why pinned is a keep-class the engines check <b>before</b> the access rule.
 *
 * <p><b>One aggregate per run, taken at the start.</b> Not per type and not per strategy: two
 * fetches inside one run can disagree, and the disagreement is a deployment that landed between them
 * whose image the second half of the run then condemns. Never cached across runs either — a warm pin
 * list deletes the image that was deployed while it was warm.
 *
 * <p><b>{@link #failures()} is what makes a run refuse.</b> A source that could not be reached or
 * read leaves its reason here, and the collector's answer is all-or-nothing rather than per type:
 * {@code GcSweepExecutor} aborts the whole run with nothing deleted, and {@code GcPlanner} still
 * answers — a dry-run that 500s tells a reviewer nothing — but marks itself non-executable and
 * reports the pin-dependent types as failed.
 *
 * @param deployments application (and therefore image) name to every sha qits-cd pins for it
 * @param daemonName the daemon qits-ci's ladder is about, blank when it named none
 * @param daemonVersions the pinned daemon versions — the current rung and its fallback, blanks
 *     dropped, because a blank is qits-ci saying "nothing is pinned" rather than naming a version
 * @param blobs digests pinned directly: a daemon version that is 64 hex characters names the blob it
 *     is fetched by as well as any row keyed with it
 * @param failures one sentence per pin source that could not answer; empty is a complete aggregate
 */
public record GcPins(
    Map<String, Set<String>> deployments,
    String daemonName,
    Set<String> daemonVersions,
    Set<String> blobs,
    List<String> failures) {

  /** The rule an image sha is kept under when a deployment holds it. */
  public static final String BY_CD = "pinned by qits-cd deployment";

  /** The rule a daemon binary is kept under when qits-ci's ladder holds it. */
  public static final String BY_CI = "pinned by qits-ci daemon ladder";

  /** A pin spelled as a digest — the historic {@code QITS_CI_DAEMON_VERSION} shape. */
  static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

  public GcPins {
    deployments = Map.copyOf(deployments);
    daemonVersions = Set.copyOf(daemonVersions);
    blobs = Set.copyOf(blobs);
    failures = List.copyOf(failures);
  }

  /** Nothing pinned and nothing broken — the shape a test states explicitly. */
  public static GcPins none() {
    return new GcPins(Map.of(), "", Set.of(), Set.of(), List.of());
  }

  /** Whether every pin source answered. A run may only delete when this is true. */
  public boolean complete() {
    return failures.isEmpty();
  }

  /** The failures as one sentence, for a receipt that has to say why it did nothing. */
  public String whyIncomplete() {
    return String.join("; ", failures);
  }

  /** Every sha qits-cd pins for an image name. */
  public Set<String> deploymentShas(String image) {
    return deployments.getOrDefault(image, Set.of());
  }

  /** {@link #BY_CD} when a deployment pins this image's tag, else null. */
  public String pinsImageTag(String image, String tag) {
    return deploymentShas(image).contains(tag) ? BY_CD : null;
  }

  /**
   * {@link #BY_CI} when qits-ci's ladder pins this daemon version, else null.
   *
   * <p>The name is compared too: {@code daemon_binary} rows are keyed {@code (repository, name,
   * version)}, and a second daemon's identically named version is not what qits-ci pinned.
   */
  public String pinsDaemonVersion(String name, String version) {
    return daemonName.equals(name) && daemonVersions.contains(version) ? BY_CI : null;
  }

  /** {@link #BY_CI} when a pin names one of these blobs by digest, else null. */
  public String pinsAnyBlob(Set<String> candidateBlobs) {
    for (String blobId : candidateBlobs) {
      if (blobs.contains(blobId)) {
        return BY_CI;
      }
    }
    return null;
  }
}
