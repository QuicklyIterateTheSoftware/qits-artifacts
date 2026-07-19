package eu.wohlben.qits.artifacts.api;

import eu.wohlben.qits.artifacts.error.ArtifactsException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

/**
 * Maps artifacts's framework-free {@link ArtifactsException}s (carrying a status code) to HTTP
 * responses — the sibling of {@code DomainExceptionMapper}, kept here in {@code service} because
 * the artifacts module carries no JAX-RS (same stance as {@code domain}).
 */
@Provider
public class ArtifactsExceptionMapper implements ExceptionMapper<ArtifactsException> {

  @Override
  public Response toResponse(ArtifactsException exception) {
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
