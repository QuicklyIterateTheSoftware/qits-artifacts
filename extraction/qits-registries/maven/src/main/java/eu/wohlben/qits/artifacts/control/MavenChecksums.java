package eu.wohlben.qits.artifacts.control;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The four checksums every stored maven file serves, computed <em>at read time</em> from the blob
 * bytes — derived, never stored, the same reasoning that keeps {@code maven-metadata.xml} out of
 * the schema: a stored copy is a second source of truth that can only ever disagree.
 *
 * <p>md5 stays because legacy clients still ask for it; sha256/sha512 because modern ones do. The
 * store itself stays sha256-only, exactly as npm keeps sha1/sha512 in columns while the blob key is
 * sha256. At platform jar sizes each pass is milliseconds, and no cache is justified.
 */
public final class MavenChecksums {

  private MavenChecksums() {}

  /**
   * The JCA name of a wire algorithm ({@code sha1} → {@code SHA-1}).
   *
   * @throws IllegalArgumentException an algorithm outside the four the wire serves
   */
  public static String jcaName(String algorithm) {
    return switch (algorithm) {
      case "md5" -> "MD5";
      case "sha1" -> "SHA-1";
      case "sha256" -> "SHA-256";
      case "sha512" -> "SHA-512";
      default -> throw new IllegalArgumentException("not a checksum the maven wire serves: " + algorithm);
    };
  }

  /** The lowercase hex digest of bytes under one of the four wire algorithms. */
  public static String hexDigest(byte[] bytes, String algorithm) {
    MessageDigest digest = digestFor(algorithm);
    digest.update(bytes);
    return HexFormat.of().formatHex(digest.digest());
  }

  /** The lowercase hex digest of a blob file under one of the four wire algorithms. */
  public static String hexDigest(Path blob, String algorithm) {
    MessageDigest digest = digestFor(algorithm);
    try (InputStream in = Files.newInputStream(blob)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) {
        digest.update(buffer, 0, read);
      }
    } catch (IOException e) {
      throw new IllegalStateException("failed to read the blob for a checksum", e);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static MessageDigest digestFor(String algorithm) {
    try {
      return MessageDigest.getInstance(jcaName(algorithm));
    } catch (NoSuchAlgorithmException e) {
      // Every JCA implementation carries all four; this is unreachable by construction.
      throw new IllegalStateException(e);
    }
  }
}
