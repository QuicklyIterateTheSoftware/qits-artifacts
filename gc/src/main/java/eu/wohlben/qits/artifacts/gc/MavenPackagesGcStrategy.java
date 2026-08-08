package eu.wohlben.qits.artifacts.gc;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The platform's own maven repository, live on the settled rule: <b>the last two release versions of
 * every artifact stay, the newest deployable set of every snapshot line stays, and the rest ages out
 * after P90D unaccessed.</b>
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
 * window decides, and the only structural keep beyond the release belt is the one a resolver would
 * break without — the newest deployable set of each snapshot line, which is what the derived {@code
 * maven-metadata.xml} redirects {@code 1.0.1-SNAPSHOT} to. The identity is a <b>coordinate rather
 * than a path</b> for the same reason: the settlement counts versions, and a jar whose pom was
 * collected out from under it is a broken resolve rather than a smaller version.
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

  @Inject MavenPackagesGcAdapter packages;

  @Override
  GcTypeAdapter adapter() {
    return packages;
  }
}
