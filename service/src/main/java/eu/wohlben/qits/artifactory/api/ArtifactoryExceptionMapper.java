package eu.wohlben.qits.artifactory.api;

import eu.wohlben.qits.artifactory.error.ArtifactoryException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

/**
 * Maps artifactory's framework-free {@link ArtifactoryException}s (carrying a status code) to HTTP
 * responses — the sibling of {@code DomainExceptionMapper}, kept here in {@code service} because
 * the artifactory module carries no JAX-RS (same stance as {@code domain}).
 */
@Provider
public class ArtifactoryExceptionMapper implements ExceptionMapper<ArtifactoryException> {

  @Override
  public Response toResponse(ArtifactoryException exception) {
    int status = exception.statusCode();
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      message = Response.Status.fromStatusCode(status).getReasonPhrase();
    }
    return Response.status(status)
        .entity(Map.of("message", message))
        .type(MediaType.APPLICATION_JSON)
        .build();
  }
}
