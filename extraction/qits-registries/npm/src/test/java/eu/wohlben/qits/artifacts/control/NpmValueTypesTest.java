package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.error.NpmException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The npm side's parsing and hashing rules, as plain JUnit — no Quarkus, because none of it needs a
 * container and the cases are cheap enough to be exhaustive.
 */
class NpmValueTypesTest {

  @Nested
  class Names {

    @Test
    void aScopedNameSplitsIntoScopeAndUnscopedName() {
      NpmPackageName scoped = NpmPackageName.parse("@qits/angular");
      assertEquals("qits", scoped.scope());
      assertEquals("angular", scoped.unscoped());
      assertEquals("@qits/angular", scoped.full());
      assertTrue(scoped.scoped());
    }

    @Test
    void anUnscopedNameHasNoScopeAtAll() {
      NpmPackageName plain = NpmPackageName.parse("left-pad");
      assertNull(plain.scope());
      assertEquals("left-pad", plain.unscoped());
      assertFalse(plain.scoped());
    }

    @Test
    void onlyTheScopeSeparatorIsDecoded() {
      // Vert.x hands the router the path with its escapes intact, so this is where %2f becomes a
      // slash. URLDecoder is deliberately not used: it also turns `+` into a space, which is FORM
      // encoding, and would corrupt a legal name.
      assertEquals("@qits/angular", NpmPackageName.decodePathSegment("@qits%2fangular"));
      assertEquals("@qits/angular", NpmPackageName.decodePathSegment("@qits%2Fangular"));
      assertEquals("a+b", NpmPackageName.decodePathSegment("a+b"));
      assertEquals("left-pad", NpmPackageName.decodePathSegment("left-pad"));
    }

    @Test
    void namesNpmWouldRefuseAreRefusedHere() {
      assertThrows(NpmException.class, () -> NpmPackageName.parse("@qits"), "a scope is not a name");
      assertThrows(NpmException.class, () -> NpmPackageName.parse("@qits/"), "nothing after it");
      assertThrows(NpmException.class, () -> NpmPackageName.parse("@/angular"), "empty scope");
      assertThrows(NpmException.class, () -> NpmPackageName.parse(".hidden"), "leading dot");
      assertThrows(NpmException.class, () -> NpmPackageName.parse("_private"), "leading underscore");
      assertThrows(NpmException.class, () -> NpmPackageName.parse("a/b/c"), "two slashes");
      assertThrows(NpmException.class, () -> NpmPackageName.parse(""), "empty");
      assertThrows(NpmException.class, () -> NpmPackageName.parse("x".repeat(215)), "over 214");
    }

    @Test
    void legacyUppercaseNamesStillParse() {
      // New packages may not carry uppercase, but JSONStream and its generation predate that rule
      // and the proxy has to be able to fetch them.
      assertEquals("JSONStream", NpmPackageName.parse("JSONStream").unscoped());
    }
  }

  @Nested
  class TarballNames {

    @Test
    void theFileNameUsesTheUnscopedNameAndTheVersionRoundTrips() {
      // The two halves of one decision: the packument emits this name, and the tarball route
      // recovers the version from it. Both live here so they cannot drift apart.
      NpmPackageName scoped = NpmPackageName.parse("@qits/angular");
      assertEquals("angular-1.2.3.tgz", scoped.tarballFile("1.2.3"));
      assertEquals("1.2.3", scoped.versionOfTarball("angular-1.2.3.tgz"));
    }

    @Test
    void hyphensInEitherHalfAreUnambiguousBecauseTheNameIsAlreadyKnown() {
      NpmPackageName hyphenated = NpmPackageName.parse("left-pad");
      assertEquals("1.3.0", hyphenated.versionOfTarball("left-pad-1.3.0.tgz"));
      assertEquals(
          "1.0.0-rc.1+build.2", hyphenated.versionOfTarball("left-pad-1.0.0-rc.1+build.2.tgz"));
    }

    @Test
    void aFileThatIsNotThisPackagesIsNotAVersion() {
      NpmPackageName pkg = NpmPackageName.parse("left-pad");
      assertNull(pkg.versionOfTarball("right-pad-1.3.0.tgz"));
      assertNull(pkg.versionOfTarball("left-pad-1.3.0.tar.gz"), "not a .tgz");
      assertNull(pkg.versionOfTarball("left-pad-.tgz"), "no version at all");
      assertNull(pkg.versionOfTarball(null));
    }
  }

  @Nested
  class Hashes {

    // The empty string's hashes, which are the one pair nobody has to trust this test about.
    private static final String EMPTY_SHA1 = "da39a3ee5e6b4b0d3255bfef95601890afd80709";

    @Test
    void shasumIsSha1HexAndIntegrityIsAnSriSha512() {
      assertEquals(EMPTY_SHA1, NpmIntegrity.shasum(new byte[0]));
      assertTrue(NpmIntegrity.integrity(new byte[0]).startsWith("sha512-"));

      byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
      assertEquals("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d", NpmIntegrity.shasum(content));
      assertEquals(
          "sha512-m3HSJL1i83hdltRq0+o9czGb+8KJDKra4t/3JRlnPKcjI8PZm6XBHXx6zG4UuMXaDEZjR1wuXDre9G9zvN7AQw==",
          NpmIntegrity.integrity(content));
    }

    @Test
    void neitherIsTheKeyTheStoreAddressesBy() {
      // The point of storing both as columns: a tarball's storage key is its sha256, and these two
      // are npm's, re-emitted on the wire. A proxied version carries UPSTREAM's values, so they can
      // never be derived from what is on disk.
      assertEquals(40, NpmIntegrity.shasum(new byte[0]).length(), "sha1 hex is 40 chars, not 64");
    }
  }

  @Nested
  class Precedence {

    @Test
    void aPrereleaseSortsBelowTheReleaseItWasBuiltFrom() {
      // The case the latest guard exists for, in the exact shape this platform publishes: a main
      // build stamped <lastReleasedVersion>-main.g<sha> must never outrank that release.
      assertBelow("2026.801.63140-main.g0fe7780", "2026.801.63140");
      assertBelow("1.0.0-rc.1", "1.0.0");
      // ...and it still outranks everything below that release, which is what makes it usable.
      assertBelow("2026.731.193059", "2026.801.63140-main.g0fe7780");
    }

    @Test
    void theCoreVersionComparesNumericallyRatherThanAsText() {
      assertBelow("0.0.4", "2026.801.63140");
      assertBelow("2.0.0", "10.0.0", "as text 10 sorts first");
      assertBelow("1.9.0", "1.10.0");
      assertBelow("1.0.9", "1.0.10");
      // Nothing is converted to a number, so a calver patch of any width orders correctly.
      assertBelow("2026.801.63140", "2026.801.99999999999999999999");
    }

    @Test
    void theSpecsOwnExampleChainHolds() {
      // semver.org §11, verbatim.
      List<String> ascending =
          List.of(
              "1.0.0-alpha",
              "1.0.0-alpha.1",
              "1.0.0-alpha.beta",
              "1.0.0-beta",
              "1.0.0-beta.2",
              "1.0.0-beta.11",
              "1.0.0-rc.1",
              "1.0.0");
      for (int i = 1; i < ascending.size(); i++) {
        assertBelow(ascending.get(i - 1), ascending.get(i));
      }
    }

    @Test
    void aNumericIdentifierAlwaysSortsBelowAnAlphanumericOne() {
      assertBelow("1.0.0-2", "1.0.0-alpha", "numeric identifiers rank lower, whatever their value");
      assertBelow("1.0.0-999999", "1.0.0-a");
      // Which is also why the `g` prefix on the sha is load-bearing: it keeps the identifier
      // alphanumeric no matter how many digits the abbreviated sha happens to be.
      assertBelow("1.0.0-main.1", "1.0.0-main.g0fe7780");
    }

    @Test
    void aLongerPrereleaseOutranksAPrefixOfItself() {
      assertBelow("1.0.0-alpha", "1.0.0-alpha.1");
      assertBelow("1.0.0-main.g0fe7780", "1.0.0-main.g0fe7780.1");
    }

    @Test
    void buildMetadataIsParsedAndThenIgnored() {
      assertEquals(
          0,
          NpmSemver.parse("1.0.0+build.1").orElseThrow()
              .compareTo(NpmSemver.parse("1.0.0+build.2").orElseThrow()),
          "two versions differing only in build metadata have equal precedence");
      assertEquals(
          0,
          NpmSemver.parse("1.0.0").orElseThrow()
              .compareTo(NpmSemver.parse("1.0.0+anything").orElseThrow()));
    }

    @Test
    void aLeadingZeroMeansTheStringIsNotAVersionAtAll() {
      // Not a synonym for the unpadded form: refusing to parse is what stops "01" and "1" being
      // ordered against each other at all, which no digit comparison could do correctly.
      assertTrue(NpmSemver.parse("01.0.0").isEmpty());
      assertTrue(NpmSemver.parse("1.00.0").isEmpty());
      assertTrue(NpmSemver.parse("1.0.0-01").isEmpty(), "a numeric prerelease identifier too");
      assertTrue(NpmSemver.parse("1.0.0-0a").isPresent(), "but 0a is alphanumeric, so it is legal");
    }

    @Test
    void whatIsNotSemverDoesNotParse() {
      assertTrue(NpmSemver.parse(null).isEmpty());
      assertTrue(NpmSemver.parse("").isEmpty());
      assertTrue(NpmSemver.parse("1.0").isEmpty(), "npm requires all three parts");
      assertTrue(NpmSemver.parse("v1.0.0").isEmpty(), "no coercion: a v prefix is not a version");
      assertTrue(NpmSemver.parse("1.0.0-").isEmpty(), "an empty prerelease part");
      assertTrue(NpmSemver.parse("1.0.0-alpha..1").isEmpty(), "an empty identifier");
      assertTrue(NpmSemver.parse("1.0.0_1").isEmpty());
    }

    @Test
    void aPrereleaseIsRecognisableWithoutComparingIt() {
      assertTrue(NpmSemver.parse("2026.801.63140-main.g0fe7780").orElseThrow().isPrerelease());
      assertFalse(NpmSemver.parse("2026.801.63140").orElseThrow().isPrerelease());
    }

    /** Asserts strict ordering in both directions, so an antisymmetric comparator cannot pass. */
    private static void assertBelow(String lower, String higher) {
      assertBelow(lower, higher, "");
    }

    private static void assertBelow(String lower, String higher, String why) {
      NpmSemver low = NpmSemver.parse(lower).orElseThrow(() -> unparseable(lower));
      NpmSemver high = NpmSemver.parse(higher).orElseThrow(() -> unparseable(higher));
      assertTrue(low.compareTo(high) < 0, lower + " should sort below " + higher + " " + why);
      assertTrue(high.compareTo(low) > 0, higher + " should sort above " + lower + " " + why);
      assertEquals(0, low.compareTo(low), lower + " should equal itself");
    }

    private static AssertionError unparseable(String version) {
      return new AssertionError("should have parsed: " + version);
    }
  }
}
