package eu.wohlben.qits.artifacts.api;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal PNG bytes + the X-Artifacts-Meta-* header set for the service-boundary tests. */
final class ArtifactsTestMedia {

  private ArtifactsTestMedia() {}

  static byte[] png(int width, int height, int salt) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (DataOutputStream d = new DataOutputStream(out)) {
      d.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
      d.writeInt(13);
      d.writeBytes("IHDR");
      d.writeInt(width);
      d.writeInt(height);
      d.writeByte(8);
      d.writeByte(6);
      d.writeByte(0);
      d.writeByte(0);
      d.writeByte(0);
      d.writeInt(0);
      d.writeInt(salt);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  /** The complete required-key header set for a ci-screenshots upload of the given dimensions. */
  static Map<String, String> screenshotHeaders(String branch, String flow, int width, int height) {
    Map<String, String> h = new LinkedHashMap<>();
    h.put("X-Artifacts-Meta-git.branch.name", branch);
    h.put("X-Artifacts-Meta-git.commit.hash", "abc123");
    h.put("X-Artifacts-Meta-qits.userflow.name", flow);
    h.put("X-Artifacts-Meta-qits.userflow.hash", "flowhash");
    h.put("X-Artifacts-Meta-qits.display.name", "step 1");
    h.put("X-Artifacts-Meta-qits.diff.hash", "diffhash");
    h.put("X-Artifacts-Meta-media.resolution.width", Integer.toString(width));
    h.put("X-Artifacts-Meta-media.resolution.height", Integer.toString(height));
    return h;
  }
}
