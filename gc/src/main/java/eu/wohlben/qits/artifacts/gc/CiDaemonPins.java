package eu.wohlben.qits.artifacts.gc;

/**
 * What qits-ci would launch right now, and what it would fall back to: the daemon ladder's top two
 * rungs.
 *
 * <p>The daemon binaries are the one blob class a running service <em>executes</em>, and the pin
 * lives in qits-ci — a deployment bumps it, an adopted release moves it, and nothing here can
 * derive it. Before this port the alternative was a hand-maintained allowlist in this repository
 * that the next pin bump would silently invalidate, arming the collector against its own CI.
 *
 * <p><b>A blank version is an answer, not an absence.</b> It means this deployment has adopted or
 * pinned no daemon — the shipped default, and the honest state of a platform that has published
 * none. A blank must never be read as "unknown" and must never abort a run: the answer arrived, and
 * the answer is that nothing is pinned. Only a qits-ci that could not be reached or parsed aborts.
 *
 * <p><b>Both versions pin.</b> {@code previousDaemonVersion} is the rung qits-ci would try next if
 * the current one stopped registering, so deleting it removes the fallback the ladder exists to
 * have.
 *
 * <p><b>A 64-hex version additionally names a blob.</b> The pin has historically been a sha256
 * digest ({@code QITS_CI_DAEMON_VERSION}), fetched as {@code /v2/qits/ci-daemon/blobs/sha256:…}, so
 * such a value protects the blob at that digest directly as well as any {@code daemon_binary} row
 * keyed by it.
 */
@FunctionalInterface
public interface CiDaemonPins {

  /**
   * The ladder's answer, verbatim as {@code GET /ci/api/daemon} spells it.
   *
   * @param daemonName the daemon's name — reported so a lookup needs no inference, since {@code
   *     daemon_binary} rows are keyed {@code (repository, name, version)}
   * @param daemonVersion what a run started now would download; blank means nothing is pinned
   * @param previousDaemonVersion the fallback rung; blank when there is none
   * @param source {@code adopted}, {@code configured} or {@code none} — reported for the receipt,
   *     never interpreted here
   */
  record DaemonPin(
      String daemonName, String daemonVersion, String previousDaemonVersion, String source) {}

  /**
   * The pin as qits-ci reports it.
   *
   * @throws RuntimeException qits-ci could not be reached or could not be parsed. Not thrown for a
   *     blank version, which is an answer.
   */
  DaemonPin daemonPin();

  /**
   * Where this implementation reads it from, for the report's pins section. Default for the reason
   * {@link CdDeploymentPins#url()} gives: provenance, not policy.
   */
  default String url() {
    return "(not reported by " + getClass().getSimpleName() + ")";
  }
}
