package eu.wohlben.qits.artifacts.gc;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The platform's own maven repository, live on the rule the 2026-09-05 outage settled: <b>every
 * published release stays, whatever its age; the newest deployable set of every snapshot line stays;
 * and superseded snapshot sets age out at the configured window.</b>
 *
 * <p>This class used to say "nothing dies", under a note naming a cleanup rule nobody had
 * implemented. That was a decision with a condition attached — {@code maven-repository-plan.md}
 * §3.6 named the shape and never priced the deletion — and the settlement discharged it by pricing
 * every own type the same way. So the append-only posture is replaced deliberately rather than
 * eroded, and the note goes with it: the rule is the report line now.
 *
 * <p>The rule is {@link OwnArtifactsStrategy}'s, the wiring {@link OwnGcStrategy}'s, and the facts —
 * what a coordinate is, what a release is, which of two versions is newer, what a resolver would
 * break without, how a row goes — are {@link MavenPackagesGcAdapter}'s.
 *
 * <p><b>Where this type is conservative, and why.</b> §3.6 sketched "keep the newest N timestamped
 * builds per snapshot version" and never settled N or priced it, so no N is invented here: the
 * window decides for snapshots, and the only structural keep among them is the one a resolver would
 * break without — the newest deployable set of each snapshot line, which is what the derived {@code
 * maven-metadata.xml} redirects {@code 1.0.1-SNAPSHOT} to. The identity is a <b>coordinate rather
 * than a path</b> for the same reason: versions are what is counted, and a jar whose pom was
 * collected out from under it is a broken resolve rather than a smaller version.
 *
 * <p><b>The release belt is gone from this type, and {@link #note()} says so on every report line.</b>
 * The own engine's configured sentence — "always keep the last 2 released versions … delete the rest
 * once unaccessed for longer than P3D" — is the echo {@code GcRules} writes for every own type out of
 * the configuration, and for this one it is now wrong in the direction that matters. A reviewer
 * reading "the rest ages out" beside a maven line would be reading a rule that no longer runs, so
 * the correction rides the type's own note rather than waiting to be noticed. {@code
 * MavenPackagesGcAdapter}'s javadoc carries the argument in full.
 *
 * <p>{@code maven-proxy} is the cache beside it — a {@code cache} in the settlement's mapping like
 * the other two, with {@link MavenProxyGcAdapter} and no rule of its own. The two share {@code
 * maven_artifact}, so each enumeration filters by the repository row's type; a leak in either
 * direction is the one mistake these two types can make.
 *
 * <p>{@code @Singleton} rather than {@code @ApplicationScoped}, for the report's sake: a
 * normal-scoped bean answers {@code getClass().getSimpleName()} through its client proxy.
 */
@Singleton
public class MavenPackagesGcStrategy extends OwnGcStrategy {

  /**
   * What a report says about this type ahead of anything the run found, because the configuration
   * echo beside it describes a belt this type no longer runs.
   */
  static final String NOTE =
      "maven-packages does not age-collect published releases. Every release version is kept"
          + " whatever its age and whatever its position in the version order — the configured"
          + " window governs superseded timestamped SNAPSHOT sets only, and the newest deployable"
          + " set of every snapshot line is kept structurally on top of that. Withdrawn on"
          + " 2026-09-05, after the access rule deleted 67 published coordinates in one run and"
          + " stopped every gating build on the platform; see MavenPackagesGcAdapter for the"
          + " argument.";

  @Inject MavenPackagesGcAdapter packages;

  @Override
  GcTypeAdapter adapter() {
    return packages;
  }

  @Override
  public String note() {
    return NOTE;
  }
}
