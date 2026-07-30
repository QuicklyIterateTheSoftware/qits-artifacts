package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.artifacts.entity.RepositoryType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * What a fresh deployment gets. The startup gate itself is {@code service}-side and never fires
 * under {@code TEST}, and the only suite that observes a real seed is {@code PackagedProcessIT},
 * which {@code mvn verify} skips — so the seeded set is asserted here, by name <em>and</em> type.
 * The type is the load-bearing half for {@code qits}: only an {@code oci-images} row satisfies the
 * registry's first-segment check, and a row of the wrong type would still list under that name.
 */
@QuarkusTest
class ArtifactsRepositorySeederTest extends ArtifactsTestSupport {

  @Inject ArtifactsRepositorySeeder seeder;

  @Inject ArtifactRepositoryService service;

  @Test
  void seedsTheTwoCiTypesAndThePlatformImageRepository() {
    seeder.ensureDefaults();
    assertEquals(
        Map.of(
            ArtifactsRepositorySeeder.CI_SCREENSHOTS, RepositoryType.CI_SCREENSHOTS,
            ArtifactsRepositorySeeder.CI_VIDEOS, RepositoryType.CI_VIDEOS,
            ArtifactsRepositorySeeder.IMAGES, RepositoryType.OCI_IMAGES),
        seededTypesByName());
  }

  @Test
  void reSeedingIsANoOp() {
    // Every boot runs it, so "the rows are already there" is the normal case rather than the edge:
    // the seed must never be what keeps a restarted instance from coming up.
    seeder.ensureDefaults();
    seeder.ensureDefaults();
    assertEquals(3, service.list().size());
  }

  private Map<String, RepositoryType> seededTypesByName() {
    return service.list().stream().collect(Collectors.toMap(r -> r.name, r -> r.type));
  }
}
