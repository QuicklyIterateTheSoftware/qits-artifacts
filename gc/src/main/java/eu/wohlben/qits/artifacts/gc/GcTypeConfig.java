package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithParentName;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * The type → (strategy, window) mapping the settlement made configuration:
 * {@code qits.artifacts.gc.type.<wire-name>.strategy} and {@code ….window}.
 *
 * <p>The shipped values are the settlement's numbers — P30D for the caches and for
 * oci-images/npm-packages, P90D for maven-packages and daemon-binaries — and they live in this
 * module's own {@code META-INF/microprofile-config.properties}, beside the code that reads them. A
 * deployment overrides any of them with an environment variable and needs no new code, which is the
 * point of the settlement's "changeable anytime".
 *
 * <p><b>The prefix is {@code …gc.type} rather than {@code …gc}, and that is deliberate.</b> A
 * mapping rooted at {@code qits.artifacts.gc} would claim a namespace that already holds keys other
 * classes read ({@code blob-grace-period}, the pin urls), and a mapped prefix reports its unmapped
 * siblings. {@link WithParentName} puts the map at the root of the narrower prefix, so the property
 * names come out exactly as the settlement spells them.
 *
 * <p><b>A type with no entry is an error, not a default.</b> {@link #of} throws rather than assuming
 * {@code excluded}: a repository type nobody configured is a decision nobody took, and defaulting it
 * silently is how a new type ships uncollected with nothing in the report to say so. Contributing a
 * {@link RepositoryTypeProfile} therefore means adding two lines of configuration, which {@code
 * GcTypeConfigTest} holds by looping over the registered keys.
 */
@ConfigMapping(prefix = "qits.artifacts.gc.type")
public interface GcTypeConfig {

  /** Keyed by the type's wire name — {@code oci-mirror}, {@code npm-packages}, … */
  @WithParentName
  Map<String, TypeEntry> types();

  /** One type's configured policy. */
  interface TypeEntry {

    /** Which engine collects this type. */
    GcPolicy strategy();

    /**
     * How long an identity may sit unaccessed before it is eligible, ISO-8601.
     *
     * <p>Optional because an {@link GcPolicy#EXCLUDED} type has no window to configure, and a
     * plausible-looking number beside a type nobody collects reads as a rule that is running. The
     * two engines take a real window, so {@link #requireWindow} is what a caller uses.
     */
    Optional<Duration> window();
  }

  /**
   * One type's entry, or a refusal naming the missing key.
   *
   * @throws IllegalStateException the type has no configuration at all
   */
  default TypeEntry of(String type) {
    String wireName = RepositoryTypeProfile.wireNameOf(type);
    TypeEntry entry = types().get(wireName);
    if (entry == null) {
      throw new IllegalStateException(
          "no garbage collection policy configured for "
              + wireName
              + "; set qits.artifacts.gc.type."
              + wireName
              + ".strategy (own or excluded)");
    }
    return entry;
  }

  /**
   * The window a collected type is configured with.
   *
   * @throws IllegalStateException a collected type has no window — the engine needs one, and
   *     guessing a window is guessing what may be deleted
   */
  default Duration requireWindow(String type) {
    String wireName = RepositoryTypeProfile.wireNameOf(type);
    return of(type)
        .window()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "no window configured for "
                        + wireName
                        + "; set qits.artifacts.gc.type."
                        + wireName
                        + ".window to an ISO-8601 duration such as P30D"));
  }
}
