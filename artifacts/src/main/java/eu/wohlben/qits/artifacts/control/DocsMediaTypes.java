package eu.wohlben.qits.artifacts.control;

import java.util.Locale;
import java.util.Map;

/**
 * The content type a docs file is served with, resolved from its <b>extension</b>.
 *
 * <p><b>Why not {@link MediaTypeSniffer}.</b> The sniffer answers "what are these bytes" for content
 * a caller declared a type for, and it knows the handful of image and video types the two CI
 * profiles accept. A static site is made of the types it does not know — {@code woff2}, {@code js},
 * {@code css}, {@code map} — and a sniffed {@code woff2} resolves to nothing, which on the
 * validating upload path is a {@code 400}. So the two are not interchangeable and this is not a
 * duplicate: sniffing is a check on declared content, and this is a lookup for content nobody
 * declares.
 *
 * <p><b>Why the extension is enough here.</b> The bundle is built by a documentation tool and its
 * own HTML refers to its own assets by name, so a file whose extension lies is a file the site
 * itself cannot use. Getting it wrong costs a browser rendering one asset oddly; getting it wrong on
 * the CI path would mean storing a video as an image.
 *
 * <p>{@code text/html} carries a charset because a browser that has to guess one guesses wrong on
 * the first non-ASCII byte, and a documentation site is exactly where those live.
 * {@link #DEFAULT} is deliberately {@code application/octet-stream}: an unknown file downloads
 * rather than executing as something a browser guessed.
 */
public final class DocsMediaTypes {

  private DocsMediaTypes() {}

  /** What an extension this map does not know is served as. */
  public static final String DEFAULT = "application/octet-stream";

  private static final Map<String, String> BY_EXTENSION =
      Map.ofEntries(
          Map.entry("html", "text/html; charset=utf-8"),
          Map.entry("htm", "text/html; charset=utf-8"),
          Map.entry("js", "text/javascript; charset=utf-8"),
          Map.entry("mjs", "text/javascript; charset=utf-8"),
          Map.entry("css", "text/css; charset=utf-8"),
          Map.entry("json", "application/json"),
          Map.entry("map", "application/json"),
          Map.entry("txt", "text/plain; charset=utf-8"),
          Map.entry("md", "text/markdown; charset=utf-8"),
          Map.entry("svg", "image/svg+xml"),
          Map.entry("png", "image/png"),
          Map.entry("jpg", "image/jpeg"),
          Map.entry("jpeg", "image/jpeg"),
          Map.entry("gif", "image/gif"),
          Map.entry("webp", "image/webp"),
          Map.entry("ico", "image/x-icon"),
          Map.entry("avif", "image/avif"),
          Map.entry("woff", "font/woff"),
          Map.entry("woff2", "font/woff2"),
          Map.entry("ttf", "font/ttf"),
          Map.entry("otf", "font/otf"),
          Map.entry("eot", "application/vnd.ms-fontobject"),
          Map.entry("wasm", "application/wasm"),
          Map.entry("webmanifest", "application/manifest+json"),
          Map.entry("xml", "application/xml"),
          Map.entry("pdf", "application/pdf"),
          Map.entry("mp4", "video/mp4"),
          Map.entry("webm", "video/webm"));

  /**
   * The content type for a bundle-relative path, or {@link #DEFAULT} when the extension is unknown
   * or absent.
   *
   * <p>The extension is read from the last segment only, so a dot in a directory name cannot be
   * mistaken for one — {@code v1.2/README} has no extension.
   */
  public static String forPath(String path) {
    if (path == null) {
      return DEFAULT;
    }
    String file = path.substring(path.lastIndexOf('/') + 1);
    int dot = file.lastIndexOf('.');
    if (dot < 0 || dot == file.length() - 1) {
      return DEFAULT;
    }
    return BY_EXTENSION.getOrDefault(
        file.substring(dot + 1).toLowerCase(Locale.ROOT), DEFAULT);
  }
}
