package eu.wohlben.qits.artifactory.api;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal PNG bytes + the X-Artifactory-Meta-* header set for the service-boundary tests. */
final class ArtifactoryTestMedia {

  private ArtifactoryTestMedia() {}

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
    h.put("X-Artifactory-Meta-git.branch.name", branch);
    h.put("X-Artifactory-Meta-git.commit.hash", "abc123");
    h.put("X-Artifactory-Meta-qits.userflow.name", flow);
    h.put("X-Artifactory-Meta-qits.userflow.hash", "flowhash");
    h.put("X-Artifactory-Meta-qits.display.name", "step 1");
    h.put("X-Artifactory-Meta-qits.diff.hash", "diffhash");
    h.put("X-Artifactory-Meta-media.resolution.width", Integer.toString(width));
    h.put("X-Artifactory-Meta-media.resolution.height", Integer.toString(height));
    return h;
  }
}
