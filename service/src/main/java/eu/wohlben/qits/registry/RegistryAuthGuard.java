package eu.wohlben.qits.registry;

import eu.wohlben.qits.artifacts.control.ArtifactsToken;
import eu.wohlben.qits.artifacts.error.OciCode;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

/**
 * The write guard on {@code /v2}, and the header every registry response carries.
 *
 * <p>{@code ArtifactsTokenFilter} cannot do this job: it is a JAX-RS {@code ContainerRequestFilter}
 * and RESTEasy never sees a raw Vert.x route, so {@code /v2/*} is unguarded by construction — the
 * same way {@code /artifacts/git/*} is. The two guards share the secret and the blank-is-open rule
 * through {@link ArtifactsToken}, and nothing else.
 *
 * <p><b>The credential is HTTP Basic</b>, username ignored, password = {@code qits.artifacts.token}.
 * Not because Basic is good, but because it is what a registry client can send: {@code skopeo
 * --dest-creds}, {@code podman push --creds} and {@code docker login} all speak it, and none of them
 * can be told to set a custom header.
 *
 * <p><b>Reads are never guarded</b>, and {@code GET /v2/} is unconditionally 200, so an anonymous
 * {@code docker pull} works with no login at all. What that costs depends on the client, and the
 * answer is not the one the design notes originally guessed — both halves below were measured
 * against a running registry rather than reasoned about:
 *
 * <ul>
 *   <li><b>docker works.</b> Tested with docker 29.6.2: after {@code docker login}, {@code docker
 *       push} succeeds. The client retries a {@code 401} with its stored credentials regardless of
 *       what the {@code /v2/} ping answered, so a non-challenging ping costs it nothing. Without a
 *       login it fails cleanly with "no basic auth credentials".
 *   <li><b>skopeo and podman do not.</b> Both are built on {@code containers/image}, which decides
 *       the auth scheme from the ping: a 200 with no {@code WWW-Authenticate} is read as "this
 *       registry needs no credentials", so {@code --creds} is never sent and the {@code 401} on the
 *       first upload is fatal rather than retried. Tested with skopeo 1.x and podman 5.x: {@code
 *       skopeo copy --dest-creds} and {@code podman push --creds} both fail against a
 *       token-guarded registry.
 * </ul>
 *
 * <p>So the honest statement of the tradeoff is: a 200 ping serves anonymous pull and docker push,
 * and excludes skopeo/podman as producers while a token is set. With a <b>blank</b> token — the
 * container-network posture — every client works. If skopeo or podman ever need to push to a
 * guarded deployment, the fix is to challenge the ping when a token is configured, which is a
 * behaviour change worth measuring against anonymous docker pull before taking.
 */
@ApplicationScoped
public class RegistryAuthGuard {

  private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
  private static final String CHALLENGE = "Basic realm=\"qits-artifacts\"";
  private static final String BASIC = "Basic ";

  @Inject ArtifactsToken token;

  /**
   * Runs ahead of every {@code /v2} route. Non-blocking — it inspects headers and nothing else.
   *
   * <p>It also stamps {@code Docker-Distribution-Api-Version} on every response, including the error
   * ones. Clients use that header to decide they are talking to a v2 registry at all, so emitting it
   * from one place is what keeps a route from forgetting it.
   */
  public void guard(RoutingContext rc) {
    rc.response().putHeader("Docker-Distribution-Api-Version", "registry/2.0");

    if (!token.enforced() || !WRITE_METHODS.contains(rc.request().method().name())) {
      rc.next();
      return;
    }
    if (authorized(rc)) {
      rc.next();
      return;
    }
    rc.response().putHeader("WWW-Authenticate", CHALLENGE);
    RegistryErrors.send(rc, OciCode.UNAUTHORIZED, "authentication required to push to this registry");
  }

  /** {@code Basic base64(user:password)} — the username is ignored, the password is the token. */
  private boolean authorized(RoutingContext rc) {
    String header = rc.request().getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.regionMatches(true, 0, BASIC, 0, BASIC.length())) {
      return false;
    }
    String decoded;
    try {
      decoded =
          new String(
              Base64.getDecoder().decode(header.substring(BASIC.length()).trim()),
              StandardCharsets.UTF_8);
    } catch (IllegalArgumentException notBase64) {
      return false;
    }
    int colon = decoded.indexOf(':');
    return colon >= 0 && token.matches(decoded.substring(colon + 1));
  }
}
