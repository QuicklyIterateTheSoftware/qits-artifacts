package eu.wohlben.qits.artifacts.gc;

import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * What a kept pom itself needs — {@code maven-packages}' second derived keep, and the one that
 * replaced the P90D window rather than shrinking it.
 *
 * <h2>The gap it closes</h2>
 *
 * <p>A manifest pin names what a repository's own pom writes: {@code qits-registries-maven}, say.
 * It does not name what THAT pom then resolves — its parent, its own dependencies on
 * {@code qits-blobstore}, an imported BOM. Those coordinates are named by nobody outside this store,
 * and under the long windows they survived on age alone. The 01:55 sweep of 2026-09-05 measured the
 * consequence: {@code qits-blobstore}, {@code qits-registries} and {@code qits-userflows-parent}
 * versions condemned while pinned poms still referenced them. So the keep-set is closed under the
 * references of the poms already in it.
 *
 * <h2>What internal means</h2>
 *
 * <p><b>Existing in this store.</b> There is no configured group prefix and there must not be one: a
 * referenced coordinate this store does not hold is somebody else's problem (maven central's,
 * through the mirror), and a coordinate it does hold is one a resolve would come here for. That
 * makes the rule a set intersection over the enumeration this adapter already has, with nothing to
 * keep in step with a naming convention.
 *
 * <h2>Version indirection</h2>
 *
 * <p>{@code ${project.version}} and its spellings resolve against the pom's own coordinates, which
 * is a fact the document carries. <b>Anything else interpolated is skipped</b> — no property table,
 * no parent chain walk, no profile evaluation. The platform's own poms spell every internal version
 * literally by house rule (the clone-alone rule in {@code CLAUDE.md}: "the poms duplicate versions
 * instead of inheriting them"), so the cases this cannot read are cases this store does not have,
 * and a half-implemented interpolator would be a keep-set that looks complete and is not.
 *
 * <h2>Bounds</h2>
 *
 * <p>The walk is a breadth-first fixpoint with a visited set, so a cycle — two poms importing each
 * other's BOM — terminates on the second visit rather than by depth counting. {@link #MAX_POMS_READ}
 * is the belt over that: the store holds on the order of 150 maven coordinates, so any run reading
 * thousands of poms is reading something this rule does not understand and should stop rather than
 * grind. Stopping early UNDER-keeps, which is the direction the grace window covers and a sweep's
 * own receipt makes visible.
 */
final class MavenPomClosure {

  /**
   * How many poms one plan may read before the walk gives up. Two thousand against a store of ~150
   * maven identities is a bound on a bug, not on the rule.
   */
  static final int MAX_POMS_READ = 2_000;

  /** How much of a pom is read. A pom that is a megabyte is not a pom. */
  static final int MAX_POM_BYTES = 1 << 20;

  private MavenPomClosure() {}

  /** The rule sentence, naming the pom whose reference saved this coordinate. */
  static String reachableFrom(String referrer) {
    return "reachable from kept pom " + referrer + " (dependency closure)";
  }

  /** Reads one coordinate's {@code .pom} bytes, or null when there are none to read. */
  interface Poms {
    byte[] read(String coordinate);
  }

  /**
   * Every hosted coordinate reachable from the seeds, mapped to the rule sentence that names its
   * referrer.
   *
   * <p>A seed is never in the answer: it is already kept under a rule of its own — a pin, the
   * release belt, the snapshot belt — and reporting it as "reachable from" something would replace
   * the fact a reviewer is reading for. The walk still traverses seeds, which is what makes the
   * second hop work.
   *
   * @param seeds the coordinates the keep-set already holds
   * @param hosted every coordinate this store has, which is the whole definition of internal
   * @param poms how a coordinate's pom bytes are read
   */
  static Map<String, String> from(Set<String> seeds, Set<String> hosted, Poms poms) {
    Map<String, String> reached = new LinkedHashMap<>();
    Set<String> visited = new LinkedHashSet<>(seeds);
    Deque<String> pending = new ArrayDeque<>(seeds);
    int read = 0;
    while (!pending.isEmpty() && read < MAX_POMS_READ) {
      String referrer = pending.poll();
      byte[] pom = poms.read(referrer);
      read++;
      if (pom == null) {
        continue;
      }
      for (String reference : referencesOf(pom)) {
        if (!hosted.contains(reference) || !visited.add(reference)) {
          continue;
        }
        reached.put(reference, reachableFrom(referrer));
        pending.add(reference);
      }
    }
    return Map.copyOf(reached);
  }

  /**
   * The coordinates one pom names: its parent, its own dependencies, and the BOMs its
   * {@code dependencyManagement} imports.
   *
   * <p>Managed dependencies that are NOT imports are deliberately absent: a
   * {@code dependencyManagement} entry states a version for a dependency somebody may declare, and
   * keeping every one of them would keep versions no build resolves. An {@code import} is different
   * in kind — resolving this pom reads that BOM's own file — so it is a reference in the same sense
   * the parent is.
   *
   * <p>Children are read as DIRECT children throughout, which is what keeps a plugin's own
   * {@code <dependencies>} out of the project's.
   */
  static Set<String> referencesOf(byte[] pom) {
    Element project = root(pom);
    if (project == null) {
      return Set.of();
    }
    Element parent = child(project, "parent");
    String parentGroup = childText(parent, "groupId");
    String parentVersion = childText(parent, "version");
    String ownGroup = or(childText(project, "groupId"), parentGroup);
    String ownVersion = or(childText(project, "version"), parentVersion);
    Coordinates own = new Coordinates(ownGroup, ownVersion, parentGroup, parentVersion);

    Set<String> references = new LinkedHashSet<>();
    add(references, coordinateOf(parent, own));
    for (Element dependencies : children(project, "dependencies")) {
      for (Element dependency : children(dependencies, "dependency")) {
        add(references, coordinateOf(dependency, own));
      }
    }
    for (Element managed : children(child(project, "dependencyManagement"), "dependencies")) {
      for (Element dependency : children(managed, "dependency")) {
        if ("import".equals(trimmed(childText(dependency, "scope")))) {
          add(references, coordinateOf(dependency, own));
        }
      }
    }
    return references;
  }

  /** The pom's own coordinates, which is everything an interpolation here may resolve against. */
  private record Coordinates(
      String groupId, String version, String parentGroupId, String parentVersion) {}

  /** {@code groupId:artifactId:version} — this adapter's identity verbatim — or null. */
  private static String coordinateOf(Element element, Coordinates own) {
    if (element == null) {
      return null;
    }
    String groupId = resolve(childText(element, "groupId"), own);
    String artifactId = resolve(childText(element, "artifactId"), own);
    String version = resolve(childText(element, "version"), own);
    if (groupId == null || artifactId == null || version == null) {
      return null;
    }
    return groupId + ":" + artifactId + ":" + version;
  }

  /**
   * A literal value unchanged, one of maven's own coordinate expressions resolved against the pom,
   * and anything else null.
   *
   * <p>Null is "this reference cannot be read", and it drops the reference rather than the pom: a
   * property-driven version beside three literal ones must not cost the other three their keep.
   */
  private static String resolve(String value, Coordinates own) {
    String text = trimmed(value);
    if (text == null || text.isEmpty()) {
      return null;
    }
    if (text.indexOf("${") < 0) {
      return text;
    }
    String resolved =
        switch (text) {
          case "${project.version}", "${pom.version}" -> own.version();
          case "${project.parent.version}", "${parent.version}" -> own.parentVersion();
          case "${project.groupId}", "${pom.groupId}" -> own.groupId();
          case "${project.parent.groupId}", "${parent.groupId}" -> own.parentGroupId();
          default -> null;
        };
    return trimmed(resolved);
  }

  private static void add(Set<String> references, String coordinate) {
    if (coordinate != null) {
      references.add(coordinate);
    }
  }

  private static String or(String first, String second) {
    String text = trimmed(first);
    return text == null || text.isEmpty() ? trimmed(second) : text;
  }

  private static String trimmed(String value) {
    if (value == null) {
      return null;
    }
    String text = value.trim();
    return text.isEmpty() ? null : text;
  }

  // --- the little DOM, with the settings a document off a wire needs ------------------------------

  /**
   * The document's root element, or null when it does not parse.
   *
   * <p><b>The JDK's own parser</b>, so the closure adds no dependency and nothing new for the native
   * image to be told about. <b>External entities and the DOCTYPE are off</b>: these bytes were
   * pushed to this registry by whoever could reach it, and a parser that resolved entities would
   * fetch whatever a pom told it to. <b>Namespace-unaware on purpose</b>, because a pom declares the
   * maven namespace and the elements are looked up by their plain names either way.
   *
   * <p>An unparseable pom is not an error here. It answers "no references", the walk carries on, and
   * the coordinates it would have kept fall back to whatever else names them — the honest failure
   * direction for a rule that only ever ADDS keeps.
   */
  private static Element root(byte[] xml) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(false);
      factory.setExpandEntityReferences(false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      DocumentBuilder builder = factory.newDocumentBuilder();
      builder.setErrorHandler(SILENT);
      Document document = builder.parse(new ByteArrayInputStream(xml));
      return document.getDocumentElement();
    } catch (Exception unreadable) {
      return null;
    }
  }

  /**
   * A parse failure is an ANSWER here, so it is returned rather than printed. The JDK's default
   * handler writes every malformed document to {@code System.err}, and a store holding one file
   * whose name ends in {@code .pom} and whose bytes are not XML would print on every run.
   */
  private static final ErrorHandler SILENT =
      new ErrorHandler() {
        @Override
        public void warning(SAXParseException ignored) {}

        @Override
        public void error(SAXParseException ignored) {}

        @Override
        public void fatalError(SAXParseException fatal) throws SAXException {
          throw fatal;
        }
      };

  /** The first direct child element of that name, or null. */
  private static Element child(Element parent, String name) {
    List<Element> found = children(parent, name);
    return found.isEmpty() ? null : found.get(0);
  }

  /** Every DIRECT child element of that name — never a descendant. */
  private static List<Element> children(Element parent, String name) {
    List<Element> found = new ArrayList<>();
    if (parent == null) {
      return found;
    }
    NodeList nodes = parent.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      Node node = nodes.item(i);
      if (node.getNodeType() == Node.ELEMENT_NODE && node.getNodeName().equals(name)) {
        found.add((Element) node);
      }
    }
    return found;
  }

  /** The text of the first direct child of that name, or null. */
  private static String childText(Element parent, String name) {
    Element element = child(parent, name);
    if (element == null) {
      return null;
    }
    StringBuilder text = new StringBuilder();
    NodeList nodes = element.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      Node node = nodes.item(i);
      if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
        text.append(node.getNodeValue());
      }
    }
    return text.toString();
  }
}
