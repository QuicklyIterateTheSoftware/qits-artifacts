package eu.wohlben.qits.artifacts.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The one static write secret, and the one place that decides a blank value means <b>open</b>.
 *
 * <p>Two mechanisms present it, because two kinds of client have to deliver it. The JSON API takes
 * it in an {@code X-Artifacts-Token} header ({@code ArtifactsTokenFilter}); the OCI registry takes
 * it as an HTTP Basic password ({@code RegistryAuthGuard}), because that is what {@code skopeo
 * --dest-creds} and {@code podman push --creds} can send and a custom header is not. What they must
 * never disagree on is <em>whether a guard exists at all</em> — a deployment that set the token and
 * found one surface still open would be a security bug, not a quirk.
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
