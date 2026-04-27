package org.gotson.komga.infrastructure.migration

import org.flywaydb.core.Flyway
import org.gotson.komga.infrastructure.datasource.DatabaseBackend
import org.gotson.komga.infrastructure.datasource.DatabaseScope
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.UUID

object MigrationSchemaPolicy {
  const val SOURCE_MAIN_VERSION = "20260427080000"
  const val SOURCE_TASKS_VERSION = "20231013114850"
  const val TARGET_MAIN_VERSION = "20260427090000"
  const val TARGET_TASKS_VERSION = "20231013114850"

  private val sourceMainSignature: MigrationSchemaSignature by lazy {
    sqliteReferenceSignature(DatabaseScope.MAIN, MigrationSource.MAIN)
  }

  private val sourceTasksSignature: MigrationSchemaSignature by lazy {
    sqliteReferenceSignature(DatabaseScope.TASKS, MigrationSource.TASKS)
  }

  private val targetMainSignature: MigrationSchemaSignature by lazy {
    parseSqlSignature(
      resourcePath = "/db/migration/postgresql/V${TARGET_MAIN_VERSION}__current_schema.sql",
      source = MigrationSource.MAIN,
      requiredObjects =
        setOf(
          MigrationSchemaObject("function", "UDF_STRIP_ACCENTS"),
          MigrationSchemaObject("collation", "COLLATION_UNICODE_3"),
          MigrationSchemaObject("collation", "NOCASE"),
        ),
    )
  }

  private val targetTasksSignature: MigrationSchemaSignature by lazy {
    parseSqlSignature(
      resourcePath = "/tasks/migration/postgresql/V${TARGET_TASKS_VERSION}__tasks.sql",
      source = MigrationSource.TASKS,
    )
  }

  fun requiredTables(source: MigrationSource): Map<String, Set<String>> = schemaSignature(source, DatabaseBackend.SQLITE).tables.mapValues { (_, table) -> table.columns.keys }

  fun schemaSignature(
    source: MigrationSource,
    backend: DatabaseBackend,
  ): MigrationSchemaSignature =
    when (backend) {
      DatabaseBackend.SQLITE ->
        when (source) {
          MigrationSource.MAIN -> sourceMainSignature
          MigrationSource.TASKS -> sourceTasksSignature
        }
      DatabaseBackend.POSTGRESQL ->
        when (source) {
          MigrationSource.MAIN -> targetMainSignature
          MigrationSource.TASKS -> targetTasksSignature
        }
    }

  fun expectedSignature(source: MigrationSource): String = signature(requiredTables(source))

  fun expectedSignature(
    source: MigrationSource,
    backend: DatabaseBackend,
  ): String = signature(schemaSignature(source, backend))

  fun signature(tables: Map<String, Set<String>>): String =
    sha256Hex(
      tables
        .toSortedMap()
        .entries
        .joinToString(separator = "\n") { (table, columns) ->
          "$table:${columns.sorted().joinToString(",")}"
        }.toByteArray(),
    )

  fun signature(schema: MigrationSchemaSignature): String = sha256Hex(schema.canonical().toByteArray())

  private fun sqliteReferenceSignature(
    scope: DatabaseScope,
    source: MigrationSource,
  ): MigrationSchemaSignature {
    val url = "jdbc:sqlite:file:komga_schema_reference_${UUID.randomUUID()}?mode=memory&cache=shared"
    DriverManager.getConnection(url).use { connection ->
      Flyway
        .configure()
        .dataSource(url, null, null)
        .locations(DatabaseBackend.SQLITE.flywayLocation(scope))
        .mixed(true)
        .placeholders(sqlitePlaceholders)
        .load()
        .migrate()
      return MigrationJdbcInspector.schemaSignature(connection, source)
    }
  }

  private fun parseSqlSignature(
    resourcePath: String,
    source: MigrationSource,
    requiredObjects: Set<MigrationSchemaObject> = emptySet(),
  ): MigrationSchemaSignature {
    val resource =
      MigrationSchemaPolicy::class.java.getResourceAsStream(resourcePath)
        ?: throw MigrationPreflightException(
          MigrationPhase.PREFLIGHT,
          "Current schema migration $resourcePath is missing from resources.",
        )
    val sql = resource.bufferedReader().use { it.readText() }
    val tables =
      createTableRegex
        .findAll(sql)
        .associate { match ->
          val table = match.groupValues[1]
          val definitions = splitSqlList(match.groupValues[2])
          val columns =
            definitions
              .mapNotNull { parseColumn(it) }
              .associateBy { it.name }
              .toSortedMap()
          val primaryKey =
            definitions
              .firstNotNullOfOrNull {
                primaryKeyRegex
                  .find(it)
                  ?.groupValues
                  ?.get(1)
                  ?.let(::parseColumnList)
              }
              ?: definitions
                .mapNotNull { parseInlinePrimaryKey(it) }
                .toSortedSet()
          val normalizedColumns =
            columns
              .mapValues { (name, column) ->
                if (primaryKey.any { it.equals(name, ignoreCase = true) }) column.copy(nullable = false) else column
              }.toSortedMap()

          table to
            MigrationTableSignature(
              name = table,
              columns = normalizedColumns,
              primaryKey = primaryKey,
              foreignKeys = emptySet(),
              indexes = emptyMap(),
            )
        }

    val foreignKeys =
      alterForeignKeyRegex
        .findAll(sql)
        .groupBy { it.groupValues[1] }
        .mapValues { (_, matches) ->
          matches
            .map {
              MigrationForeignKeySignature(
                columns = parseColumnList(it.groupValues[3]),
                referencedTable = it.groupValues[4],
                referencedColumns = parseColumnList(it.groupValues[5]),
              )
            }.toSet()
        }

    val indexes =
      createIndexRegex
        .findAll(sql)
        .groupBy { it.groupValues[3] }
        .mapValues { (_, matches) ->
          matches
            .associate {
              it.groupValues[2] to
                MigrationIndexSignature(
                  name = it.groupValues[2],
                  unique = it.groupValues[1].isNotBlank(),
                  columns = parseColumnList(it.groupValues[4]),
                )
            }.toSortedMap()
        }

    return MigrationSchemaSignature(
      source = source,
      tables =
        tables
          .mapValues { (tableName, table) ->
            table.copy(
              foreignKeys = foreignKeys[tableName].orEmpty(),
              indexes = indexes[tableName].orEmpty(),
            )
          }.toSortedMap(),
      requiredObjects = requiredObjects,
    )
  }

  private fun parseColumn(definition: String): MigrationColumnSignature? {
    val match = columnRegex.find(definition) ?: return null
    val upper = definition.uppercase()
    if (
      upper.startsWith("CONSTRAINT ") ||
      upper.startsWith("PRIMARY KEY ") ||
      upper.startsWith("FOREIGN KEY ") ||
      upper.startsWith("UNIQUE ") ||
      upper.startsWith("CHECK ")
    ) {
      return null
    }

    val name = match.groupValues[1].ifBlank { match.groupValues[2] }
    val remainder = match.groupValues[3]
    return MigrationColumnSignature(
      name = name,
      type = normalizeSqlType(remainder),
      nullable = !upper.contains(" NOT NULL") && !upper.contains(" PRIMARY KEY"),
      default = normalizeDefault(defaultRegex.find(remainder)?.groupValues?.get(1)),
    )
  }

  private fun parseInlinePrimaryKey(definition: String): String? =
    columnRegex
      .find(definition)
      ?.takeIf { definition.uppercase().contains(" PRIMARY KEY") }
      ?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }

  private fun splitSqlList(content: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var depth = 0
    var inSingleQuote = false
    var inDoubleQuote = false
    content.forEach { char ->
      when (char) {
        '\'' -> if (!inDoubleQuote) inSingleQuote = !inSingleQuote
        '"' -> if (!inSingleQuote) inDoubleQuote = !inDoubleQuote
        '(' -> if (!inSingleQuote && !inDoubleQuote) depth++
        ')' -> if (!inSingleQuote && !inDoubleQuote) depth--
        ',' ->
          if (!inSingleQuote && !inDoubleQuote && depth == 0) {
            parts += current.toString().trim()
            current.clear()
            return@forEach
          }
      }
      current.append(char)
    }
    val last = current.toString().trim()
    if (last.isNotBlank()) parts += last
    return parts
  }

  private fun parseColumnList(columns: String): Set<String> =
    splitSqlList(columns)
      .map { it.trim().trim('"') }
      .filter { it.isNotBlank() }
      .toSortedSet()

  private fun normalizeSqlType(definition: String): String {
    val type =
      definition
        .replace(Regex("""(?i)\s+NOT\s+NULL.*$"""), "")
        .replace(Regex("""(?i)\s+DEFAULT\s+.*$"""), "")
        .replace(Regex("""(?i)\s+PRIMARY\s+KEY.*$"""), "")
        .trim()
        .lowercase()
    return when {
      type.startsWith("varchar") || type.startsWith("text") || type.startsWith("char") -> "text"
      type.startsWith("integer") || type == "int" || type.startsWith("int ") -> "integer"
      type.startsWith("bigint") -> "bigint"
      type.startsWith("boolean") || type.startsWith("bool") -> "boolean"
      type.startsWith("timestamp") || type.startsWith("datetime") -> "timestamp"
      type.startsWith("date") -> "date"
      type.startsWith("bytea") || type.startsWith("blob") -> "blob"
      type.startsWith("double") || type.startsWith("float") || type.startsWith("real") -> "numeric"
      else -> type
    }
  }

  private val sqlitePlaceholders =
    mapOf(
      "library-file-hashing" to "true",
      "library-scan-startup" to "false",
      "delete-empty-collections" to "true",
      "delete-empty-read-lists" to "true",
    )

  private val createTableRegex = Regex("""(?is)CREATE\s+TABLE\s+"?([A-Za-z0-9_]+)"?\s*\((.*?)\);""")
  private val columnRegex = Regex("""(?is)^\s*(?:"([^"]+)"|([A-Za-z_][A-Za-z0-9_]*))\s+(.+)$""")
  private val primaryKeyRegex = Regex("""(?is)PRIMARY\s+KEY\s*\((.*?)\)""")
  private val defaultRegex = Regex("""(?is)\bDEFAULT\s+(.+?)(?:\s+CONSTRAINT|\s+PRIMARY\s+KEY|\s+REFERENCES|\s+NOT\s+NULL|$)""")
  private val alterForeignKeyRegex =
    Regex(
      """(?is)ALTER\s+TABLE\s+"?([A-Za-z0-9_]+)"?\s+ADD\s+CONSTRAINT\s+"?([A-Za-z0-9_]+)"?\s+FOREIGN\s+KEY\s*\((.*?)\)\s+REFERENCES\s+"?([A-Za-z0-9_]+)"?\s*\((.*?)\)""",
    )
  private val createIndexRegex =
    Regex("""(?is)CREATE\s+(UNIQUE\s+)?INDEX\s+"?([A-Za-z0-9_]+)"?\s+ON\s+"?([A-Za-z0-9_]+)"?\s*\((.*?)\)""")

  private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .joinToString("") { "%02x".format(it) }
}

data class MigrationSchemaSignature(
  val source: MigrationSource,
  val tables: Map<String, MigrationTableSignature>,
  val requiredObjects: Set<MigrationSchemaObject> = emptySet(),
) {
  fun canonical(): String =
    buildString {
      appendLine("source=$source")
      tables.toSortedMap().values.forEach { table ->
        appendLine("table=${table.name}")
        table.columns.toSortedMap().values.forEach { column ->
          appendLine("column=${column.name}:${column.type}:${column.nullable}:${column.default}")
        }
        appendLine("primaryKey=${table.primaryKey.sorted().joinToString(",")}")
        table.foreignKeys.sortedBy { it.canonical() }.forEach { appendLine("foreignKey=${it.canonical()}") }
        table.indexes
          .toSortedMap()
          .values
          .forEach { appendLine("index=${it.canonical()}") }
      }
      requiredObjects.sortedBy { it.canonical() }.forEach { appendLine("object=${it.canonical()}") }
    }
}

data class MigrationTableSignature(
  val name: String,
  val columns: Map<String, MigrationColumnSignature>,
  val primaryKey: Set<String>,
  val foreignKeys: Set<MigrationForeignKeySignature>,
  val indexes: Map<String, MigrationIndexSignature>,
)

data class MigrationColumnSignature(
  val name: String,
  val type: String,
  val nullable: Boolean,
  val default: String?,
)

data class MigrationForeignKeySignature(
  val columns: Set<String>,
  val referencedTable: String,
  val referencedColumns: Set<String>,
) {
  fun canonical(): String = "${columns.sorted().joinToString(",")}->$referencedTable(${referencedColumns.sorted().joinToString(",")})"
}

data class MigrationIndexSignature(
  val name: String,
  val unique: Boolean,
  val columns: Set<String>,
) {
  fun canonical(): String = "$name:$unique:${columns.sorted().joinToString(",")}"
}

data class MigrationSchemaObject(
  val type: String,
  val name: String,
) {
  fun canonical(): String = "$type:$name"
}

fun normalizeDefault(default: String?): String? {
  val value =
    default
      ?.trim()
      ?.takeIf { it.isNotBlank() }
      ?.let(::unwrapDefaultExpression)
      ?: return null

  return if (value.isQuotedSqlString()) {
    value.removeSurrounding("'").replace("''", "'")
  } else {
    value.lowercase().takeUnless { it == "null" }
  }
}

private fun unwrapDefaultExpression(default: String): String {
  var current = default.trim()
  do {
    val previous = current
    current = current.stripOuterParentheses().stripTrailingCast().trim()
  } while (current != previous)
  return current
}

private fun String.stripOuterParentheses(): String {
  var current = trim()
  while (current.length >= 2 && current.first() == '(' && current.last() == ')' && current.outerParenthesesWrapWholeExpression()) {
    current = current.substring(1, current.lastIndex).trim()
  }
  return current
}

private fun String.outerParenthesesWrapWholeExpression(): Boolean {
  var depth = 0
  var inSingleQuote = false
  var index = 0
  while (index <= lastIndex) {
    val char = this[index]
    when {
      char == '\'' && inSingleQuote && getOrNull(index + 1) == '\'' -> index++
      char == '\'' -> inSingleQuote = !inSingleQuote
      !inSingleQuote && char == '(' -> depth++
      !inSingleQuote && char == ')' -> {
        depth--
        if (depth == 0 && index != lastIndex) return false
        if (depth < 0) return false
      }
    }
    index++
  }
  return depth == 0 && !inSingleQuote
}

private val trailingCastRegex = Regex("""(?is)::\s*"?[A-Za-z_][A-Za-z0-9_]*"?(?:\s+"?[A-Za-z_][A-Za-z0-9_]*"?)*(?:\s*\[\s*])?\s*$""")

private fun String.stripTrailingCast(): String = replace(trailingCastRegex, "")

private fun String.isQuotedSqlString(): Boolean = length >= 2 && first() == '\'' && last() == '\''

data class MigrationRequest(
  val sourceMain: JdbcEndpoint,
  val sourceTasks: JdbcEndpoint,
  val target: JdbcEndpoint,
  val mode: MigrationMode,
  val resume: Boolean = false,
)

data class JdbcEndpoint(
  val url: String,
  val username: String? = null,
  val password: String? = null,
) {
  fun redacted(): JdbcEndpoint =
    copy(
      url = MigrationSecretRedactor.redact(url),
      username = username?.let { "<redacted>" },
      password = password?.let { "<redacted>" },
    )
}

enum class MigrationMode {
  HELP,
  PREFLIGHT,
  MIGRATE,
}

enum class MigrationPhase {
  ARGUMENTS,
  LOCKING,
  PREFLIGHT,
  VALIDATION,
  MIGRATION,
  REPORT,
}

enum class MigrationStatus {
  SUCCESS,
  FAILED,
}

enum class InventoryClassification {
  MIGRATED_APPLICATION_DATA,
  REBUILT_DERIVED_DATA,
  TRANSIENT_INTERNAL_EXCLUDED,
  SCHEMA_METADATA,
}

data class MigrationInventoryItem(
  val source: MigrationSource,
  val type: String,
  val name: String,
  val tableName: String?,
  val classification: InventoryClassification,
  val reason: String,
)

enum class MigrationSource {
  MAIN,
  TASKS,
}

data class TableCount(
  val source: MigrationSource,
  val table: String,
  val sourceRows: Long,
  val targetRows: Long?,
  val status: ValidationStatus,
  val reason: String? = null,
)

data class TableDigest(
  val source: MigrationSource,
  val table: String,
  val sourceDigest: String,
  val targetDigest: String?,
  val status: ValidationStatus,
  val reason: String? = null,
)

data class IntegrityCheck(
  val source: MigrationSource,
  val name: String,
  val status: ValidationStatus,
  val details: String,
)

enum class ValidationStatus {
  PASS,
  FAIL,
  SKIPPED,
}

data class SourceFingerprint(
  val value: String,
  val mainSchemaVersion: String,
  val tasksSchemaVersion: String,
  val tableDigests: List<TableDigest>,
)

data class MigrationReport(
  val status: MigrationStatus,
  val mode: MigrationMode,
  val phase: MigrationPhase,
  val startedAt: Instant,
  val duration: Duration,
  val sourceMain: JdbcEndpoint,
  val sourceTasks: JdbcEndpoint,
  val target: JdbcEndpoint,
  val currentSchemaPolicy: Map<String, String>,
  val inventory: List<MigrationInventoryItem> = emptyList(),
  val counts: List<TableCount> = emptyList(),
  val digests: List<TableDigest> = emptyList(),
  val integrityChecks: List<IntegrityCheck> = emptyList(),
  val sourceFingerprint: SourceFingerprint? = null,
  val resumable: Boolean = false,
  val failure: String? = null,
  val timings: Map<String, Long> = emptyMap(),
) {
  fun redacted(): MigrationReport =
    copy(
      sourceMain = sourceMain.redacted(),
      sourceTasks = sourceTasks.redacted(),
      target = target.redacted(),
      failure = failure?.let(MigrationSecretRedactor::redact),
    )
}

class MigrationPreflightException(
  val phase: MigrationPhase,
  override val message: String,
) : RuntimeException(message)
