package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.control.LiveBlobCensus;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Locale;

/**
 * What binds a cache type's {@link GcTypeAdapter} to {@link CacheEvictionStrategy} — wiring, and
 * deliberately nothing else.
 *
 * <p>The settlement's shape has three parts and this is the third: the <b>rule</b> is the engine's,
 * written once; the <b>facts</b> are the adapter's, one per type; and something has to read the
 * configuration, hand the engine the window, and route deletion back to the adapter. That is all
 * that lives here. No line below decides what dies, which is what keeps a shared base class from
 * being the retention framework the plan forbids — the forbidden thing is two types sharing a
 * <em>rule</em> they only appear to agree on, and these two share an engine the user chose for both
 * by name.
 *
 * <p><b>The configured policy is checked rather than assumed.</b> A deployment can move a type
 * between engines with an environment variable, and a subclass of this class running the cache
 * engine over a type someone reconfigured as {@code own} would silently delete the releases that
 * configuration meant to protect. So a mismatch refuses, and the refusal lands on that type's line
 * in the report.
 *
 * <p><b>Pins are read, so {@link #readsPins()} is true.</b> Nothing pins a mirror tag or a cached
 * npm version <em>by coordinate</em> — qits-cd pins application image shas and qits-ci pins daemon
 * versions, neither of which lives in a cache namespace — but a pin can name a <b>blob</b> by
 * digest, and blobs dedupe globally, so the same bytes can be reachable through a cache row. The
 * check costs one set lookup per identity and the alternative is planning a type against "nothing is
 * pinned", which is the documented way to condemn something a live service still fetches. Declaring
 * it means a run whose pins are incomplete does not collect these types either — which costs
 * nothing operationally, because such a run aborts the sweep whole in any case.
 */
abstract class CacheGcStrategy implements GcStrategy {

  private final CacheEvictionStrategy engine = new CacheEvictionStrategy();

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
   * carries blobs, and this rule is about identities and their access times. What the type still
   * retains comes back from the engine as the surviving identities' blobs, in the census's own
   * vocabulary.
   */
  @Override
  public Plan plan(LiveBlobCensus.Census census, GcPins pins) {
    if (!pins.complete()) {
      throw new IllegalStateException(
          "refusing to plan " + type().wireName() + " without live pins: " + pins.whyIncomplete());
    }
    GcPolicy policy = config.of(type()).strategy();
    if (policy != GcPolicy.CACHE) {
      throw new IllegalStateException(
          type().wireName()
              + " is configured as '"
              + policy.name().toLowerCase(Locale.ROOT)
              + "' but this strategy only runs the cache engine; set"
              + " qits.artifacts.gc.type."
              + type().wireName()
              + ".strategy=cache or retire this bean");
    }
    return engine.plan(adapter(), config.requireWindow(type()), Instant.now(), pinnedBy(pins));
  }

  /** Deletion is the adapter's own funnel — eviction removes exactly what the plan condemned. */
  @Override
  public Applied apply(Plan plan, GraceWindow grace) {
    return adapter().delete(plan, grace);
  }

  /**
   * The keep-class checked before the access rule: a live pin naming one of this identity's blobs
   * by digest.
   *
   * <p>Only the digest half of {@link GcPins} can fire on a cache, for the reason the class javadoc
   * gives, and the rule comes back as qits-ci's own sentence so a report names what saved the row
   * rather than merely that something did.
   */
  private static GcPinned pinnedBy(GcPins pins) {
    return candidate -> pins.pinsAnyBlob(candidate.blobs());
  }
}
