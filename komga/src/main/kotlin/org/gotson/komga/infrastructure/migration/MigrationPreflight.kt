package org.gotson.komga.infrastructure.migration

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.gotson.komga.infrastructure.datasource.DatabaseBackend
import java.security.MessageDigest
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import kotlin.time.measureTimedValue

class MigrationPreflight(
  private val afterLocksAcquired: () -> Unit = {},
) {
  private val mapper = jacksonObjectMapper().registerKotlinModule().findAndRegisterModules()

  fun run(request: MigrationRequest): MigrationReport {
    val startedAt = Instant.now()
    val timings = linkedMapOf<String, Long>()

    fun failure(
      phase: MigrationPhase,
      message: String,
      inventory: List<MigrationInventoryItem> = emptyList(),
      fingerprint: SourceFingerprint? = null,
    ) = MigrationReport(
      status = MigrationStatus.FAILED,
      mode = request.mode,
      phase = phase,
      startedAt = startedAt,
      duration = Duration.between(startedAt, Instant.now()),
      sourceMain = request.sourceMain.redacted(),
      sourceTasks = request.sourceTasks.redacted(),
      target = request.target.redacted(),
      currentSchemaPolicy = schemaPolicy(),
      inventory = inventory,
      sourceFingerprint = fingerprint,
      failure = MigrationSecretRedactor.redact(message),
      timings = timings,
    )

    try {
      MigrationJdbcInspector.connect(request.sourceMain).use { sourceMain ->
        MigrationJdbcInspector.connect(request.sourceTasks).use { sourceTasks ->
          MigrationJdbcInspector.connect(request.target).use { target ->
            val lockGuards =
              measure("locking", timings) {
                listOf(
                  MigrationJdbcInspector.acquireSqliteOfflineLock(sourceMain),
                  MigrationJdbcInspector.acquireSqliteOfflineLock(sourceTasks),
                ).also {
                  MigrationJdbcInspector.verifyTargetMigrationLock(target)
                  afterLocksAcquired()
                }
              }

            lockGuards[0].use {
              lockGuards[1].use {
                measure("schema", timings) {
                  requireCurrentSchema(sourceMain, "flyway_schema_history", MigrationSchemaPolicy.SOURCE_MAIN_VERSION, MigrationSource.MAIN, "source main SQLite")
                  requireCurrentSchema(sourceTasks, "flyway_schema_history", MigrationSchemaPolicy.SOURCE_TASKS_VERSION, MigrationSource.TASKS, "source tasks SQLite")
                  requireCurrentSchema(target, "flyway_schema_history_main", MigrationSchemaPolicy.TARGET_MAIN_VERSION, MigrationSource.MAIN, "target PostgreSQL main")
                  requireCurrentSchema(target, "flyway_schema_history_tasks", MigrationSchemaPolicy.TARGET_TASKS_VERSION, MigrationSource.TASKS, "target PostgreSQL tasks")
                  MigrationJdbcInspector.verifyPostgresqlCurrentObjects(target)
                }

                val inventory =
                  measure("inventory", timings) {
                    MigrationJdbcInspector.inventory(sourceMain, MigrationSource.MAIN) +
                      MigrationJdbcInspector.inventory(sourceTasks, MigrationSource.TASKS)
                  }

                val sourceFingerprint =
                  measure("sourceFingerprint", timings) {
                    fingerprint(sourceMain, sourceTasks, inventory)
                  }

                val resumable =
                  measure("targetState", timings) {
                    verifyTargetState(target, sourceMain, sourceTasks, inventory, sourceFingerprint.value, request.resume)
                  }

                val sourceIntegrity =
                  measure("sourceIntegrity", timings) {
                    MigrationValidators.validateIntegrity(sourceMain, MigrationSource.MAIN, inventory) +
                      MigrationValidators.validateIntegrity(sourceTasks, MigrationSource.TASKS, inventory)
                  }

                if (request.mode == MigrationMode.MIGRATE) {
                  return migrate(
                    request = request,
                    startedAt = startedAt,
                    timings = timings,
                    sourceMain = sourceMain,
                    sourceTasks = sourceTasks,
                    target = target,
                    inventory = inventory,
                    sourceFingerprint = sourceFingerprint,
                    sourceIntegrity = sourceIntegrity,
                    resumable = resumable,
                  )
                }

                return MigrationReport(
                  status = MigrationStatus.SUCCESS,
                  mode = request.mode,
                  phase = MigrationPhase.PREFLIGHT,
                  startedAt = startedAt,
                  duration = Duration.between(startedAt, Instant.now()),
                  sourceMain = request.sourceMain.redacted(),
                  sourceTasks = request.sourceTasks.redacted(),
                  target = request.target.redacted(),
                  currentSchemaPolicy = schemaPolicy(),
                  inventory = inventory,
                  counts = sourceCounts(sourceMain, sourceTasks, target, inventory),
                  digests = sourceFingerprint.tableDigests,
                  integrityChecks = sourceIntegrity,
                  sourceFingerprint = sourceFingerprint,
                  resumable = resumable,
                  timings = timings,
                ).redacted()
              }
            }
          }
        }
      }
    } catch (e: MigrationPreflightException) {
      return failure(e.phase, e.message ?: "Migration preflight failed.")
    } catch (e: Exception) {
      return failure(MigrationPhase.PREFLIGHT, e.message ?: "Migration preflight failed.")
    }
  }

  private fun migrate(
    request: MigrationRequest,
    startedAt: Instant,
    timings: MutableMap<String, Long>,
    sourceMain: Connection,
    sourceTasks: Connection,
    target: Connection,
    inventory: List<MigrationInventoryItem>,
    sourceFingerprint: SourceFingerprint,
    sourceIntegrity: List<IntegrityCheck>,
    resumable: Boolean,
  ): MigrationReport {
    val originalAutoCommit = target.autoCommit
    try {
      target.autoCommit = false
      measure("copy", timings) {
        MigrationDataCopier.copy(sourceMain, target, MigrationSource.MAIN, inventory)
        MigrationDataCopier.copy(sourceTasks, target, MigrationSource.TASKS, inventory)
      }

      val counts =
        measure("rowCountValidation", timings) {
          MigrationValidators.validateRowCounts(sourceMain, target, MigrationSource.MAIN, inventory) +
            MigrationValidators.validateRowCounts(sourceTasks, target, MigrationSource.TASKS, inventory)
        }
      requireValidationPassed(counts, "row-count")

      val digests =
        measure("digestValidation", timings) {
          MigrationValidators.validateDigests(sourceMain, target, MigrationSource.MAIN, inventory) +
            MigrationValidators.validateDigests(sourceTasks, target, MigrationSource.TASKS, inventory)
        }
      requireValidationPassed(digests, "digest")

      val integrity =
        sourceIntegrity +
          measure("targetIntegrity", timings) {
            MigrationValidators.validateIntegrity(target, MigrationSource.MAIN, inventory) +
              MigrationValidators.validateIntegrity(target, MigrationSource.TASKS, inventory)
          }
      requireValidationPassed(integrity, "integrity")

      val report =
        MigrationReport(
          status = MigrationStatus.SUCCESS,
          mode = request.mode,
          phase = MigrationPhase.MIGRATION,
          startedAt = startedAt,
          duration = Duration.between(startedAt, Instant.now()),
          sourceMain = request.sourceMain.redacted(),
          sourceTasks = request.sourceTasks.redacted(),
          target = request.target.redacted(),
          currentSchemaPolicy = schemaPolicy(),
          inventory = inventory,
          counts = counts,
          digests = digests,
          integrityChecks = integrity,
          sourceFingerprint = sourceFingerprint,
          resumable = resumable,
          timings = timings,
        ).redacted()

      MigrationBookkeeping.recordCompleted(target, sourceFingerprint.value, mapper.writeValueAsString(report))
      target.commit()
      return report
    } catch (e: Exception) {
      target.rollback()
      target.autoCommit = true
      val report =
        MigrationReport(
          status = MigrationStatus.FAILED,
          mode = request.mode,
          phase = if (e is MigrationPreflightException) e.phase else MigrationPhase.MIGRATION,
          startedAt = startedAt,
          duration = Duration.between(startedAt, Instant.now()),
          sourceMain = request.sourceMain.redacted(),
          sourceTasks = request.sourceTasks.redacted(),
          target = request.target.redacted(),
          currentSchemaPolicy = schemaPolicy(),
          inventory = inventory,
          digests = sourceFingerprint.tableDigests,
          integrityChecks = sourceIntegrity,
          sourceFingerprint = sourceFingerprint,
          resumable = resumable,
          failure = MigrationSecretRedactor.redact(e.message ?: "Migration failed."),
          timings = timings,
        ).redacted()

      MigrationBookkeeping.recordIncomplete(target, sourceFingerprint.value, mapper.writeValueAsString(report))
      return report
    } finally {
      target.autoCommit = originalAutoCommit
    }
  }

  private fun requireValidationPassed(
    validations: List<Any>,
    label: String,
  ) {
    val failures =
      validations.filter {
        when (it) {
          is TableCount -> it.status == ValidationStatus.FAIL
          is TableDigest -> it.status == ValidationStatus.FAIL
          is IntegrityCheck -> it.status == ValidationStatus.FAIL
          else -> false
        }
      }
    if (failures.isNotEmpty()) {
      val names =
        failures.joinToString { failure ->
          when (failure) {
            is TableCount -> failure.table
            is TableDigest -> failure.table
            is IntegrityCheck -> failure.name
            else -> failure.toString()
          }
        }
      throw MigrationPreflightException(MigrationPhase.VALIDATION, "Post-copy $label validation failed: ${failures.size} failure(s): $names.")
    }
  }

  private fun requireCurrentSchema(
    connection: Connection,
    historyTable: String,
    expectedVersion: String,
    source: MigrationSource,
    label: String,
  ) {
    val version = MigrationJdbcInspector.currentFlywayVersion(connection, historyTable)
    if (version != expectedVersion) {
      throw MigrationPreflightException(
        MigrationPhase.PREFLIGHT,
        "$label schema must be current before migration. Expected Flyway version $expectedVersion, found ${version ?: "none"}.",
      )
    }

    val backend = MigrationJdbcInspector.backend(connection)
    val expectedSchema = MigrationSchemaPolicy.schemaSignature(source, backend)
    val missingRequiredSchema =
      MigrationJdbcInspector.missingRequiredSchema(connection, expectedSchema)
    if (missingRequiredSchema.isNotEmpty()) {
      throw MigrationPreflightException(
        MigrationPhase.PREFLIGHT,
        "$label schema is incomplete for current Flyway version $expectedVersion. Current schema signature ${MigrationSchemaPolicy.expectedSignature(source, backend)} requires: ${missingRequiredSchema.joinToString("; ")}.",
      )
    }
  }

  private fun verifyTargetState(
    target: Connection,
    sourceMain: Connection,
    sourceTasks: Connection,
    inventory: List<MigrationInventoryItem>,
    fingerprint: String,
    resume: Boolean,
  ): Boolean {
    val incompleteFingerprint = MigrationBookkeeping.findIncompleteFingerprint(target)
    if (incompleteFingerprint != null) {
      if (!resume) {
        throw MigrationPreflightException(MigrationPhase.PREFLIGHT, "Target contains an incomplete migration attempt; rerun with resume enabled after verifying the source is unchanged.")
      }
      if (incompleteFingerprint != fingerprint) {
        throw MigrationPreflightException(MigrationPhase.PREFLIGHT, "Target resume fingerprint does not match the current source fingerprint; refusing unsafe resume.")
      }
      return true
    }

    val nonEmptyTables =
      sourceCounts(sourceMain, sourceTasks, target, inventory)
        .filter { it.targetRows != null && it.targetRows > 0 }

    if (nonEmptyTables.isNotEmpty()) {
      throw MigrationPreflightException(
        MigrationPhase.PREFLIGHT,
        "Target must be empty or resumable before migration; non-empty tables: ${nonEmptyTables.joinToString { it.table }}.",
      )
    }

    return false
  }

  private fun sourceCounts(
    sourceMain: Connection,
    sourceTasks: Connection,
    target: Connection,
    inventory: List<MigrationInventoryItem>,
  ): List<TableCount> =
    countSnapshot(sourceMain, target, MigrationSource.MAIN, inventory) +
      countSnapshot(sourceTasks, target, MigrationSource.TASKS, inventory)

  private fun countSnapshot(
    sourceConnection: Connection,
    target: Connection,
    source: MigrationSource,
    inventory: List<MigrationInventoryItem>,
  ): List<TableCount> =
    MigrationValidators
      .applicationTables(inventory, source)
      .map { item ->
        TableCount(
          source = source,
          table = item.name,
          sourceRows = MigrationJdbcInspector.rowCount(sourceConnection, item.name),
          targetRows =
            if (MigrationJdbcInspector.tableExists(target, item.name))
              MigrationJdbcInspector.rowCount(target, item.name)
            else
              null,
          status = ValidationStatus.SKIPPED,
          reason = "Preflight snapshot only; post-copy row-count validation runs after data copy.",
        )
      }

  private fun fingerprint(
    sourceMain: Connection,
    sourceTasks: Connection,
    inventory: List<MigrationInventoryItem>,
  ): SourceFingerprint {
    val tableDigests =
      sourceDigests(sourceMain, MigrationSource.MAIN, inventory) +
        sourceDigests(sourceTasks, MigrationSource.TASKS, inventory)
    val payload =
      buildString {
        appendLine(MigrationSchemaPolicy.SOURCE_MAIN_VERSION)
        appendLine(MigrationSchemaPolicy.SOURCE_TASKS_VERSION)
        tableDigests.forEach { appendLine("${it.source}:${it.table}:${it.sourceDigest}") }
      }

    return SourceFingerprint(
      value = sha256Hex(payload.toByteArray()),
      mainSchemaVersion = MigrationSchemaPolicy.SOURCE_MAIN_VERSION,
      tasksSchemaVersion = MigrationSchemaPolicy.SOURCE_TASKS_VERSION,
      tableDigests = tableDigests,
    )
  }

  private fun sourceDigests(
    connection: Connection,
    source: MigrationSource,
    inventory: List<MigrationInventoryItem>,
  ): List<TableDigest> =
    MigrationValidators
      .applicationTables(inventory, source)
      .map { item ->
        TableDigest(
          source = source,
          table = item.name,
          sourceDigest = MigrationJdbcInspector.tableDigest(connection, item.name),
          targetDigest = null,
          status = ValidationStatus.SKIPPED,
          reason = "Source fingerprint digest.",
        )
      }

  private fun schemaPolicy(): Map<String, String> =
    mapOf(
      "sourceMain" to MigrationSchemaPolicy.SOURCE_MAIN_VERSION,
      "sourceTasks" to MigrationSchemaPolicy.SOURCE_TASKS_VERSION,
      "targetMain" to MigrationSchemaPolicy.TARGET_MAIN_VERSION,
      "targetTasks" to MigrationSchemaPolicy.TARGET_TASKS_VERSION,
      "sourceMainSignature" to MigrationSchemaPolicy.expectedSignature(MigrationSource.MAIN, DatabaseBackend.SQLITE),
      "sourceTasksSignature" to MigrationSchemaPolicy.expectedSignature(MigrationSource.TASKS, DatabaseBackend.SQLITE),
      "targetMainSignature" to MigrationSchemaPolicy.expectedSignature(MigrationSource.MAIN, DatabaseBackend.POSTGRESQL),
      "targetTasksSignature" to MigrationSchemaPolicy.expectedSignature(MigrationSource.TASKS, DatabaseBackend.POSTGRESQL),
    )

  private fun <T> measure(
    name: String,
    timings: MutableMap<String, Long>,
    block: () -> T,
  ): T {
    val measured = measureTimedValue(block)
    timings[name] = measured.duration.inWholeMilliseconds
    return measured.value
  }

  private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .joinToString("") { "%02x".format(it) }
}
