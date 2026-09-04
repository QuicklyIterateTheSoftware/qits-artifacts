package eu.wohlben.qits.artifacts.gc;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The platform's own images, live on the settled rule: <b>the last two calver releases of every
 * image stay, everything a live pin names stays, and the rest ages out.</b>
 *
 * <p>This class used to carry docker's whole bespoke rule — five keep-classes, a two-phase plan and
 * its own deletion mechanics. The settlement of 2026-08-05 replaced "one bespoke strategy per type"
 * with "two engines, configured per type", and the rule moved rather than changed shape: the
 * <b>rule</b> is {@link OwnArtifactsStrategy}'s, the wiring is {@link OwnGcStrategy}'s, and the
 * facts — what a coordinate is, what a release is, which of two releases is newer, what qits-platform-deployments
 * would pull, how a row goes — are {@link OciImagesGcAdapter}'s. This class is the bean the planner
 * finds and the name a reviewer reads on the report line.
 *
 * <p><b>Two of the old rules changed direction, and both changes are the settlement's.</b> Calver
 * releases used to be kept <em>forever</em>; they are now kept as the last two per image, and an
 * older one survives on use — a release something still pulls is accessed, so use keeps it alive
 * where policy no longer does. And build-sha tags used to die <em>structurally</em>, the moment a
 * newer build existed; they now die only once nothing has pulled them for P30D. That second change
 * loosens the rule: a sha tag something is still pulling now survives, where the structural rule
 * condemned it.
 *
 * <p><b>What did not change is the belt that closes the IMAGE_MISSING hazard — only which tag it
 * names.</b> Every coordinate qits-platform-deployments pins is kept, and so is each image's newest
 * <b>calver</b> tag: the pull the next deploy will make, which cd cannot answer for because it has
 * not happened yet. It named the newest build sha until 2026-09-04, when deployments started being
 * made by version coordinate and the sha stopped being anything's pull target; {@link
 * OciImagesGcAdapter} carries the flip and the audit behind it. Both pins live in {@link
 * OciImagesGcAdapter#pinnedBy}, checked before the access rule, and an image with no deployment row
 * at all still keeps the tag a deploy would ask for.
 *
 * <p>The unclassified-means-keep backstop retired into the access rule rather than being dropped: a
 * coordinate that is neither a calver release nor a build sha is now kept for as long as something
 * pulls it, which is a better answer than "forever" and a strictly safer one than the structural
 * rule it replaces. The live store holds none — 110 tags, 108 shas, one short sha and one calver —
 * so nothing measured changes hands.
 *
 * <p>{@code @Singleton} rather than {@code @ApplicationScoped}, for the report's sake: a
 * normal-scoped bean answers {@code getClass().getSimpleName()} through its client proxy.
 */
@Singleton
public class OciImageGcStrategy extends OwnGcStrategy {

  @Inject OciImagesGcAdapter images;

  @Override
  GcTypeAdapter adapter() {
    return images;
  }
}
