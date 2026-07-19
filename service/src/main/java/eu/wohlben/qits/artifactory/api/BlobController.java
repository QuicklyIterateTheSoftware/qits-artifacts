package eu.wohlben.qits.artifactory.api;

import eu.wohlben.qits.artifactory.control.ArtifactQueryService;
import eu.wohlben.qits.artifactory.control.BlobService;
import eu.wohlben.qits.artifactory.dto.ArtifactRecordDto;
import eu.wohlben.qits.artifactory.dto.UploadResult;
import eu.wohlben.qits.artifactory.mapper.ArtifactRecordMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * The artifactory blob boundary (docs/epics/qits-artifactory/): upload (raw body stream + {@code
 * X-Artifactory-Meta-*} headers), serve by content id, and metadata query with the {@code latest}
 * collapse. Hidden from the OpenAPI document (a wire/system API). Uploads (POST) are token-guarded
 * by {@code ArtifactoryTokenFilter}; GET is open so a blob is usable directly as an {@code
 * <img>}/{@code <video>} src.
 *
 * <p>The upload injects the body as an {@link InputStream} — RESTEasy Reactive streams it, so
 * {@link BlobService} writes it to disk incrementally (no whole-video buffer). The wire size is
 * bounded by {@code quarkus.http.limits.max-body-size} (a hard global ceiling on every route — see
 * the service config), sized to the largest type cap; the specific per-type cap is enforced while
 * streaming.
 */
@Path("/artifactory/repositories/{repo}/blobs")
public class BlobController {

  /**
   * Metadata rides these request headers (flat strings map cleanly to headers). Case-insensitive.
   */
  static final String META_PREFIX = "X-Artifactory-Meta-";

  private static final String META_PREFIX_LC = META_PREFIX.toLowerCase(Locale.ROOT);

  @Inject BlobService blobService;

  @Inject ArtifactQueryService queryService;

  @Inject ArtifactRecordMapper recordMapper;

  @POST
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(hidden = true)
  public Response upload(
      @PathParam("repo") String repo,
      @HeaderParam(HttpHeaders.CONTENT_TYPE) String contentType,
      @Context HttpHeaders headers,
      InputStream body) {
    UploadResult result = blobService.upload(repo, contentType, metadataFrom(headers), body);
    return Response.status(Response.Status.CREATED).entity(result).build();
  }

  @GET
  @Path("/{id}")
  @Operation(hidden = true)
  public Response serve(@PathParam("repo") String repo, @PathParam("id") String id) {
    BlobService.BlobContent content = blobService.serve(repo, id);
    return Response.ok(content.stream())
        .type(content.mediatype())
        .header(HttpHeaders.CONTENT_LENGTH, content.size())
        // Content-addressed ids never change meaning — cache aggressively and immutably.
        .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
        .build();
  }

  public record ListBlobsResponse(List<ArtifactRecordDto> records) {}

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(hidden = true)
  public ListBlobsResponse query(@PathParam("repo") String repo, @Context UriInfo uriInfo) {
    Map<String, String> predicates = new LinkedHashMap<>();
    boolean latest = false;
    for (var entry : uriInfo.getQueryParameters().entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue().isEmpty() ? "" : entry.getValue().get(0);
      if (key.startsWith("meta.")) {
        predicates.put(key.substring("meta.".length()), value);
      } else if (key.equals("latest")) {
        latest = value.isBlank() || Boolean.parseBoolean(value);
      }
    }
    var records =
        queryService.query(repo, predicates, latest).stream().map(recordMapper::toDto).toList();
    return new ListBlobsResponse(records);
  }

  /** Collects {@code X-Artifactory-Meta-<key>} request headers into the flat metadata map. */
  private static Map<String, String> metadataFrom(HttpHeaders headers) {
    Map<String, String> metadata = new LinkedHashMap<>();
    headers
        .getRequestHeaders()
        .forEach(
            (name, values) -> {
              if (name.toLowerCase(Locale.ROOT).startsWith(META_PREFIX_LC) && !values.isEmpty()) {
                metadata.put(name.substring(META_PREFIX.length()), values.get(0));
              }
            });
    return metadata;
  }
}
