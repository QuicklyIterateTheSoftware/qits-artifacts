package eu.wohlben.qits.maven;

import eu.wohlben.qits.artifacts.control.MavenLayout;
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

  /**
   * The version-level document of a snapshot directory: the timestamped filenames, read back into
   * the {@code <snapshotVersions>} a resolver maps {@code 1.0.1-SNAPSHOT} through.
   *
   * <p>The server computed none of these names — the client did, at deploy time — so the document
   * is derivation, not rewriting: each timestamped name contributes one {@code <snapshotVersion>}
   * with its extension (and classifier when present), and the {@code <snapshot>} block takes the
   * newest. A directory holding only literal {@code -SNAPSHOT} files has nothing to derive and the
   * caller answers 404 instead — the resolver's defined fallback for a missing version-level
   * document is exactly that literal filename, and serving an empty document would pre-empt the
   * fallback with nothing in it.
   */
  static String snapshotDocument(
      String groupId,
      String artifactId,
      String version,
      List<MavenLayout.SnapshotFileName> files,
      Instant lastUpdated) {
    String baseVersion = version.substring(0, version.length() - "-SNAPSHOT".length());
    List<MavenLayout.SnapshotFileName> ordered = new ArrayList<>(files);
    ordered.sort(
        java.util.Comparator.comparing(MavenLayout.SnapshotFileName::timestamp)
            .thenComparing(MavenLayout.SnapshotFileName::buildNumber)
            .thenComparing(MavenLayout.SnapshotFileName::extension)
            .thenComparing(
                file -> file.classifier() == null ? "" : file.classifier()));
    MavenLayout.SnapshotFileName newest = ordered.get(ordered.size() - 1);

    StringBuilder doc = new StringBuilder();
    doc.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<metadata>\n");
    doc.append("  <groupId>").append(escape(groupId)).append("</groupId>\n");
    doc.append("  <artifactId>").append(escape(artifactId)).append("</artifactId>\n");
    doc.append("  <version>").append(escape(version)).append("</version>\n");
    doc.append("  <versioning>\n");
    doc.append("    <snapshot>\n");
    doc.append("      <timestamp>").append(newest.timestamp()).append("</timestamp>\n");
    doc.append("      <buildNumber>").append(newest.buildNumber()).append("</buildNumber>\n");
    doc.append("    </snapshot>\n");
    doc.append("    <lastUpdated>")
        .append(LAST_UPDATED.format(lastUpdated))
        .append("</lastUpdated>\n");
    doc.append("    <snapshotVersions>\n");
    for (MavenLayout.SnapshotFileName file : ordered) {
      doc.append("      <snapshotVersion>\n");
      if (file.classifier() != null) {
        doc.append("        <classifier>")
            .append(escape(file.classifier()))
            .append("</classifier>\n");
      }
      doc.append("        <extension>")
          .append(escape(file.extension()))
          .append("</extension>\n");
      doc.append("        <value>")
          .append(escape(file.value(baseVersion)))
          .append("</value>\n");
      doc.append("        <updated>")
          .append(file.timestamp().replace(".", ""))
          .append("</updated>\n");
      doc.append("      </snapshotVersion>\n");
    }
    doc.append("    </snapshotVersions>\n");
    doc.append("  </versioning>\n</metadata>\n");
    return doc.toString();
  }

  /** Path segments are wire-checked, but the document is text: escape rather than trust. */
  static String escape(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
