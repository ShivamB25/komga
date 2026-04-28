package org.gotson.komga.infrastructure.migration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager

@EnabledIfEnvironmentVariable(named = "KOMGA_POSTGRESQL_TESTS", matches = "true")
class PostgreSqlMigrationPreflightTest {
  @TempDir
  lateinit var tempDir: Path
  private lateinit var fixture: PostgreSqlMigrationFixture

  @BeforeEach
  fun migrateFreshTarget() {
    fixture = PostgreSqlMigrationFixture(tempDir)
    PostgreSqlMigrationFixture.resetAndMigrateTarget()
  }

  @Test
  fun `given Docker PostgreSQL target when migration preflight runs then target schema and redaction are validated`() {
    val report =
      MigrationPreflight().run(
        MigrationRequest(
          sourceMain = JdbcEndpoint(fixture.createSourceMain()),
          sourceTasks = JdbcEndpoint(fixture.createSourceTasks()),
          target =
            JdbcEndpoint(
              url = JDBC_URL,
              username = USERNAME,
              password = PASSWORD,
            ),
          mode = MigrationMode.PREFLIGHT,
        ),
      )

    assertThat(report.status).isEqualTo(MigrationStatus.SUCCESS)
    assertThat(report.failure).isNull()
    assertThat(MigrationSecretRedactor.redact("$JDBC_URL?password=url-secret"))
      .doesNotContain("url-secret")
      .contains("password=<redacted>")
    assertThat(MigrationSecretRedactor.redact("$JDBC_URL?apiKey=url-token"))
      .doesNotContain("url-token")
      .contains("apiKey=<redacted>")
    assertThat(report.target.password).isEqualTo("<redacted>")
    assertThat(report.counts.map { it.table }).contains("BOOK", "TASK")
    assertThat(report.sourceFingerprint?.value).isNotBlank
  }

  @Test
  fun `given empty PostgreSQL target when migration preflight runs then target main and tasks schemas are initialized`() {
    PostgreSqlMigrationFixture.resetTarget()

    val report =
      MigrationPreflight().run(
        MigrationRequest(
          sourceMain = JdbcEndpoint(fixture.createSourceMain()),
          sourceTasks = JdbcEndpoint(fixture.createSourceTasks()),
          target = JdbcEndpoint(JDBC_URL, USERNAME, PASSWORD),
          mode = MigrationMode.PREFLIGHT,
        ),
      )

    assertThat(report.status).describedAs(report.failure ?: "preflight should initialize target schema").isEqualTo(MigrationStatus.SUCCESS)
    assertThat(report.failure).isNull()
    DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD).use { targetConnection ->
      assertThat(MigrationJdbcInspector.currentFlywayVersion(targetConnection, "flyway_schema_history_main"))
        .isEqualTo(MigrationSchemaPolicy.TARGET_MAIN_VERSION)
      assertThat(MigrationJdbcInspector.currentFlywayVersion(targetConnection, "flyway_schema_history_tasks"))
        .isEqualTo(MigrationSchemaPolicy.TARGET_TASKS_VERSION)
      assertThat(MigrationJdbcInspector.tableExists(targetConnection, "BOOK")).isTrue
      assertThat(MigrationJdbcInspector.tableExists(targetConnection, "TASK")).isTrue
      assertThat(MigrationJdbcInspector.rowCount(targetConnection, "BOOK")).isZero()
      assertThat(MigrationJdbcInspector.rowCount(targetConnection, "TASK")).isZero()
    }
  }

  @Test
  fun `given empty PostgreSQL target when migration runs then target schema is initialized before copying data`() {
    PostgreSqlMigrationFixture.resetTarget()

    val report =
      MigrationPreflight().run(
        MigrationRequest(
          sourceMain = JdbcEndpoint(fixture.createMigratedSourceMain()),
          sourceTasks = JdbcEndpoint(fixture.createMigratedSourceTasks()),
          target = JdbcEndpoint(JDBC_URL, USERNAME, PASSWORD),
          mode = MigrationMode.MIGRATE,
        ),
      )

    assertThat(report.status).describedAs(report.failure ?: "migration should initialize target schema").isEqualTo(MigrationStatus.SUCCESS)
    assertThat(report.failure).isNull()
    DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD).use { targetConnection ->
      assertThat(MigrationJdbcInspector.currentFlywayVersion(targetConnection, "flyway_schema_history_main"))
        .isEqualTo(MigrationSchemaPolicy.TARGET_MAIN_VERSION)
      assertThat(MigrationJdbcInspector.currentFlywayVersion(targetConnection, "flyway_schema_history_tasks"))
        .isEqualTo(MigrationSchemaPolicy.TARGET_TASKS_VERSION)
      assertThat(MigrationJdbcInspector.tableExists(targetConnection, "TASK")).isTrue
      assertThat(MigrationJdbcInspector.rowCount(targetConnection, "BOOK")).isGreaterThan(0)
    }
  }

  @Test
  fun `given held PostgreSQL migration lock when preflight runs then it aborts before bookkeeping writes`() {
    DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD).use { lockConnection ->
      lockConnection.createStatement().executeQuery("select pg_advisory_lock(hashtext('komga_migration'))").use { it.next() }

      val report =
        MigrationPreflight().run(
          MigrationRequest(
            sourceMain = JdbcEndpoint(fixture.createSourceMain()),
            sourceTasks = JdbcEndpoint(fixture.createSourceTasks()),
            target = JdbcEndpoint(JDBC_URL, USERNAME, PASSWORD),
            mode = MigrationMode.PREFLIGHT,
          ),
        )

      assertThat(report.status).isEqualTo(MigrationStatus.FAILED)
      assertThat(report.phase).isEqualTo(MigrationPhase.LOCKING)
      assertThat(report.failure).contains("already locked")
      DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD).use { targetConnection ->
        assertThat(MigrationJdbcInspector.tableExists(targetConnection, MigrationBookkeeping.TABLE)).isFalse
      }
    }
  }

  @Test
  fun `given PostgreSQL target missing required current function when preflight runs then it fails before inventory`() {
    DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD).use { targetConnection ->
      targetConnection.createStatement().execute("""drop function "UDF_STRIP_ACCENTS"(text)""")
    }

    val report =
      MigrationPreflight().run(
        MigrationRequest(
          sourceMain = JdbcEndpoint(fixture.createSourceMain()),
          sourceTasks = JdbcEndpoint(fixture.createSourceTasks()),
          target = JdbcEndpoint(JDBC_URL, USERNAME, PASSWORD),
          mode = MigrationMode.PREFLIGHT,
        ),
      )

    assertThat(report.status).isEqualTo(MigrationStatus.FAILED)
    assertThat(report.phase).isEqualTo(MigrationPhase.PREFLIGHT)
    assertThat(report.failure)
      .contains("missing required current database objects")
      .contains("UDF_STRIP_ACCENTS")
    assertThat(report.inventory).isEmpty()
    assertThat(report.counts).isEmpty()
    assertThat(report.digests).isEmpty()
    assertThat(report.sourceFingerprint).isNull()
  }

  @Test
  fun `given PostgreSQL target with wrong current function behavior when preflight runs then it fails before inventory`() {
    DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD).use { targetConnection ->
      targetConnection.createStatement().execute(
        """
        create or replace function "UDF_STRIP_ACCENTS"(value text)
        returns text
        language sql
        immutable
        parallel safe
        as ${'$'}${'$'} select value ${'$'}${'$'}
        """.trimIndent(),
      )
    }

    val report =
      MigrationPreflight().run(
        MigrationRequest(
          sourceMain = JdbcEndpoint(fixture.createSourceMain()),
          sourceTasks = JdbcEndpoint(fixture.createSourceTasks()),
          target = JdbcEndpoint(JDBC_URL, USERNAME, PASSWORD),
          mode = MigrationMode.PREFLIGHT,
        ),
      )

    assertThat(report.status).isEqualTo(MigrationStatus.FAILED)
    assertThat(report.phase).isEqualTo(MigrationPhase.PREFLIGHT)
    assertThat(report.failure)
      .contains("malformed required current database objects")
      .contains("UDF_STRIP_ACCENTS('École') returned École instead of Ecole")
    assertThat(report.inventory).isEmpty()
    assertThat(report.counts).isEmpty()
    assertThat(report.digests).isEmpty()
    assertThat(report.sourceFingerprint).isNull()
  }

  @Test
  fun `given PostgreSQL target with wrong current collation settings when preflight runs then it fails before inventory`() {
    DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD).use { targetConnection ->
      targetConnection.createStatement().execute("drop collation \"COLLATION_UNICODE_3\"")
      targetConnection.createStatement().execute(
        """create collation "COLLATION_UNICODE_3" (provider = icu, locale = 'und-u-ks-level3-kk-true', deterministic = true)""",
      )
    }

    val report =
      MigrationPreflight().run(
        MigrationRequest(
          sourceMain = JdbcEndpoint(fixture.createSourceMain()),
          sourceTasks = JdbcEndpoint(fixture.createSourceTasks()),
          target = JdbcEndpoint(JDBC_URL, USERNAME, PASSWORD),
          mode = MigrationMode.PREFLIGHT,
        ),
      )

    assertThat(report.status).isEqualTo(MigrationStatus.FAILED)
    assertThat(report.phase).isEqualTo(MigrationPhase.PREFLIGHT)
    assertThat(report.failure)
      .contains("malformed required current database objects")
      .contains("COLLATION_UNICODE_3 deterministic is true instead of false")
    assertThat(report.inventory).isEmpty()
    assertThat(report.counts).isEmpty()
    assertThat(report.digests).isEmpty()
    assertThat(report.sourceFingerprint).isNull()
  }

  @Test
  fun `given PostgreSQL target missing required current index when preflight runs then it fails before inventory`() {
    DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD).use { targetConnection ->
      targetConnection.createStatement().execute("drop index \"idx__book__series_id\"")
    }

    val report =
      MigrationPreflight().run(
        MigrationRequest(
          sourceMain = JdbcEndpoint(fixture.createSourceMain()),
          sourceTasks = JdbcEndpoint(fixture.createSourceTasks()),
          target = JdbcEndpoint(JDBC_URL, USERNAME, PASSWORD),
          mode = MigrationMode.PREFLIGHT,
        ),
      )

    assertThat(report.status).isEqualTo(MigrationStatus.FAILED)
    assertThat(report.phase).isEqualTo(MigrationPhase.PREFLIGHT)
    assertThat(report.failure)
      .contains("schema is incomplete")
      .contains("BOOK missing required indexes")
      .contains("idx__book__series_id")
    assertThat(report.inventory).isEmpty()
    assertThat(report.counts).isEmpty()
    assertThat(report.digests).isEmpty()
    assertThat(report.sourceFingerprint).isNull()
  }

  @Test
  fun `given PostgreSQL target with malformed current column when preflight runs then it fails before inventory`() {
    DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD).use { targetConnection ->
      targetConnection.createStatement().execute("""alter table "BOOK" alter column "FILE_SIZE" type varchar using "FILE_SIZE"::varchar""")
    }

    val report =
      MigrationPreflight().run(
        MigrationRequest(
          sourceMain = JdbcEndpoint(fixture.createSourceMain()),
          sourceTasks = JdbcEndpoint(fixture.createSourceTasks()),
          target = JdbcEndpoint(JDBC_URL, USERNAME, PASSWORD),
          mode = MigrationMode.PREFLIGHT,
        ),
      )

    assertThat(report.status).isEqualTo(MigrationStatus.FAILED)
    assertThat(report.phase).isEqualTo(MigrationPhase.PREFLIGHT)
    assertThat(report.failure)
      .contains("schema is incomplete")
      .contains("BOOK.FILE_SIZE malformed column definition")
    assertThat(report.inventory).isEmpty()
    assertThat(report.counts).isEmpty()
    assertThat(report.digests).isEmpty()
    assertThat(report.sourceFingerprint).isNull()
  }

  @Test
  fun `given PostgreSQL target with changed column default when preflight runs then it fails before inventory`() {
    DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD).use { targetConnection ->
      targetConnection.createStatement().execute("""alter table "LIBRARY" alter column "HASH_FILES" set default false""")
    }

    val report =
      MigrationPreflight().run(
        MigrationRequest(
          sourceMain = JdbcEndpoint(fixture.createSourceMain()),
          sourceTasks = JdbcEndpoint(fixture.createSourceTasks()),
          target = JdbcEndpoint(JDBC_URL, USERNAME, PASSWORD),
          mode = MigrationMode.PREFLIGHT,
        ),
      )

    assertThat(report.status).isEqualTo(MigrationStatus.FAILED)
    assertThat(report.phase).isEqualTo(MigrationPhase.PREFLIGHT)
    assertThat(report.failure)
      .contains("schema is incomplete")
      .contains("LIBRARY.HASH_FILES malformed column definition")
      .contains("default false != true")
    assertThat(report.inventory).isEmpty()
    assertThat(report.counts).isEmpty()
    assertThat(report.digests).isEmpty()
    assertThat(report.sourceFingerprint).isNull()
  }

  @Test
  fun `given PostgreSQL target with case-only string default drift when preflight runs then it fails before inventory`() {
    DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD).use { targetConnection ->
      targetConnection.createStatement().execute("""alter table "LIBRARY" alter column "SERIES_COVER" set default 'first'""")
      targetConnection.createStatement().execute("""alter table "LIBRARY" alter column "SCAN_INTERVAL" set default 'every_6h'""")
    }

    val report =
      MigrationPreflight().run(
        MigrationRequest(
          sourceMain = JdbcEndpoint(fixture.createSourceMain()),
          sourceTasks = JdbcEndpoint(fixture.createSourceTasks()),
          target = JdbcEndpoint(JDBC_URL, USERNAME, PASSWORD),
          mode = MigrationMode.PREFLIGHT,
        ),
      )

    assertThat(report.status).isEqualTo(MigrationStatus.FAILED)
    assertThat(report.phase).isEqualTo(MigrationPhase.PREFLIGHT)
    assertThat(report.failure)
      .contains("schema is incomplete")
      .contains("LIBRARY.SERIES_COVER malformed column definition")
      .contains("default first != FIRST")
      .contains("LIBRARY.SCAN_INTERVAL malformed column definition")
      .contains("default every_6h != EVERY_6H")
    assertThat(report.inventory).isEmpty()
    assertThat(report.counts).isEmpty()
    assertThat(report.digests).isEmpty()
    assertThat(report.sourceFingerprint).isNull()
  }

  private companion object {
    const val JDBC_URL = PostgreSqlMigrationFixture.JDBC_URL
    const val USERNAME = PostgreSqlMigrationFixture.USERNAME
    const val PASSWORD = PostgreSqlMigrationFixture.PASSWORD
  }
}
