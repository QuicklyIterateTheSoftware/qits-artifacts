package eu.wohlben.qits.artifacts.gc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The fourth and last outbound pin call: {@code GET /configuration/api/pins}, at plan time, every
 * time.
 *
 * <p>Everything {@link CdHttpDeploymentPins}' javadoc says about the direction, the caching and the
 * failure posture holds here verbatim. The fact this one adds is the pull that has not happened
 * <b>and is not a deployment</b>: a workspace image, an editor image, a project agent image is
 * started on demand from a version held in a configuration entry, so no deployment row names it and
 * no timestamp in this store implies it.
 *
 * <p><b>An empty {@code pins} array is an answer</b> — a platform that has released none of these
 * images yet — and it pins nothing, which is what it says. A missing array is not: that is a shape
 * this cannot read, and reading it as "nothing is configured" would condemn exactly the image the
 * next launch pulls.
 *
 * <p>The {@code HttpClient} is an <b>instance</b> field for the native-image reason every outbound
 * client in this repository carries; the rule travels with the client.
 */
@ApplicationScoped
public class ConfigurationHttpImagePins implements ConfigurationImagePins {

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @ConfigProperty(name = "qits.artifacts.gc.pins.configuration-base-url")
  String baseUrl;

  @ConfigProperty(name = "qits.artifacts.gc.pins.configuration-timeout")
  Duration timeout;

  @Inject ObjectMapper objectMapper;

  @Override
  public String url() {
    return baseUrl + "/pins";
  }

  @Override
  public List<ImagePin> pins() {
    String url = url();
    return parse(get(url), url);
  }

  /**
   * qits-configuration's answer, read into pins — the parsing half, with no transport in it.
   *
   * <p>Separate from {@link #pins()} for the reason {@link CdHttpDeploymentPins#parse} gives: the
   * same document arrives two ways, fetched here or handed in by qits-platform-orchestrator, and one
   * reader for both is what stops one document being read two ways.
   *
   * @param body the response body, verbatim
   * @param origin where it came from, for the message a refusal carries
   * @throws IllegalStateException the shape is one this cannot read. Never an empty list on doubt.
   */
  public static List<ImagePin> parse(JsonNode body, String origin) {
    if (body == null || !body.isObject()) {
      throw new IllegalStateException("qits-configuration answered a non-object body for " + origin);
    }
    JsonNode pins = body.get("pins");
    if (pins == null || !pins.isArray()) {
      throw new IllegalStateException(
          "qits-configuration answered without a 'pins' array for " + origin);
    }
    List<ImagePin> rows = new ArrayList<>();
    for (JsonNode pin : pins) {
      String image = text(pin, "image");
      String version = text(pin, "version");
      if (image == null || version == null) {
        // A configured entry this cannot name pins nothing, and guessing is how a live image gets
        // deleted. Refusing the whole answer is the honest response to a shape this does not know.
        throw new IllegalStateException(
            "qits-configuration returned a pin with no image or no version");
      }
      rows.add(new ImagePin(image, version, text(pin, "application"), text(pin, "key")));
    }
    return List.copyOf(rows);
  }

  /** Reads the JSON body of one GET, or throws with the url in the message. */
  private JsonNode get(String url) {
    try {
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200) {
        throw new IllegalStateException(
            "qits-configuration answered " + response.statusCode() + " for " + url);
      }
      return objectMapper.readTree(response.body());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while reading " + url, interrupted);
    } catch (IllegalStateException refused) {
      throw refused;
    } catch (Exception unreachable) {
      throw new IllegalStateException(
          "qits-configuration unreachable at " + url + ": " + unreachable, unreachable);
    }
  }

  private static String text(JsonNode row, String field) {
    JsonNode value = row == null ? null : row.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    String read = value.asText().trim();
    return read.isEmpty() ? null : read;
  }
}
