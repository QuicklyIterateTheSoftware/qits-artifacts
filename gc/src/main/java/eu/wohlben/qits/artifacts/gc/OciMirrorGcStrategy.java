package eu.wohlben.qits.artifacts.gc;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The mirror's rule, live: <b>everything unaccessed past the configured window is evicted.</b>
 *
 * <p>This class used to say "nothing dies, append-only pending access tracking" (⚖2,
 * proxy-pulling-normal-images.md). That was a decision with a condition attached, and <b>the
 * condition is met</b>: access tracking shipped for {@code oci_tag} and {@code oci_manifest} in V9,
 * and the settlement of 2026-08-05 configured this type as a {@code cache}. So the pin the old test
 * held — "this type condemns nothing, ever" — is replaced deliberately rather than eroded. What
 * ships now is what the recorded price was always going to be paid off by: the estimated 1.5–2.5 GiB
 * one-time fill stays only for as long as something keeps pulling it, and the low-GiB-per-year drift
 * from upstream tag movement ({@code jdk-25} rebinding and stranding the manifest it named) now ages
 * out on its own.
 *
 * <p><b>Why the type still exists separately from {@code oci-images}, unchanged by any of this.</b>
 * Mirror tags — {@code jdk-25}, {@code 9.6}, {@code latest} — are neither calver releases nor build
 * shas, so under docker's rules every one of them would land on the unclassified-means-keep
 * backstop: the same outcome reported as a rule that nearly fired. The two types now run different
 * <em>engines</em>, which is the sharper form of the same argument — own-ness earns version
 * protection, and a cache has none to earn.
 *
 * <p>Everything below the rule sits somewhere else by design: the rule is {@link
 * CacheEvictionStrategy}'s, the wiring is {@link CacheGcStrategy}'s, and the facts — what a cached
 * identity is, when it was last pulled, how a row goes — are {@link OciMirrorGcAdapter}'s. This
 * class is the bean the planner finds and the name a reviewer reads on the report line.
 *
 * <p>{@code @Singleton} rather than {@code @ApplicationScoped}, for the report's sake: a
 * normal-scoped bean answers {@code getClass().getSimpleName()} through its client proxy.
 */
@Singleton
public class OciMirrorGcStrategy extends CacheGcStrategy {

  @Inject OciMirrorGcAdapter mirror;

  @Override
  GcTypeAdapter adapter() {
    return mirror;
  }
}
