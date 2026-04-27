package org.gotson.komga.infrastructure.datasource

import org.flywaydb.core.Flyway
import org.gotson.komga.infrastructure.configuration.KomgaProperties
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import javax.sql.DataSource

@Configuration
class FlywayConfiguration(
  private val komgaProperties: KomgaProperties,
) {
  @Bean
  fun flywayConfigurationCustomizer(): FlywayConfigurationCustomizer =
    FlywayConfigurationCustomizer { configuration ->
      configuration.locations(komgaProperties.database.backend.flywayLocation(DatabaseScope.MAIN))
      komgaProperties
        .database
        .backend
        .flywayHistoryTable(DatabaseScope.MAIN)
        ?.let { table -> configuration.table(table) }
    }
}

@Component
class FlywaySecondaryMigrationInitializer(
  @Qualifier("tasksDataSourceRW")
  private val tasksDataSource: DataSource,
  private val komgaProperties: KomgaProperties,
) : InitializingBean {
  // by default Spring Boot will perform migration only on the @Primary datasource
  override fun afterPropertiesSet() {
    val backend = komgaProperties.tasksDb.backend
    val configuration =
      Flyway
        .configure()
        .locations(backend.flywayLocation(DatabaseScope.TASKS))
        .dataSource(tasksDataSource)

    backend.flywayHistoryTable(DatabaseScope.TASKS)?.let { configuration.table(it) }
    if (backend == DatabaseBackend.POSTGRESQL) configuration.baselineOnMigrate(true)
    configuration.load().migrate()
  }
}
