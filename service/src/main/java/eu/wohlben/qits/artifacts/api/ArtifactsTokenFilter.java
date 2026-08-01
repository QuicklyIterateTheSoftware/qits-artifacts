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
 * raw Vert.x routes — neither {@code /artifacts/git/*} nor the registry's {@code /v2/*}, and both
 * are unguarded <b>on purpose</b>: the git host trades on capability-url repo ids, and the registry
 * is deliberately tokenless (trusted producers on qits-net; external writes die on the gateway's
 * session policy — see {@code RegistryRoutes.init}). Setting this token guards the JSON API and
 * nothing else.
 */
@Provider
public class ArtifactsTokenFilter implements ContainerRequestFilter {

  static final String TOKEN_HEADER = "X-Artifacts-Token";

  private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

  /**
   * The JAX-RS base-relative prefixes this filter claims. A resource served outside them is
   * <b>unguarded</b>, so this set is extended when one is added rather than assumed to cover it.
   * {@code store} holds only the read-only store summary today and is listed so that stays a choice.
   * {@code gc} holds only the dry-run plan, and is listed for a sharper reason: the day anything
   * under it writes, it deletes bytes, and inheriting the guard beats remembering it.
   * {@code mirror-upstreams} is the first entry here that was added <em>with</em> its writes rather
   * than ahead of them: registering an upstream is what decides which public registry this service
   * dials on a miss, so shipping that route unguarded would be handing out an outbound fetch.
   */
  private static final Set<String> GUARDED_PREFIXES =
      Set.of("repositories", "store", "gc", "mirror-upstreams");

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
    String guardedPath = path;
    if (GUARDED_PREFIXES.stream().noneMatch(guardedPath::startsWith)
        || !WRITE_METHODS.contains(requestContext.getMethod())) {
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
