package org.gotson.komga.infrastructure.migration

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.gotson.komga.infrastructure.datasource.DatabaseBackend
import org.gotson.komga.infrastructure.datasource.DatabaseScope
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.readText

class MigrationPreflightTest {
  @TempDir
  lateinit var tempDir: Path

  @Test
  fun `given default expressions when normalizing then string literals preserve case while wrappers normalize`() {
    assertThat(normalizeDefault("'FIRST'")).isEqualTo("FIRST")
    assertThat(normalizeDefault("('EVERY_6H'::character varying)")).isEqualTo("EVERY_6H")
    assertThat(normalizeDefault("'first'")).isEqualTo("first")
    assertThat(normalizeDefault("TRUE")).isEqualTo("true")
    assertThat(normalizeDefault("(false::boolean)")).isEqualTo("false")
    assertThat(normalizeDefault("((42))::integer")).isEqualTo("42")
    assertThat(normalizeDefault("(CURRENT_TIMESTAMP)")).isEqualTo("current_timestamp")
    assertThat(normalizeDefault("NULL::character varying")).isNull()
  }

  @Test
  fun `given current offline sources when preflight runs then report is complete redacted and non destructive`() {
    val sourceMain = createSourceMain(rows = listOf("book-1" to "Top Secret Book"))
    val sourceTasks = createSourceTasks(rows = listOf("task-1" to "payload"))
    val target = createTarget()
    val beforeSourceCount = count(sourceMain, "BOOK")

    val report =
      MigrationPreflight().run(
        request(sourceMain, sourceTasks, target),
      )

    assertThat(report.status).describedAs(report.failure ?: "preflight should succeed").isEqualTo(MigrationStatus.SUCCESS)
    assertThat(report.phase).isEqualTo(MigrationPhase.PREFLIGHT)
    assertThat(report.failure).isNull()
    assertThat(report.sourceFingerprint?.value).isNotBlank
    assertThat(report.currentSchemaPolicy)
      .containsEntry("sourceMain", MigrationSchemaPolicy.SOURCE_MAIN_VERSION)
      .containsEntry("sourceTasks", MigrationSchemaPolicy.SOURCE_TASKS_VERSION)
      .containsEntry("targetMain", MigrationSchemaPolicy.TARGET_MAIN_VERSION)
      .containsEntry("targetTasks", MigrationSchemaPolicy.TARGET_TASKS_VERSION)
    assertThat(report.inventory)
      .anySatisfy {
        assertThat(it.name).isEqualTo("BOOK")
        assertThat(it.classification).isEqualTo(InventoryClassification.MIGRATED_APPLICATION_DATA)
      }.anySatisfy {
        assertThat(it.name).isEqualTo("idx__book__series_id")
        assertThat(it.classification).isEqualTo(InventoryClassification.SCHEMA_METADATA)
      }.anySatisfy {
        assertThat(it.name).isEqualTo("flyway_schema_history")
        assertThat(it.classification).isEqualTo(InventoryClassification.SCHEMA_METADATA)
      }
    assertThat(report.counts.map { it.table }).contains("BOOK", "TASK")
    assertThat(report.digests.map { it.table }).contains("BOOK", "TASK")
    assertThat(report.integrityChecks).allSatisfy { assertThat(it.status).isEqualTo(ValidationStatus.PASS) }
    assertThat(report.target.url)
      .doesNotContain("target-secret")
    assertThat(report.target.username).isEqualTo("<redacted>")
    assertThat(report.target.password).isEqualTo("<redacted>")
    assertThat(MigrationSecretRedactor.redact("jdbc:postgresql://user:secret@db/komga?password=target-secret&apiKey=target-token"))
      .doesNotContain("target-secret")
      .doesNotContain("target-token")
      .contains("password=<redacted>")
      .contains("apiKey=<redacted>")
    assertThat(count(sourceMain, "BOOK")).isEqualTo(beforeSourceCount)
    assertThat(tableExists(target, MigrationBookkeeping.TABLE)).isFalse
  }

  @Test
  fun `given current offline sources when migrate runs then main and tasks rows are copied and validated`() {
    val sourceMain = createSourceMain(rows = listOf("book-1" to "Top Secret Book"))
    val sourceTasks = createSourceTasks(rows = listOf("task-1" to "payload"))
    val target = createTarget()
    val beforeSourceCount = count(sourceMain, "BOOK")

    val report =
      MigrationPreflight().run(
        request(sourceMain, sourceTasks, target, mode = MigrationMode.MIGRATE),
      )

    assertThat(report.status).describedAs(report.failure ?: "migration should succeed").isEqualTo(MigrationStatus.SUCCESS)
    assertThat(report.phase).isEqualTo(MigrationPhase.MIGRATION)
    assertThat(report.failure).isNull()
    assertThat(report.counts).allSatisfy { assertThat(it.status).isEqualTo(ValidationStatus.PASS) }
    assertThat(report.digests).allSatisfy { assertThat(it.status).isEqualTo(ValidationStatus.PASS) }
    assertThat(report.integrityChecks).allSatisfy { assertThat(it.status).isEqualTo(ValidationStatus.PASS) }
    assertThat(count(target, "BOOK")).isEqualTo(1)
    assertThat(count(target, "TASK")).isEqualTo(1)
    assertThat(count(sourceMain, "BOOK")).isEqualTo(beforeSourceCount)
    assertThat(bookkeepingStatus(target)).isEqualTo("COMPLETED")
  }

  @Test
  fun `given forged target current version with missing current table when migrate runs then it fails before target writes`() {
    val sourceMain = createSourceMain(rows = listOf("book-1" to "Book"))
    val sourceTasks = createSourceTasks(rows = listOf("task-1" to "payload"))
    val target = createTarget(includeTasks = false)

    val report =
      MigrationPreflight().run(
        request(sourceMain, sourceTasks, target, mode = MigrationMode.MIGRATE),
      )

    assertThat(report.status).isEqualTo(MigrationStatus.FAILED)
    assertThat(report.phase).isEqualTo(MigrationPhase.PREFLIGHT)
    assertThat(report.failure)
      .contains("schema is incomplete")
      .contains("TASK table is missing")
    assertThat(count(target, "BOOK")).isEqualTo(0)
    assertThat(tableExists(target, MigrationBookkeeping.TABLE)).isFalse
  }

  @Test
  fun `given active SQLite writer when preflight runs then it aborts before target bookkeeping writes`() {
    val sourceMain = createSourceMain(rows = listOf("book-1" to "Book"))
    val sourceTasks = createSourceTasks()
    val target = createTarget()

    DriverManager.getConnection(sourceMain).use { writer ->
      writer.autoCommit = false
      insertBook(writer, "book-locked", "Locked")

      val report = MigrationPreflight().run(request(sourceMain, sourceTasks, target))

      assertThat(report.status).isEqualTo(MigrationStatus.FAILED)
      assertThat(report.phase).isEqualTo(MigrationPhase.LOCKING)
      assertThat(report.failure).contains("must be offline")
      assertThat(tableExists(target, MigrationBookkeeping.TABLE)).isFalse
      writer.rollback()
    }
  }

  @Test
  fun `given old source schema when preflight runs then it refuses without mutation`() {
    val sourceMain = createSourceMain(schemaVersion = "20240529120934")
    val sourceTasks = createSourceTasks()
    val target = createTarget()

    val report = MigrationPreflight().run(request(sourceMain, sourceTasks, target))

    assertThat(report.status).isEqualTo(MigrationStatus.FAILED)
    assertThat(report.failure).contains("schema must be current")
    assertThat(tableExists(target, MigrationBookkeeping.TABLE)).isFalse
  }

  @Test
  fun `given forged current source with missing application table when preflight runs then it fails closed before inventory`() {
    val sourceMain = createSourceMain(omitTables = setOf("BOOK"))
    val sourceTasks = createSourceTasks()
    val target = createTarget()

    val report = MigrationPreflight().run(request(sourceMain, sourceTasks, target))

    assertThat(report.status).isEqualTo(MigrationStatus.FAILED)
    assertThat(report.phase).isEqualTo(MigrationPhase.PREFLIGHT)
    assertThat(report.failure)
      .contains("schema is incomplete")
      .contains("BOOK table is missing")
    assertThat(report.inventory).isEmpty()
    assertThat(report.counts).isEmpty()
    assertThat(report.digests).isEmpty()
    assertThat(report.sourceFingerprint).isNull()
    assertThat(tableExists(target, MigrationBookkeeping.TABLE)).isFalse
  }

  @Test
  fun `given forged current source with missing required column when preflight runs then it fails closed before inventory`() {
    val sourceMain = createSourceMain(omitColumns = mapOf("BOOK" to setOf("URL")))
    val sourceTasks = createSourceTasks()
    val target = createTarget()

    val report = MigrationPreflight().run(request(sourceMain, sourceTasks, target))

    assertThat(report.status).isEqualTo(MigrationStatus.FAILED)
    assertThat(report.phase).isEqualTo(MigrationPhase.PREFLIGHT)
    assertThat(report.failure)
      .contains("schema is incomplete")
      .contains("BOOK missing required columns: URL")
    assertThat(report.inventory).isEmpty()
    assertThat(report.counts).isEmpty()
    assertThat(report.digests).isEmpty()
    assertThat(report.sourceFingerprint).isNull()
  }

  @Test
  fun `given forged current source with all names but no constraints or indexes when preflight runs then it fails before inventory`() {
    val sourceMain = createSourceMain(forgedAllVarcharSchema = true)
    val sourceTasks = createSourceTasks()
    val target = createTarget()

    val report = MigrationPreflight().run(request(sourceMain, sourceTasks, target))

    assertThat(report.status).isEqualTo(MigrationStatus.FAILED)
    assertThat(report.phase).isEqualTo(MigrationPhase.PREFLIGHT)
    assertThat(report.failure)
      .contains("schema is incomplete")
      .contains("malformed column definition")
      .contains("missing or malformed primary key")
      .contains("missing required indexes")
    assertThat(report.inventory).isEmpty()
    assertThat(report.counts).isEmpty()
    assertThat(report.digests).isEmpty()
    assertThat(report.sourceFingerprint).isNull()
  }

  @Test
  fun `given forged current source with malformed column definition when preflight runs then it fails before inventory`() {
    val sourceMain = createSourceMain(malformedColumns = mapOf("BOOK" to mapOf("FILE_SIZE" to "varchar")))
    val sourceTasks = createSourceTasks()
    val target = createTarget()

    val report = MigrationPreflight().run(request(sourceMain, sourceTasks, target))

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
  fun `given forged current source with changed column default when preflight runs then it fails before inventory`() {
    val sourceMain = createSourceMain(defaultOverrides = mapOf("LIBRARY" to mapOf("HASH_FILES" to "0")))
    val sourceTasks = createSourceTasks()
    val target = createTarget()

    val report = MigrationPreflight().run(request(sourceMain, sourceTasks, target))

    assertThat(report.status).isEqualTo(MigrationStatus.FAILED)
    assertThat(report.phase).isEqualTo(MigrationPhase.PREFLIGHT)
    assertThat(report.failure)
      .contains("schema is incomplete")
      .contains("LIBRARY.HASH_FILES malformed column definition")
      .contains("default 0 !=")
    assertThat(report.inventory).isEmpty()
    assertThat(report.counts).isEmpty()
    assertThat(report.digests).isEmpty()
    assertThat(report.sourceFingerprint).isNull()
  }

  @Test
  fun `given forged current source with case-only string default drift when preflight runs then it fails before inventory`() {
    val sourceMain =
      createSourceMain(
        defaultOverrides =
          mapOf(
            "LIBRARY" to
              mapOf(
                "SERIES_COVER" to "first",
                "SCAN_INTERVAL" to "every_6h",
              ),
          ),
      )
    val sourceTasks = createSourceTasks()
    val target = createTarget()

    val report = MigrationPreflight().run(request(sourceMain, sourceTasks, target))

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

  @Test
  fun `given forged current source with missing column default when preflight runs then it fails before inventory`() {
    val sourceMain = createSourceMain(defaultOverrides = mapOf("LIBRARY" to mapOf("HASH_FILES" to null)))
    val sourceTasks = createSourceTasks()
    val target = createTarget()

    val report = MigrationPreflight().run(request(sourceMain, sourceTasks, target))

    assertThat(report.status).isEqualTo(MigrationStatus.FAILED)
    assertThat(report.phase).isEqualTo(MigrationPhase.PREFLIGHT)
    assertThat(report.failure)
      .contains("schema is incomplete")
      .contains("LIBRARY.HASH_FILES malformed column definition")
      .contains("default null !=")
    assertThat(report.inventory).isEmpty()
    assertThat(report.counts).isEmpty()
    assertThat(report.digests).isEmpty()
    assertThat(report.sourceFingerprint).isNull()
  }

  @Test
  fun `given forged current source missing inline unique constraints when preflight runs then it fails before inventory`() {
    val sourceMain =
      createSourceMain(
        omitIndexes =
          mapOf(
            "USER" to setOf("inline_unique__USER__EMAIL"),
            "USER_API_KEY" to setOf("inline_unique__USER_API_KEY__API_KEY"),
          ),
      )
    val sourceTasks = createSourceTasks()
    val target = createTarget()

    val report = MigrationPreflight().run(request(sourceMain, sourceTasks, target))

    assertThat(report.status).isEqualTo(MigrationStatus.FAILED)
    assertThat(report.phase).isEqualTo(MigrationPhase.PREFLIGHT)
    assertThat(report.failure)
      .contains("schema is incomplete")
      .contains("USER missing required indexes: inline_unique__USER__EMAIL")
      .contains("USER_API_KEY missing required indexes: inline_unique__USER_API_KEY__API_KEY")
    assertThat(report.inventory).isEmpty()
    assertThat(report.counts).isEmpty()
    assertThat(report.digests).isEmpty()
    assertThat(report.sourceFingerprint).isNull()
  }

  @Test
  fun `given current schemas when preflight succeeds then every expected application table is inventoried counted and digested`() {
    val sourceMain = createSourceMain()
    val sourceTasks = createSourceTasks()
    val target = createTarget()
    val expectedMainTables = MigrationSchemaPolicy.requiredTables(MigrationSource.MAIN).keys
    val expectedTasksTables = MigrationSchemaPolicy.requiredTables(MigrationSource.TASKS).keys

    val report = MigrationPreflight().run(request(sourceMain, sourceTasks, target))

    assertThat(report.status).describedAs(report.failure ?: "preflight should succeed").isEqualTo(MigrationStatus.SUCCESS)
    assertThat(
      report.inventory
        .filter { it.source == MigrationSource.MAIN && it.classification == InventoryClassification.MIGRATED_APPLICATION_DATA }
        .map { it.name },
    ).containsAll(expectedMainTables)
    assertThat(
      report.inventory
        .filter { it.source == MigrationSource.TASKS && it.classification == InventoryClassification.MIGRATED_APPLICATION_DATA }
        .map { it.name },
    ).containsAll(expectedTasksTables)
    assertThat(report.counts.filter { it.source == MigrationSource.MAIN }.map { it.table }).containsAll(expectedMainTables)
    assertThat(report.counts.filter { it.source == MigrationSource.TASKS }.map { it.table }).containsAll(expectedTasksTables)
    assertThat(report.digests.filter { it.source == MigrationSource.MAIN }.map { it.table }).containsAll(expectedMainTables)
    assertThat(report.digests.filter { it.source == MigrationSource.TASKS }.map { it.table }).containsAll(expectedTasksTables)
  }

  @Test
  fun `given preflight holds source locks when concurrent writer tries to mutate then write is rejected`() {
    val sourceMain = createSourceMain(rows = listOf("book-1" to "Book"))
    val sourceTasks = createSourceTasks()
    val target = createTarget()

    val report =
      MigrationPreflight(
        afterLocksAcquired = {
          assertThatThrownBySql {
            DriverManager.getConnection(sourceMain).use { writer ->
              writer.createStatement().use { statement ->
                statement.queryTimeout = 1
                statement.execute("PRAGMA busy_timeout = 100")
                statement.execute(
                  """
                  insert into "BOOK" ("ID", "FILE_LAST_MODIFIED", "NAME", "URL", "SERIES_ID", "FILE_SIZE", "NUMBER", "LIBRARY_ID")
                  values ('book-locked-out', CURRENT_TIMESTAMP, 'Blocked', 'file:///tmp/library/series/book-locked-out.cbz', 'series-1', 0, 0, 'library-1')
                  """.trimIndent(),
                )
              }
            }
          }.hasMessageContaining("database is locked")
        },
      ).run(request(sourceMain, sourceTasks, target))

    assertThat(report.status).describedAs(report.failure ?: "preflight should succeed").isEqualTo(MigrationStatus.SUCCESS)
    assertThat(count(sourceMain, "BOOK")).isEqualTo(1)
  }

  @Test
  fun `given copied target rows when validators run then count and digest drift is detected`() {
    val sourceMain = createSourceMain(rows = listOf("book-1" to "Book"))
    val target = createTarget(bookRows = listOf("book-1" to "Book"))

    DriverManager.getConnection(sourceMain).use { sourceConnection ->
      DriverManager.getConnection(target).use { targetConnection ->
        val inventory =
          MigrationJdbcInspector
            .inventory(sourceConnection, MigrationSource.MAIN)
            .filter { it.name in setOf("BOOK", "LIBRARY", "SERIES") }

        assertThat(MigrationValidators.validateRowCounts(sourceConnection, targetConnection, MigrationSource.MAIN, inventory))
          .allSatisfy { assertThat(it.status).isEqualTo(ValidationStatus.PASS) }
        assertThat(MigrationValidators.validateDigests(sourceConnection, targetConnection, MigrationSource.MAIN, inventory))
          .allSatisfy { assertThat(it.status).isEqualTo(ValidationStatus.PASS) }

        targetConnection.createStatement().executeUpdate("""update "BOOK" set "NAME" = 'Changed' where "ID" = 'book-1'""")

        assertThat(MigrationValidators.validateRowCounts(sourceConnection, targetConnection, MigrationSource.MAIN, inventory))
          .allSatisfy { assertThat(it.status).isEqualTo(ValidationStatus.PASS) }
        assertThat(MigrationValidators.validateDigests(sourceConnection, targetConnection, MigrationSource.MAIN, inventory))
          .anySatisfy { assertThat(it.status).isEqualTo(ValidationStatus.FAIL) }
      }
    }
  }

  @Test
  fun `given incomplete attempt when resuming then source fingerprint must match`() {
    val sourceMain = createSourceMain(rows = listOf("book-1" to "Book"))
    val sourceTasks = createSourceTasks()
    val target = createTarget()
    val initialReport = MigrationPreflight().run(request(sourceMain, sourceTasks, target))

    DriverManager.getConnection(target).use { targetConnection ->
      MigrationBookkeeping.recordIncomplete(targetConnection, initialReport.sourceFingerprint!!.value, "password=secret")
    }

    val matchingResume = MigrationPreflight().run(request(sourceMain, sourceTasks, target, resume = true))
    assertThat(matchingResume.status).isEqualTo(MigrationStatus.SUCCESS)
    assertThat(matchingResume.resumable).isTrue

    DriverManager.getConnection(sourceMain).use {
      insertBook(it, "book-2", "Changed source")
    }

    val changedSourceResume = MigrationPreflight().run(request(sourceMain, sourceTasks, target, resume = true))
    assertThat(changedSourceResume.status).isEqualTo(MigrationStatus.FAILED)
    assertThat(changedSourceResume.failure).contains("fingerprint does not match")
  }

  @Test
  fun `given command help when requested then it describes standalone offline modes`() {
    assertThat(MigrationCommand.help())
      .contains("preflight")
      .contains("migrate")
      .contains("--source-config-dir")
      .contains("does not start the HTTP server")
      .contains("task processors")
  }

  @Test
  fun `given command help or empty args when executed then it returns zero`() {
    assertThat(MigrationCommand.execute(emptyArray())).isEqualTo(0)
    assertThat(MigrationCommand.execute(arrayOf("--help"))).isEqualTo(0)
  }

  @Test
  fun `given missing required command args when executed then it returns argument error`() {
    assertThat(MigrationCommand.execute(arrayOf("preflight", "--source-main=jdbc:sqlite:missing-main.sqlite"))).isEqualTo(2)
  }

  @Test
  fun `given source config dir when command executes then source SQLite urls are inferred`() {
    val sourceConfigDir = tempDir.resolve("config")
    val reportPath = tempDir.resolve("config-dir-preflight-report.json")

    val exitCode =
      MigrationCommand.execute(
        arrayOf(
          "preflight",
          "--source-config-dir=${sourceConfigDir.absolutePathString()}",
          "--target=jdbc:sqlite:${tempDir.resolve("target.sqlite").absolutePathString()}",
          "--report=${reportPath.absolutePathString()}",
        ),
      )

    assertThat(exitCode).isEqualTo(1)
    assertThat(reportPath.readText())
      .contains("jdbc:sqlite:${sourceConfigDir.resolve("database.sqlite")}")
      .contains("jdbc:sqlite:${sourceConfigDir.resolve("tasks.sqlite")}")
  }

  @Test
  fun `given explicit source urls with source config dir when command executes then explicit urls win`() {
    val sourceConfigDir = tempDir.resolve("config")
    val explicitMain = "jdbc:sqlite:${tempDir.resolve("custom-main.sqlite").absolutePathString()}"
    val explicitTasks = "jdbc:sqlite:${tempDir.resolve("custom-tasks.sqlite").absolutePathString()}"
    val reportPath = tempDir.resolve("explicit-sources-preflight-report.json")

    val exitCode =
      MigrationCommand.execute(
        arrayOf(
          "preflight",
          "--source-config-dir=${sourceConfigDir.absolutePathString()}",
          "--source-main=$explicitMain",
          "--source-tasks=$explicitTasks",
          "--target=jdbc:sqlite:${tempDir.resolve("target.sqlite").absolutePathString()}",
          "--report=${reportPath.absolutePathString()}",
        ),
      )

    assertThat(exitCode).isEqualTo(1)
    assertThat(reportPath.readText())
      .contains(explicitMain)
      .contains(explicitTasks)
      .doesNotContain("jdbc:sqlite:${sourceConfigDir.resolve("database.sqlite")}")
      .doesNotContain("jdbc:sqlite:${sourceConfigDir.resolve("tasks.sqlite")}")
  }

  @Test
  fun `given unknown command mode when executed then it returns argument error`() {
    assertThat(MigrationCommand.execute(arrayOf("unknown-mode"))).isEqualTo(2)
  }

  @Test
  fun `given failed preflight report when command executes then it returns failure status and writes report`() {
    val reportPath = tempDir.resolve("failed-preflight-report.json")
    val sourceMain = sqliteUrl("command-source-main")
    val sourceTasks = sqliteUrl("command-source-tasks")
    val target = sqliteUrl("command-target")

    val exitCode =
      MigrationCommand.execute(
        arrayOf(
          "preflight",
          "--source-main=$sourceMain",
          "--source-tasks=$sourceTasks",
          "--target=$target",
          "--report=${reportPath.absolutePathString()}",
        ),
      )

    assertThat(exitCode).isEqualTo(1)
    assertThat(reportPath.readText())
      .contains("FAILED")
      .contains("schema must be current")
  }

  @Test
  fun `given standalone launcher receives invalid mode then process exits with argument error status`() {
    assertThat(runMigrationCommandProcess("unknown-mode")).isEqualTo(2)
  }

  @Test
  fun `given standalone launcher receives failed preflight then process exits with failure status`() {
    val sourceMain = sqliteUrl("process-source-main")
    val sourceTasks = sqliteUrl("process-source-tasks")
    val target = sqliteUrl("process-target")

    assertThat(
      runMigrationCommandProcess(
        "preflight",
        "--source-main=$sourceMain",
        "--source-tasks=$sourceTasks",
        "--target=$target",
      ),
    ).isEqualTo(1)
  }

  private fun request(
    sourceMain: String,
    sourceTasks: String,
    target: String,
    resume: Boolean = false,
    mode: MigrationMode = MigrationMode.PREFLIGHT,
  ): MigrationRequest =
    MigrationRequest(
      sourceMain = JdbcEndpoint(sourceMain),
      sourceTasks = JdbcEndpoint(sourceTasks),
      target = JdbcEndpoint(target, username = "komga", password = "target-secret"),
      mode = mode,
      resume = resume,
    )

  private fun createSourceMain(
    schemaVersion: String = MigrationSchemaPolicy.SOURCE_MAIN_VERSION,
    rows: List<Pair<String, String>> = emptyList(),
    omitTables: Set<String> = emptySet(),
    omitColumns: Map<String, Set<String>> = emptyMap(),
    malformedColumns: Map<String, Map<String, String>> = emptyMap(),
    defaultOverrides: Map<String, Map<String, String?>> = emptyMap(),
    omitIndexes: Map<String, Set<String>> = emptyMap(),
    forgedAllVarcharSchema: Boolean = false,
  ): String {
    val url = sqliteUrl("source-main")
    DriverManager.getConnection(url).use { connection ->
      if (
        schemaVersion == MigrationSchemaPolicy.SOURCE_MAIN_VERSION &&
        omitTables.isEmpty() &&
        omitColumns.isEmpty() &&
        malformedColumns.isEmpty() &&
        defaultOverrides.isEmpty() &&
        omitIndexes.isEmpty() &&
        !forgedAllVarcharSchema
      ) {
        migrateSqlite(url, DatabaseScope.MAIN)
      } else {
        createFlywayHistory(connection, "flyway_schema_history", schemaVersion)
        createRequiredTables(
          connection = connection,
          source = MigrationSource.MAIN,
          omitTables = omitTables,
          omitColumns = omitColumns,
          malformedColumns = malformedColumns,
          defaultOverrides = defaultOverrides,
          omitIndexes = omitIndexes,
          allVarchar = forgedAllVarcharSchema,
        )
      }
      rows.forEach { (id, name) -> insertBook(connection, id, name) }
    }
    return url
  }

  private fun createSourceTasks(
    schemaVersion: String = MigrationSchemaPolicy.SOURCE_TASKS_VERSION,
    rows: List<Pair<String, String>> = emptyList(),
    omitTables: Set<String> = emptySet(),
    omitColumns: Map<String, Set<String>> = emptyMap(),
    malformedColumns: Map<String, Map<String, String>> = emptyMap(),
    defaultOverrides: Map<String, Map<String, String?>> = emptyMap(),
    omitIndexes: Map<String, Set<String>> = emptyMap(),
    forgedAllVarcharSchema: Boolean = false,
  ): String {
    val url = sqliteUrl("source-tasks")
    DriverManager.getConnection(url).use { connection ->
      if (
        schemaVersion == MigrationSchemaPolicy.SOURCE_TASKS_VERSION &&
        omitTables.isEmpty() &&
        omitColumns.isEmpty() &&
        malformedColumns.isEmpty() &&
        defaultOverrides.isEmpty() &&
        omitIndexes.isEmpty() &&
        !forgedAllVarcharSchema
      ) {
        migrateSqlite(url, DatabaseScope.TASKS)
      } else {
        createFlywayHistory(connection, "flyway_schema_history", schemaVersion)
        createRequiredTables(
          connection = connection,
          source = MigrationSource.TASKS,
          omitTables = omitTables,
          omitColumns = omitColumns,
          malformedColumns = malformedColumns,
          defaultOverrides = defaultOverrides,
          omitIndexes = omitIndexes,
          allVarchar = forgedAllVarcharSchema,
        )
      }
      rows.forEach { (id, payload) ->
        connection
          .prepareStatement(
            """
            insert into "TASK" ("ID", "PRIORITY", "CLASS", "SIMPLE_TYPE", "PAYLOAD", "CREATED_DATE", "LAST_MODIFIED_DATE")
            values (?, 0, 'Task', 'Task', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """.trimIndent(),
          ).use { statement ->
            statement.setString(1, id)
            statement.setString(2, payload)
            statement.executeUpdate()
          }
      }
    }
    return url
  }

  private fun createTarget(
    bookRows: List<Pair<String, String>> = emptyList(),
    includeTasks: Boolean = true,
    omitMainTables: Set<String> = emptySet(),
    omitMainColumns: Map<String, Set<String>> = emptyMap(),
  ): String {
    val url = sqliteUrl("target")
    DriverManager.getConnection(url).use { connection ->
      if (omitMainTables.isEmpty() && omitMainColumns.isEmpty()) {
        migrateSqlite(url, DatabaseScope.MAIN, "flyway_schema_history_main")
        connection
          .createStatement()
          .execute(
            """update "flyway_schema_history_main" set "version" = '${MigrationSchemaPolicy.TARGET_MAIN_VERSION}' where "installed_rank" = (select max("installed_rank") from "flyway_schema_history_main")""",
          )
        clearApplicationRows(connection, MigrationSource.MAIN)
      } else {
        createFlywayHistory(connection, "flyway_schema_history_main", MigrationSchemaPolicy.TARGET_MAIN_VERSION)
        createRequiredTables(connection, MigrationSource.MAIN, omitMainTables, omitMainColumns, allVarchar = true)
      }
      createFlywayHistory(connection, "flyway_schema_history_tasks", MigrationSchemaPolicy.TARGET_TASKS_VERSION)
      if (includeTasks) {
        createSqliteTasksSchema(connection)
        clearApplicationRows(connection, MigrationSource.TASKS)
      }
      bookRows.forEach { (id, name) -> insertBook(connection, id, name) }
    }
    return url
  }

  private fun createRequiredTables(
    connection: Connection,
    source: MigrationSource,
    omitTables: Set<String> = emptySet(),
    omitColumns: Map<String, Set<String>> = emptyMap(),
    malformedColumns: Map<String, Map<String, String>> = emptyMap(),
    defaultOverrides: Map<String, Map<String, String?>> = emptyMap(),
    omitIndexes: Map<String, Set<String>> = emptyMap(),
    allVarchar: Boolean = false,
  ) {
    MigrationSchemaPolicy.schemaSignature(source, DatabaseBackend.SQLITE).tables.forEach { (table, expectedTable) ->
      if (table in omitTables) return@forEach
      val columns =
        expectedTable.columns.values
          .filterNot { it.name in omitColumns[table].orEmpty() }
          .joinToString { column ->
            val type = malformedColumns[table]?.get(column.name) ?: if (allVarchar) "varchar" else sqliteType(column.type)
            val nullability = if (column.nullable || allVarchar) "" else " NOT NULL"
            val defaultValue =
              if (defaultOverrides[table]?.containsKey(column.name) == true)
                defaultOverrides[table]?.get(column.name)
              else
                column.default
            val default = if (allVarchar) "" else sqliteDefault(defaultValue)
            """"${column.name}" $type$nullability$default"""
          }
      val primaryKey =
        if (!allVarchar && expectedTable.primaryKey.isNotEmpty())
          """, primary key (${expectedTable.primaryKey.joinToString { """"$it"""" }})"""
        else
          ""
      connection.createStatement().execute("""create table "$table" ($columns$primaryKey)""")
      if (!allVarchar) {
        expectedTable.indexes.values
          .filterNot { it.name in omitIndexes[table].orEmpty() }
          .forEach { index ->
            val unique = if (index.unique) "unique " else ""
            connection
              .createStatement()
              .execute("""create ${unique}index "${index.name}" on "$table" (${index.columns.joinToString { """"$it"""" }})""")
          }
      }
    }
  }

  private fun createFlywayHistory(
    connection: Connection,
    table: String,
    version: String,
  ) {
    connection.createStatement().use { statement ->
      statement.execute("""create table "$table" ("installed_rank" integer not null, "version" varchar, "success" boolean not null)""")
      statement.execute("""insert into "$table" ("installed_rank", "version", "success") values (1, '$version', 1)""")
    }
  }

  private fun clearApplicationRows(
    connection: Connection,
    source: MigrationSource,
  ) {
    connection.createStatement().use { statement ->
      statement.execute("PRAGMA foreign_keys = OFF")
      MigrationSchemaPolicy.schemaSignature(source, DatabaseBackend.SQLITE).tables.keys.reversed().forEach { table ->
        statement.execute("delete from \"$table\"")
      }
      statement.execute("PRAGMA foreign_keys = ON")
    }
  }

  private fun insertBook(
    connection: Connection,
    id: String,
    name: String,
  ) {
    connection.createStatement().use { statement ->
      statement.execute(
        """
        insert or ignore into "LIBRARY" ("ID", "CREATED_DATE", "LAST_MODIFIED_DATE", "NAME", "ROOT")
        values ('library-1', '2020-01-01 00:00:00', '2020-01-01 00:00:00', 'Library', 'file:///tmp/library')
        """.trimIndent(),
      )
      statement.execute(
        """
        insert or ignore into "SERIES" ("ID", "CREATED_DATE", "LAST_MODIFIED_DATE", "FILE_LAST_MODIFIED", "NAME", "URL", "LIBRARY_ID", "BOOK_COUNT")
        values ('series-1', '2020-01-01 00:00:00', '2020-01-01 00:00:00', '2020-01-01 00:00:00', 'Series', 'file:///tmp/library/series', 'library-1', 0)
        """.trimIndent(),
      )
    }
    connection
      .prepareStatement(
        """
        insert into "BOOK" ("ID", "CREATED_DATE", "LAST_MODIFIED_DATE", "FILE_LAST_MODIFIED", "NAME", "URL", "SERIES_ID", "FILE_SIZE", "NUMBER", "LIBRARY_ID")
        values (?, '2020-01-01 00:00:00', '2020-01-01 00:00:00', '2020-01-01 00:00:00', ?, ?, 'series-1', 0, 0, 'library-1')
        """.trimIndent(),
      ).use { statement ->
        statement.setString(1, id)
        statement.setString(2, name)
        statement.setString(3, "file:///tmp/library/series/$id.cbz")
        statement.executeUpdate()
      }
  }

  private fun migrateSqlite(
    url: String,
    scope: DatabaseScope,
    historyTable: String? = null,
  ) {
    val configuration =
      Flyway
        .configure()
        .dataSource(url, null, null)
        .locations(DatabaseBackend.SQLITE.flywayLocation(scope))
        .mixed(true)
        .placeholders(sqlitePlaceholders)
    if (historyTable != null) configuration.table(historyTable)
    configuration.load().migrate()
  }

  private fun createSqliteTasksSchema(connection: Connection) {
    val sql =
      MigrationPreflightTest::class.java
        .getResourceAsStream("/tasks/migration/sqlite/V${MigrationSchemaPolicy.SOURCE_TASKS_VERSION}__tasks.sql")!!
        .bufferedReader()
        .use { it.readText() }
    connection.createStatement().use { statement ->
      sql
        .split(";")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .forEach(statement::execute)
    }
  }

  private fun sqliteType(type: String): String =
    when (type) {
      "integer" -> "integer"
      "bigint" -> "bigint"
      "boolean" -> "boolean"
      "timestamp" -> "datetime"
      "date" -> "date"
      "blob" -> "blob"
      "numeric" -> "double"
      else -> "varchar"
    }

  private fun sqliteDefault(default: String?): String =
    when (default) {
      null -> ""
      "true" -> " DEFAULT 1"
      "false" -> " DEFAULT 0"
      "current_timestamp" -> " DEFAULT CURRENT_TIMESTAMP"
      "" -> " DEFAULT ''"
      else ->
        if (default.matches(Regex("""-?\d+(\.\d+)?"""))) {
          " DEFAULT $default"
        } else {
          " DEFAULT '${default.replace("'", "''")}'"
        }
    }

  private fun assertThatThrownBySql(block: () -> Unit) =
    org.assertj.core.api.Assertions
      .assertThatThrownBy(block)
      .isInstanceOf(SQLException::class.java)

  private fun runMigrationCommandProcess(vararg args: String): Int {
    val javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").absolutePathString()
    val process =
      ProcessBuilder(
        listOf(
          javaExecutable,
          "-cp",
          System.getProperty("java.class.path"),
          "org.gotson.komga.infrastructure.migration.MigrationCommandKt",
        ) + args,
      ).redirectErrorStream(true)
        .start()
    assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue
    return process.exitValue()
  }

  private fun count(
    url: String,
    table: String,
  ): Long =
    DriverManager.getConnection(url).use {
      MigrationJdbcInspector.rowCount(it, table)
    }

  private fun tableExists(
    url: String,
    table: String,
  ): Boolean =
    DriverManager.getConnection(url).use {
      MigrationJdbcInspector.tableExists(it, table)
    }

  private fun bookkeepingStatus(url: String): String? =
    DriverManager.getConnection(url).use { connection ->
      connection.createStatement().use { statement ->
        statement
          .executeQuery("""select "STATUS" from "${MigrationBookkeeping.TABLE}" order by "UPDATED_AT" desc limit 1""")
          .use { result -> if (result.next()) result.getString(1) else null }
      }
    }

  private fun sqliteUrl(prefix: String): String = "jdbc:sqlite:${tempDir.resolve("$prefix.sqlite").absolutePathString()}"

  private val sqlitePlaceholders =
    mapOf(
      "library-file-hashing" to "true",
      "library-scan-startup" to "false",
      "delete-empty-collections" to "true",
      "delete-empty-read-lists" to "true",
    )
}
