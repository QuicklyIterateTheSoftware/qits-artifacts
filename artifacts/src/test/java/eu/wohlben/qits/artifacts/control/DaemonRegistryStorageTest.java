package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.dto.StoreSummary;
import eu.wohlben.qits.artifacts.entity.RepositoryType;
import eu.wohlben.qits.artifacts.error.DaemonException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The daemon-binaries rows, and the one number they were created to fix.
 *
 * <p>The type exists because of a measurement: every row-less byte in the platform's store was a
 * ci-daemon build, uploaded through the OCI blob-upload session, which promotes bytes and writes no
 * row by construction. So the census reported a live executable — the one every build downloads —
 * as an orphan. {@link #aPublishedDaemonIsLiveUnderItsOwnTypeRatherThanOrphaned} is that fact
 * inverted into an assertion, and it is the reason this suite exists at all.
 *
 * <p>The wire behaviour lives in {@code service}'s {@code DaemonRegistryTest}; the rules are here,
 * the same split every other type here uses.
 */
@QuarkusTest
class DaemonRegistryStorageTest extends GcFixture {

  private static final String DAEMONS = ArtifactsRepositorySeeder.DAEMONS;
  private static final String CI_DAEMON = "qits-ci-daemon";

  @Inject DaemonRegistryService daemons;
  @Inject ArtifactExplorerService explorer;

  @Test
  void theWireNameIsKebabAndRoundTrips() {
    // The enum name is not the API contract, and this type's wire name is what a deployment writes
    // into a repository body and what every GC report prints.
    assertEquals("daemon-binaries", RepositoryType.DAEMON_BINARIES.wireName());
    assertEquals(RepositoryType.DAEMON_BINARIES, RepositoryType.fromWire("daemon-binaries"));
    assertEquals(RepositoryType.DAEMON_BINARIES, RepositoryType.fromWire("DAEMON_BINARIES"));
  }

  @Test
  void theProfileIsEmptyAndTheCapIsZeroLikeEveryOtherProtocolType() {
    // Not an oversight: these bytes never flow through BlobService, so there is no media type to
    // sniff — an ELF binary sniffs to nothing and would 400 — and no metadata to require. The empty
    // media-type set is what makes the zero cap safe rather than merely unused: accepts() refuses a
    // stray JSON-API upload before anything reads maxBytes().
    assertEquals(Set.of(), RepositoryType.DAEMON_BINARIES.allowedMediaTypes());
    assertEquals(Set.of(), RepositoryType.DAEMON_BINARIES.requiredMetadataKeys());
    assertEquals(0L, RepositoryType.DAEMON_BINARIES.maxBytes());
    assertTrue(!RepositoryType.DAEMON_BINARIES.accepts("application/octet-stream"));
  }

  @Test
  void aPublishWritesTheIdentityAndTheDigestItComputed() throws IOException {
    String blobId = seedDaemonRepository(64, (byte) 7);

    DaemonRegistryService.StoredBinary published =
        daemons.publish(DAEMONS, CI_DAEMON, "2026.801.120000", blobId, 64);

    assertEquals(CI_DAEMON, published.name());
    assertEquals("2026.801.120000", published.version());
    assertEquals(blobId, published.blobId());
    assertEquals(64, published.sizeBytes());
    // Read back through a second unit of work: the row written IS the publish, so there is no
    // second step that could be skipped. Compared field by field rather than as a whole record
    // because the column is timestamp(6) — a nanosecond stamp comes back ROUNDED to microseconds,
    // which is a property of the schema and not something a test should pretend away.
    DaemonRegistryService.StoredBinary stored =
        daemons.find(DAEMONS, CI_DAEMON, "2026.801.120000").orElseThrow();
    assertEquals(published.name(), stored.name());
    assertEquals(published.version(), stored.version());
    assertEquals(published.blobId(), stored.blobId());
    assertEquals(published.sizeBytes(), stored.sizeBytes());
    assertTrue(
        Duration.between(published.publishedAt(), stored.publishedAt()).abs().toMillis() < 1000,
        "published_at is server-stamped, and the column keeps it to the microsecond");
  }

  @Test
  void republishingAVersionIsRefusedEvenWithTheSameBytes() throws IOException {
    String blobId = seedDaemonRepository(64, (byte) 9);
    daemons.publish(DAEMONS, CI_DAEMON, "2026.801.130000", blobId, 64);

    // Identical bytes are refused too, and that differs from maven's idempotent re-deploy on
    // purpose: a maven deploy sends one file per request and retries are routine, while a daemon
    // publish is one request from one release pipeline. A second one means the version was reused
    // or the release ran twice, and both are worth saying loudly.
    DaemonException refused =
        assertThrows(
            DaemonException.class,
            () -> daemons.publish(DAEMONS, CI_DAEMON, "2026.801.130000", blobId, 64));
    assertEquals(409, refused.statusCode());
    assertTrue(refused.getMessage().contains("immutable"), refused.getMessage());

    // A different daemon's identically-named version is a different identity, not a conflict.
    daemons.publish(DAEMONS, "qits-workspace-daemon", "2026.801.130000", blobId, 64);
  }

  @Test
  void aRepositoryOfAnotherTypeIsNotADaemonRepository() throws IOException {
    repositoryService.ensure("npm", RepositoryType.NPM_PACKAGES);

    DaemonException refused =
        assertThrows(DaemonException.class, () -> daemons.requireDaemonRepository("npm"));
    assertEquals(404, refused.statusCode());
    assertTrue(refused.getMessage().contains("daemon-binaries"), refused.getMessage());
  }

  @Test
  void aPublishedDaemonIsLiveUnderItsOwnTypeRatherThanOrphaned() throws IOException {
    // THE assertion this workstream exists for. Publish the same shape of bytes the bootstrap used
    // to POST into the blob-upload session, and prove the census now reaches them through a row.
    String blobId = seedDaemonRepository(43, (byte) 11);
    daemons.publish(DAEMONS, CI_DAEMON, "2026.801.140000", blobId, 43);

    LiveBlobCensus.Census taken = census.take();

    assertEquals(Set.of(blobId), taken.live(RepositoryType.DAEMON_BINARIES).keySet());
    assertEquals(43L, taken.liveBytes(RepositoryType.DAEMON_BINARIES));
    assertEquals(
        Set.of(),
        taken.rowless(),
        "the bytes that used to BE the orphan pool are reached by an identity now");
    assertEquals(0L, taken.rowlessBytes());
  }

  @Test
  void theStoreSummaryReportsDaemonBytesAsTheirOwnFigure() throws IOException {
    String blobId = seedDaemonRepository(43, (byte) 13);
    daemons.publish(DAEMONS, CI_DAEMON, "2026.801.150000", blobId, 43);

    LiveBlobCensus.Census taken = census.take();
    StoreSummary summary = explorer.storeSummary();

    assertEquals(taken.liveBytes(RepositoryType.DAEMON_BINARIES), summary.daemonBinaryBytes());
    assertEquals(43L, summary.daemonBinaryBytes());
    // The identity the whole panel rests on, with the new figure carrying its share: no byte is
    // both live and an orphan, and the disk total is the sum of the two.
    assertEquals(0L, summary.orphanBytes());
    assertEquals(summary.daemonBinaryBytes(), summary.diskTotalBytes());
  }

  @Test
  void twoVersionsSharingBytesAreCountedOnce() throws IOException {
    // Content addressing means a rebuild that produced identical bytes is one blob under two
    // versions. The census unions rather than sums, or the panel would over-count exactly the case
    // a content-addressed store exists to make free.
    String blobId = seedDaemonRepository(43, (byte) 17);
    daemons.publish(DAEMONS, CI_DAEMON, "2026.801.160000", blobId, 43);
    daemons.publish(DAEMONS, CI_DAEMON, "2026.801.170000", blobId, 43);

    assertEquals(43L, census.take().liveBytes(RepositoryType.DAEMON_BINARIES));
    assertEquals(2, daemons.listVersions(DAEMONS, CI_DAEMON).size());
  }

  /** The seeded {@code daemons} row plus one staged, promoted blob — the publish route's halves. */
  private String seedDaemonRepository(int size, byte fill) throws IOException {
    repositoryService.ensure(DAEMONS, RepositoryType.DAEMON_BINARIES);
    return store(filled(size, fill));
  }
}
