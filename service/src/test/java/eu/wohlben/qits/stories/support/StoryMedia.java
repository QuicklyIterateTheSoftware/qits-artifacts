package eu.wohlben.qits.stories.support;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The byte fixtures the story classes publish — a copy of the generators the wire suites already
 * own, not a widening of them.
 *
 * <p>{@code artifacts/}'s {@code TestMedia}, this module's {@code ArtifactsTestMedia}, {@code
 * daemon/TinyDaemon} and {@code docs/TinyBundle} are each package-private (or all but) to the suite
 * they serve, and making them public so the stories could share them would hand four suites one
 * contract to save a few dozen lines of synthesis. The repository already carries that decision for
 * {@code GcFixture} and {@code SeededStoreFixture}: copies rather than a published test jar. This
 * is the same trade, one package over.
 *
 * <p><b>Content is unique per RUN, not merely per story.</b> Blobs dedupe globally and
 * content-addressed, so every generator here takes a salt: reuse another story's bytes and the blob
 * is already stored, which makes any figure over stored bytes come out short with nothing in the
 * failure to say why.
 */
public final class StoryMedia {

  private StoryMedia() {}

  // --- CI media ---------------------------------------------------------------------------------

  /**
   * A PNG whose IHDR really encodes {@code width}×{@code height}, so the store's cheap dimension
   * check reads the declared resolution off the bytes rather than trusting the header.
   */
  public static byte[] png(int width, int height, int salt) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (DataOutputStream d = new DataOutputStream(out)) {
      d.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'}); // signature
      d.writeInt(13); // IHDR length
      d.writeBytes("IHDR");
      d.writeInt(width);
      d.writeInt(height);
      d.writeByte(8); // bit depth
      d.writeByte(6); // colour type (RGBA)
      d.writeByte(0); // compression
      d.writeByte(0); // filter
      d.writeByte(0); // interlace
      d.writeInt(0); // (bogus) CRC — the store never validates it
      d.writeInt(salt); // uniqueness
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  /** The EBML magic a WebM starts with, then a salt — enough for the sniffer, and free. */
  public static byte[] webm(int salt) {
    byte[] out = new byte[8];
    out[0] = 0x1A;
    out[1] = 0x45;
    out[2] = (byte) 0xDF;
    out[3] = (byte) 0xA3;
    out[4] = (byte) (salt >> 24);
    out[5] = (byte) (salt >> 16);
    out[6] = (byte) (salt >> 8);
    out[7] = (byte) salt;
    return out;
  }

  /**
   * The complete required-key header set for a {@code ci-screenshots} upload. Every key is
   * mandatory: the repository type's profile refuses an upload that omits one, so a partial map
   * here would be a 400 rather than a smaller record.
   */
  public static Map<String, String> screenshotHeaders(
      String branch, String commit, String flow, String flowHash, String display,
      String diffHash, int width, int height) {
    Map<String, String> headers = metadata(branch, commit, flow, flowHash, display, diffHash);
    headers.put("X-Artifacts-Meta-media.resolution.width", Integer.toString(width));
    headers.put("X-Artifacts-Meta-media.resolution.height", Integer.toString(height));
    return headers;
  }

  /**
   * The {@code ci-videos} twin. Identical but for the last key: a recording has a <b>length</b>
   * where a screenshot has a width and a height, which is the one place the two profiles differ.
   */
  public static Map<String, String> videoHeaders(
      String branch, String commit, String flow, String flowHash, String display,
      String diffHash, long lengthMillis) {
    Map<String, String> headers = metadata(branch, commit, flow, flowHash, display, diffHash);
    headers.put("X-Artifacts-Meta-media.resolution.length", Long.toString(lengthMillis));
    return headers;
  }

  private static Map<String, String> metadata(
      String branch, String commit, String flow, String flowHash, String display,
      String diffHash) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("X-Artifacts-Meta-git.branch.name", branch);
    headers.put("X-Artifacts-Meta-git.commit.hash", commit);
    headers.put("X-Artifacts-Meta-qits.userflow.name", flow);
    headers.put("X-Artifacts-Meta-qits.userflow.hash", flowHash);
    headers.put("X-Artifacts-Meta-qits.display.name", display);
    headers.put("X-Artifacts-Meta-qits.diff.hash", diffHash);
    return headers;
  }

  // --- daemon binaries --------------------------------------------------------------------------

  /**
   * {@code 0x7f E L F}, then {@code salt} repeated to {@code size} bytes — the shape that
   * identified the three orphaned binaries on the live volume, and the reminder that this type's
   * profile sniffs nothing (there is no ELF entry in {@code MediaTypeSniffer}, and a daemon's bytes
   * never pass through {@code BlobService}).
   */
  public static byte[] daemonBinary(String salt, int size) {
    byte[] filler = salt.getBytes(StandardCharsets.UTF_8);
    byte[] out = new byte[Math.max(size, 4)];
    out[0] = 0x7f;
    out[1] = 'E';
    out[2] = 'L';
    out[3] = 'F';
    for (int i = 4; i < out.length; i++) {
      out[i] = filler[(i - 4) % filler.length];
    }
    return out;
  }

  // --- docs bundles -----------------------------------------------------------------------------

  /**
   * A three-file documentation site on disk, ready for {@code tar -czf … -C <dir> .}: an {@code
   * index.html}, one hashed asset under {@code assets/}, and a font under a second directory.
   *
   * <p>The font is <b>byte-identical across every call</b>, on purpose. Two versions of one site
   * share their fonts and their unchanged chunks in reality, and the store is content-addressed, so
   * a fixture whose every file differed would make the per-site union equal the sum of its versions
   * and quietly stop testing the thing the size columns are about.
   */
  public static void siteTree(Path directory) {
    siteTree(directory, "story");
  }

  /** {@link #siteTree(Path)} with an explicit salt, for a second version of the same site. */
  public static void siteTree(Path directory, String salt) {
    write(
        directory.resolve("index.html"),
        ("<!doctype html><html><head><title>" + salt + "</title>"
                + "<script src=\"assets/app.js\"></script></head>"
                + "<body><h1>" + salt + "</h1></body></html>")
            .getBytes(StandardCharsets.UTF_8));
    write(
        directory.resolve("assets").resolve("app.js"),
        ("export const site = '" + salt + "';\n").getBytes(StandardCharsets.UTF_8));
    write(directory.resolve("fonts").resolve("nunito-sans-bold.woff2"), sharedFont());
  }

  /** The one blob every bundle here ships, so versions of a site provably share bytes. */
  private static byte[] sharedFont() {
    byte[] font = new byte[512];
    for (int i = 0; i < font.length; i++) {
      font[i] = (byte) (i % 251);
    }
    return font;
  }

  // --- digests ----------------------------------------------------------------------------------

  /**
   * The hex sha256 of {@code bytes} — the same string the store uses as a blob id, which is what
   * makes it comparable to a {@code Docker-Content-Digest} header, a publish receipt's {@code
   * digest} member and a docs {@code ETag}.
   */
  public static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /** {@link #sha256Hex(byte[])} over a file the story just wrote or downloaded. */
  public static String sha256Hex(Path file) {
    try {
      return sha256Hex(Files.readAllBytes(file));
    } catch (IOException e) {
      throw new UncheckedIOException("no readable " + file, e);
    }
  }

  private static void write(Path target, byte[] content) {
    try {
      Files.createDirectories(target.getParent());
      Files.write(target, content);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to write " + target, e);
    }
  }
}
