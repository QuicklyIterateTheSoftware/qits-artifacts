package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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
 * <p>Every suite in this module starts by wiping the tables, so what the chain <em>leaves</em> there
 * is invisible to all of them: a row would be gone before the first assertion either way, so a
 * prefill that came back — or one that never went — would look exactly like a passing build. This
 * test owns a private database, runs Flyway over the real migration directory, and reads what is
 * there.
 *
 * <p>It also pins the rule the constraint is maintained by: full re-enumeration. Every migration
 * that touches {@code ck_artifact_repository_type} re-declares it naming every key, so whichever
 * migration lands second necessarily includes the first one's.
 */
class MigrationLineageTest {

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
        walk.sorted(Comparator.reverseOrder()).forEach(MigrationLineageTest::deleteQuietly);
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

  /**
   * The ten keys the lineage's last re-enumeration (V13) declares, spelled out.
   *
   * <p>It used to be {@code RepositoryType.values()}, which made "the constraint lists every type"
   * a property rather than a list. There is no enum any more — types are registered as profile
   * beans — and a list read from the CLASSPATH would prove the wrong thing: the constraint is a
   * fact about the DATABASE, and this service registers seven of these while a mirror on its own
   * schema registers three. The three cache keys stay listed because the chain is byte-untouched by
   * the split and still accepts them; rows of those types are a cutover data question, not a schema
   * one.
   */
  private static final List<String> DECLARED_TYPES =
      List.of(
          "CI_SCREENSHOTS",
          "CI_VIDEOS",
          "OCI_IMAGES",
          "NPM_PACKAGES",
          "NPM_PROXY",
          "OCI_MIRROR",
          "MAVEN_PACKAGES",
          "MAVEN_PROXY",
          "DAEMON_BINARIES",
          "DOCS");

  @Test
  void theTypeCheckAcceptsEveryKeyTheLineageDeclares() throws SQLException {
    for (String type : DECLARED_TYPES) {
      insertRepository("probe-" + type.toLowerCase(java.util.Locale.ROOT), type);
    }
    assertEquals(
        DECLARED_TYPES.size(),
        count("select count(*) from artifact_repository where name like 'probe-%'"));
  }

  @Test
  void aTypeTheLineageDoesNotDeclareIsRefusedByTheConstraint() {
    // The other half: the constraint is a constraint, not documentation. Without it a typo in a
    // deployment's provisioning script would mint a repository of a type no code can read.
    SQLException refused =
        assertThrows(SQLException.class, () -> insertRepository("maven-someday", "MAVEN_ARTIFACTS"));
    assertTrue(
        refused.getMessage().toUpperCase(java.util.Locale.ROOT).contains("CK_ARTIFACT_REPOSITORY_TYPE"),
        refused.getMessage());
  }

  @Test
  void v7sMirrorPrefillIsRetiredByTheEndOfTheChain() throws SQLException {
    // V7 prefilled three `oci-mirror` repository rows and their upstreams; V14 takes them out again.
    // This is the assertion that has to be read from a migrated database rather than from a suite:
    // every @QuarkusTest here wipes the tables, so a prefill that came back would be invisible to
    // all of them.
    //
    // The rows mattered because the wire code outlived the profile. `resolveForPull` matches the
    // repository's type by string and reads the upstream table, so while a row stood a pull aimed
    // here still resolved into somebody else's registry — and a first segment naming no repository
    // still remapped into `hub`. No row, no path.
    Map<String, String> slugsByDomain = new LinkedHashMap<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery("select domain, slug from oci_mirror_upstream order by slug")) {
      while (rows.next()) {
        slugsByDomain.put(rows.getString(1), rows.getString(2));
      }
    }
    assertEquals(Map.of(), slugsByDomain, "the upstream rows went with the code that read them");

    assertEquals(
        0,
        count("select count(*) from artifact_repository where type in"
            + " ('OCI_MIRROR','NPM_PROXY','MAVEN_PROXY')"),
        "no cache type is registered here, so no row of one may stand");
  }

  @Test
  void theTwoMirrorTablesSurviveTheirRowsBecauseLiveCodeStillReadsThem() throws SQLException {
    // V14 empties both and drops neither, which is the half of it that looks like an oversight.
    // `OciMirrorUpstreamRepository` and `OciMirrorTagCheckRepository` ride in on the
    // qits-registries-oci jar and are live beans here — excluding a profile does not unregister a
    // DAO. `resolveForPull` reads the upstream table on every pull whose first segment names no
    // repository, and `collectTag` deletes a tag's freshness row for HOSTED tags too. A dropped
    // table turns both into a missing-table error at runtime, which no build here would show.
    assertEquals(0, count("select count(*) from oci_mirror_upstream"));
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
  void theMavenCacheGetsOneTableAndReusesTheArtifactTableForItsFiles() throws SQLException {
    // V13's shape, and the half of it that is a decision rather than a table: a cached file is an
    // ordinary maven_artifact row under a maven-proxy repository, so the same insert works under
    // both types and every reader tells them apart by the repository's type. Only the one document
    // that mutates needed a table.
    insertRepository("central", "MAVEN_PROXY");
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "insert into maven_artifact (repository, path, blob_id, size_bytes, created_at) values"
              + " ('central', 'org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar',"
              + " '" + "e".repeat(64) + "', 68000, current_timestamp)");
      statement.executeUpdate(
          "insert into maven_proxy_metadata (repository, path, doc, fetched_at) values"
              + " ('central', 'org/slf4j/slf4j-api/maven-metadata.xml', '<metadata/>',"
              + " current_timestamp)");
    }
    assertEquals(1, count("select count(*) from maven_artifact where repository = 'central'"));
    assertEquals(1, count("select count(*) from maven_proxy_metadata"));

    SQLException refused =
        assertThrows(
            SQLException.class,
            () -> {
              try (Connection connection = dataSource.getConnection();
                  Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                    "insert into maven_proxy_metadata (repository, path, doc, fetched_at) values"
                        + " ('no-such-repo', 'x/maven-metadata.xml', '<metadata/>',"
                        + " current_timestamp)");
              }
            });
    assertTrue(
        refused
            .getMessage()
            .toUpperCase(java.util.Locale.ROOT)
            .contains("FK_MAVEN_PROXY_METADATA_REPOSITORY"),
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
