package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The maven wire's value types — {@link MavenLayout}'s path grammar, {@link MavenVersionOrder}'s
 * ordering, {@link MavenChecksums}' derivations — as plain JUnit, the {@code NpmValueTypesTest}
 * shape: no Quarkus, because these are properties of the classes and the cases that matter are
 * cheap to be exhaustive about.
 */
class MavenValueTypesTest {

  @Nested
  class Layout {

    @Test
    void aFullPathParsesIntoItsCoordinates() {
      MavenLayout.ArtifactPath parsed =
          MavenLayout.parse(
              "eu/wohlben/qits/qits-eventstream/1.0.0/qits-eventstream-1.0.0.jar");
      assertEquals("eu.wohlben.qits", parsed.groupId());
      assertEquals("qits-eventstream", parsed.artifactId());
      assertEquals("1.0.0", parsed.version());
      assertEquals("qits-eventstream-1.0.0.jar", parsed.file());
      assertEquals(
          "eu/wohlben/qits/qits-eventstream/1.0.0/qits-eventstream-1.0.0.jar", parsed.path());
    }

    @Test
    void theFileMustStartWithTheArtifactName() {
      assertNull(
          MavenLayout.parse("eu/wohlben/qits/qits-eventstream/1.0.0/something-else-1.0.0.jar"));
      // A pom and a classified artifact of the same coordinate parse just the same.
      assertTrue(
          MavenLayout.parse("g/a/1.0.0/a-1.0.0.pom") != null, "the pom");
      assertTrue(
          MavenLayout.parse("g/a/1.0.0/a-1.0.0-sources.jar") != null, "a classifier");
    }

    @Test
    void tooFewSegmentsOrEmptyOnesDoNotParse() {
      assertNull(MavenLayout.parse("g/a/1.0.0"));
      assertNull(MavenLayout.parse("a/1.0.0/a-1.0.0.jar"), "no group segment");
      assertNull(MavenLayout.parse("g//a/1.0.0/a-1.0.0.jar"), "an empty group segment");
    }

    @Test
    void metadataAndItsChecksumSiblingsAreRecognisedByNameAtAnyDepth() {
      assertTrue(MavenLayout.isMetadata("maven-metadata.xml"));
      assertFalse(MavenLayout.isMetadata("maven-metadata.xml.sha1"));
      assertEquals("sha1", MavenLayout.metadataChecksumAlgorithm("maven-metadata.xml.sha1"));
      assertEquals("sha256", MavenLayout.metadataChecksumAlgorithm("maven-metadata.xml.sha256"));
      assertNull(MavenLayout.metadataChecksumAlgorithm("maven-metadata.xml"));
      assertNull(MavenLayout.metadataChecksumAlgorithm("a-1.0.0.jar"));
    }

    @Test
    void theFourChecksumAlgorithmsAndNoFifth() {
      assertEquals("md5", MavenLayout.checksumAlgorithm("a-1.0.0.jar.md5"));
      assertEquals("sha1", MavenLayout.checksumAlgorithm("a-1.0.0.jar.sha1"));
      assertEquals("sha256", MavenLayout.checksumAlgorithm("a-1.0.0.pom.sha256"));
      assertEquals("sha512", MavenLayout.checksumAlgorithm("a-1.0.0.jar.sha512"));
      assertNull(MavenLayout.checksumAlgorithm("a-1.0.0.jar"));
      assertNull(MavenLayout.checksumAlgorithm("a-1.0.0.jar.asc"), "signatures are not checksums");
    }

    @Test
    void aTimestampedSnapshotNameParsesBackIntoItsParts() {
      MavenLayout.SnapshotFileName timestamped =
          MavenLayout.parseTimestampedSnapshot(
              "qits-eventstream", "1.0.1-SNAPSHOT", "qits-eventstream-1.0.1-20260802.123456-3.jar");
      assertEquals("jar", timestamped.extension());
      assertNull(timestamped.classifier());
      assertEquals("20260802.123456", timestamped.timestamp());
      assertEquals(3, timestamped.buildNumber());
      assertEquals("1.0.1-20260802.123456-3", timestamped.value("1.0.1"));

      MavenLayout.SnapshotFileName classified =
          MavenLayout.parseTimestampedSnapshot(
              "qits-eventstream",
              "1.0.1-SNAPSHOT",
              "qits-eventstream-1.0.1-20260802.124501-4-sources.jar");
      assertEquals("sources", classified.classifier());
      assertEquals("jar", classified.extension());
    }

    @Test
    void theLiteralSnapshotNameIsNotATimestampedOne() {
      assertNull(
          MavenLayout.parseTimestampedSnapshot(
              "qits-eventstream", "1.0.1-SNAPSHOT", "qits-eventstream-1.0.1-SNAPSHOT.jar"));
      assertNull(
          MavenLayout.parseTimestampedSnapshot(
              "qits-eventstream",
              "1.0.1-SNAPSHOT",
              "qits-eventstream-1.0.1-SNAPSHOT-sources.jar"));
      // And a timestamp-shaped name under a RELEASE version is not a snapshot at all.
      assertNull(
          MavenLayout.parseTimestampedSnapshot(
              "qits-eventstream", "1.0.1", "qits-eventstream-1.0.1-20260802.123456-3.jar"));
    }

    @Test
    void mutabilityIsAPropertyOfThePathClass() {
      assertTrue(
          MavenLayout.isMutablePath(
              "g/a/1.0.1-SNAPSHOT/a-1.0.1-SNAPSHOT.jar"), "the literal name is the moving target");
      assertFalse(
          MavenLayout.isMutablePath(
              "g/a/1.0.1-SNAPSHOT/a-1.0.1-20260802.123456-3.jar"), "timestamped is unique");
      assertFalse(MavenLayout.isMutablePath("g/a/1.0.0/a-1.0.0.jar"), "a release is immutable");
      assertFalse(MavenLayout.isMutablePath("g/a/1.0.0/a-1.0.0.pom"), "and so is its pom");
    }
  }

  @Nested
  class Ordering {

    @Test
    void numericTokensCompareNumericallyNotLexically() {
      assertOrdered("1.0.0", "1.0.9", "1.0.10");
      assertOrdered("2026.801.85447", "2026.801.85448", "the platform's calver");
      assertEquals(0, MavenVersionOrder.INSTANCE.compare("1.0", "1.0.0"), "a missing token is zero");
    }

    @Test
    void aSnapshotSortsBelowItsOwnRelease() {
      assertOrdered("1.0.1-SNAPSHOT", "1.0.1");
      assertOrdered("1.0.0", "1.0.1-SNAPSHOT", "a newer snapshot still outranks an older release");
    }

    @Test
    void theQualifierLadderRanksReleasesAbovePrereleases() {
      assertOrdered("1.0.0-alpha", "1.0.0-beta", "1.0.0-rc", "1.0.0");
    }

    @Test
    void whatCannotBeOrderedSortsLastAndTheOrderStaysTotal() {
      // The refusal-honesty rule: a metadata GET must never 500 on a version it cannot order, so
      // the unorderable sorts last — deterministically — and the document still serves.
      assertOrdered("9.9.9", "not.a.version");
      assertOrdered("1.0.0", "1.0.0-qits-custom");
      assertEquals(
          MavenVersionOrder.INSTANCE.compare("x.y", "a.b"),
          -MavenVersionOrder.INSTANCE.compare("a.b", "x.y"),
          "antisymmetric even off the ladder");
    }

    private static void assertOrdered(String... versions) {
      List<String> shuffled = new ArrayList<>(List.of(versions));
      java.util.Collections.shuffle(shuffled, new java.util.Random(42));
      shuffled.sort(MavenVersionOrder.INSTANCE);
      assertEquals(List.of(versions), shuffled);
    }

    private static void assertOrdered(String lesser, String greater, String why) {
      assertTrue(
          MavenVersionOrder.INSTANCE.compare(lesser, greater) < 0,
          lesser + " should sort below " + greater + " — " + why);
    }
  }

  @Nested
  class Checksums {

    @Test
    void allFourAlgorithmsDeriveFromTheSameBytes() {
      byte[] jar = "a tiny jar".getBytes(StandardCharsets.UTF_8);
      assertEquals("MD5", MavenChecksums.jcaName("md5"));
      assertEquals("SHA-1", MavenChecksums.jcaName("sha1"));
      assertEquals("SHA-256", MavenChecksums.jcaName("sha256"));
      assertEquals("SHA-512", MavenChecksums.jcaName("sha512"));
      assertEquals(32, MavenChecksums.hexDigest(jar, "md5").length());
      assertEquals(40, MavenChecksums.hexDigest(jar, "sha1").length());
      assertEquals(64, MavenChecksums.hexDigest(jar, "sha256").length());
      assertEquals(128, MavenChecksums.hexDigest(jar, "sha512").length());
    }
  }
}
