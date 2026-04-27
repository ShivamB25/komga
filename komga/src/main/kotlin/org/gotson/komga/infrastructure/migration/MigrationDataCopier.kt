package org.gotson.komga.infrastructure.migration

import java.sql.Connection
import java.sql.JDBCType
import java.sql.Types
import java.time.LocalDate
import java.time.LocalDateTime

object MigrationDataCopier {
  fun copy(
    sourceConnection: Connection,
    targetConnection: Connection,
    source: MigrationSource,
    inventory: List<MigrationInventoryItem>,
  ) {
    val tables = orderedTables(targetConnection, MigrationValidators.applicationTables(inventory, source).map { it.name })
    tables.forEach { table -> copyTable(sourceConnection, targetConnection, table) }
  }

  private fun copyTable(
    sourceConnection: Connection,
    targetConnection: Connection,
    table: String,
  ) {
    if (!MigrationJdbcInspector.tableExists(targetConnection, table)) {
      throw MigrationPreflightException(MigrationPhase.MIGRATION, "Target table $table is missing.")
    }

    val sourceColumns = MigrationJdbcInspector.columns(sourceConnection, table)
    val targetColumns = MigrationJdbcInspector.columns(targetConnection, table)
    val copiedColumns = sourceColumns.filter { sourceColumn -> targetColumns.any { it == sourceColumn } }

    if (copiedColumns.isEmpty()) return

    val targetTypes = targetColumnTypes(targetConnection, table)
    val selectSql =
      "select ${copiedColumns.joinToString { MigrationJdbcInspector.quote(sourceConnection, it) }} " +
        "from ${MigrationJdbcInspector.quote(sourceConnection, table)}"
    val insertSql =
      "insert into ${MigrationJdbcInspector.quote(targetConnection, table)} " +
        "(${copiedColumns.joinToString { MigrationJdbcInspector.quote(targetConnection, it) }}) " +
        "values (${copiedColumns.joinToString { "?" }})"

    sourceConnection.createStatement().use { select ->
      select.executeQuery(selectSql).use { result ->
        targetConnection.prepareStatement(insertSql).use { insert ->
          while (result.next()) {
            copiedColumns.forEachIndexed { index, column ->
              val value = result.getObject(column)
              setValue(insert, index + 1, value, targetTypes[column])
            }
            insert.addBatch()
          }
          insert.executeBatch()
        }
      }
    }
  }

  private fun setValue(
    statement: java.sql.PreparedStatement,
    index: Int,
    value: Any?,
    targetType: Int?,
  ) {
    if (value == null) {
      statement.setObject(index, null)
      return
    }

    when (targetType) {
      Types.BOOLEAN, Types.BIT -> statement.setBoolean(index, toBoolean(value))
      Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> statement.setBytes(index, value as ByteArray)
      Types.DATE -> statement.setDate(index, java.sql.Date.valueOf(toLocalDate(value)))
      Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> statement.setTimestamp(index, java.sql.Timestamp.valueOf(toLocalDateTime(value)))
      else -> statement.setObject(index, value)
    }
  }

  private fun toBoolean(value: Any): Boolean =
    when (value) {
      is Boolean -> value
      is Number -> value.toInt() != 0
      is String -> value == "1" || value.equals("true", ignoreCase = true)
      else -> error("Cannot convert ${value::class.simpleName} to ${JDBCType.BOOLEAN.name}")
    }

  private fun toLocalDate(value: Any): LocalDate =
    when (value) {
      is java.sql.Date -> value.toLocalDate()
      is java.sql.Timestamp -> value.toLocalDateTime().toLocalDate()
      is String -> LocalDate.parse(value.take(10))
      else -> error("Cannot convert ${value::class.simpleName} to ${JDBCType.DATE.name}")
    }

  private fun toLocalDateTime(value: Any): LocalDateTime =
    when (value) {
      is java.sql.Timestamp -> value.toLocalDateTime()
      is java.sql.Date -> value.toLocalDate().atStartOfDay()
      is String -> LocalDateTime.parse(value.replace(' ', 'T').removeSuffix("Z"))
      else -> error("Cannot convert ${value::class.simpleName} to ${JDBCType.TIMESTAMP.name}")
    }

  private fun orderedTables(
    targetConnection: Connection,
    tables: List<String>,
  ): List<String> {
    val tableSet = tables.toSet()
    val dependencies =
      tableSet.associateWith { table ->
        importedTables(targetConnection, table)
          .filter { it in tableSet && it != table }
          .toSet()
      }

    val ordered = mutableListOf<String>()
    val remaining = dependencies.toMutableMap()
    while (remaining.isNotEmpty()) {
      val ready =
        remaining
          .filterValues { deps -> deps.all { it in ordered } }
          .keys
          .sorted()

      if (ready.isEmpty()) {
        ordered += remaining.keys.sorted()
        break
      }

      ordered += ready
      ready.forEach(remaining::remove)
    }

    return ordered
  }

  private fun importedTables(
    connection: Connection,
    table: String,
  ): List<String> =
    connection.metaData.getImportedKeys(null, null, table).use { result ->
      buildList {
        while (result.next()) add(result.getString("PKTABLE_NAME"))
      }
    }

  private fun targetColumnTypes(
    connection: Connection,
    table: String,
  ): Map<String, Int> =
    connection.metaData
      .getColumns(null, null, table, null)
      .use { result ->
        buildMap {
          while (result.next()) put(result.getString("COLUMN_NAME"), result.getInt("DATA_TYPE"))
        }
      }.ifEmpty {
        connection.metaData.getColumns(null, null, table.uppercase(), null).use { result ->
          buildMap {
            while (result.next()) put(result.getString("COLUMN_NAME"), result.getInt("DATA_TYPE"))
          }
        }
      }
}
