package eu.wohlben.qits.registry;

/**
 * Where an upstream registry's Distribution API actually answers, given the domain the entity row
 * carries.
 *
 * <p>The row's {@code domain} is an <b>identity</b>, not an address — {@code docker.io} is what a
 * reference says and {@code registry-1.docker.io} is what a client dials, and every registry client
 * in the world hardcodes that hop. It is spelled here, once, rather than in the table: it is a fact
 * about Docker Hub, not a deployment decision, and putting it in a row would invite an operator to
 * "fix" it.
 *
 * <p>Everything else is {@code https://<domain>}, which is the whole of the mapping for quay.io and
 * registry.access.redhat.com and for any upstream registered later. There is no scheme column and
 * no per-upstream port: an upstream that does not speak TLS on 443 is not an upstream this cache
 * dials, and a domain may already carry a port if one is genuinely needed.
 *
 * <p>Static rather than a bean so the mapping is provable without starting an application — {@code
 * MirrorEndpointsTest} is a plain JUnit test over exactly the three prefilled domains.
 */
final class MirrorEndpoints {

  /** The domain a reference names. */
  static final String DOCKER_HUB = "docker.io";

  /** The host that domain's registry API actually answers on — the one well-known exception. */
  static final String DOCKER_HUB_ENDPOINT = "https://registry-1.docker.io";

  private MirrorEndpoints() {}

  /**
   * The base URI to build {@code /v2/…} requests against.
   *
   * @param domain the upstream row's identity
   * @param override {@code qits.artifacts.oci.mirror.endpoint-override} — blank in every
   *     deployment. When set it replaces the derivation for <b>every</b> upstream, which is what
   *     lets this repo's suite prove the miss path against an in-process stub without a network and
   *     without a second code path. See the key's comment for why a global value is the honest
   *     shape for that and a useless one for a deployment.
   */
  static String apiBase(String domain, String override) {
    if (override != null && !override.isBlank()) {
      return trimSlash(override.trim());
    }
    if (DOCKER_HUB.equals(domain)) {
      return DOCKER_HUB_ENDPOINT;
    }
    return "https://" + domain;
  }

  private static String trimSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
