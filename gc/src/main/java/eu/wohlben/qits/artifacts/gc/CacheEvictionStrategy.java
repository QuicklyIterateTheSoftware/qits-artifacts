package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The cache engine: delete everything unaccessed past the window, keep what a live pin names.
 *
 * <p>The rule the settlement gave the proxy and mirror types, and it is short because a cache has
 * nothing to protect structurally. Every byte in an {@code npm-proxy} or an {@code oci-mirror} came
 * from upstream and can be fetched again, so the only question worth asking is whether anything
 * still uses it. Being wrong costs one re-download; being wrong in the other direction costs disk
 * forever.
 *
 * <p><b>Unaccessed means the effective access time is older than the window</b>, and the effective
 * access time is {@code max(created/published/fetched, accessed_at)} — creation counts as a first
 * access, which is what keeps a tag cached ten minutes ago from reading as never-read. The adapter
 * computes it ({@link GcCandidate#lastAccessAt()}); this engine only compares it.
 *
 * <p><b>No release rule lives here, deliberately.</b> A cache's content has no releases of
 * <em>ours</em> to protect: a mirrored {@code jdk-25} is upstream's release, and keeping it forever
 * because upstream calls it a release is how a mirror never shrinks. Version protection is
 * {@link OwnArtifactsStrategy}'s, earned by own-ness.
 *
 * <p>Stateless and not a bean. Something that binds a configured policy to a {@link GcTypeAdapter}
 * constructs one; deletion is the adapter's {@link GcTypeAdapter#delete}, because eviction removes
 * exactly the identities this plan condemns and adds nothing of its own.
 */
public final class CacheEvictionStrategy {

  /** The rule sentence a report echoes for a type configured as a cache. */
  public static String rule(Duration window) {
    return "cache: delete every identity unaccessed for longer than "
        + GcPlanner.iso(window)
        + ", keep everything a live pin names. Creation counts as the first access, so nothing is"
        + " eligible before the window has passed since it was cached.";
  }

  static String keptAccessed(Duration window) {
    return "accessed inside the " + GcPlanner.iso(window) + " window";
  }

  static String deadUnaccessed(Duration window) {
    return "cached content unaccessed for longer than " + GcPlanner.iso(window);
  }

  /**
   * Reads the adapter's identities and says what would die. Deletes nothing.
   *
   * @param adapter the type's own facts — what exists, and when each was last touched
   * @param window the configured eviction window for that type
   * @param now the run's clock, passed in rather than read here so a plan and its receipt judge
   *     every identity against one instant
   * @param pins what a live service is holding on to, checked before the access rule
   */
  public GcStrategy.Plan plan(
      GcTypeAdapter adapter, Duration window, Instant now, GcPinned pins) {
    Instant cut = now.minus(window);
    List<GcIdentity> dead = new ArrayList<>();
    List<GcIdentity> kept = new ArrayList<>();
    Set<String> released = new HashSet<>();
    Set<String> retained = new HashSet<>();

    for (GcCandidate candidate : adapter.enumerate()) {
      String pin = pins.pinnedBy(candidate);
      if (pin != null) {
        keep(candidate, pin, kept, retained);
      } else if (candidate.unaccessedSince(cut)) {
        dead.add(new GcIdentity(candidate.repository(), candidate.identity(), deadUnaccessed(window)));
        released.addAll(candidate.blobs());
      } else {
        keep(candidate, keptAccessed(window), kept, retained);
      }
    }

    dead.sort(BY_IDENTITY);
    kept.sort(BY_IDENTITY);
    return dead.isEmpty()
        ? GcStrategy.Plan.nothingDies(kept, retained)
        : new GcStrategy.Plan(dead, kept, released, retained);
  }

  private static void keep(
      GcCandidate candidate, String rule, List<GcIdentity> kept, Set<String> retained) {
    kept.add(new GcIdentity(candidate.repository(), candidate.identity(), rule));
    retained.addAll(candidate.blobs());
  }

  static final Comparator<GcIdentity> BY_IDENTITY =
      Comparator.comparing(GcIdentity::repository).thenComparing(GcIdentity::identity);
}
