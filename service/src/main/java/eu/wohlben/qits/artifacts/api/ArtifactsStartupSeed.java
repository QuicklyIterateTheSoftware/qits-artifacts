package eu.wohlben.qits.artifacts.api;

import eu.wohlben.qits.artifacts.control.ArtifactsRepositorySeeder;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Startup gate that self-seeds the two default CI repository types ({@code ci-screenshots}, {@code
 * ci-videos}) so a fresh deployment has them out of the box (the user-chosen provisioning model;
 * the {@code PUT} lifecycle endpoint still exists for any others). Mirrors {@code StartupSelfSeed}:
 * lives in {@code service} so it fires only when the web app boots (never the cli), and runs
 * additively via the idempotent {@link ArtifactsRepositorySeeder#ensureDefaults()}.
 *
 * <p>Runs in {@link LaunchMode#NORMAL} and {@code DEVELOPMENT} (so {@code quarkus:dev} is usable
 * immediately) but never under {@code TEST} — {@code @QuarkusTest} suites create the repositories
 * they need. The ensure is trivial and local (its own H2), so it runs inline on the startup thread.
 */
@ApplicationScoped
public class ArtifactsStartupSeed {

  private static final Logger LOG = Logger.getLogger(ArtifactsStartupSeed.class);

  @Inject ArtifactsRepositorySeeder seeder;

  @ConfigProperty(name = "qits.artifacts.startup-seed.enabled", defaultValue = "true")
  boolean enabled;

  void onStart(@Observes StartupEvent event) {
    if (!shouldSeed(LaunchMode.current(), enabled)) {
      return;
    }
    try {
      seeder.ensureDefaults();
    } catch (RuntimeException e) {
      LOG.error(
          "Artifacts default-repository seed failed — instance is usable; retried next boot.", e);
    }
  }

  /** Packaged and dev launches only, and only when enabled — the test suites never self-seed. */
  static boolean shouldSeed(LaunchMode mode, boolean enabled) {
    return enabled && (mode == LaunchMode.NORMAL || mode == LaunchMode.DEVELOPMENT);
  }
}
