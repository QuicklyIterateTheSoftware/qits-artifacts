package eu.wohlben.qits.artifacts.gc;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The platform's own daemon binaries, live on the settled rule: <b>the last two versions of every
 * daemon stay, both rungs of qits-ci's ladder stay, and the rest ages out after P90D unaccessed.</b>
 *
 * <p>This type reported "no strategy registered for daemon-binaries" until now, and that was the
 * honest report of a decision waiting on a fact: the keep-set is partly qits-ci's answer, and a
 * strategy shipped ahead of {@code GET /ci/api/daemon} would have planned the one blob class a
 * running service <em>executes</em> against "nothing is pinned". The pin source landed, so the
 * report line changes from a decision's absence to a decision's outcome.
 *
 * <p>The rule is {@link OwnArtifactsStrategy}'s, the wiring {@link OwnGcStrategy}'s, and the facts —
 * one row is one identity, every row is a release, what qits-ci pins, how a row goes — are {@link
 * DaemonBinariesGcAdapter}'s.
 *
 * <p><b>The row-less legacy blobs are still untouchable and nothing here changes that.</b> A blob
 * becomes a candidate only by losing its last identity row, so bytes that never had one cannot be
 * reached by any sweep. The settlement's answer to them is an ops action, once, by hand — not an
 * allowlist here and not an adoption path in the service.
 *
 * <p>{@code @Singleton} rather than {@code @ApplicationScoped}, for the report's sake: a
 * normal-scoped bean answers {@code getClass().getSimpleName()} through its client proxy.
 */
@Singleton
public class DaemonBinariesGcStrategy extends OwnGcStrategy {

  @Inject DaemonBinariesGcAdapter daemons;

  @Override
  GcTypeAdapter adapter() {
    return daemons;
  }
}
