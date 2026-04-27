package org.gotson.komga.infrastructure.migration

import java.sql.Connection

object MigrationValidators {
  fun validateRowCounts(
    sourceConnection: Connection,
    targetConnection: Connection,
    source: MigrationSource,
    inventory: List<MigrationInventoryItem>,
  ): List<TableCount> =
    applicationTables(inventory, source).map { item ->
      val sourceRows = MigrationJdbcInspector.rowCount(sourceConnection, item.name)
      val targetRows =
        if (MigrationJdbcInspector.tableExists(targetConnection, item.name))
          MigrationJdbcInspector.rowCount(targetConnection, item.name)
        else
          null
      TableCount(
        source = source,
        table = item.name,
        sourceRows = sourceRows,
        targetRows = targetRows,
        status = if (targetRows == sourceRows) ValidationStatus.PASS else ValidationStatus.FAIL,
        reason = if (targetRows == null) "Target table is missing." else null,
      )
    }

  fun validateDigests(
    sourceConnection: Connection,
    targetConnection: Connection,
    source: MigrationSource,
    inventory: List<MigrationInventoryItem>,
  ): List<TableDigest> =
    applicationTables(inventory, source).map { item ->
      val sourceDigest = MigrationJdbcInspector.tableDigest(sourceConnection, item.name)
      val targetDigest =
        if (MigrationJdbcInspector.tableExists(targetConnection, item.name))
          MigrationJdbcInspector.tableDigest(targetConnection, item.name)
        else
          null
      TableDigest(
        source = source,
        table = item.name,
        sourceDigest = sourceDigest,
        targetDigest = targetDigest,
        status = if (targetDigest == sourceDigest) ValidationStatus.PASS else ValidationStatus.FAIL,
        reason = if (targetDigest == null) "Target table is missing." else null,
      )
    }

  fun validateIntegrity(
    connection: Connection,
    source: MigrationSource,
    inventory: List<MigrationInventoryItem>,
  ): List<IntegrityCheck> =
    if (connection.metaData.url.startsWith("jdbc:sqlite:"))
      validateSqliteIntegrity(connection, source)
    else
      validateJdbcIntegrity(connection, source, inventory)

  private fun validateSqliteIntegrity(
    connection: Connection,
    source: MigrationSource,
  ): List<IntegrityCheck> =
    connection.createStatement().use { statement ->
      statement.executeQuery("PRAGMA foreign_key_check").use { result ->
        val failures =
          buildList {
            while (result.next()) add("${result.getString(1)} row ${result.getString(2)} references ${result.getString(3)}")
          }

        listOf(
          IntegrityCheck(
            source = source,
            name = "foreign_key_check",
            status = if (failures.isEmpty()) ValidationStatus.PASS else ValidationStatus.FAIL,
            details = failures.joinToString().ifBlank { "No orphaned foreign keys reported by SQLite." },
          ),
        )
      }
    }

  private fun validateJdbcIntegrity(
    connection: Connection,
    source: MigrationSource,
    inventory: List<MigrationInventoryItem>,
  ): List<IntegrityCheck> {
    val tableChecks =
      applicationTables(inventory, source).flatMap { table ->
        primaryKeyChecks(connection, source, table.name) + foreignKeyChecks(connection, source, table.name)
      }

    return tableChecks.ifEmpty {
      listOf(IntegrityCheck(source, "metadata", ValidationStatus.SKIPPED, "No JDBC primary or foreign keys were reported by metadata."))
    }
  }

  private fun primaryKeyChecks(
    connection: Connection,
    source: MigrationSource,
    table: String,
  ): List<IntegrityCheck> {
    val primaryKeyColumns =
      connection.metaData.getPrimaryKeys(null, null, table).use { result ->
        buildList {
          while (result.next()) add(result.getString("COLUMN_NAME"))
        }
      }

    if (primaryKeyColumns.isEmpty()) {
      return listOf(
        IntegrityCheck(
          source = source,
          name = "$table primary key uniqueness",
          status = ValidationStatus.PASS,
          details = "No primary key declared; duplicate primary key check is not applicable.",
        ),
      )
    }

    val quotedColumns = primaryKeyColumns.joinToString { MigrationJdbcInspector.quote(connection, it) }
    val duplicateCount =
      connection.createStatement().use { statement ->
        statement
          .executeQuery(
            """
            select count(*) from (
              select $quotedColumns
              from ${MigrationJdbcInspector.quote(connection, table)}
              group by $quotedColumns
              having count(*) > 1
            ) duplicates
            """.trimIndent(),
          ).use {
            it.next()
            it.getLong(1)
          }
      }

    return listOf(
      IntegrityCheck(
        source = source,
        name = "$table primary key uniqueness",
        status = if (duplicateCount == 0L) ValidationStatus.PASS else ValidationStatus.FAIL,
        details = if (duplicateCount == 0L) "No duplicate primary keys." else "$duplicateCount duplicate primary key groups found.",
      ),
    )
  }

  private fun foreignKeyChecks(
    connection: Connection,
    source: MigrationSource,
    table: String,
  ): List<IntegrityCheck> =
    connection.metaData.getImportedKeys(null, null, table).use { result ->
      buildList {
        while (result.next()) {
          val fkColumn = result.getString("FKCOLUMN_NAME")
          val pkTable = result.getString("PKTABLE_NAME")
          val pkColumn = result.getString("PKCOLUMN_NAME")
          val orphanCount = orphanCount(connection, table, fkColumn, pkTable, pkColumn)
          add(
            IntegrityCheck(
              source = source,
              name = "$table.$fkColumn references $pkTable.$pkColumn",
              status = if (orphanCount == 0L) ValidationStatus.PASS else ValidationStatus.FAIL,
              details = if (orphanCount == 0L) "No orphaned references." else "$orphanCount orphaned references found.",
            ),
          )
        }
      }
    }

  private fun orphanCount(
    connection: Connection,
    table: String,
    fkColumn: String,
    pkTable: String,
    pkColumn: String,
  ): Long =
    connection.createStatement().use { statement ->
      statement
        .executeQuery(
          """
          select count(*)
          from ${MigrationJdbcInspector.quote(connection, table)} child
          left join ${MigrationJdbcInspector.quote(connection, pkTable)} parent
            on child.${MigrationJdbcInspector.quote(connection, fkColumn)} = parent.${MigrationJdbcInspector.quote(connection, pkColumn)}
          where child.${MigrationJdbcInspector.quote(connection, fkColumn)} is not null
            and parent.${MigrationJdbcInspector.quote(connection, pkColumn)} is null
          """.trimIndent(),
        ).use {
          it.next()
          it.getLong(1)
        }
    }

  fun applicationTables(
    inventory: List<MigrationInventoryItem>,
    source: MigrationSource,
  ): List<MigrationInventoryItem> =
    inventory
      .filter {
        it.source == source &&
          it.type.equals("table", ignoreCase = true) &&
          it.classification == InventoryClassification.MIGRATED_APPLICATION_DATA
      }.sortedBy { it.name }
}
