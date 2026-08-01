package eu.wohlben.qits.artifacts.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The one place this service dials qits-cd: two GETs on qits-net, at plan time, every time.
 *
 * <p><b>This is a deliberate new dependency direction</b> and the only one this repository has. The
 * artifacts service is domain-blind by design, and it learns where cd lives for exactly one reason:
 * the docker keep-set is "which shas would a restart pull", and cd is the only thing that knows. The
 * alternative — a driver assembling the pin list and posting it in — was considered and rejected in
 * the GC plan (⚖4): a safety-critical input computed outside the service that acts on it is a place
 * for the two to drift, and drift here deletes an image a container is about to restart from.
 *
 * <p><b>Never cached.</b> A pin list is only true at the moment it was read; a cached one deletes the
 * image that was deployed while the cache was warm. The cost is two HTTP calls per plan, which is a
 * report a person requests.
 *
 * <p>Environment discovery is a listing rather than a configured id, and the keep-set is the
 * <b>union over every environment</b>. Naming one environment in config would mean a second
 * environment's ACTIVE deployments pin nothing, which is the failure that ends in {@code
 * IMAGE_MISSING} on its next restart. Over-keeping is the safe direction here and under-keeping is
 * not, so nothing here tries to pick "the live one".
 *
 * <p>Every failure — connect, status, shape — is an {@link IllegalStateException}. Fail-closed: the
 * planner reports the type as failed, the sweep keeps the whole OCI census set, and the run reclaims
 * nothing. There is deliberately no empty-list fallback, because an empty answer condemns every tag.
 */
@ApplicationScoped
public class CdHttpDeploymentPins implements CdDeploymentPins {

  /**
   * An <b>instance</b> field, not a static one, for the reason {@code CiPostReceiveNotifier} spells
   * out at length: a static {@code HttpClient} is built by the class initialiser, which under
   * GraalVM is image-build time, and native-image refuses an {@code HttpClientFacade} in the image
   * heap. The bean is {@code @ApplicationScoped}, so there is still one client per process.
   */
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @ConfigProperty(name = "qits.artifacts.gc.oci.cd-base-url")
  String baseUrl;

  @ConfigProperty(name = "qits.artifacts.gc.oci.cd-timeout")
  Duration timeout;

  @Inject ObjectMapper objectMapper;

  @Override
  public List<Deployment> deployments() {
    List<Deployment> rows = new ArrayList<>();
    for (String environmentId : environmentIds()) {
      JsonNode listing =
          get(
              "/deployments?environmentId="
                  + URLEncoder.encode(environmentId, StandardCharsets.UTF_8));
      for (JsonNode row : array(listing, "deployments")) {
        String application = text(row, "applicationName");
        String commitSha = text(row, "commitSha");
        if (application == null || commitSha == null) {
          // A row cd cannot name an image for pins nothing, and guessing is how a pinned tag gets
          // deleted. Refusing the whole plan is the honest answer to a shape this does not know.
          throw new IllegalStateException(
              "qits-cd returned a deployment with no applicationName or commitSha");
        }
        rows.add(
            new Deployment(
                text(row, "applicationId"), application, commitSha, text(row, "status")));
      }
    }
    return List.copyOf(rows);
  }

  private List<String> environmentIds() {
    List<String> ids = new ArrayList<>();
    for (JsonNode environment : array(get("/environments"), "environments")) {
      String id = text(environment, "id");
      if (id != null) {
        ids.add(id);
      }
    }
    return ids;
  }

  /** Reads the JSON body of one GET, or throws with the url in the message. */
  private JsonNode get(String path) {
    String url = baseUrl + path;
    try {
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200) {
        throw new IllegalStateException(
            "qits-cd answered " + response.statusCode() + " for " + url);
      }
      JsonNode body = objectMapper.readTree(response.body());
      if (body == null || !body.isObject()) {
        throw new IllegalStateException("qits-cd answered a non-object body for " + url);
      }
      return body;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while reading " + url, interrupted);
    } catch (IllegalStateException refused) {
      throw refused;
    } catch (Exception unreachable) {
      throw new IllegalStateException(
          "qits-cd unreachable at " + url + ": " + unreachable, unreachable);
    }
  }

  private static Iterable<JsonNode> array(JsonNode body, String field) {
    JsonNode array = body.get(field);
    if (array == null || !array.isArray()) {
      throw new IllegalStateException("qits-cd answered without a '" + field + "' array");
    }
    return array;
  }

  private static String text(JsonNode row, String field) {
    JsonNode value = row.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }
}
