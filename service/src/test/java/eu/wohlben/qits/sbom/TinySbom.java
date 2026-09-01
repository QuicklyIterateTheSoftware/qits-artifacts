package eu.wohlben.qits.sbom;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * A synthetic CycloneDX 1.6 document: the four fields the wire validates, plus the smallest
 * structure that is actually a bill of materials — a root component, one dependency and the
 * dependency graph that links them.
 *
 * <p>Real enough to be the thing under test and small enough to be free. The graph is here rather
 * than trimmed away because a document with {@code components} and no {@code dependencies} is the
 * shape a generator emits when it fails halfway, and a fixture that looks like that teaches the
 * wrong thing to whoever copies it.
 *
 * <p><b>Content must be unique per RUN, not merely per test.</b> Blobs dedupe globally and
 * content-addressed, and the {@code service} module's suite has no blob wipe — the mirror suites'
 * hard-won lesson. Both {@code name} and {@code version} travel into the bytes, so a case that names
 * its own version is distinguishable for free.
 */
public final class TinySbom {

  private TinySbom() {}

  /**
   * The document as a mutable {@link JsonObject}, so a refusal case can break exactly one field —
   * {@code json(…).put("specVersion", "1.3")} is the whole of that test's fixture.
   */
  public static JsonObject json(String name, String version) {
    return json(name, version, 0);
  }

  /**
   * The same document with {@code extraComponents} filler entries — how the cap suite makes an
   * oversized body without inventing a second synthesiser.
   */
  public static JsonObject json(String name, String version, int extraComponents) {
    String root = "pkg:generic/" + name + "@" + version;
    JsonArray components = new JsonArray();
    JsonArray dependsOn = new JsonArray();
    for (int i = 0; i <= extraComponents; i++) {
      String ref = "pkg:generic/dependency-" + i + "@1.0." + i;
      components.add(
          new JsonObject()
              .put("bom-ref", ref)
              .put("type", "library")
              .put("name", "dependency-" + i)
              .put("version", "1.0." + i));
      dependsOn.add(ref);
    }
    JsonArray dependencies = new JsonArray().add(new JsonObject().put("ref", root).put("dependsOn", dependsOn));
    dependsOn.forEach(ref -> dependencies.add(new JsonObject().put("ref", ref).put("dependsOn", new JsonArray())));

    return new JsonObject()
        .put("bomFormat", "CycloneDX")
        .put("specVersion", "1.6")
        .put("version", 1)
        .put(
            "metadata",
            new JsonObject()
                .put(
                    "component",
                    new JsonObject()
                        .put("bom-ref", root)
                        .put("type", "library")
                        .put("name", name)
                        .put("version", version)))
        .put("components", components)
        .put("dependencies", dependencies);
  }

  /** The document as it goes on the wire. */
  public static byte[] document(String name, String version) {
    return bytes(json(name, version));
  }

  /** The document with enough filler to be over a lowered cap. */
  public static byte[] padded(String name, String version, int extraComponents) {
    return bytes(json(name, version, extraComponents));
  }

  public static byte[] bytes(JsonObject document) {
    return document.encode().getBytes(StandardCharsets.UTF_8);
  }

  /** The digest the publish response must carry, computed the way the store computes it. */
  public static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
