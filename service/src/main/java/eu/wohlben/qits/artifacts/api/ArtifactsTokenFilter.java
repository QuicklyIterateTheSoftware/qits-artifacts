package eu.wohlben.qits.artifacts.api;

import eu.wohlben.qits.artifacts.control.ArtifactsToken;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;
import java.util.Set;

/**
 * Guards the artifacts <b>write</b> surface (POST upload + PUT repository ensure under {@code
 * /artifacts/api/repositories/}) with a single static token — this is a pure system API
 * (docs/epics/qits-artifacts/). The paths are on {@code auth-core}'s token-free {@code PublicPaths}
 * allowlist (their callers are CI processes in containers with no user session), so this filter is
 * the write protection.
 *
 * <p>The header is {@code X-Artifacts-Token}. When {@code qits.artifacts.token} is blank (the
 * dev/test default) the guard is a no-op, keeping dev and the suites friction-free. Reads (GET) are
 * never guarded — a blob must be usable directly as an {@code <img>}/{@code <video>} src.
 *
 * <p>This filter is JAX-RS, so it sees only what RESTEasy dispatches. It does <b>not</b> run on the
 * raw Vert.x routes — neither {@code /artifacts/git/*} nor the registry's {@code /v2/*}, which
 * carries its own guard in {@code RegistryAuthGuard}. The two share the secret and the blank-is-open
 * rule through {@link ArtifactsToken}, and nothing else.
 */
@Provider
public class ArtifactsTokenFilter implements ContainerRequestFilter {

  static final String TOKEN_HEADER = "X-Artifacts-Token";

  private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

  @Inject ArtifactsToken token;

  @Override
  public void filter(ContainerRequestContext requestContext) {
    if (!token.enforced()) {
      return; // open in dev/test — no token configured
    }
    // getPath() is relative to the JAX-RS base (quarkus.rest.path, /artifacts/api); normalize any
    // leading slash. A write to /artifacts/api/repositories/... lands here as "repositories/...".
    // The prefix is "repositories" and not "artifacts" because the segment carries that now — the
    // resource @Paths dropped it. Every JAX-RS resource this service ships is under repositories/,
    // so the match is the whole write surface, exactly as it was.
    String path = requestContext.getUriInfo().getPath();
    if (path.startsWith("/")) {
      path = path.substring(1);
    }
    if (!path.startsWith("repositories") || !WRITE_METHODS.contains(requestContext.getMethod())) {
      return;
    }
    if (!token.matches(requestContext.getHeaderString(TOKEN_HEADER))) {
      requestContext.abortWith(
          Response.status(Response.Status.UNAUTHORIZED)
              .entity(Map.of("message", "Missing or invalid " + TOKEN_HEADER))
              .type(MediaType.APPLICATION_JSON)
              .build());
    }
  }
}
