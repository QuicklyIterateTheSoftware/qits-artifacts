package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.error.NpmException;
import java.nio.charset.StandardCharsets;
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
}
