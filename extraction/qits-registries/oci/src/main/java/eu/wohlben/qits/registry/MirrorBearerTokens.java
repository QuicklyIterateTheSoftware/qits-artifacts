package eu.wohlben.qits.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * The {@code WWW-Authenticate: Bearer} dance, anonymously, for every upstream at once.
 *
 * <p>All three launch upstreams speak the same protocol: a {@code /v2} request with no credential
 * answers {@code 401} with a challenge naming a token realm, a service and a scope; a plain {@code
 * GET} on that realm returns a short-lived token; the request is retried carrying it. Docker Hub
 * demands the round trip even for public images, quay.io and registry.access.redhat.com mostly do
 * not — so the client sends every request bare first and only pays the hop when it is challenged.
 * That order is why one implementation covers all three and why "does this upstream need a token"
 * is not a column anybody has to maintain.
 *
 * <p><b>Anonymous only</b> (proxy-pulling-normal-images.md ⚖3), and the clarification that ruling
 * needed is worth repeating where the code is: a client's {@code docker login} does not traverse a
 * pull-through hop. The daemon authenticates to the registry it dials — this one — and this one
 * dials upstream <b>as itself</b>. So a private upstream needs a server-side credential, which
 * arrives as an additive column pair on {@code oci_mirror_upstream} and rides in here as Basic auth
 * on the token GET: one line, on a code path that already exists. Until then a private registry is
 * out of scope rather than half-supported.
 *
 * <p>Tokens are cached in memory per {@code (realm, service, scope)} and honour {@code expires_in},
 * because a scope is per-repository: pulling twelve base images means twelve scopes, and re-doing
 * the hop for every manifest and every layer would triple the request count against exactly the
 * upstream that counts requests. Nothing is persisted — a token outlives neither a restart nor its
 * own expiry, and both are cheap to re-earn.
 */
final class MirrorBearerTokens {

  private static final Logger LOG = Logger.getLogger(MirrorBearerTokens.class);

  /**
   * Taken off every token's lifetime before it is trusted, so one that expires while a blob is in
   * flight was never handed out. Upstream lifetimes are minutes; this costs nothing.
   */
  private static final Duration SKEW = Duration.ofSeconds(30);

  /** What a token answer is assumed to be worth when it declares no {@code expires_in}. */
  private static final Duration DEFAULT_LIFETIME = Duration.ofSeconds(60);

  private final Map<String, CachedToken> tokens = new ConcurrentHashMap<>();

  /**
   * An instance field for the same reason the {@code HttpClient} is one: this object is reachable
   * from an {@code @ApplicationScoped} bean's instance field, so it is built when the process
   * starts. A static would be built by a class initialiser, which under GraalVM runs at image-build
   * time and freezes the result into the image heap.
   */
  private final ObjectMapper json = new ObjectMapper();

  private record CachedToken(String value, Instant expiresAt) {}

  /** A parsed {@code WWW-Authenticate: Bearer …} challenge. */
  record Challenge(String realm, String service, String scope) {}

  /**
   * Reads a challenge header, or returns null if it is not a Bearer one.
   *
   * <p>Quoted values are honoured because they have to be: a scope legitimately contains commas
   * ({@code repository:x:pull,push}), and splitting on commas first would truncate it into a scope
   * the upstream then refuses.
   */
  static Challenge parseChallenge(String header) {
    if (header == null) {
      return null;
    }
    String trimmed = header.trim();
    if (!trimmed.regionMatches(true, 0, "Bearer", 0, "Bearer".length())) {
      return null;
    }
    Map<String, String> parameters = new LinkedHashMap<>();
    String rest = trimmed.substring("Bearer".length()).trim();
    int index = 0;
    while (index < rest.length()) {
      int equals = rest.indexOf('=', index);
      if (equals < 0) {
        break;
      }
      String key = rest.substring(index, equals).trim().toLowerCase(java.util.Locale.ROOT);
      int valueStart = equals + 1;
      String value;
      if (valueStart < rest.length() && rest.charAt(valueStart) == '"') {
        int closing = rest.indexOf('"', valueStart + 1);
        if (closing < 0) {
          break;
        }
        value = rest.substring(valueStart + 1, closing);
        index = closing + 1;
      } else {
        int comma = rest.indexOf(',', valueStart);
        int end = comma < 0 ? rest.length() : comma;
        value = rest.substring(valueStart, end).trim();
        index = end;
      }
      parameters.put(key, value);
      while (index < rest.length() && (rest.charAt(index) == ',' || rest.charAt(index) == ' ')) {
        index++;
      }
    }
    String realm = parameters.get("realm");
    if (realm == null || realm.isBlank()) {
      return null;
    }
    return new Challenge(realm, parameters.get("service"), parameters.get("scope"));
  }

  /**
   * The token this challenge asks for, from cache when one is still valid.
   *
   * @param fallbackScope the scope to ask for when the challenge names none — {@code
   *     repository:<image>:pull}, which is what every registry would have asked for anyway
   * @return the token, or null if the realm did not produce one; a null makes the caller retry
   *     nothing and surface the upstream's own 401
   */
  String token(HttpClient http, Challenge challenge, String fallbackScope, Duration timeout) {
    String scope =
        challenge.scope() == null || challenge.scope().isBlank()
            ? fallbackScope
            : challenge.scope();
    String key = challenge.realm() + "|" + challenge.service() + "|" + scope;

    CachedToken cached = tokens.get(key);
    if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
      return cached.value();
    }

    StringBuilder uri = new StringBuilder(challenge.realm());
    uri.append(challenge.realm().indexOf('?') >= 0 ? '&' : '?');
    if (challenge.service() != null && !challenge.service().isBlank()) {
      uri.append("service=").append(encode(challenge.service())).append('&');
    }
    uri.append("scope=").append(encode(scope));

    try {
      HttpResponse<String> response =
          http.send(
              HttpRequest.newBuilder(URI.create(uri.toString()))
                  .timeout(timeout)
                  .header("Accept", "application/json")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        LOG.debugf("mirror: token realm %s answered %d", challenge.realm(), response.statusCode());
        return null;
      }
      JsonNode document = json.readTree(response.body());
      // Both spellings are in the wild: Docker Hub answers `token`, the OAuth2 shape answers
      // `access_token`, and several registries send both with the same value.
      String value = document.path("token").asText(null);
      if (value == null || value.isBlank()) {
        value = document.path("access_token").asText(null);
      }
      if (value == null || value.isBlank()) {
        return null;
      }
      long seconds = document.path("expires_in").asLong(DEFAULT_LIFETIME.toSeconds());
      Duration lifetime = Duration.ofSeconds(Math.max(seconds, 1));
      Instant expiresAt =
          Instant.now()
              .plus(lifetime.compareTo(SKEW) > 0 ? lifetime.minus(SKEW) : Duration.ofSeconds(1));
      tokens.put(key, new CachedToken(value, expiresAt));
      return value;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return null;
    } catch (Exception unreachable) {
      LOG.debugf(unreachable, "mirror: token realm %s is unreachable", challenge.realm());
      return null;
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
