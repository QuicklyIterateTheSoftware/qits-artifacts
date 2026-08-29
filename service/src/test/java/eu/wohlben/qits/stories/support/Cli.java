package eu.wohlben.qits.stories.support;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Where the command-line tools a story drives actually are — resolved once per JVM, and answerable
 * as a plain {@code boolean} so a class can gate itself with {@code @EnabledIf}.
 *
 * <p><b>The resolved value is passed as an ARGUMENT, never written into a template.</b> A story
 * spells {@code commands.run("{} publish --registry {}", Cli.npm(), registry)} rather than {@code
 * run("npm publish …")}, for the same reason a URL is never spelled into one: the display line gets
 * the real program ({@code /opt/node/bin/npm publish …}) while the fingerprint keeps {@code {}}, so
 * the story's definition hash is the same on a workstation, in CI and in a container that resolves
 * the tool somewhere else entirely.
 *
 * <p><b>Missing is a SKIP, and the skip must happen before the story starts.</b> The {@code
 * *Present()} predicates below exist for {@code @EnabledIf} at class level; a story body must never
 * call {@code assumeTrue}, because the userflows extension has already opened a report by then and
 * an aborted story emits a red one. A skipped story emits nothing at all, which is the honest
 * answer for "this machine has no npm".
 *
 * <h2>The workspace npm shim</h2>
 *
 * <p>{@code npm} on {@code PATH} inside a qits workspace container is a <b>shim</b>: with {@code
 * QITS_WORKSPACE_NPM_REGISTRY_URL} set it re-execs the real npm through {@code env
 * "npm_config_@qits:registry=…"}, because that config key's environment spelling contains {@code @}
 * and {@code :} and no shell can {@code export} it. So a story that publishes to the launched
 * process would silently talk to the <i>platform's</i> registry instead. npm ranks the command line
 * above the environment, so every story here passes <b>both</b> {@code --registry=…} and {@code
 * --@qits:registry=…} explicitly, and the shim loses. Do not "simplify" either flag away.
 */
public final class Cli {

  /** An explicit override for the maven launcher; the pom fills it from {@code ${maven.home}}. */
  public static final String MVN_PROPERTY = "qits.userflows.mvn";

  /** An explicit override for npm. */
  public static final String NPM_PROPERTY = "qits.userflows.npm";

  /**
   * Where Quinoa's <b>managed</b> node distribution was unpacked, when the build asked for one
   * ({@code quarkus.quinoa.package-manager-install}). The pom points this at {@code
   * ${project.basedir}/.quinoa/node}; the layout underneath is Quinoa's business and is therefore
   * probed rather than assumed — see {@link #quinoaNpm()}.
   */
  public static final String QUINOA_NODE_DIR_PROPERTY = "qits.userflows.quinoa-node-dir";

  public static final String SKOPEO_PROPERTY = "qits.userflows.skopeo";
  public static final String CURL_PROPERTY = "qits.userflows.curl";
  public static final String TAR_PROPERTY = "qits.userflows.tar";

  /**
   * One resolution per tool per JVM. {@code @EnabledIf} is evaluated once per class and a story may
   * ask again, so the {@code PATH} walk should happen once; {@link Optional} rather than {@code
   * null} because {@link ConcurrentHashMap} admits no null value.
   */
  private static final Map<String, Optional<String>> RESOLVED = new ConcurrentHashMap<>();

  private Cli() {}

  // --- the tools -------------------------------------------------------------------------------

  /** The maven launcher: the override property, else {@code PATH}. */
  public static String mvn() {
    return require("mvn");
  }

  public static boolean mvnPresent() {
    return resolve("mvn").isPresent();
  }

  /** npm: the override property, else Quinoa's managed node distribution, else {@code PATH}. */
  public static String npm() {
    return require("npm");
  }

  public static boolean npmPresent() {
    return resolve("npm").isPresent();
  }

  public static String skopeo() {
    return require("skopeo");
  }

  public static boolean skopeoPresent() {
    return resolve("skopeo").isPresent();
  }

  public static String curl() {
    return require("curl");
  }

  public static boolean curlPresent() {
    return resolve("curl").isPresent();
  }

  public static String tar() {
    return require("tar");
  }

  public static boolean tarPresent() {
    return resolve("tar").isPresent();
  }

  /** Both of the docs stories' tools — the shape {@code @EnabledIf} takes for a two-tool gate. */
  public static boolean curlAndTarPresent() {
    return curlPresent() && tarPresent();
  }

  // --- resolution ------------------------------------------------------------------------------

  private static String require(String tool) {
    return resolve(tool)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "no "
                        + tool
                        + " on this machine — a story needing it must be gated with @EnabledIf on"
                        + " Cli#"
                        + tool
                        + "Present"));
  }

  private static Optional<String> resolve(String tool) {
    return RESOLVED.computeIfAbsent(tool, key -> resolver(key).get());
  }

  private static Supplier<Optional<String>> resolver(String tool) {
    return switch (tool) {
      case "mvn" -> () -> declared(MVN_PROPERTY).or(() -> onPath("mvn"));
      case "npm" -> () -> declared(NPM_PROPERTY).or(Cli::quinoaNpm).or(() -> onPath("npm"));
      case "skopeo" -> () -> declared(SKOPEO_PROPERTY).or(() -> onPath("skopeo"));
      case "curl" -> () -> declared(CURL_PROPERTY).or(() -> onPath("curl"));
      case "tar" -> () -> declared(TAR_PROPERTY).or(() -> onPath("tar"));
      default -> () -> onPath(tool);
    };
  }

  /**
   * A system property naming an executable. A property that is unset, blank or names something that
   * is not executable is <b>not</b> an error: the pom fills these unconditionally (with {@code
   * ${maven.home}}, with a {@code .quinoa} directory that may not have been created), so an
   * unusable value has to fall through to the next source rather than fail a machine that has the
   * tool on {@code PATH} anyway.
   */
  private static Optional<String> declared(String property) {
    String value = System.getProperty(property);
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    Path candidate = Path.of(value.strip());
    return Files.isExecutable(candidate) && !Files.isDirectory(candidate)
        ? Optional.of(candidate.toAbsolutePath().toString())
        : Optional.empty();
  }

  /**
   * npm from Quinoa's managed node distribution, if the build unpacked one.
   *
   * <p>The layout under the configured directory is Quinoa's and has changed between versions — the
   * distribution is sometimes unpacked directly into it and sometimes into a versioned {@code
   * node-vX.Y.Z-<platform>} child — so both shapes are probed one level deep rather than one being
   * assumed. An {@code npm} that has no {@code node} beside it is rejected: the launcher script is
   * a {@code node} invocation, so a distribution missing its interpreter would resolve here and
   * then fail as an unreadable exit 1 inside a story.
   */
  private static Optional<String> quinoaNpm() {
    String configured = System.getProperty(QUINOA_NODE_DIR_PROPERTY);
    if (configured == null || configured.isBlank()) {
      return Optional.empty();
    }
    Path root = Path.of(configured.strip());
    if (!Files.isDirectory(root)) {
      return Optional.empty();
    }
    List<Path> roots = new ArrayList<>();
    roots.add(root);
    try (var children = Files.list(root)) {
      children.filter(Files::isDirectory).forEach(roots::add);
    } catch (Exception unreadable) {
      return Optional.empty();
    }
    for (Path base : roots) {
      for (Path npm : List.of(base.resolve("bin").resolve("npm"), base.resolve("npm"))) {
        Path node = npm.resolveSibling("node");
        if (Files.isExecutable(npm) && Files.isExecutable(node)) {
          return Optional.of(npm.toAbsolutePath().toString());
        }
      }
    }
    return Optional.empty();
  }

  /**
   * The bare tool name, if some {@code PATH} entry holds it. The <b>name</b> rather than the
   * absolute path on purpose: {@link ProcessBuilder} resolves it identically, and a transcript
   * reading {@code npm publish …} is the line a reader would retype.
   */
  private static Optional<String> onPath(String tool) {
    String path = System.getenv("PATH");
    if (path == null || path.isBlank()) {
      return Optional.empty();
    }
    for (String entry : path.split(File.pathSeparator)) {
      if (entry.isBlank()) {
        continue;
      }
      Path candidate = Path.of(entry).resolve(tool);
      if (Files.isExecutable(candidate) && !Files.isDirectory(candidate)) {
        return Optional.of(tool);
      }
    }
    return Optional.empty();
  }
}
