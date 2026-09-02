package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.blobstore.control.ArtifactRepositoryService;
import eu.wohlben.qits.blobstore.control.CiScreenshotsProfile;
import eu.wohlben.qits.blobstore.control.CiVideosProfile;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * What a fresh deployment gets. The startup gate itself is {@code service}-side and never fires
 * under {@code TEST}, and the only suite that observes a real seed is {@code PackagedProcessIT},
 * which {@code mvn verify} skips — so the seeded set is asserted here, by name <em>and</em> type.
 * The type is the load-bearing half for {@code qits}: only an {@code OCI_IMAGES} row satisfies the
 * registry's first-segment check, and a row of the wrong type would still list under that name.
 *
 * <p>Eight rows, all hosted. The cache roots ({@code npmjs}, {@code central}, and the three mirror
 * namespaces with their upstream rows) went to qits-platform-mirror's own seeder with the code that
 * serves them, so their absence here is the assertion that matters most.
 */
@QuarkusTest
class ArtifactsRepositorySeederTest extends ArtifactsTestSupport {

  @Inject ArtifactsRepositorySeeder seeder;

  @Inject ArtifactRepositoryService service;

  @Test
  void seedsTheTwoCiTypesThePlatformImageRepositoryTheHostedNpmAndMavenRootsAndTheDaemonDocsAndSbomRoots() {
    seeder.ensureDefaults();
    assertEquals(
        Map.ofEntries(
            Map.entry(ArtifactsRepositorySeeder.CI_SCREENSHOTS, CiScreenshotsProfile.KEY),
            Map.entry(ArtifactsRepositorySeeder.CI_VIDEOS, CiVideosProfile.KEY),
            Map.entry(ArtifactsRepositorySeeder.IMAGES, OciImagesProfile.KEY),
            Map.entry(ArtifactsRepositorySeeder.NPM, NpmPackagesProfile.KEY),
            Map.entry(ArtifactsRepositorySeeder.MAVEN, MavenPackagesProfile.KEY),
            Map.entry(ArtifactsRepositorySeeder.DAEMONS, DaemonBinariesProfile.KEY),
            Map.entry(ArtifactsRepositorySeeder.DOCS, DocsProfile.KEY),
            Map.entry(ArtifactsRepositorySeeder.SBOMS, SbomProfile.KEY)),
        seededTypesByName());
  }

  @Test
  void reSeedingIsANoOp() {
    // Every boot runs it, so "the rows are already there" is the normal case rather than the edge:
    // the seed must never be what keeps a restarted instance from coming up.
    seeder.ensureDefaults();
    seeder.ensureDefaults();
    assertEquals(8, service.list().size());
  }

  private Map<String, String> seededTypesByName() {
    return service.list().stream().collect(Collectors.toMap(r -> r.name, r -> r.type));
  }
}
