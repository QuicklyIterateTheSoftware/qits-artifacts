package eu.wohlben.qits.docs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

/**
 * A real {@code .tar.gz} docs bundle, synthesised in memory.
 *
 * <p>{@code registry/TinyImage} and {@code npm/TinyPackage}'s reasoning: the archive format is
 * exactly what {@code DocsBundle} has to parse, so a fixture that faked it would prove nothing. This
 * builds the same bytes {@code tar -czf} would, including the {@code ./} prefix that {@code tar -C
 * <dir> .} puts on every entry — the spelling a pipeline reaches for and the one the publish path
 * has to normalise away.
 */
public final class TinyBundle {

  private final Map<String, byte[]> files = new LinkedHashMap<>();
  private boolean dotSlash;
  private boolean withDirectories;

  /** The {@code ./index.html} spelling {@code tar -C storybook-static .} produces. */
  TinyBundle dotSlashPrefixed() {
    this.dotSlash = true;
    return this;
  }

  /** Emit directory entries too, as a real tar does — they must be skipped, not stored as files. */
  TinyBundle withDirectoryEntries() {
    this.withDirectories = true;
    return this;
  }

  TinyBundle file(String path, String content) {
    files.put(path, content.getBytes(StandardCharsets.UTF_8));
    return this;
  }

  TinyBundle file(String path, byte[] content) {
    files.put(path, content);
    return this;
  }

  /** A Storybook-shaped bundle: an index, a chunk, and a font under a nested directory. */
  public static TinyBundle storybookLike(String salt) {
    return new TinyBundle()
        .file("index.html", "<!doctype html><title>" + salt + "</title>")
        .file("assets/iframe-" + salt + ".js", "export const story = '" + salt + "';")
        .file("sb-common-assets/nunito-sans-bold.woff2", sharedFont());
  }

  /**
   * Byte-identical across every bundle built here, on purpose: it is what makes the dedupe assertion
   * a measurement rather than a hope. Two versions that ship this font must reference one blob.
   */
  static byte[] sharedFont() {
    byte[] font = new byte[512];
    for (int i = 0; i < font.length; i++) {
      font[i] = (byte) (i % 251);
    }
    return font;
  }

  public byte[] toTarGz() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(out);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
      // Long names are the normal case for a hashed asset filename, so the POSIX extension is what
      // a real archive would carry rather than a truncation this fixture would silently accept.
      tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
      if (withDirectories) {
        for (String directory : directoriesOf()) {
          TarArchiveEntry entry = new TarArchiveEntry(prefixed(directory) + "/");
          tar.putArchiveEntry(entry);
          tar.closeArchiveEntry();
        }
      }
      for (Map.Entry<String, byte[]> file : files.entrySet()) {
        writeEntry(tar, prefixed(file.getKey()), file.getValue());
      }
    } catch (IOException e) {
      throw new IllegalStateException("could not build the bundle", e);
    }
    return out.toByteArray();
  }

  /**
   * A single entry under a raw name, for the cases that are about names rather than content.
   *
   * <p><b>The leading slash has to be preserved deliberately.</b> {@code TarArchiveEntry}'s ordinary
   * constructor strips one — GNU tar does the same, with a warning — so a fixture built the normal
   * way could not express an absolute entry name at all, and the publish path's refusal of one would
   * look untested because it was untestable. The two-argument constructor is what keeps the name
   * intact, and a hand-crafted hostile archive is exactly the thing that check exists for.
   */
  static byte[] singleEntry(String name, String content) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(out);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
      tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
      writeEntry(tar, name, content.getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new IllegalStateException("could not build the bundle", e);
    }
    return out.toByteArray();
  }

  private static void writeEntry(TarArchiveOutputStream tar, String name, byte[] content)
      throws IOException {
    TarArchiveEntry entry = new TarArchiveEntry(name, true);
    entry.setSize(content.length);
    tar.putArchiveEntry(entry);
    writeAll(tar, content);
    tar.closeArchiveEntry();
  }

  private static void writeAll(OutputStream out, byte[] content) throws IOException {
    out.write(content);
  }

  private String prefixed(String path) {
    return dotSlash ? "./" + path : path;
  }

  private java.util.Set<String> directoriesOf() {
    java.util.Set<String> directories = new java.util.LinkedHashSet<>();
    for (String path : files.keySet()) {
      int slash = path.lastIndexOf('/');
      if (slash > 0) {
        directories.add(path.substring(0, slash));
      }
    }
    return directories;
  }
}
