package eu.wohlben.qits.artifacts.gc;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads both pin sources once and folds them into one {@link GcPins}.
 *
 * <p>Called at the start of a plan and at the start of a sweep, and nowhere else — one aggregate per
 * run is what keeps two halves of a run from planning against two different truths.
 *
 * <p><b>Both sources are asked even when the first one fails.</b> A run that stopped at the first
 * failure would report one broken service and hide the second, and whoever fixed it would find the
 * next run just as dead with no warning it was coming. Every failure lands in {@link
 * GcPins#failures()}, and the run's refusal is what the executor and the planner do with it.
 */
@ApplicationScoped
public class GcPinSources {

  @Inject CdDeploymentPins cd;
  @Inject CiDaemonPins ci;

  /** One reading of every pin the platform holds. Never cached. */
  public GcPins fetch() {
    Map<String, Set<String>> deployments = new HashMap<>();
    String daemonName = "";
    Set<String> daemonVersions = new HashSet<>();
    Set<String> blobs = new HashSet<>();
    List<String> failures = new ArrayList<>();

    try {
      for (CdDeploymentPins.ApplicationPin pin : cd.pins()) {
        deployments
            .computeIfAbsent(pin.applicationName(), image -> new HashSet<>())
            .addAll(pin.shas());
      }
    } catch (RuntimeException unreachable) {
      failures.add("qits-cd deployment pins: " + message(unreachable));
    }

    try {
      CiDaemonPins.DaemonPin pin = ci.daemonPin();
      daemonName = blank(pin.daemonName());
      // Blank is an ANSWER: qits-ci saying this deployment has pinned no daemon. Dropped from the
      // set rather than treated as a version, and never a failure.
      pin(pin.daemonVersion(), daemonVersions, blobs);
      pin(pin.previousDaemonVersion(), daemonVersions, blobs);
    } catch (RuntimeException unreachable) {
      failures.add("qits-ci daemon pin: " + message(unreachable));
    }

    return new GcPins(deployments, daemonName, daemonVersions, blobs, failures);
  }

  /**
   * One rung of the ladder. A 64-hex version pins the blob at that digest as well as the row —
   * the pin has historically been a digest, fetched as {@code /v2/qits/ci-daemon/blobs/sha256:…},
   * and a version-addressed row may not exist for it at all.
   */
  private static void pin(String version, Set<String> versions, Set<String> blobs) {
    String trimmed = blank(version);
    if (trimmed.isEmpty()) {
      return;
    }
    versions.add(trimmed);
    if (GcPins.DIGEST.matcher(trimmed).matches()) {
      blobs.add(trimmed);
    }
  }

  private static String blank(String value) {
    return value == null ? "" : value.trim();
  }

  private static String message(RuntimeException failed) {
    String message = failed.getMessage();
    return failed.getClass().getSimpleName() + (message == null ? "" : ": " + message);
  }
}
