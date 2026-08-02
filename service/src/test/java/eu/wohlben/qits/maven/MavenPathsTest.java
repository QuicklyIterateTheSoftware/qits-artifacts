package eu.wohlben.qits.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The route grammar, as plain JUnit — no Quarkus, because this is a property of the regexes and the
 * cases that matter are cheap to be exhaustive about. {@code MavenRegistryTest} then proves the
 * router actually behaves this way over the wire, which is the half a regex test cannot reach.
 */
class MavenPathsTest {

  @Test
  void theRepositoryIsTheFirstSegmentAndThePathIsEverythingAfter() {
    String url =
        "/artifacts/maven/maven/eu/wohlben/qits/qits-eventstream/1.0.0/qits-eventstream-1.0.0.jar";
    assertEquals("maven", group(url, "repository"));
    assertEquals(
        "eu/wohlben/qits/qits-eventstream/1.0.0/qits-eventstream-1.0.0.jar", group(url, "path"));
  }

  @Test
  void theBaseItselfAndARepositoryWithNothingAfterItMatchNoRoute() {
    // Both fall to the catch-all, which answers a short plain-text 404 — never the SPA's HTML.
    assertFalse(matches("/artifacts/maven"));
    assertFalse(matches("/artifacts/maven/"));
    assertFalse(matches("/artifacts/maven/maven"));
    assertFalse(matches("/artifacts/maven/maven/"));
    // And a repository segment outside the grammar misses too — the handler never sees it.
    assertFalse(matches("/artifacts/maven/Maven/eu/x/1.0.0/x-1.0.0.jar"), "uppercase repository");
  }

  @Test
  void deepPathsMetadataAndChecksumsAllMatchTheOneTail() {
    assertTrue(
        matches(
            "/artifacts/maven/maven/eu/wohlben/qits/qits-eventstream/maven-metadata.xml"));
    assertTrue(
        matches(
            "/artifacts/maven/maven/eu/wohlben/qits/qits-eventstream/maven-metadata.xml.sha1"));
    assertTrue(
        matches(
            "/artifacts/maven/maven/eu/wohlben/qits/qits-eventstream/1.0.0/qits-eventstream-1.0.0.jar.sha256"));
    assertTrue(
        matches(
            "/artifacts/maven/maven/eu/wohlben/qits/qits-eventstream/1.0.1-SNAPSHOT/qits-eventstream-1.0.1-20260802.123456-3.jar"));
  }

  @Test
  void everyGroupIsNamedOrNonCapturing() {
    // vertx-web compares Matcher.groupCount() against the named groups it scraped from the pattern
    // and falls back to positional param0..paramN when they disagree — so ONE bare (...) anywhere in
    // these patterns breaks pathParam("path") on that route, at runtime, silently. Counting the
    // groups is the cheapest possible guard.
    long named =
        Pattern.compile("\\(\\?<[a-zA-Z][a-zA-Z0-9]*>").matcher(MavenPaths.ARTIFACT).results()
            .count();
    assertEquals(2, named, "named group count changed in: " + MavenPaths.ARTIFACT);
    assertEquals(
        2,
        Pattern.compile(MavenPaths.ARTIFACT).matcher("").groupCount(),
        "a bare capturing group crept into: " + MavenPaths.ARTIFACT);
  }

  private static boolean matches(String path) {
    return Pattern.compile(MavenPaths.ARTIFACT).matcher(path).matches();
  }

  private static String group(String path, String groupName) {
    Matcher matcher = Pattern.compile(MavenPaths.ARTIFACT).matcher(path);
    assertTrue(matcher.matches(), MavenPaths.ARTIFACT + " should match " + path);
    return matcher.group(groupName);
  }
}
