package eu.wohlben.qits.artifacts.api;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Guards the artifacts <b>write</b> surface (POST upload + PUT repository ensure under {@code
 * /api/artifacts/}) with a single static token — this is a pure system API
 * (docs/epics/qits-artifacts/). The paths are on {@code auth-core}'s token-free {@code PublicPaths}
 * allowlist (their callers are CI processes in containers with no user session), so this filter is
 * the write protection.
 *
 * <p>The header is {@code X-Artifacts-Token}. When {@code qits.artifacts.token} is blank (the
 * dev/test default) the guard is a no-op, keeping dev and the suites friction-free. Reads (GET) are
 * never guarded — a blob must be usable directly as an {@code <img>}/{@code <video>} src.
 */
@Provider
public class ArtifactsTokenFilter implements ContainerRequestFilter {

  static final String TOKEN_HEADER = "X-Artifacts-Token";

  private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

  // Optional so a blank/absent value is "no token configured" (open) — an empty String value is
  // treated as absent by SmallRye Config and would fail a plain String injection.
  @ConfigProperty(name = "qits.artifacts.token")
  Optional<String> configuredToken;

  @Override
  public void filter(ContainerRequestContext requestContext) {
    String token = configuredToken.map(String::trim).filter(t -> !t.isEmpty()).orElse(null);
    if (token == null) {
      return; // open in dev/test — no token configured
    }
    // getPath() is relative to the JAX-RS base (/api); normalize any leading slash. A write to
    // /api/artifacts/... lands here as "artifacts/...".
    String path = requestContext.getUriInfo().getPath();
    if (path.startsWith("/")) {
      path = path.substring(1);
    }
    if (!path.startsWith("artifacts") || !WRITE_METHODS.contains(requestContext.getMethod())) {
      return;
    }
    if (!token.equals(requestContext.getHeaderString(TOKEN_HEADER))) {
      requestContext.abortWith(
          Response.status(Response.Status.UNAUTHORIZED)
              .entity(Map.of("message", "Missing or invalid " + TOKEN_HEADER))
              .type(MediaType.APPLICATION_JSON)
              .build());
    }
  }
}
