package eu.wohlben.qits.artifactory.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.artifactory.entity.RepositoryType;
import eu.wohlben.qits.artifactory.error.BadRequestException;
import eu.wohlben.qits.artifactory.error.NotFoundException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ArtifactRepositoryServiceTest extends ArtifactoryTestSupport {

  @Inject ArtifactRepositoryService service;

  @Test
  void ensureIsIdempotent() {
    var first = service.ensure("shots", RepositoryType.CI_SCREENSHOTS);
    var second = service.ensure("shots", RepositoryType.CI_SCREENSHOTS);
    assertEquals(first.name, second.name);
    assertEquals(1, service.list().size());
  }

  @Test
  void ensureRejectsATypeChange() {
    service.ensure("shots", RepositoryType.CI_SCREENSHOTS);
    assertThrows(
        BadRequestException.class, () -> service.ensure("shots", RepositoryType.CI_VIDEOS));
  }

  @Test
  void requireFailsOnUnknownRepository() {
    assertThrows(NotFoundException.class, () -> service.require("nope"));
  }
}
