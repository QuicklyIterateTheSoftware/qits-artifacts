package eu.wohlben.qits.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The route grammar, as plain JUnit — no Quarkus, because this is a property of the regexes and the
 * cases that matter are cheap to be exhaustive about.
 *
 * <p>Two things make this worth its own suite. An OCI name may contain slashes, so the split between
 * name and operation happens inside a regex rather than in Vert.x' path-param machinery, and the
 * interesting cases are the ones where a name looks like an operation ({@code qits/manifests}). And
 * a stray <em>capturing</em> group in any of these patterns silently disables {@code
 * pathParam(name)} on that route at runtime, with nothing to see at build time — the last test here
 * is the guard against that.
 */
class RegistryPathsTest {

  private static final String HEX = "a".repeat(64);

  @Test
  void aMultiSlashNameIsKeptWholeAndSplitOnTheLastOperation() {
    // The feature document's own example.
    assertEquals(
        "qits/build-images/ci-base",
        name(RegistryPaths.MANIFEST, "/v2/qits/build-images/ci-base/manifests/latest"));
    assertEquals(
        "latest", group(RegistryPaths.MANIFEST, "/v2/qits/build-images/ci-base/manifests/latest", "ref"));
    assertEquals(
        "qits/build-images/ci-base",
        name(RegistryPaths.UPLOADS, "/v2/qits/build-images/ci-base/blobs/uploads/"));
  }

  @Test
  void anImageNamedLikeAnOperationStillResolves() {
    // The greedy name group splits on the LAST occurrence, which is what makes these work. A
    // first-match split would read the repository as "qits" and the image as "" here.
    assertEquals("qits/blobs", name(RegistryPaths.MANIFEST, "/v2/qits/blobs/manifests/latest"));

    // Greedy means "as much as possible while the rest still matches", so the name takes everything
    // up to the LAST /manifests/<ref> that leaves a valid reference behind — here an image named
    // `manifests` inside a repository named `qits`, tagged `manifests`.
    String pathological = "/v2/qits/manifests/manifests/manifests";
    assertEquals("qits/manifests", name(RegistryPaths.MANIFEST, pathological));
    assertEquals("manifests", group(RegistryPaths.MANIFEST, pathological, "ref"));
    assertEquals("foo/manifests", name(RegistryPaths.TAGS_LIST, "/v2/foo/manifests/tags/list"));
    assertEquals(
        "qits/blobs/uploads", name(RegistryPaths.UPLOADS, "/v2/qits/blobs/uploads/blobs/uploads/"));
  }

  @Test
  void theRoutesDoNotOverlapEachOther() {
    // Every ambiguity a reader worries about, pinned. If any of these started matching, a request
    // would be answered by the wrong handler rather than erroring.
    assertFalse(matches(RegistryPaths.BLOB, "/v2/qits/alpine/blobs/uploads/"));
    assertFalse(matches(RegistryPaths.UPLOADS, "/v2/qits/alpine/blobs/uploads/" + uuid()));
    assertFalse(matches(RegistryPaths.MANIFEST, "/v2/qits/alpine/tags/list"));
    assertFalse(matches(RegistryPaths.TAGS_LIST, "/v2/qits/alpine/manifests/latest"));
    assertFalse(matches(RegistryPaths.UPLOAD_SESSION, "/v2/qits/alpine/blobs/uploads/"));
  }

  @Test
  void bothTrailingSlashSpellingsOfTheUploadRouteAreAccepted() {
    // Clients disagree about the trailing slash and both forms are seen in the wild.
    assertTrue(matches(RegistryPaths.UPLOADS, "/v2/qits/alpine/blobs/uploads/"));
    assertTrue(matches(RegistryPaths.UPLOADS, "/v2/qits/alpine/blobs/uploads"));
  }

  @Test
  void aReferenceIsUnambiguouslyATagOrADigest() {
    assertEquals("latest", group(RegistryPaths.MANIFEST, "/v2/qits/alpine/manifests/latest", "ref"));
    assertEquals(
        "sha256:" + HEX,
        group(RegistryPaths.MANIFEST, "/v2/qits/alpine/manifests/sha256:" + HEX, "ref"));
    assertEquals("sha256:" + HEX, group(RegistryPaths.BLOB, "/v2/qits/alpine/blobs/sha256:" + HEX, "digest"));

    // A blob is addressed by digest only — a tag there is not a route.
    assertFalse(matches(RegistryPaths.BLOB, "/v2/qits/alpine/blobs/latest"));
    assertFalse(matches(RegistryPaths.BLOB, "/v2/qits/alpine/blobs/sha256:" + "a".repeat(63)));
    // A tag may not begin with a separator.
    assertFalse(matches(RegistryPaths.MANIFEST, "/v2/qits/alpine/manifests/-latest"));
    assertFalse(matches(RegistryPaths.MANIFEST, "/v2/qits/alpine/manifests/.latest"));
  }

  @Test
  void namesOutsideTheGrammarNeverReachAHandler() {
    // These fall through to the catch-all, which is the intended answer for all of them —
    // including /v2/_catalog, whose leading underscore cannot start a name component.
    assertFalse(matches(RegistryPaths.MANIFEST, "/v2/Qits/alpine/manifests/latest"), "uppercase");
    assertFalse(matches(RegistryPaths.MANIFEST, "/v2//alpine/manifests/latest"), "empty component");
    assertFalse(matches(RegistryPaths.TAGS_LIST, "/v2/_catalog"));
    assertFalse(matches(RegistryPaths.MANIFEST, "/v2/-qits/alpine/manifests/latest"));
    // A single-segment name DOES match the route — it has to, so the handler can answer
    // NAME_INVALID with an explanation rather than the route missing and answering "unknown name".
    assertTrue(matches(RegistryPaths.MANIFEST, "/v2/alpine/manifests/latest"));
  }

  @Test
  void everyGroupIsNamedOrNonCapturing() {
    // vertx-web compares Matcher.groupCount() against the named groups it scraped from the pattern
    // and falls back to positional param0..paramN when they disagree — so ONE bare (...) anywhere
    // in these patterns breaks pathParam("name") on that route, at runtime, silently. Counting the
    // groups is the cheapest possible guard.
    assertGroupsAllNamed(RegistryPaths.BLOB, 2);
    assertGroupsAllNamed(RegistryPaths.UPLOADS, 1);
    assertGroupsAllNamed(RegistryPaths.UPLOAD_SESSION, 2);
    assertGroupsAllNamed(RegistryPaths.MANIFEST, 2);
    assertGroupsAllNamed(RegistryPaths.TAGS_LIST, 1);
  }

  private static void assertGroupsAllNamed(String regex, int expectedNamed) {
    long named = Pattern.compile("\\(\\?<[a-zA-Z][a-zA-Z0-9]*>").matcher(regex).results().count();
    assertEquals(expectedNamed, named, "named group count changed in: " + regex);
    assertEquals(
        expectedNamed,
        Pattern.compile(regex).matcher("").groupCount(),
        "a bare capturing group crept into: " + regex);
  }

  private static String uuid() {
    return "b3f0c2de-0000-4000-8000-000000000000";
  }

  private static boolean matches(String regex, String path) {
    return Pattern.compile(regex).matcher(path).matches();
  }

  private static String name(String regex, String path) {
    return group(regex, path, "name");
  }

  private static String group(String regex, String path, String groupName) {
    Matcher matcher = Pattern.compile(regex).matcher(path);
    assertTrue(matcher.matches(), regex + " should match " + path);
    return matcher.group(groupName);
  }
}
