package eu.wohlben.qits.daemon;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * A synthetic daemon binary: the ELF magic, then filler.
 *
 * <p>Real enough to be the thing under test and small enough to be free. The four magic bytes are
 * what identified the three orphans on the live volume in the first place, so they belong in the
 * fixture — and they double as the reminder that {@code MediaTypeSniffer} has no ELF entry, which
 * is exactly why this type's profile accepts nothing and its bytes never touch {@code BlobService}.
 *
 * <p><b>Content must be unique per RUN, not merely per test.</b> Blobs dedupe globally and
 * content-addressed, and nothing wipes {@code target/artifacts-svc-test-blobs} between runs — the
 * mirror suites' hard-won lesson. The {@code salt} argument is how each case stays distinguishable;
 * pass something derived from the version under test.
 */
final class TinyDaemon {

  private TinyDaemon() {}

  /** {@code 0x7f E L F}, then {@code salt} repeated to {@code size} bytes. */
  static byte[] binary(String salt, int size) {
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

  /** The digest the publish response must carry, computed the way the store computes it. */
  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
