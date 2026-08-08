package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.artifacts.dto.MirrorUpstreamSummary;
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

  @Inject OciMirrorUpstreams upstreams;

  @Test
  void seedsTheTwoCiTypesThePlatformImageRepositoryTheTwoNpmRootsTheTwoMavenRootsTheDaemonAndDocsRootsAndTheThreeMirrorNamespaces() {
    seeder.ensureDefaults();
    assertEquals(
        Map.ofEntries(
            Map.entry(ArtifactsRepositorySeeder.CI_SCREENSHOTS, RepositoryType.CI_SCREENSHOTS),
            Map.entry(ArtifactsRepositorySeeder.CI_VIDEOS, RepositoryType.CI_VIDEOS),
            Map.entry(ArtifactsRepositorySeeder.IMAGES, RepositoryType.OCI_IMAGES),
            Map.entry(ArtifactsRepositorySeeder.NPM, RepositoryType.NPM_PACKAGES),
            Map.entry(ArtifactsRepositorySeeder.NPM_PROXY, RepositoryType.NPM_PROXY),
            Map.entry(ArtifactsRepositorySeeder.MAVEN, RepositoryType.MAVEN_PACKAGES),
            Map.entry(ArtifactsRepositorySeeder.MAVEN_CENTRAL, RepositoryType.MAVEN_PROXY),
            Map.entry(ArtifactsRepositorySeeder.DAEMONS, RepositoryType.DAEMON_BINARIES),
            Map.entry(ArtifactsRepositorySeeder.DOCS, RepositoryType.DOCS),
            Map.entry("hub", RepositoryType.OCI_MIRROR),
            Map.entry("quay", RepositoryType.OCI_MIRROR),
            Map.entry("redhat", RepositoryType.OCI_MIRROR)),
        seededTypesByName());
  }

  @Test
  void everyMirrorNamespaceIsSeededWithTheUpstreamItFronts() {
    // The pairing, from the boot path rather than from the migration: a repository row with no
    // upstream is a namespace nothing can be fetched into, and an upstream with no repository row is
    // a namespace nothing resolves to. Neither half is useful alone, so neither is written alone.
    seeder.ensureDefaults();
    assertEquals(
        Map.of(
            "docker.io", "hub",
            "quay.io", "quay",
            "registry.access.redhat.com", "redhat"),
        upstreams.list().stream()
            .collect(Collectors.toMap(MirrorUpstreamSummary::domain, MirrorUpstreamSummary::slug)));
  }

  @Test
  void reSeedingIsANoOp() {
    // Every boot runs it, so "the rows are already there" is the normal case rather than the edge:
    // the seed must never be what keeps a restarted instance from coming up.
    seeder.ensureDefaults();
    seeder.ensureDefaults();
    assertEquals(12, service.list().size());
    assertEquals(3, upstreams.list().size());
  }

  private Map<String, RepositoryType> seededTypesByName() {
    return service.list().stream().collect(Collectors.toMap(r -> r.name, r -> r.type));
  }
}
