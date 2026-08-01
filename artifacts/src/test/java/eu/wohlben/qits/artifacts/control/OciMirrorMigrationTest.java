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
