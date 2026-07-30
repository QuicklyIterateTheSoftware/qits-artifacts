package eu.wohlben.qits.artifacts.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The one static write secret, and the one place that decides a blank value means <b>open</b>.
 *
 * <p>One mechanism presents it: the JSON API's {@code X-Artifacts-Token} header
 * ({@code ArtifactsTokenFilter}). The OCI registry used to be a second consumer (HTTP Basic
 * password, the retired {@code RegistryAuthGuard}) and is now deliberately tokenless — producers
 * on qits-net are trusted, external writes die on the gateway's session policy, and the
 * skopeo/podman-cannot-authenticate tradeoff dissolved with the guard. Setting this token guards
 * the JSON API and nothing else; {@code RegistryOpenPushTest} pins that it stays that way.
 */
@ApplicationScoped
public class ArtifactsToken {

  // Optional so a blank/absent value is "no token configured" (open) — an empty String value is
  // treated as absent by SmallRye Config and would fail a plain String injection.
  @ConfigProperty(name = "qits.artifacts.token")
  Optional<String> configured;

  /**
   * Whether a token is configured at all. When false every guard is a no-op — the dev/test default,
   * and the container-network deployment posture where the registry is unreachable from outside.
   */
  public boolean enforced() {
    return token() != null;
  }

  /**
   * Whether {@code candidate} is the configured token. False when nothing is configured, so this is
   * never on its own a reason to allow a request — callers check {@link #enforced()} first.
   *
   * <p>Constant-time, via {@link MessageDigest#isEqual}: the comparison is against a shared secret
   * an attacker may retry freely, which is the case byte-by-byte {@code String.equals} leaks.
   */
  public boolean matches(String candidate) {
    String token = token();
    if (token == null || candidate == null) {
      return false;
    }
    return MessageDigest.isEqual(
        token.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8));
  }

  private String token() {
    return configured.map(String::trim).filter(t -> !t.isEmpty()).orElse(null);
  }
}
