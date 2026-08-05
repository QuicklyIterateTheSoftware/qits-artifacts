package eu.wohlben.qits.artifacts.gc;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The platform's own npm packages, live on the settled rule: <b>the last two releases of every
 * package stay, anything a dist-tag names stays, and the rest ages out after P30D unaccessed.</b>
 *
 * <p>This class used to carry npm's whole bespoke rule. The settlement of 2026-08-05 replaced "one
 * bespoke strategy per type" with "two engines, configured per type", so the <b>rule</b> is now
 * {@link OwnArtifactsStrategy}'s, the wiring {@link OwnGcStrategy}'s, and the facts — what a release
 * is, which of two is newer, what a dist-tag holds, how a row goes — are {@link
 * NpmPackagesGcAdapter}'s.
 *
 * <p><b>Two rules changed direction, and both changes are the settlement's.</b> Releases used to be
 * kept <em>forever</em> and are now kept as the last two per package; an older one survives on use,
 * which is what a lockfile install already does to {@code accessed_at}. And a prerelease used to die
 * <em>structurally</em>, the moment a newer main build existed; it now dies only once nothing has
 * installed it for P30D. That second change loosens the rule.
 *
 * <p>The newest-main-build rule and the unmodelled-prerelease backstop both retired into the access
 * window rather than being dropped: what {@code @main} resolves to was published minutes ago and is
 * young by construction, and an {@code -rc.1} somebody made by hand is kept for as long as anything
 * installs it. What is gone is "kept because nothing else claimed it", which was never a reason.
 *
 * <p><b>The tombstone stays, and it is npm's alone.</b> Version immutability is enforced by looking
 * for the row, so deleting one would re-open that version's name for a publish with different bytes
 * — one coordinate resolving to two tarballs over its lifetime. {@code
 * NpmRegistryCollection.collect} writes {@code npm_version_tombstone} in the <b>same transaction</b>
 * as the row deletion and refuses a version a dist-tag still names; both are the mechanism's
 * guarantees rather than this rule's, so no path around them exists to forget.
 *
 * <p>{@code @Singleton} rather than {@code @ApplicationScoped}, for the report's sake: a
 * normal-scoped bean answers {@code getClass().getSimpleName()} through its client proxy.
 */
@Singleton
public class NpmPackagesGcStrategy extends OwnGcStrategy {

  @Inject NpmPackagesGcAdapter packages;

  @Override
  GcTypeAdapter adapter() {
    return packages;
  }
}
