package eu.wohlben.qits.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The route grammar, as plain JUnit — no Quarkus, because this is a property of the regexes and the
 * cases that matter are cheap to be exhaustive about. {@code DocsRegistryTest} then proves the router
 * actually behaves this way over the wire, which is the half a regex test cannot reach.
 *
 * <p>The load-bearing test is {@link #theThreeRoutesDoNotOverlap}, and the property it pins is the
 * one the whole grammar rests on: a site name may be several segments deep, so {@code
 * <name>/<version>/<path>} could be split more than one way, and what makes it unambiguous is that a
 * bare {@code -} is not a legal name segment. That is a character-class fact — exactly the kind that
 * stays true until someone loosens one — so it is asserted rather than trusted.
 */
class DocsPathsTest {

  @Test
  void aSiteNameMayBeScopedOrNested() {
    assertEquals(
        "@qits/ui-components",
        group(DocsPaths.SITE, "/artifacts/docs/docs/@qits/ui-components", "name"));
    assertEquals("ui-components", group(DocsPaths.SITE, "/artifacts/docs/docs/ui-components", "name"));
    // The nesting the type exists to allow: a project namespace, not an npm scope.
    assertEquals(
        "someproject/somelib",
        group(DocsPaths.SITE, "/artifacts/docs/docs/someproject/somelib", "name"));
    assertEquals(
        "a/b/c/d", group(DocsPaths.SITE, "/artifacts/docs/docs/a/b/c/d", "name"), "four deep");
    assertFalse(matches(DocsPaths.SITE, "/artifacts/docs/docs/a/b/c/d/e"), "five is past the cap");
  }

  @Test
  void theRepositoryIsTheFirstSegmentAfterTheBase() {
    assertEquals(
        "docs", group(DocsPaths.SITE, "/artifacts/docs/docs/ui-components", "repository"));
    // The base itself is not a repository, and neither is a repository with nothing after it.
    assertFalse(matches(DocsPaths.SITE, "/artifacts/docs/docs"));
    assertFalse(matches(DocsPaths.SITE, "/artifacts/docs/docs/"));
  }

  @Test
  void aBundlePathSplitsIntoNameAndVersion() {
    String scoped = "/artifacts/docs/docs/@qits/ui-components/-/2026.807.0";
    assertEquals("@qits/ui-components", group(DocsPaths.BUNDLE, scoped, "name"));
    assertEquals("2026.807.0", group(DocsPaths.BUNDLE, scoped, "version"));

    // A prerelease with build metadata is a legal version, so the grammar must not be the thing
    // that decides docs cannot have one.
    String prerelease = "/artifacts/docs/docs/ui-components/-/1.0.0-main.gab854a1";
    assertEquals("1.0.0-main.gab854a1", group(DocsPaths.BUNDLE, prerelease, "version"));
  }

  @Test
  void aFilePathCarriesEverythingAfterTheVersion() {
    String nested =
        "/artifacts/docs/docs/@qits/ui-components/-/2026.807.0/assets/iframe-BPG5Eshk.js";
    assertEquals("@qits/ui-components", group(DocsPaths.FILE, nested, "name"));
    assertEquals("2026.807.0", group(DocsPaths.FILE, nested, "version"));
    assertEquals("assets/iframe-BPG5Eshk.js", group(DocsPaths.FILE, nested, "path"));

    // The bundle root's own index, and a font — the two shapes every real request takes.
    String index = "/artifacts/docs/docs/ui-components/-/1.0.0/index.html";
    assertEquals("index.html", group(DocsPaths.FILE, index, "path"));
    String font = "/artifacts/docs/docs/ui-components/-/1.0.0/sb-common-assets/nunito-sans-bold.woff2";
    assertEquals("sb-common-assets/nunito-sans-bold.woff2", group(DocsPaths.FILE, font, "path"));
  }

  @Test
  void theThreeRoutesDoNotOverlap() {
    // THE property the grammar rests on. A name segment must begin with @ or an alphanumeric, so a
    // bare `-` cannot be one and `@qits/ui-components/-/2026.807.0` cannot be read as a four-segment
    // name however hard the matcher tries. Without that, SITE would swallow every BUNDLE path and
    // the version list would answer a publish URL.
    String bundle = "/artifacts/docs/docs/@qits/ui-components/-/2026.807.0";
    assertFalse(matches(DocsPaths.SITE, bundle), "SITE must not swallow a bundle path");
    assertFalse(matches(DocsPaths.FILE, bundle), "a bundle path has no file after it");

    String file = "/artifacts/docs/docs/@qits/ui-components/-/2026.807.0/index.html";
    assertFalse(matches(DocsPaths.SITE, file), "SITE must not swallow a file path");
    assertFalse(matches(DocsPaths.BUNDLE, file), "BUNDLE must not swallow a file path");

    String site = "/artifacts/docs/docs/@qits/ui-components";
    assertFalse(matches(DocsPaths.BUNDLE, site), "a site path has no version");
    assertFalse(matches(DocsPaths.FILE, site));
  }

  @Test
  void aBareSeparatorIsNotASiteName() {
    // The character-class fact the test above depends on, asserted directly so a loosening shows up
    // here as well as there.
    assertFalse(matches(DocsPaths.SITE, "/artifacts/docs/docs/-"));
    assertFalse(matches(DocsPaths.SITE, "/artifacts/docs/docs/-/-"));
    assertFalse(matches(DocsPaths.BUNDLE, "/artifacts/docs/docs/-/-/1.0.0"));
  }

  @Test
  void namesOutsideTheGrammarNeverReachAHandler() {
    assertFalse(matches(DocsPaths.SITE, "/artifacts/docs/docs/.hidden"), "leading dot");
    assertFalse(matches(DocsPaths.SITE, "/artifacts/docs/docs/_private"), "leading underscore");
    assertFalse(matches(DocsPaths.SITE, "/artifacts/docs/DOCS/ui-components"), "uppercase repository");
    // A scope with nothing after it is not a site — the npm rule, and for the same reason.
    assertFalse(matches(DocsPaths.SITE, "/artifacts/docs/docs/@qits/"));
    // Uppercase in a site name is legal: a name mirrors whatever the publishing project calls
    // itself, and refusing one here would be this grammar deciding that for them.
    assertTrue(matches(DocsPaths.SITE, "/artifacts/docs/docs/JSONStream"));
  }

  @Test
  void everyGroupIsNamedOrNonCapturing() {
    // vertx-web compares Matcher.groupCount() against the named groups it scraped from the pattern
    // and falls back to positional param0..paramN when they disagree — so ONE bare (...) anywhere in
    // these patterns breaks pathParam("name") on that route, at runtime, silently. This grammar is
    // the one most at risk of it: NAME nests a repeated group inside a named one.
    assertGroupsAllNamed(DocsPaths.SITE, 2);
    assertGroupsAllNamed(DocsPaths.BUNDLE, 3);
    assertGroupsAllNamed(DocsPaths.FILE, 4);
  }

  private static void assertGroupsAllNamed(String regex, int expectedNamed) {
    long named = Pattern.compile("\\(\\?<[a-zA-Z][a-zA-Z0-9]*>").matcher(regex).results().count();
    assertEquals(expectedNamed, named, "named group count changed in: " + regex);
    assertEquals(
        expectedNamed,
        Pattern.compile(regex).matcher("").groupCount(),
        "a bare capturing group crept into: " + regex);
  }

  private static boolean matches(String regex, String path) {
    return Pattern.compile(regex).matcher(path).matches();
  }

  private static String group(String regex, String path, String group) {
    Matcher matcher = Pattern.compile(regex).matcher(path);
    assertTrue(matcher.matches(), regex + " did not match " + path);
    return matcher.group(group);
  }
}
