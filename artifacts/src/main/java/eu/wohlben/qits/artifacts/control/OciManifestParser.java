package eu.wohlben.qits.artifacts.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.artifacts.error.OciCode;
import eu.wohlben.qits.artifacts.error.OciException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads a manifest far enough to validate it and to find what it references.
 *
 * <p>Deliberately {@code readTree} into {@link JsonNode} rather than binding to records. A DTO
 * serialised or deserialised only inside a raw Vert.x handler is invisible to the native-image
 * build — there is no JAX-RS provider chain for it to be discovered through — so it needs
 * {@code @RegisterForReflection} and produces a green build that 500s in production when it does not
 * have it. That is the {@code dto/UploadResult} lesson, and the cheapest way to not relearn it is to
 * have no bound types at all. {@code JsonNode} is Map- and List-backed and needs no registration.
 */
@ApplicationScoped
public class OciManifestParser {

  @Inject ObjectMapper objectMapper;

  /**
   * What a manifest points at.
   *
   * @param mediaType the manifest's own type, as validated against the request's Content-Type
   * @param index whether this is a multi-arch index (children are manifests) or an image manifest
   *     (children are blobs)
   * @param references the digests, in bare hex, that must exist before the manifest may be bound —
   *     foreign layers already excluded
   */
  public record ParsedManifest(String mediaType, boolean index, List<String> references) {}

  /**
   * @param bytes the manifest exactly as received — never re-serialized, since a manifest's digest
   *     covers its literal whitespace
   * @param contentType the request's Content-Type, or null
   */
  public ParsedManifest parse(byte[] bytes, String contentType) {
    JsonNode root;
    try {
      root = objectMapper.readTree(bytes);
    } catch (Exception e) {
      throw new OciException(OciCode.MANIFEST_INVALID, "manifest is not valid JSON");
    }
    if (root == null || !root.isObject()) {
      throw new OciException(OciCode.MANIFEST_INVALID, "manifest is not a JSON object");
    }

    // Schema 1 is signed, uses a completely different shape, and is long deprecated. Reject it by
    // name so the error says which thing is unsupported rather than failing later on a missing key.
    if (root.has("signatures") || root.path("schemaVersion").asInt(0) == 1) {
      throw new OciException(
          OciCode.MANIFEST_INVALID, "manifest schema version 1 is not supported; push a v2 image");
    }
    if (root.path("schemaVersion").asInt(0) != 2) {
      throw new OciException(OciCode.MANIFEST_INVALID, "manifest schemaVersion must be 2");
    }

    String mediaType = resolveMediaType(root, contentType);
    boolean index = OciMediaTypes.isIndex(mediaType);
    return new ParsedManifest(mediaType, index, index ? children(root) : blobs(root));
  }

  /**
   * The manifest's type: the request's Content-Type if it carries one, else the body's own {@code
   * mediaType}. If both are present they must agree — a mismatch means the client and the document
   * disagree about what was pushed, and guessing which is right would store the wrong Content-Type
   * for every future pull.
   */
  private static String resolveMediaType(JsonNode root, String contentType) {
    String declared = stripParameters(contentType);
    String embedded = root.path("mediaType").asText(null);

    if (declared != null && embedded != null && !declared.equals(embedded)) {
      throw new OciException(
          OciCode.MANIFEST_INVALID,
          "Content-Type does not match the manifest's own mediaType",
          Map.of("contentType", declared, "mediaType", embedded));
    }
    String mediaType = declared != null ? declared : embedded;
    if (mediaType == null) {
      throw new OciException(
          OciCode.MANIFEST_INVALID, "manifest has neither a Content-Type nor a mediaType");
    }
    if (!OciMediaTypes.isSupportedManifest(mediaType)) {
      throw new OciException(
          OciCode.MANIFEST_INVALID,
          "unsupported manifest media type",
          Map.of("mediaType", mediaType));
    }
    return mediaType;
  }

  /** An image manifest references its config blob and every distributable layer. */
  private static List<String> blobs(JsonNode root) {
    List<String> references = new ArrayList<>();
    JsonNode config = root.path("config");
    if (config.isObject()) {
      references.add(OciDigest.requireHex(config.path("digest").asText(null)));
    }
    for (JsonNode layer : root.path("layers")) {
      if (OciMediaTypes.isForeignLayer(layer.path("mediaType").asText(null))) {
        continue;
      }
      references.add(OciDigest.requireHex(layer.path("digest").asText(null)));
    }
    // `subject` (OCI 1.1 referrers) is parsed and ignored on purpose: it may name a manifest that
    // was never pushed here, and requiring it would reject valid images. /v2/<name>/referrers/ is
    // not implemented, so nothing else needs it either.
    return List.copyOf(references);
  }

  /** An index references child MANIFESTS, which must already be known to this name. */
  private static List<String> children(JsonNode root) {
    List<String> references = new ArrayList<>();
    for (JsonNode child : root.path("manifests")) {
      references.add(OciDigest.requireHex(child.path("digest").asText(null)));
    }
    return List.copyOf(references);
  }

  private static String stripParameters(String contentType) {
    if (contentType == null || contentType.isBlank()) {
      return null;
    }
    int semicolon = contentType.indexOf(';');
    return (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim();
  }
}
