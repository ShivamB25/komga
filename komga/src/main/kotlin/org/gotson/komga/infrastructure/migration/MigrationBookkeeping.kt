package org.gotson.komga.infrastructure.migration

import java.sql.Connection
import java.time.Instant

object MigrationBookkeeping {
  const val TABLE = "KOMGA_MIGRATION_ATTEMPT"

  fun ensureTable(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.execute(
        """
        create table if not exists ${MigrationJdbcInspector.quote(connection, TABLE)} (
          ${MigrationJdbcInspector.quote(connection, "ID")} varchar primary key,
          ${MigrationJdbcInspector.quote(connection, "SOURCE_FINGERPRINT")} varchar not null,
          ${MigrationJdbcInspector.quote(connection, "STATUS")} varchar not null,
          ${MigrationJdbcInspector.quote(connection, "STARTED_AT")} varchar not null,
          ${MigrationJdbcInspector.quote(connection, "UPDATED_AT")} varchar not null,
          ${MigrationJdbcInspector.quote(connection, "REPORT")} text
        )
        """.trimIndent(),
      )
    }
  }

  fun findIncompleteFingerprint(connection: Connection): String? {
    if (!MigrationJdbcInspector.tableExists(connection, TABLE)) return null

    return connection.createStatement().use { statement ->
      statement
        .executeQuery(
          """
          select ${MigrationJdbcInspector.quote(connection, "SOURCE_FINGERPRINT")}
          from ${MigrationJdbcInspector.quote(connection, TABLE)}
          where ${MigrationJdbcInspector.quote(connection, "STATUS")} = 'INCOMPLETE'
          order by ${MigrationJdbcInspector.quote(connection, "UPDATED_AT")} desc
          limit 1
          """.trimIndent(),
        ).use { result ->
          if (result.next()) result.getString(1) else null
        }
    }
  }

  fun recordIncomplete(
    connection: Connection,
    fingerprint: String,
    report: String,
  ) {
    ensureTable(connection)
    val now = Instant.now().toString()
    connection
      .prepareStatement(
        """
        insert into ${MigrationJdbcInspector.quote(connection, TABLE)} (
          ${MigrationJdbcInspector.quote(connection, "ID")},
          ${MigrationJdbcInspector.quote(connection, "SOURCE_FINGERPRINT")},
          ${MigrationJdbcInspector.quote(connection, "STATUS")},
          ${MigrationJdbcInspector.quote(connection, "STARTED_AT")},
          ${MigrationJdbcInspector.quote(connection, "UPDATED_AT")},
          ${MigrationJdbcInspector.quote(connection, "REPORT")}
        ) values (?, ?, 'INCOMPLETE', ?, ?, ?)
        """.trimIndent(),
      ).use { statement ->
        statement.setString(1, "attempt-$now")
        statement.setString(2, fingerprint)
        statement.setString(3, now)
        statement.setString(4, now)
        statement.setString(5, MigrationSecretRedactor.redact(report))
        statement.executeUpdate()
      }
  }

  fun recordCompleted(
    connection: Connection,
    fingerprint: String,
    report: String,
  ) {
    ensureTable(connection)
    val now = Instant.now().toString()
    val updated =
      connection
        .prepareStatement(
          """
          update ${MigrationJdbcInspector.quote(connection, TABLE)}
          set ${MigrationJdbcInspector.quote(connection, "STATUS")} = 'COMPLETED',
              ${MigrationJdbcInspector.quote(connection, "UPDATED_AT")} = ?,
              ${MigrationJdbcInspector.quote(connection, "REPORT")} = ?
          where ${MigrationJdbcInspector.quote(connection, "SOURCE_FINGERPRINT")} = ?
            and ${MigrationJdbcInspector.quote(connection, "STATUS")} = 'INCOMPLETE'
          """.trimIndent(),
        ).use { statement ->
          statement.setString(1, now)
          statement.setString(2, MigrationSecretRedactor.redact(report))
          statement.setString(3, fingerprint)
          statement.executeUpdate()
        }

    if (updated == 0) {
      connection
        .prepareStatement(
          """
          insert into ${MigrationJdbcInspector.quote(connection, TABLE)} (
            ${MigrationJdbcInspector.quote(connection, "ID")},
            ${MigrationJdbcInspector.quote(connection, "SOURCE_FINGERPRINT")},
            ${MigrationJdbcInspector.quote(connection, "STATUS")},
            ${MigrationJdbcInspector.quote(connection, "STARTED_AT")},
            ${MigrationJdbcInspector.quote(connection, "UPDATED_AT")},
            ${MigrationJdbcInspector.quote(connection, "REPORT")}
          ) values (?, ?, 'COMPLETED', ?, ?, ?)
          """.trimIndent(),
        ).use { statement ->
          statement.setString(1, "attempt-$now")
          statement.setString(2, fingerprint)
          statement.setString(3, now)
          statement.setString(4, now)
          statement.setString(5, MigrationSecretRedactor.redact(report))
          statement.executeUpdate()
        }
    }
  }
}
