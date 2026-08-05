package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.control.LiveBlobCensus;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * What binds an own type's {@link GcTypeAdapter} to {@link OwnArtifactsStrategy} — wiring, and
 * deliberately nothing else. The twin of {@link CacheGcStrategy}, one engine over.
 *
 * <p>The settlement's shape has three parts and this is the third: the <b>rule</b> is the engine's,
 * written once; the <b>facts</b> are the adapter's, one per type; and something has to read the
 * configuration, hand the engine the window, compose the keep-classes and route deletion back to the
 * adapter. That is all that lives here. No line below decides what dies, which is what keeps a
 * shared base class from being the retention framework the plan forbids — the forbidden thing is two
 * types sharing a <em>rule</em> they only appear to agree on, and these four share an engine the
 * user chose for all of them by name.
 *
 * <p><b>The configured policy is checked rather than assumed.</b> A deployment can move a type
 * between engines with an environment variable, and a subclass of this class running the own engine
 * over a type someone reconfigured as {@code cache} would keep releases that configuration meant to
 * evict. So a mismatch refuses, and the refusal lands on that type's line in the report.
 *
 * <p><b>Pins are read, so {@link #readsPins()} is true — for all four own types.</b> Two of them
 * carry a pin by <em>coordinate</em> (an image sha qits-cd would pull, a daemon version qits-ci's
 * ladder would launch) and every one of them can carry a pin by <b>blob digest</b>, because blobs
 * dedupe globally and a pinned digest may be the same bytes a published version names. The
 * alternative is planning a type against "nothing is pinned", which is the documented way to condemn
 * something a live service still fetches. Declaring it means a run whose pins are incomplete does
 * not collect these types either — which costs nothing operationally, because such a run aborts the
 * sweep whole in any case.
 *
 * <p><b>The two keep-classes are composed here, in order.</b> The adapter answers first, over the
 * whole enumeration, because only it knows what its coordinates mean; the digest check is the floor
 * under it, identical for every type and therefore written once. Both are checked before the access
 * rule inside the engine, which is the safety property the settlement rests on.
 */
abstract class OwnGcStrategy implements GcStrategy {

  private final OwnArtifactsStrategy engine = new OwnArtifactsStrategy();

  @Inject GcTypeConfig config;

  /** This type's facts. Injected by the subclass, which is all a subclass is for. */
  abstract GcTypeAdapter adapter();

  @Override
  public RepositoryType type() {
    return adapter().type();
  }

  @Override
  public boolean readsPins() {
    return true;
  }

  /**
   * Reads the configured window and lets the engine judge the adapter's identities.
   *
   * <p>The census is unused, and that is the honest shape rather than an omission: the census
   * carries blobs, and this rule is about identities, their releases and their access times. What
   * the type still retains comes back from the engine as the surviving identities' blobs, in the
   * census's own vocabulary.
   */
  @Override
  public Plan plan(LiveBlobCensus.Census census, GcPins pins) {
    if (!pins.complete()) {
      throw new IllegalStateException(
          "refusing to plan " + type().wireName() + " without live pins: " + pins.whyIncomplete());
    }
    GcPolicy policy = config.of(type()).strategy();
    if (policy != GcPolicy.OWN) {
      throw new IllegalStateException(
          type().wireName()
              + " is configured as '"
              + policy.name().toLowerCase(Locale.ROOT)
              + "' but this strategy only runs the own engine; set"
              + " qits.artifacts.gc.type."
              + type().wireName()
              + ".strategy=own or retire this bean");
    }
    GcTypeAdapter adapter = adapter();
    List<GcCandidate> candidates = adapter.enumerate();
    return engine.plan(
        adapter,
        candidates,
        config.requireWindow(type()),
        Instant.now(),
        keeps(adapter.pinnedBy(candidates, pins), pins));
  }

  /** Deletion is the adapter's own funnel — the engine condemns, the type removes. */
  @Override
  public Applied apply(Plan plan, GraceWindow grace) {
    return adapter().delete(plan, grace);
  }

  /**
   * The type's coordinate pins, with the digest pin as the floor under them.
   *
   * <p>Order matters only for the report: both answers are a keep, and naming the coordinate that
   * held an identity reads better than naming the bytes it happens to share.
   */
  private static GcPinned keeps(GcPinned byCoordinate, GcPins pins) {
    return candidate -> {
      String named = byCoordinate.pinnedBy(candidate);
      return named != null ? named : pins.pinsAnyBlob(candidate.blobs());
    };
  }
}
