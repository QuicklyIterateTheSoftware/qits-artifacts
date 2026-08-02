package eu.wohlben.qits.maven;

import eu.wohlben.qits.artifacts.control.MavenVersionOrder;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The derived {@code maven-metadata.xml} documents, assembled as text per request and never
 * stored.
 *
 * <p>This is the packument precedent word for word: the metadata document is assembled from rows
 * on every read, so it cannot become a second source of truth that a second deploy silently
 * invalidates. XML is built as text rather than through DOM or JAXB — the documents are small, and
 * a tree API would buy reflection surface for nothing.
 */
final class MavenMetadata {

  private static final DateTimeFormatter LAST_UPDATED =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

  private MavenMetadata() {}

  /**
   * The artifact-level document: every version directory under {@code <groupId>/<artifactId>}.
   *
   * <p>{@code latest} is the highest version by {@link MavenVersionOrder}, {@code release} the
   * highest non-{@code SNAPSHOT} one — with full snapshot support the two genuinely differ, and
   * both are served. A version that cannot be ordered sorts last and the document still serves,
   * because a metadata {@code GET} that 500s breaks every consumer of the repository at once.
   */
  static String artifactDocument(
      String groupId, String artifactId, List<String> versions, Instant lastUpdated) {
    List<String> ordered = new ArrayList<>(versions);
    ordered.sort(MavenVersionOrder.INSTANCE);
    StringBuilder doc = new StringBuilder();
    doc.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<metadata>\n");
    doc.append("  <groupId>").append(escape(groupId)).append("</groupId>\n");
    doc.append("  <artifactId>").append(escape(artifactId)).append("</artifactId>\n");
    doc.append("  <versioning>\n");
    if (!ordered.isEmpty()) {
      doc.append("    <latest>")
          .append(escape(ordered.get(ordered.size() - 1)))
          .append("</latest>\n");
      String release = null;
      for (int i = ordered.size() - 1; i >= 0; i--) {
        if (!ordered.get(i).endsWith("-SNAPSHOT")) {
          release = ordered.get(i);
          break;
        }
      }
      if (release != null) {
        doc.append("    <release>").append(escape(release)).append("</release>\n");
      }
    }
    doc.append("    <versions>\n");
    for (String version : ordered) {
      doc.append("      <version>").append(escape(version)).append("</version>\n");
    }
    doc.append("    </versions>\n");
    doc.append("    <lastUpdated>")
        .append(LAST_UPDATED.format(lastUpdated))
        .append("</lastUpdated>\n");
    doc.append("  </versioning>\n</metadata>\n");
    return doc.toString();
  }

  /** Path segments are wire-checked, but the document is text: escape rather than trust. */
  static String escape(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
