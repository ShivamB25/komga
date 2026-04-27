package org.gotson.komga.infrastructure.datasource

import org.jooq.SQLDialect

enum class DatabaseBackend(
  val jooqDialect: SQLDialect,
  private val flywayVendor: String,
) {
  SQLITE(SQLDialect.SQLITE, "sqlite"),
  POSTGRESQL(SQLDialect.POSTGRES, "postgresql"),
  ;

  fun flywayLocation(scope: DatabaseScope): String =
    when (scope) {
      DatabaseScope.MAIN -> "classpath:db/migration/$flywayVendor"
      DatabaseScope.TASKS -> "classpath:tasks/migration/$flywayVendor"
    }

  fun flywayHistoryTable(scope: DatabaseScope): String? =
    when (this) {
      SQLITE -> null
      POSTGRESQL ->
        when (scope) {
          DatabaseScope.MAIN -> "flyway_schema_history_main"
          DatabaseScope.TASKS -> "flyway_schema_history_tasks"
        }
    }
}

enum class DatabaseScope {
  MAIN,
  TASKS,
}
