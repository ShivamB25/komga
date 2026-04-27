package org.gotson.komga.infrastructure.migration

import org.gotson.komga.infrastructure.datasource.DatabaseBackend
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Base64

object MigrationJdbcInspector {
  fun connect(endpoint: JdbcEndpoint): Connection =
    if (endpoint.username != null)
      DriverManager.getConnection(endpoint.url, endpoint.username, endpoint.password)
    else
      DriverManager.getConnection(endpoint.url)

  fun verifySqliteOffline(connection: Connection) {
    acquireSqliteOfflineLock(connection).close()
  }

  fun acquireSqliteOfflineLock(connection: Connection): AutoCloseable {
    if (!isSqlite(connection)) return AutoCloseable {}

    try {
      connection.createStatement().use { statement ->
        statement.queryTimeout = 1
        statement.execute("PRAGMA busy_timeout = 100")
        statement.execute("BEGIN EXCLUSIVE")
      }
      return AutoCloseable {
        connection.createStatement().use { statement ->
          statement.execute("ROLLBACK")
        }
      }
    } catch (e: SQLException) {
      throw MigrationPreflightException(
        MigrationPhase.LOCKING,
        "Source SQLite database must be offline before migration; could not acquire an exclusive preflight lock: ${e.message}",
      )
    }
  }

  fun missingRequiredSchema(
    connection: Connection,
    expected: MigrationSchemaSignature,
  ): List<String> =
    buildList {
      val actual = schemaSignature(connection, expected.source, expected.tables.keys)
      expected.tables.forEach { (table, expectedTable) ->
        val actualTable = actual.tables[table]
        if (!tableExists(connection, table)) {
          add("$table table is missing")
          return@forEach
        }
        if (actualTable == null) {
          add("$table table could not be inspected")
          return@forEach
        }

        val actualColumns = actualTable.columns.mapKeys { it.key.uppercase() }
        val missingColumns = expectedTable.columns.keys.filterNot { it.uppercase() in actualColumns }
        if (missingColumns.isNotEmpty()) {
          add("$table missing required columns: ${missingColumns.joinToString()}")
        }
        expectedTable.columns.values.forEach { expectedColumn ->
          val actualColumn = actualColumns[expectedColumn.name.uppercase()] ?: return@forEach
          val columnProblems = mutableListOf<String>()
          if (actualColumn.type != expectedColumn.type) columnProblems += "type ${actualColumn.type} != ${expectedColumn.type}"
          if (actualColumn.nullable != expectedColumn.nullable) columnProblems += "nullable ${actualColumn.nullable} != ${expectedColumn.nullable}"
          if (actualColumn.default != expectedColumn.default) columnProblems += "default ${actualColumn.default} != ${expectedColumn.default}"
          if (columnProblems.isNotEmpty()) {
            add("$table.${expectedColumn.name} malformed column definition: ${columnProblems.joinToString(", ")}")
          }
        }

        if (expectedTable.primaryKey.isNotEmpty() && actualTable.primaryKey.map { it.uppercase() }.toSet() != expectedTable.primaryKey.map { it.uppercase() }.toSet()) {
          add("$table missing or malformed primary key: expected ${expectedTable.primaryKey.sorted().joinToString()}")
        }

        val missingForeignKeys =
          expectedTable.foreignKeys
            .filterNot { expectedForeignKey ->
              actualTable.foreignKeys.any { it.matches(expectedForeignKey) }
            }
        if (missingForeignKeys.isNotEmpty()) {
          add("$table missing required foreign keys: ${missingForeignKeys.joinToString { it.canonical() }}")
        }

        val actualIndexes = actualTable.indexes.mapKeys { it.key.uppercase() }
        val missingIndexes =
          expectedTable.indexes.values
            .filterNot { expectedIndex ->
              actualIndexes[expectedIndex.name.uppercase()]?.matches(expectedIndex) == true ||
                (
                  expectedIndex.isSqliteInlineUniqueConstraint() &&
                    actualTable.indexes.values.any { it.matches(expectedIndex) }
                )
            }
        if (missingIndexes.isNotEmpty()) {
          add("$table missing required indexes: ${missingIndexes.joinToString { it.name }}")
        }
      }
    }

  fun verifyPostgresqlCurrentObjects(connection: Connection) {
    if (!isPostgresql(connection)) return

    val problems = mutableListOf<String>()
    try {
      connection.createStatement().use { statement ->
        statement.executeQuery("""select "UDF_STRIP_ACCENTS"('École')""").use { result ->
          result.next()
          if (result.getString(1) != "Ecole") problems += """UDF_STRIP_ACCENTS('École') returned ${result.getString(1)} instead of Ecole"""
        }
        statement.executeQuery("""select "UDF_STRIP_ACCENTS"(null)""").use { result ->
          result.next()
          if (result.getString(1) != "") problems += "UDF_STRIP_ACCENTS(null) did not coalesce to an empty string"
        }
        statement.executeQuery("""select 'é' collate "COLLATION_UNICODE_3"""").use { result ->
          result.next()
        }
        statement.executeQuery("""select 'A' collate "NOCASE"""").use { result ->
          result.next()
        }
      }
    } catch (e: SQLException) {
      throw MigrationPreflightException(
        MigrationPhase.PREFLIGHT,
        "target PostgreSQL main schema is missing required current database objects: ${e.message}",
      )
    }

    problems += postgresqlFunctionProblems(connection)
    problems += postgresqlCollationProblems(connection, "COLLATION_UNICODE_3", "i", setOf("und-u-ks-level3-kk-true", "und-u-kk-ks-level3"), false)
    problems += postgresqlCollationProblems(connection, "NOCASE", "i", setOf("und-u-ks-level2"), false)
    if (problems.isNotEmpty()) {
      throw MigrationPreflightException(
        MigrationPhase.PREFLIGHT,
        "target PostgreSQL main schema has malformed required current database objects: ${problems.joinToString("; ")}",
      )
    }
  }

  fun verifyTargetMigrationLock(connection: Connection) {
    if (!isPostgresql(connection)) return

    try {
      connection.createStatement().use { statement ->
        statement
          .executeQuery("select pg_try_advisory_lock(hashtext('komga_migration'))")
          .use { result ->
            result.next()
            if (!result.getBoolean(1)) {
              throw MigrationPreflightException(
                MigrationPhase.LOCKING,
                "Target PostgreSQL database is already locked by another migration attempt; aborting before writes.",
              )
            }
          }
      }
    } catch (e: MigrationPreflightException) {
      throw e
    } catch (e: SQLException) {
      throw MigrationPreflightException(
        MigrationPhase.LOCKING,
        "Target PostgreSQL database must be reachable and lockable before migration: ${e.message}",
      )
    }
  }

  fun currentFlywayVersion(
    connection: Connection,
    historyTable: String,
  ): String? {
    if (!tableExists(connection, historyTable)) return null

    return connection
      .createStatement()
      .use { statement ->
        val successPredicate =
          if (isSqlite(connection))
            """"success" = 1"""
          else
            """"success" = true"""
        statement
          .executeQuery(
            """select "version" from ${quote(connection, historyTable)} where $successPredicate order by "installed_rank" desc limit 1""",
          ).use { result ->
            if (result.next()) result.getString(1) else null
          }
      }
  }

  fun tableExists(
    connection: Connection,
    table: String,
  ): Boolean =
    connection.metaData.getTables(null, null, table, arrayOf("TABLE")).use { result ->
      if (result.next()) true else tableExistsCaseInsensitive(connection, table)
    }

  private fun tableExistsCaseInsensitive(
    connection: Connection,
    table: String,
  ): Boolean =
    connection.metaData.getTables(null, null, null, arrayOf("TABLE")).use { result ->
      generateSequence { if (result.next()) result.getString("TABLE_NAME") else null }
        .any { it.equals(table, ignoreCase = true) }
    }

  fun inventory(
    connection: Connection,
    source: MigrationSource,
  ): List<MigrationInventoryItem> = if (isSqlite(connection)) sqliteInventory(connection, source) else jdbcInventory(connection, source)

  fun schemaSignature(
    connection: Connection,
    source: MigrationSource,
    requiredTables: Set<String>? = null,
  ): MigrationSchemaSignature {
    val tableNames =
      requiredTables
        ?: connection.metaData.getTables(null, null, null, arrayOf("TABLE")).use { result ->
          buildSet {
            while (result.next()) {
              val table = result.getString("TABLE_NAME")
              val upper = table.uppercase()
              if (!upper.startsWith("SQLITE_") && !upper.startsWith("FLYWAY_SCHEMA_HISTORY")) add(table)
            }
          }
        }

    return MigrationSchemaSignature(
      source = source,
      tables =
        tableNames
          .associate { expectedName ->
            val actualName = resolveTableName(connection, expectedName)
            expectedName to
              MigrationTableSignature(
                name = expectedName,
                columns = actualName?.let { columnSignatures(connection, it) }.orEmpty(),
                primaryKey = actualName?.let { primaryKey(connection, it) }.orEmpty(),
                foreignKeys = actualName?.let { foreignKeys(connection, it) }.orEmpty(),
                indexes = actualName?.let { indexSignatures(connection, it) }.orEmpty(),
              )
          }.toSortedMap(),
    )
  }

  private fun sqliteInventory(
    connection: Connection,
    source: MigrationSource,
  ): List<MigrationInventoryItem> =
    connection
      .createStatement()
      .use { statement ->
        statement
          .executeQuery(
            """
            select type, name, tbl_name
            from sqlite_schema
            where name not like 'sqlite_autoindex%'
            order by type, name
            """.trimIndent(),
          ).use { result ->
            buildList {
              while (result.next()) {
                val type = result.getString("type")
                val name = result.getString("name")
                val tableName = result.getString("tbl_name")
                add(classify(source, type, name, tableName))
              }
            }
          }
      }

  private fun jdbcInventory(
    connection: Connection,
    source: MigrationSource,
  ): List<MigrationInventoryItem> {
    val tables =
      connection.metaData.getTables(null, null, null, arrayOf("TABLE", "VIEW")).use { result ->
        buildList {
          while (result.next()) {
            val name = result.getString("TABLE_NAME")
            val type = result.getString("TABLE_TYPE").lowercase()
            add(classify(source, type, name, name))
          }
        }
      }

    val indexes =
      tables
        .filter { it.classification == InventoryClassification.MIGRATED_APPLICATION_DATA }
        .flatMap { table ->
          connection.metaData.getIndexInfo(null, null, table.name, false, false).use { result ->
            buildList {
              while (result.next()) {
                val indexName = result.getString("INDEX_NAME") ?: continue
                add(classify(source, "index", indexName, table.name))
              }
            }
          }
        }

    return (tables + indexes).distinctBy { "${it.type}:${it.name}" }.sortedWith(compareBy({ it.type }, { it.name }))
  }

  private fun classify(
    source: MigrationSource,
    type: String,
    name: String,
    tableName: String?,
  ): MigrationInventoryItem {
    val upperName = name.uppercase()
    val classification =
      when {
        upperName == "FLYWAY_SCHEMA_HISTORY" || upperName.startsWith("SQLITE_") ->
          InventoryClassification.SCHEMA_METADATA
        type.equals("index", ignoreCase = true) || type.equals("trigger", ignoreCase = true) || type.equals("view", ignoreCase = true) ->
          InventoryClassification.SCHEMA_METADATA
        upperName.startsWith("SPRING_SESSION") || upperName.startsWith("TEMP_") ->
          InventoryClassification.TRANSIENT_INTERNAL_EXCLUDED
        upperName.contains("SEARCH") || upperName.contains("LUCENE") ->
          InventoryClassification.REBUILT_DERIVED_DATA
        else ->
          InventoryClassification.MIGRATED_APPLICATION_DATA
      }

    val reason =
      when (classification) {
        InventoryClassification.MIGRATED_APPLICATION_DATA -> "Application table copied and validated by row count, digest, and integrity checks."
        InventoryClassification.REBUILT_DERIVED_DATA -> "Derived data is rebuilt or validated after migration rather than copied blindly."
        InventoryClassification.TRANSIENT_INTERNAL_EXCLUDED -> "Transient/internal runtime state is intentionally excluded from user-data migration."
        InventoryClassification.SCHEMA_METADATA -> "Schema metadata is recreated by the target schema migration history."
      }

    return MigrationInventoryItem(source, type, name, tableName, classification, reason)
  }

  fun rowCount(
    connection: Connection,
    table: String,
  ): Long =
    connection.createStatement().use { statement ->
      statement.executeQuery("select count(*) from ${quote(connection, table)}").use {
        it.next()
        it.getLong(1)
      }
    }

  fun tableDigest(
    connection: Connection,
    table: String,
  ): String {
    val columns = columns(connection, table).sorted()
    if (columns.isEmpty()) return sha256Hex("table:$table:no-columns".toByteArray())
    val columnTypes = columnTypes(connection, table)

    val rowDigests =
      connection.createStatement().use { statement ->
        statement.executeQuery("select ${columns.joinToString { quote(connection, it) }} from ${quote(connection, table)}").use { result ->
          buildList {
            while (result.next()) add(rowDigest(result, columns, columnTypes))
          }
        }
      }

    return sha256Hex(rowDigests.sorted().joinToString(separator = "\n").toByteArray())
  }

  fun columns(
    connection: Connection,
    table: String,
  ): List<String> =
    connection.metaData
      .getColumns(null, null, table, null)
      .use { result ->
        buildList {
          while (result.next()) add(result.getString("COLUMN_NAME"))
        }
      }.ifEmpty {
        connection.metaData.getColumns(null, null, table.uppercase(), null).use { result ->
          buildList {
            while (result.next()) add(result.getString("COLUMN_NAME"))
          }
        }
      }

  private fun columnSignatures(
    connection: Connection,
    table: String,
  ): Map<String, MigrationColumnSignature> =
    connection.metaData
      .getColumns(null, null, table, null)
      .use { result ->
        buildMap {
          while (result.next()) {
            val name = result.getString("COLUMN_NAME")
            put(
              name,
              MigrationColumnSignature(
                name = name,
                type = normalizeJdbcType(result.getInt("DATA_TYPE"), result.getString("TYPE_NAME")),
                nullable = result.getInt("NULLABLE") != java.sql.DatabaseMetaData.columnNoNulls,
                default = normalizeDefault(result.getString("COLUMN_DEF")),
              ),
            )
          }
        }
      }.ifEmpty {
        connection.metaData.getColumns(null, null, table.uppercase(), null).use { result ->
          buildMap {
            while (result.next()) {
              val name = result.getString("COLUMN_NAME")
              put(
                name,
                MigrationColumnSignature(
                  name = name,
                  type = normalizeJdbcType(result.getInt("DATA_TYPE"), result.getString("TYPE_NAME")),
                  nullable = result.getInt("NULLABLE") != java.sql.DatabaseMetaData.columnNoNulls,
                  default = normalizeDefault(result.getString("COLUMN_DEF")),
                ),
              )
            }
          }
        }
      }

  private fun primaryKey(
    connection: Connection,
    table: String,
  ): Set<String> =
    connection.metaData.getPrimaryKeys(null, null, table).use { result ->
      buildList {
        while (result.next()) add(result.getShort("KEY_SEQ").toInt() to result.getString("COLUMN_NAME"))
      }.sortedBy { it.first }
        .map { it.second }
        .toSortedSet()
    }

  private fun foreignKeys(
    connection: Connection,
    table: String,
  ): Set<MigrationForeignKeySignature> =
    connection.metaData.getImportedKeys(null, null, table).use { result ->
      data class ForeignKeyColumn(
        val sequence: Int,
        val column: String,
        val referencedTable: String,
        val referencedColumn: String,
      )

      buildMap<String, MutableList<ForeignKeyColumn>> {
        while (result.next()) {
          val keyName =
            result.getString("FK_NAME")
              ?: "${result.getString("FKTABLE_NAME")}_${result.getString("PKTABLE_NAME")}_${result.getString("UPDATE_RULE")}_${result.getString("DELETE_RULE")}"
          getOrPut(keyName) { mutableListOf() } +=
            ForeignKeyColumn(
              sequence = result.getShort("KEY_SEQ").toInt(),
              column = result.getString("FKCOLUMN_NAME"),
              referencedTable = result.getString("PKTABLE_NAME"),
              referencedColumn = result.getString("PKCOLUMN_NAME"),
            )
        }
      }.map { (_, columns) ->
        val sorted = columns.sortedBy { it.sequence }
        MigrationForeignKeySignature(
          columns = sorted.map { it.column }.toSortedSet(),
          referencedTable = sorted.firstOrNull()?.referencedTable.orEmpty(),
          referencedColumns = sorted.map { it.referencedColumn }.toSortedSet(),
        )
      }.toSet()
    }

  private fun indexSignatures(
    connection: Connection,
    table: String,
  ): Map<String, MigrationIndexSignature> {
    if (isSqlite(connection)) return sqliteIndexSignatures(connection, table)

    val uniqueness = mutableMapOf<String, Boolean>()
    val columns =
      connection.metaData.getIndexInfo(null, null, table, false, false).use { result ->
        buildMap<String, MutableList<Pair<Int, String>>> {
          while (result.next()) {
            val name = result.getString("INDEX_NAME") ?: continue
            if (name.startsWith("sqlite_autoindex", ignoreCase = true)) continue
            val column = result.getString("COLUMN_NAME") ?: continue
            getOrPut(name) { mutableListOf() } += result.getShort("ORDINAL_POSITION").toInt() to column
            uniqueness[name] = !result.getBoolean("NON_UNIQUE")
          }
        }
      }
    return columns
      .mapValues { (name, indexColumns) ->
        MigrationIndexSignature(
          name = name,
          unique = uniqueness[name] == true,
          columns = indexColumns.sortedBy { it.first }.map { it.second }.toSortedSet(),
        )
      }.toSortedMap()
  }

  private fun sqliteIndexSignatures(
    connection: Connection,
    table: String,
  ): Map<String, MigrationIndexSignature> =
    connection
      .createStatement()
      .use { statement ->
        statement.executeQuery("""PRAGMA index_list("${table.replace("\"", "\"\"")}")""").use { indexes ->
          buildMap {
            while (indexes.next()) {
              val name = indexes.getString("name") ?: continue
              val unique = indexes.getInt("unique") == 1
              val origin = indexes.getString("origin")
              if (origin.equals("pk", ignoreCase = true)) continue
              val columns = sqliteIndexColumns(connection, name)
              val signatureName =
                if (name.startsWith("sqlite_autoindex", ignoreCase = true) && unique && origin.equals("u", ignoreCase = true))
                  sqliteInlineUniqueConstraintName(table, columns)
                else
                  name
              put(
                signatureName,
                MigrationIndexSignature(
                  name = signatureName,
                  unique = unique,
                  columns = columns.toSortedSet(),
                ),
              )
            }
          }.toSortedMap()
        }
      }

  private fun sqliteIndexColumns(
    connection: Connection,
    index: String,
  ): List<String> =
    connection
      .createStatement()
      .use { statement ->
        statement.executeQuery("""PRAGMA index_info("${index.replace("\"", "\"\"")}")""").use { columns ->
          buildList {
            while (columns.next()) add(columns.getInt("seqno") to columns.getString("name"))
          }.sortedBy { it.first }.map { it.second }
        }
      }

  private fun sqliteInlineUniqueConstraintName(
    table: String,
    columns: List<String>,
  ): String = "$SQLITE_INLINE_UNIQUE_PREFIX${table.uppercase()}__${columns.joinToString("__") { it.uppercase() }}"

  private fun resolveTableName(
    connection: Connection,
    table: String,
  ): String? =
    connection.metaData.getTables(null, null, table, arrayOf("TABLE")).use { result ->
      if (result.next()) result.getString("TABLE_NAME") else null
    } ?: connection.metaData.getTables(null, null, null, arrayOf("TABLE")).use { result ->
      generateSequence { if (result.next()) result.getString("TABLE_NAME") else null }
        .firstOrNull { it.equals(table, ignoreCase = true) }
    }

  private fun normalizeJdbcType(
    jdbcType: Int,
    typeName: String?,
  ): String {
    val type = typeName.orEmpty().lowercase()
    return when {
      jdbcType in setOf(Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR, Types.CLOB) -> "text"
      jdbcType in setOf(Types.INTEGER, Types.SMALLINT, Types.TINYINT) -> "integer"
      jdbcType == Types.BIGINT -> "bigint"
      jdbcType in setOf(Types.BOOLEAN, Types.BIT) || type.contains("bool") -> "boolean"
      jdbcType in setOf(Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE) || type.contains("timestamp") || type.contains("datetime") -> "timestamp"
      jdbcType == Types.DATE || type == "date" -> "date"
      jdbcType in setOf(Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB) || type.contains("bytea") || type.contains("blob") -> "blob"
      jdbcType in setOf(Types.DOUBLE, Types.FLOAT, Types.REAL, Types.NUMERIC, Types.DECIMAL) -> "numeric"
      type.startsWith("int") -> "integer"
      type.startsWith("varchar") || type.startsWith("text") -> "text"
      else -> type.ifBlank { jdbcType.toString() }
    }
  }

  private fun rowDigest(
    result: ResultSet,
    columns: List<String>,
    columnTypes: Map<String, ColumnType>,
  ): String =
    sha256Hex(
      columns
        .joinToString(separator = "\u001f") { column ->
          "$column=${normalizeValue(result.getObject(column), columnTypes[column])}"
        }.toByteArray(),
    )

  private fun normalizeValue(
    value: Any?,
    columnType: ColumnType?,
  ): String =
    when (value) {
      null -> "<null>"
      is ByteArray -> Base64.getEncoder().encodeToString(value)
      is Boolean -> value.toString()
      is Number ->
        if (columnType?.isBoolean == true && (value.toInt() == 0 || value.toInt() == 1))
          (value.toInt() != 0).toString()
        else
          value.toString()
      is java.sql.Timestamp -> normalizeDateTime(value.toLocalDateTime())
      is java.sql.Date -> value.toLocalDate().toString()
      else ->
        if (columnType?.isTemporal == true)
          normalizeTemporalString(value.toString(), columnType)
        else
          value.toString()
    }

  private fun normalizeTemporalString(
    value: String,
    columnType: ColumnType,
  ): String =
    when {
      columnType.isDateOnly -> LocalDate.parse(value.take(10)).toString()
      else -> normalizeDateTime(LocalDateTime.parse(value.replace(' ', 'T').removeSuffix("Z")))
    }

  private fun normalizeDateTime(value: LocalDateTime): String = value.toString()

  private fun columnTypes(
    connection: Connection,
    table: String,
  ): Map<String, ColumnType> =
    connection.metaData
      .getColumns(null, null, table, null)
      .use { result ->
        buildMap {
          while (result.next()) put(result.getString("COLUMN_NAME"), ColumnType(result.getInt("DATA_TYPE"), result.getString("TYPE_NAME")))
        }
      }.ifEmpty {
        connection.metaData.getColumns(null, null, table.uppercase(), null).use { result ->
          buildMap {
            while (result.next()) put(result.getString("COLUMN_NAME"), ColumnType(result.getInt("DATA_TYPE"), result.getString("TYPE_NAME")))
          }
        }
      }

  fun quote(
    connection: Connection,
    identifier: String,
  ): String {
    val quote = connection.metaData.identifierQuoteString?.takeIf { it.isNotBlank() } ?: "\""
    return quote + identifier.replace(quote, quote + quote) + quote
  }

  fun isSqlite(connection: Connection): Boolean = connection.metaData.url.startsWith("jdbc:sqlite:")

  fun isPostgresql(connection: Connection): Boolean = connection.metaData.url.startsWith("jdbc:postgresql:")

  fun backend(connection: Connection): DatabaseBackend = if (isPostgresql(connection)) DatabaseBackend.POSTGRESQL else DatabaseBackend.SQLITE

  private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .joinToString("") { "%02x".format(it) }

  private fun postgresqlFunctionProblems(connection: Connection): List<String> {
    val sql =
      """
      select l.lanname,
             p.provolatile,
             p.proparallel,
             p.prorettype::regtype::text,
             pg_get_functiondef(p.oid)
      from pg_proc p
      join pg_namespace n on n.oid = p.pronamespace
      join pg_language l on l.oid = p.prolang
      where n.nspname = current_schema()
        and p.proname = 'UDF_STRIP_ACCENTS'
        and pg_get_function_arguments(p.oid) = 'value text'
      """.trimIndent()

    return connection.prepareStatement(sql).use { statement ->
      statement.executeQuery().use { result ->
        if (!result.next()) return listOf("UDF_STRIP_ACCENTS(value text) definition is missing")

        buildList {
          if (result.getString("lanname") != "sql") add("UDF_STRIP_ACCENTS language is ${result.getString("lanname")} instead of sql")
          if (result.getString("provolatile") != "i") add("UDF_STRIP_ACCENTS volatility is ${result.getString("provolatile")} instead of immutable")
          if (result.getString("proparallel") != "s") add("UDF_STRIP_ACCENTS parallel safety is ${result.getString("proparallel")} instead of safe")
          if (result.getString("prorettype") != "text") add("UDF_STRIP_ACCENTS return type is ${result.getString("prorettype")} instead of text")
          val definition = result.getString("pg_get_functiondef").lowercase()
          if (!definition.contains("unaccent") || !definition.contains("coalesce")) add("UDF_STRIP_ACCENTS definition does not call unaccent(coalesce(...))")
        }
      }
    }
  }

  private fun postgresqlCollationProblems(
    connection: Connection,
    name: String,
    expectedProvider: String,
    expectedLocales: Set<String>,
    expectedDeterministic: Boolean,
  ): List<String> {
    val sql =
      """
      select c.collprovider::text,
             coalesce(c.colliculocale, c.collcollate) as locale,
             c.collisdeterministic
      from pg_collation c
      join pg_namespace n on n.oid = c.collnamespace
      where n.nspname = current_schema()
        and c.collname = ?
      """.trimIndent()

    return connection.prepareStatement(sql).use { statement ->
      statement.setString(1, name)
      statement.executeQuery().use { result ->
        if (!result.next()) return listOf("$name collation definition is missing")

        buildList {
          if (result.getString("collprovider") != expectedProvider) {
            add("$name provider is ${result.getString("collprovider")} instead of $expectedProvider")
          }
          if (result.getString("locale") !in expectedLocales) {
            add("$name locale is ${result.getString("locale")} instead of one of ${expectedLocales.sorted().joinToString()}")
          }
          if (result.getBoolean("collisdeterministic") != expectedDeterministic) {
            add("$name deterministic is ${result.getBoolean("collisdeterministic")} instead of $expectedDeterministic")
          }
        }
      }
    }
  }

  private data class ColumnType(
    val jdbcType: Int,
    val typeName: String?,
  ) {
    val isBoolean: Boolean = jdbcType == Types.BOOLEAN || jdbcType == Types.BIT || typeName?.contains("bool", ignoreCase = true) == true
    val isDateOnly: Boolean = jdbcType == Types.DATE || typeName.equals("date", ignoreCase = true)
    val isTemporal: Boolean =
      isDateOnly ||
        jdbcType == Types.TIMESTAMP ||
        jdbcType == Types.TIMESTAMP_WITH_TIMEZONE ||
        typeName?.contains("timestamp", ignoreCase = true) == true ||
        typeName?.contains("datetime", ignoreCase = true) == true
  }

  private fun MigrationForeignKeySignature.matches(expected: MigrationForeignKeySignature): Boolean =
    columns.map { it.uppercase() }.toSet() == expected.columns.map { it.uppercase() }.toSet() &&
      referencedTable.equals(expected.referencedTable, ignoreCase = true) &&
      referencedColumns.map { it.uppercase() }.toSet() == expected.referencedColumns.map { it.uppercase() }.toSet()

  private fun MigrationIndexSignature.matches(expected: MigrationIndexSignature): Boolean =
    unique == expected.unique &&
      columns.map { it.uppercase() }.toSet() == expected.columns.map { it.uppercase() }.toSet()

  private fun MigrationIndexSignature.isSqliteInlineUniqueConstraint(): Boolean = name.startsWith(SQLITE_INLINE_UNIQUE_PREFIX)

  private const val SQLITE_INLINE_UNIQUE_PREFIX = "inline_unique__"
}
