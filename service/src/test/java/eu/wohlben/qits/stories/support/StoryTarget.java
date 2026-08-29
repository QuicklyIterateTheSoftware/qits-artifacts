package eu.wohlben.qits.stories.support;

import java.net.URL;

/**
 * The one launched process, addressed the way each of its wire stacks is addressed.
 *
 * <p>A story receives its root URL from {@code @TestHTTPResource("/")} — a {@code localhost} URL on
 * a <b>random</b> port, because the packaged process is launched with {@code
 * quarkus.http.test-port=0}. That randomness is the reason this class exists: a URL built here is
 * always passed to {@link eu.wohlben.qits.userflows.Commands#run} (or {@code Flow.navigate}) as an
 * <b>argument</b>, never spelled into a template, so the recorded fingerprint stays {@code {}} and
 * the story's definition hash survives the port changing on every run.
 *
 * <p>Every prefix here is a literal in the service's own sources — {@code NpmPaths.BASE}, {@code
 * MavenPaths.BASE}, {@code DaemonPaths.BASE}, {@code DocsPaths.BASE} and the {@code /v2} root — so
 * this class is the stories' single copy of them rather than nine.
 */
public final class StoryTarget {

  /** Always with a trailing slash, so every accessor below is a plain concatenation. */
  private final String root;

  public StoryTarget(URL root) {
    this(root.toString());
  }

  public StoryTarget(String root) {
    this.root = root.endsWith("/") ? root : root + "/";
  }

  /** The host root — the SPA, and the base a browser story navigates to. */
  public String root() {
    return root;
  }

  /** {@code /artifacts/api} — the JAX-RS explorer surface, every read of it admin-only. */
  public String apiBase() {
    return root + "artifacts/api";
  }

  /**
   * The npm registry URL a client is pointed at: the {@code npm} repository row inside {@code
   * /artifacts/npm}. Trailing slash included, because that is the spelling npm stores in a lockfile
   * and the one its {@code _authToken} key is derived from.
   */
  public String npmRegistry() {
    return root + "artifacts/npm/npm/";
  }

  /**
   * {@link #npmRegistry()} with its scheme removed — npm's "nerf dart", the key an {@code .npmrc}
   * credential line is written under ({@code //<here>:_authToken=…}). npm matches a registry to a
   * credential by this string and by nothing else, so a story that spells it differently publishes
   * anonymously and is refused with {@code ENEEDAUTH}.
   */
  public String npmRegistryAuthKey() {
    return npmRegistry().replaceFirst("^https?://", "");
  }

  /** The maven repository URL — the {@code maven} repository row inside {@code /artifacts/maven}. */
  public String mavenRepository() {
    return root + "artifacts/maven/maven";
  }

  /**
   * {@code host:port} with no scheme and no path — the only shape docker, podman and skopeo accept
   * for a registry, because they resolve an image reference against {@code <host>/v2/} themselves.
   */
  public String ociHost() {
    return root.replaceFirst("^https?://", "").replaceFirst("/.*$", "");
  }

  /**
   * {@code /artifacts/daemons} — the platform's own binaries. <b>No repository segment:</b> the
   * daemon wire serves the seeded {@code daemons} row and nothing else, so the first segment after
   * this base is the daemon's name.
   */
  public String daemonBase() {
    return root + "artifacts/daemons";
  }

  /**
   * {@code /artifacts/docs} — published documentation bundles. The repository segment <i>is</i>
   * present here (unlike the daemon wire), so a caller spells {@code docsBase() + "/docs/…"}.
   */
  public String docsBase() {
    return root + "artifacts/docs";
  }
}
