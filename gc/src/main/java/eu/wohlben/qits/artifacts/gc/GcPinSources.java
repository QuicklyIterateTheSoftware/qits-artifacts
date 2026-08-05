package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.gc.dto.GcPinSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
 *
 * <p><b>Each read also records how it went</b> ({@link GcPinSource}): the url, the outcome, the time
 * it took and the keep-identities the answer resolved to. That is the half of a keep-set a reviewer
 * cannot check from the plan alone — a run pointed at the wrong qits-cd answers plausibly and pins
 * the wrong shas — and it is recorded here, at the only place that knows what was actually called.
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
    List<GcPinSource> sources = new ArrayList<>();

    Instant startedCd = Instant.now();
    try {
      List<CdDeploymentPins.ApplicationPin> pins = cd.pins();
      Set<String> keeps = new TreeSet<>();
      for (CdDeploymentPins.ApplicationPin pin : pins) {
        deployments
            .computeIfAbsent(pin.applicationName(), image -> new HashSet<>())
            .addAll(pin.shas());
        for (String sha : pin.shas()) {
          keeps.add(pin.applicationName() + ":" + sha);
        }
      }
      sources.add(
          answered(
              "qits-cd",
              cd.url(),
              startedCd,
              pins.size()
                  + " application pins over "
                  + keeps.size()
                  + " image shas — what is serving, and what a rollback would restore",
              pins.size(),
              keeps));
    } catch (RuntimeException unreachable) {
      String why = "qits-cd deployment pins: " + message(unreachable);
      failures.add(why);
      sources.add(failed("qits-cd", cd.url(), startedCd, why));
    }

    Instant startedCi = Instant.now();
    try {
      CiDaemonPins.DaemonPin pin = ci.daemonPin();
      daemonName = blank(pin.daemonName());
      // Blank is an ANSWER: qits-ci saying this deployment has pinned no daemon. Dropped from the
      // set rather than treated as a version, and never a failure.
      pin(pin.daemonVersion(), daemonVersions, blobs);
      pin(pin.previousDaemonVersion(), daemonVersions, blobs);
      Set<String> keeps = new TreeSet<>();
      for (String version : daemonVersions) {
        keeps.add(daemonName + "@" + version);
      }
      for (String blob : blobs) {
        // A digest rung pins BYTES as well as a row, and may name bytes no row exists for at all.
        // Spelled separately so the pins section shows both halves of what one rung protects.
        keeps.add("blob " + blob);
      }
      sources.add(
          answered(
              "qits-ci",
              ci.url(),
              startedCi,
              daemonVersions.isEmpty()
                  ? "no daemon is pinned (source: "
                      + blank(pin.source())
                      + ") — an answer, not an absence, and the shipped default"
                  : "daemon "
                      + daemonName
                      + ", "
                      + daemonVersions.size()
                      + " ladder rungs pinned (source: "
                      + blank(pin.source())
                      + ")",
              daemonVersions.size(),
              keeps));
    } catch (RuntimeException unreachable) {
      String why = "qits-ci daemon pin: " + message(unreachable);
      failures.add(why);
      sources.add(failed("qits-ci", ci.url(), startedCi, why));
    }

    return new GcPins(deployments, daemonName, daemonVersions, blobs, failures, sources);
  }

  private static GcPinSource answered(
      String source,
      String url,
      Instant startedAt,
      String outcome,
      int pinCount,
      Set<String> keeps) {
    return new GcPinSource(
        source, url, true, outcome, startedAt, took(startedAt), pinCount, List.copyOf(keeps));
  }

  private static GcPinSource failed(String source, String url, Instant startedAt, String why) {
    return new GcPinSource(source, url, false, why, startedAt, took(startedAt), 0, List.of());
  }

  private static long took(Instant startedAt) {
    return Duration.between(startedAt, Instant.now()).toMillis();
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
