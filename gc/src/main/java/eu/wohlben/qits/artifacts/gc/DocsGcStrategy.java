package eu.wohlben.qits.artifacts.gc;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Published documentation sites, on the settled rule: <b>the last releases of every site stay, and
 * the rest ages out after P90D unread.</b>
 *
 * <p>P90D rather than P30D, for the reason maven's and the daemon's carry it: reading documentation
 * is not a monthly cadence. A version's docs are opened when someone is on that version, which for a
 * library can be a long time after it was published and a long time between visits.
 *
 * <p>The rule is {@link OwnArtifactsStrategy}'s, the wiring {@link OwnGcStrategy}'s, and the facts —
 * one <b>version</b> is one identity, every version is a release, nothing pins one, how a version
 * goes — are {@link DocsGcAdapter}'s.
 *
 * <p><b>What this type never plans is a file.</b> A candidate is a whole published version and a
 * bundle's files have no identity to be condemned separately, so the eviction that this strategy can
 * express is "this version goes" and nothing narrower. That is a property of the adapter and the
 * schema rather than of this class, and it is written here too because the report line this class
 * names is where someone will come looking for it.
 *
 * <p>{@code @Singleton} rather than {@code @ApplicationScoped}, for the report's sake: a
 * normal-scoped bean answers {@code getClass().getSimpleName()} through its client proxy.
 */
@Singleton
public class DocsGcStrategy extends OwnGcStrategy {

  @Inject DocsGcAdapter docs;

  @Override
  GcTypeAdapter adapter() {
    return docs;
  }
}
