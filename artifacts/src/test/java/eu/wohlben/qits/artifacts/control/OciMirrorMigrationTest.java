package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.entity.RepositoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The lineage itself, run from empty — the one thing a {@code @QuarkusTest} cannot show.
 *
 * <p>Every suite in this module starts by wiping the tables, so what the migrations <em>put</em>
 * there is invisible to all of them: the prefilled mirror rows would be gone before the first
 * assertion, and a prefill that quietly stopped running would look exactly like a passing build.
 * This test owns a private database, runs Flyway over the real migration directory, and reads what
 * is there.
 *
 * <p>It also pins the rule the three in-flight plans share (proxy-pulling-normal-images.md §4): the
 * type check constraint is maintained by full re-enumeration, and the {@link RepositoryType} enum is
 * the source of truth it copies. Looping over {@code values()} is what makes that a property rather
 * than a list somebody has to remember to extend — a constant added without a migration fails here
 * on the constant's own name.
 */
class OciMirrorMigrationTest {

  private Path directory;
  private JdbcDataSource dataSource;

  /**
   * A <b>file</b> database under {@code target/}, not an in-memory one, and the reason is the suite
   * around it: this class shares a JVM with the {@code @QuarkusTest} classes, whose applications stop
   * between methods and take every in-memory H2 with them — a held connection then dies mid-test
   * with "the database has been closed". On disk the schema outlives that, and every statement below
   * opens its own connection.
   */
  @BeforeEach
  void migrate() throws Exception {
    directory = Path.of("target", "migration-" + UUID.randomUUID());
    dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:file:" + directory.toAbsolutePath().resolve("db"));
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/artifacts/migration")
        .load()
        .migrate();
  }

  @AfterEach
  void drop() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("shutdown");
    } catch (SQLException alreadyGone) {
      // Nothing to shut down is a fine state to tidy up from.
    }
    if (Files.exists(directory)) {
      try (var walk = Files.walk(directory)) {
        walk.sorted(Comparator.reverseOrder()).forEach(OciMirrorMigrationTest::deleteQuietly);
      }
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (Exception ignored) {
      // best effort
    }
  }

  @Test
  void theTypeCheckEnumeratesEveryConstantTheEnumHas() throws SQLException {
    // The widening rule, as a property. Each migration that touches this constraint re-declares it
    // naming EVERY value, so whichever plan lands second necessarily includes the first one's — and
    // the enum in the tree at land time is what it copies.
    for (RepositoryType type : RepositoryType.values()) {
      insertRepository("probe-" + type.wireName(), type.name());
    }
    assertEquals(
        RepositoryType.values().length,
        count("select count(*) from artifact_repository where name like 'probe-%'"));
  }

  @Test
  void aTypeTheEnumDoesNotHaveIsRefusedByTheConstraint() {
    // The other half: the constraint is a constraint, not documentation. Without it a typo in a
    // deployment's provisioning script would mint a repository of a type no code can read.
    SQLException refused =
        assertThrows(SQLException.class, () -> insertRepository("maven-someday", "MAVEN_ARTIFACTS"));
    assertTrue(
        refused.getMessage().toUpperCase(java.util.Locale.ROOT).contains("CK_ARTIFACT_REPOSITORY_TYPE"),
        refused.getMessage());
  }

  @Test
  void theThreeUpstreamsArePrefilledAndPairedWithAMirrorRepositoryEach() throws SQLException {
    // Static public domains, so they belong in the lineage rather than in a deployment's data: a
    // fresh platform mirrors quay, Red Hat and Hub with no manual step, which is what makes a
    // rewritten `FROM localhost:8081/quay/…` work on first boot.
    Map<String, String> slugsByDomain = new LinkedHashMap<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery("select domain, slug from oci_mirror_upstream order by slug")) {
      while (rows.next()) {
        slugsByDomain.put(rows.getString(1), rows.getString(2));
      }
    }
    assertEquals(
        Map.of(
            "docker.io", "hub",
            "quay.io", "quay",
            "registry.access.redhat.com", "redhat"),
        slugsByDomain);

    assertEquals(
        3,
        count(
            "select count(*) from artifact_repository where type = 'OCI_MIRROR'"
                + " and name in ('hub','quay','redhat')"),
        "every upstream is paired with the repository row its namespace resolves to");
  }

  @Test
  void theTagFreshnessTableIsThereForTheMissPathToWrite() throws SQLException {
    // Nothing writes it yet (workstream BX does). It ships with the rest so the lineage is not
    // re-opened for one table, the same way the npm tombstone shipped ahead of its only writer.
    assertEquals(0, count("select count(*) from oci_mirror_tag_check"));
  }

  @Test
  void theMavenTableIsThereKeyedByRepositoryAndPath() throws SQLException {
    // V8's one table, exercised the way the lineage pins everything else: insert a repository and a
    // deployed file under it, and prove the foreign key does its half of the job.
    insertRepository("maven", "MAVEN_PACKAGES");
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "insert into maven_artifact (repository, path, blob_id, size_bytes, created_at) values"
              + " ('maven', 'eu/wohlben/qits/qits-eventstream/1.0.0/qits-eventstream-1.0.0.jar',"
              + " '" + "0".repeat(64) + "', 47940, current_timestamp)");
    }
    assertEquals(1, count("select count(*) from maven_artifact where repository = 'maven'"));
    SQLException refused =
        assertThrows(
            SQLException.class,
            () -> {
              try (Connection connection = dataSource.getConnection();
                  Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                    "insert into maven_artifact (repository, path, blob_id, size_bytes, created_at)"
                        + " values ('no-such-repo', 'x/y.jar', '" + "1".repeat(64)
                        + "', 1, current_timestamp)");
              }
            });
    assertTrue(
        refused.getMessage().toUpperCase(java.util.Locale.ROOT).contains("FK_MAVEN_ARTIFACT_REPOSITORY"),
        refused.getMessage());
  }

  @Test
  void theDaemonTableIsThereKeyedByRepositoryNameAndVersion() throws SQLException {
    // V10's one table. The plan that asked for it said "V6" — written when the lineage ended at V5,
    // with four migrations landing behind it. No plan reserves a number; this took the next free V
    // and re-enumerated the constraint from the enum as it stands, which is what the first test in
    // this class turns into a property.
    insertRepository("daemons", "DAEMON_BINARIES");
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "insert into daemon_binary (repository, name, version, blob_id, size_bytes, published_at)"
              + " values ('daemons', 'qits-ci-daemon', '2026.801.120000',"
              + " '" + "c".repeat(64) + "', 43123792, current_timestamp)");
    }
    assertEquals(1, count("select count(*) from daemon_binary where repository = 'daemons'"));

    // The uniqueness that makes a republish answerable at all: without it there is no "409, this
    // version exists", which is the whole reason this type beat an artifact_record row.
    SQLException duplicate =
        assertThrows(
            SQLException.class,
            () -> {
              try (Connection connection = dataSource.getConnection();
                  Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                    "insert into daemon_binary (repository, name, version, blob_id, size_bytes,"
                        + " published_at) values ('daemons', 'qits-ci-daemon', '2026.801.120000',"
                        + " '" + "d".repeat(64) + "', 1, current_timestamp)");
              }
            });
    assertTrue(
        duplicate.getMessage().toUpperCase(java.util.Locale.ROOT).contains("PRIMARY KEY"),
        duplicate.getMessage());

    SQLException refused =
        assertThrows(
            SQLException.class,
            () -> {
              try (Connection connection = dataSource.getConnection();
                  Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                    "insert into daemon_binary (repository, name, version, blob_id, size_bytes,"
                        + " published_at) values ('no-such-repo', 'x', '1', '" + "1".repeat(64)
                        + "', 1, current_timestamp)");
              }
            });
    assertTrue(
        refused.getMessage().toUpperCase(java.util.Locale.ROOT).contains("FK_DAEMON_BINARY_REPOSITORY"),
        refused.getMessage());
  }

  @Test
  void theThreeProtocolTablesCarryANullableAccessedAtWithNoBackfill() throws SQLException {
    // V11's whole shape, from empty. The insert names no accessed_at, so a NOT NULL column or a
    // default would fail here — and null is the state the sweep has to be able to read as "never
    // accessed", which a backfill of created_at/published_at would have destroyed silently.
    insertRepository("npm", "NPM_PACKAGES");
    insertRepository("maven", "MAVEN_PACKAGES");
    insertRepository("daemons", "DAEMON_BINARIES");
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "insert into npm_version (repository, package_name, version, tarball_blob_id,"
              + " manifest_json, created_at) values ('npm', '@qits/ui', '1.0.0', '"
              + "a".repeat(64) + "', '{}', current_timestamp)");
      statement.executeUpdate(
          "insert into maven_artifact (repository, path, blob_id, size_bytes, created_at) values"
              + " ('maven', 'eu/wohlben/qits/lib/1.0.0/lib-1.0.0.jar', '" + "b".repeat(64)
              + "', 1, current_timestamp)");
      statement.executeUpdate(
          "insert into daemon_binary (repository, name, version, blob_id, size_bytes, published_at)"
              + " values ('daemons', 'qits-ci-daemon', '2026.801.120000', '" + "c".repeat(64)
              + "', 1, current_timestamp)");
    }
    assertEquals(1, count("select count(*) from npm_version where accessed_at is null"));
    assertEquals(1, count("select count(*) from maven_artifact where accessed_at is null"));
    assertEquals(1, count("select count(*) from daemon_binary where accessed_at is null"));
  }

  @Test
  void theLineageEmbedsNoLivePlatformDigest() throws SQLException {
    // §5 step 2's rule, as an assertion: adopting the three ELF blobs already on the volume is an
    // OPS action, never a migration. A migration cannot verify a digest against the running store,
    // and a lineage carrying one would replay it onto every fresh platform that has no such bytes.
    assertEquals(0, count("select count(*) from daemon_binary"));
  }

  private void insertRepository(String name, String type) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "insert into artifact_repository (name, type, created_at) values ('"
              + name
              + "', '"
              + type
              + "', current_timestamp)");
    }
  }

  private long count(String query) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(query)) {
      rows.next();
      return rows.getLong(1);
    }
  }
}
