package org.gotson.komga.infrastructure.datasource

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.gotson.komga.infrastructure.configuration.KomgaProperties
import org.springframework.boot.jdbc.DataSourceBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import javax.sql.DataSource

@Configuration
class DataSourcesConfiguration(
  private val komgaProperties: KomgaProperties,
) {
  @Bean(name = ["dataSourceRW", "sqliteDataSourceRW"])
  @Primary
  fun dataSourceRW(): DataSource =
    buildDataSource(DatabaseScope.MAIN, "RW", SqliteUdfDataSource::class.java, komgaProperties.database)
      .apply {
        // force pool size to 1 if the pool is only used for writes
        if (komgaProperties.database.backend == DatabaseBackend.SQLITE && komgaProperties.database.shouldSeparateReadFromWrites()) {
          this.maximumPoolSize = 1
        }
      }

  @Bean(name = ["dataSourceRO", "sqliteDataSourceRO"])
  fun dataSourceRO(): DataSource =
    if (komgaProperties.database.shouldSeparateReadFromWrites())
      buildDataSource(DatabaseScope.MAIN, "RO", SqliteUdfDataSource::class.java, komgaProperties.database)
    else
      dataSourceRW()

  @Bean("tasksDataSourceRW")
  fun tasksDataSourceRW(): DataSource =
    buildDataSource(DatabaseScope.TASKS, "RW", SQLiteDataSource::class.java, komgaProperties.tasksDb)
      .apply {
        // pool size is always 1:
        // - if there's only 1 pool for read and writes, size should be 1
        // - if there's a separate read pool, the write pool size should be 1
        if (komgaProperties.tasksDb.backend == DatabaseBackend.SQLITE) this.maximumPoolSize = 1
      }

  @Bean("tasksDataSourceRO")
  fun tasksDataSourceRO(): DataSource =
    if (komgaProperties.tasksDb.shouldSeparateReadFromWrites())
      buildDataSource(DatabaseScope.TASKS, "RO", SQLiteDataSource::class.java, komgaProperties.tasksDb)
    else
      tasksDataSourceRW()

  private fun buildDataSource(
    scope: DatabaseScope,
    accessMode: String,
    dataSourceClass: Class<out SQLiteDataSource>,
    databaseProps: KomgaProperties.Database,
  ): HikariDataSource =
    when (databaseProps.backend) {
      DatabaseBackend.SQLITE -> buildSqliteDataSource(scope, accessMode, dataSourceClass, databaseProps)
      DatabaseBackend.POSTGRESQL -> buildPostgresqlDataSource(scope, accessMode, databaseProps)
    }

  private fun buildSqliteDataSource(
    scope: DatabaseScope,
    accessMode: String,
    dataSourceClass: Class<out SQLiteDataSource>,
    databaseProps: KomgaProperties.Database,
  ): HikariDataSource {
    val extraPragmas =
      databaseProps.pragmas.let {
        if (it.isEmpty())
          ""
        else
          "?" + it.map { (key, value) -> "$key=$value" }.joinToString(separator = "&")
      }

    val dataSource =
      DataSourceBuilder
        .create()
        .driverClassName("org.sqlite.JDBC")
        .url("jdbc:sqlite:${databaseProps.file}$extraPragmas")
        .type(dataSourceClass)
        .build()

    with(dataSource) {
      setEnforceForeignKeys(true)
      setGetGeneratedKeys(false)
    }
    with(databaseProps) {
      journalMode?.let { dataSource.setJournalMode(it.name) }
      busyTimeout?.let { dataSource.config.busyTimeout = it.toMillis().toInt() }
    }

    val poolSize =
      if (databaseProps.isMemory())
        1
      else if (databaseProps.poolSize != null)
        databaseProps.poolSize!!
      else
        Runtime.getRuntime().availableProcessors().coerceAtMost(databaseProps.maxPoolSize)

    return HikariDataSource(
      HikariConfig().apply {
        this.dataSource = dataSource
        this.poolName = "${databaseProps.backend.poolNamePrefix()}${scope.poolNamePart()}Pool$accessMode"
        this.maximumPoolSize = poolSize
      },
    )
  }

  private fun buildPostgresqlDataSource(
    scope: DatabaseScope,
    accessMode: String,
    databaseProps: KomgaProperties.Database,
  ): HikariDataSource {
    val poolSize = databaseProps.poolSize ?: Runtime.getRuntime().availableProcessors().coerceAtMost(databaseProps.maxPoolSize)

    return HikariDataSource(
      HikariConfig().apply {
        this.driverClassName = "org.postgresql.Driver"
        this.jdbcUrl = databaseProps.postgresql.url
        this.username = databaseProps.postgresql.username
        this.password = databaseProps.postgresql.password
        this.poolName = "${databaseProps.backend.poolNamePrefix()}${scope.poolNamePart()}Pool$accessMode"
        this.maximumPoolSize = poolSize
        this.initializationFailTimeout = -1
        if (accessMode == "RO") this.isReadOnly = true
      },
    )
  }

  fun KomgaProperties.Database.isMemory() = backend == DatabaseBackend.SQLITE && (file.contains(":memory:") || file.contains("mode=memory"))

  fun KomgaProperties.Database.shouldSeparateReadFromWrites(): Boolean =
    when (backend) {
      DatabaseBackend.SQLITE -> !isMemory() && journalMode == SQLiteConfig.JournalMode.WAL
      DatabaseBackend.POSTGRESQL -> true
    }

  private fun DatabaseBackend.poolNamePrefix(): String =
    when (this) {
      DatabaseBackend.SQLITE -> "Sqlite"
      DatabaseBackend.POSTGRESQL -> "Postgresql"
    }

  private fun DatabaseScope.poolNamePart(): String =
    when (this) {
      DatabaseScope.MAIN -> "Main"
      DatabaseScope.TASKS -> "Tasks"
    }
}
