package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The configured-image pin adapter against a stub serving qits-configuration's real response shape.
 *
 * <p>{@code GET /configuration/api/pins} answers {@code {"generatedAt":…,"pins":[{"image","version",
 * "application","key"}]}} — one row per configured image-version entry that has a value. Two rows
 * may name the same image under different applications, which is the shape this suite fixes: the
 * keep-set is a set of {@code image:version} coordinates and the two rows are one keep.
 *
 * <p>Plain JUnit, no Quarkus, for the reason {@link CdHttpDeploymentPinsTest} gives.
 */
class ConfigurationHttpImagePinsTest {

  private static final String BODY =
      """
      {"generatedAt":"2026-09-04T20:00:00Z",
       "pins":[
         {"image":"qits/project-agent","version":"2026.904.160152","application":"qits-projects",
          "key":"env.QITS_PROJECTS_AGENT_IMAGE_VERSION"},
         {"image":"qits/workspace","version":"2026.904.160522","application":"qits-workspaces",
          "key":"env.QITS_WORKSPACE_IMAGE_VERSION"},
         {"image":"qits/workspace","version":"2026.904.160522","application":"qits-projects",
          "key":"env.QITS_PROJECTS_REFINEMENT_IMAGE_VERSION"}
       ]}
      """;

  @Test
  void everyConfiguredEntryIsReadAndTwoApplicationsSharingAnImageAreTwoRows() throws IOException {
    // The port answers rows, not coordinates: which application and which key held a version is the
    // provenance a receipt shows. Folding them into one keep is the aggregate's job, and this is
    // where the two halves are kept apart.
    try (StubPinService configuration = StubPinService.serving("/pins", BODY)) {
      List<ConfigurationImagePins.ImagePin> pins = adapter(configuration.baseUrl()).pins();

      assertEquals(3, pins.size());
      assertEquals("qits/project-agent", pins.get(0).image());
      assertEquals("2026.904.160152", pins.get(0).version());
      assertEquals("qits-projects", pins.get(0).application());
      assertEquals("env.QITS_PROJECTS_AGENT_IMAGE_VERSION", pins.get(0).key());
      assertEquals("qits/workspace", pins.get(1).image());
      assertEquals("qits/workspace", pins.get(2).image());
      assertEquals("qits-projects", pins.get(2).application());
    }
  }

  @Test
  void anEmptyPinsArrayIsAnAnswerAndPinsNothing() throws IOException {
    // An entry with no stored value is omitted rather than sent blank — never released means nothing
    // to keep — so a platform that has released none of these images answers with an empty array,
    // and it must parse.
    try (StubPinService configuration = StubPinService.serving("/pins", "{\"pins\":[]}")) {
      assertEquals(List.of(), adapter(configuration.baseUrl()).pins());
    }
  }

  @Test
  void aShapeThisCannotReadIsRefusedRatherThanReadAsNothingConfigured() throws IOException {
    // The hazard this refusal exists for: the configured workspace image is pulled on demand and no
    // deployment row names it, so a keep-set read as empty here deletes exactly the image the next
    // click needs.
    try (StubPinService configuration = StubPinService.serving("/pins", "{\"entries\":[]}")) {
      assertTrue(
          assertThrows(IllegalStateException.class, () -> adapter(configuration.baseUrl()).pins())
              .getMessage()
              .contains("'pins' array"));
    }
    try (StubPinService configuration =
        StubPinService.serving("/pins", "{\"pins\":[{\"image\":\"qits/workspace\"}]}")) {
      assertTrue(
          assertThrows(IllegalStateException.class, () -> adapter(configuration.baseUrl()).pins())
              .getMessage()
              .contains("no image or no version"));
    }
  }

  @Test
  void aNon200AndAClosedPortBothRefuseWithTheUrlInTheMessage() throws IOException {
    try (StubPinService configuration = StubPinService.answering("/pins", 500, "boom")) {
      assertTrue(
          assertThrows(IllegalStateException.class, () -> adapter(configuration.baseUrl()).pins())
              .getMessage()
              .contains("500"));
    }
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () -> adapter("http://127.0.0.1:1/configuration/api").pins())
            .getMessage()
            .contains("unreachable"));
  }

  /** The adapter as CDI would build it: the two config values and the mapper. */
  private static ConfigurationHttpImagePins adapter(String baseUrl) {
    ConfigurationHttpImagePins pins = new ConfigurationHttpImagePins();
    pins.baseUrl = baseUrl;
    pins.timeout = Duration.ofSeconds(5);
    pins.objectMapper = new ObjectMapper();
    return pins;
  }
}
