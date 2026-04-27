package org.gotson.komga.infrastructure.datasource

import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.infrastructure.configuration.ActuatorSanitizationConfiguration
import org.gotson.komga.infrastructure.configuration.KomgaProperties
import org.gotson.komga.infrastructure.jooq.KomgaJooqConfiguration
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.actuate.endpoint.SanitizableData
import org.springframework.boot.actuate.endpoint.SanitizingFunction
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.MapPropertySource
import org.springframework.test.context.ActiveProfiles
import javax.sql.DataSource

class DataSourcesConfigurationTest {
  private val tmpDir = System.getProperty("java.io.tmpdir")

  private val datasourceContextRunner =
    ApplicationContextRunner()
      .withConfiguration(
        AutoConfigurations.of(
          ConfigurationPropertiesAutoConfiguration::class.java,
          ValidationAutoConfiguration::class.java,
        ),
      ).withUserConfiguration(
        KomgaProperties::class.java,
        ActuatorSanitizationConfiguration::class.java,
        DataSourcesConfiguration::class.java,
        PostgreSqlExperimentalMarker::class.java,
        KomgaJooqConfiguration::class.java,
        FlywayConfiguration::class.java,
      ).withPropertyValues(
        "komga.database.file=$tmpDir/komga-datasource-main.sqlite",
        "komga.tasks-db.file=$tmpDir/komga-datasource-tasks.sqlite",
      )

  @SpringBootTest
  @Nested
  inner class WalMode(
    @Autowired private val dataSourceRW: DataSource,
    @Autowired @Qualifier("sqliteDataSourceRO") private val dataSourceRO: DataSource,
    @Autowired @Qualifier("tasksDataSourceRW") private val tasksDataSourceRW: DataSource,
    @Autowired @Qualifier("tasksDataSourceRO") private val tasksDataSourceRO: DataSource,
    @Autowired @Qualifier("dslContextRW") private val dslContextRW: DSLContext,
    @Autowired @Qualifier("dslContextRO") private val dslContextRO: DSLContext,
    @Autowired @Qualifier("tasksDslContextRW") private val tasksDslContextRW: DSLContext,
    @Autowired @Qualifier("tasksDslContextRO") private val tasksDslContextRO: DSLContext,
  ) {
    @Test
    fun `given wal mode when autoriwiring beans then bean instances are different between RW and RO`() {
      assertThat(dataSourceRW).isNotSameAs(dataSourceRO)
      assertThat(tasksDataSourceRW).isNotSameAs(tasksDataSourceRO)
    }

    @Test
    fun `given default configuration when inspecting datasource and jooq beans then SQLite remains default`() {
      listOf(dataSourceRW, dataSourceRO, tasksDataSourceRW, tasksDataSourceRO)
        .map { dataSource ->
          dataSource.connection.use { it.metaData.url }
        }.forEach { jdbcUrl ->
          assertThat(jdbcUrl).startsWith("jdbc:sqlite:")
          assertThat(jdbcUrl).doesNotContain("postgresql")
        }

      assertThat(listOf(dslContextRW, dslContextRO, tasksDslContextRW, tasksDslContextRO).map { it.configuration().dialect().family() })
        .containsOnly(SQLDialect.SQLITE)
    }
  }

  @SpringBootTest
  @ActiveProfiles("test", "memorydb")
  @Nested
  inner class MemoryMode(
    @Autowired private val dataSourceRW: DataSource,
    @Autowired @Qualifier("sqliteDataSourceRO") private val dataSourceRO: DataSource,
    @Autowired @Qualifier("tasksDataSourceRW") private val tasksDataSourceRW: DataSource,
    @Autowired @Qualifier("tasksDataSourceRO") private val tasksDataSourceRO: DataSource,
  ) {
    @Test
    fun `given wal mode when autoriwiring beans then bean instances are the same between RW and RO`() {
      assertThat(dataSourceRW).isSameAs(dataSourceRO)
      assertThat(tasksDataSourceRW).isSameAs(tasksDataSourceRO)
    }
  }

  @Test
  fun `given explicit PostgreSQL configuration when creating datasource beans then PostgreSQL dialect is selected`() {
    datasourceContextRunner
      .withPropertyValues(
        "komga.database.backend=POSTGRESQL",
        "komga.database.postgresql.url=jdbc:postgresql://127.0.0.1:5432/komga",
        "komga.database.postgresql.username=komga",
        "komga.database.postgresql.password=komga",
        "komga.tasks-db.backend=POSTGRESQL",
        "komga.tasks-db.postgresql.url=jdbc:postgresql://127.0.0.1:5432/komga",
        "komga.tasks-db.postgresql.username=komga",
        "komga.tasks-db.postgresql.password=komga",
      ).run { context ->
        assertThat(context).hasNotFailed()

        assertThat(context.getBean("dataSourceRW", HikariDataSource::class.java).jdbcUrl)
          .isEqualTo("jdbc:postgresql://127.0.0.1:5432/komga")
        assertThat(context.getBean("tasksDataSourceRW", HikariDataSource::class.java).jdbcUrl)
          .isEqualTo("jdbc:postgresql://127.0.0.1:5432/komga")
        val mainDialect =
          context
            .getBean("dslContextRW", DSLContext::class.java)
            .configuration()
            .dialect()
            .family()
        val tasksDialect =
          context
            .getBean("tasksDslContextRW", DSLContext::class.java)
            .configuration()
            .dialect()
            .family()

        assertThat(mainDialect).isEqualTo(SQLDialect.POSTGRES)
        assertThat(tasksDialect).isEqualTo(SQLDialect.POSTGRES)
        assertThat(context.getBean(FlywayConfiguration::class.java).flywayConfigurationCustomizer())
          .isNotNull
      }
  }

  @Test
  fun `given explicit PostgreSQL configuration when exposing experimental marker then message is sanitized`() {
    datasourceContextRunner
      .withPropertyValues(
        "komga.database.backend=POSTGRESQL",
        "komga.database.postgresql.url=jdbc:postgresql://main-user:secret-main@127.0.0.1:5432/komga?password=url-secret",
        "komga.database.postgresql.username=main-user",
        "komga.database.postgresql.password=secret-main",
        "komga.tasks-db.backend=POSTGRESQL",
        "komga.tasks-db.postgresql.url=jdbc:postgresql://tasks-user:secret-tasks@127.0.0.1:5432/komga?password=task-secret",
        "komga.tasks-db.postgresql.username=tasks-user",
        "komga.tasks-db.postgresql.password=secret-tasks",
      ).run { context ->
        assertThat(context).hasNotFailed()

        assertThat(context.getBean(PostgreSqlExperimentalMarker::class.java).marker())
          .contains("PostgreSQL backend is advanced, optional, and experimental")
          .contains("SQLite remains the default backend")
          .doesNotContain("jdbc:postgresql://")
          .doesNotContain("main-user")
          .doesNotContain("tasks-user")
          .doesNotContain("secret-main")
          .doesNotContain("secret-tasks")
          .doesNotContain("url-secret")
          .doesNotContain("task-secret")
      }
  }

  @Test
  fun `given default SQLite configuration when exposing experimental marker then no marker is returned`() {
    datasourceContextRunner.run { context ->
      assertThat(context).hasNotFailed()

      assertThat(context.getBean(PostgreSqlExperimentalMarker::class.java).marker()).isNull()
    }
  }

  @Test
  fun `given incomplete PostgreSQL configuration when creating datasource beans then startup fails without leaking secrets`() {
    datasourceContextRunner
      .withPropertyValues(
        "komga.database.backend=POSTGRESQL",
        "komga.database.postgresql.url=jdbc:postgresql://main-user:secret-main@127.0.0.1:5432/komga?apiKey=generated-main-secret",
        "komga.database.postgresql.password=secret-main",
        "komga.tasks-db.backend=POSTGRESQL",
        "komga.tasks-db.postgresql.url=jdbc:postgresql://127.0.0.1:5432/komga?password=secret-tasks",
        "komga.tasks-db.postgresql.username=komga",
        "komga.tasks-db.postgresql.password=secret-tasks",
      ).run { context ->
        assertThat(context).hasFailed()
        assertThat(context.startupFailure.stackTraceToString())
          .contains("komga.database.postgresql.username")
          .doesNotContain("secret-main")
          .doesNotContain("secret-tasks")
          .doesNotContain("generated-main-secret")
          .doesNotContain("jdbc:postgresql://main-user")
      }
  }

  @Test
  fun `given PostgreSQL backend with SQLite-only properties when creating datasource beans then startup fails fast with redacted message`() {
    datasourceContextRunner
      .withPropertyValues(
        "komga.database.backend=POSTGRESQL",
        "komga.database.postgresql.url=jdbc:postgresql://127.0.0.1:5432/komga?password=secret-main",
        "komga.database.postgresql.username=komga",
        "komga.database.postgresql.password=secret-main",
        "komga.database.pragmas.cache_size=-2000",
        "komga.tasks-db.backend=POSTGRESQL",
        "komga.tasks-db.postgresql.url=jdbc:postgresql://127.0.0.1:5432/komga?password=secret-tasks",
        "komga.tasks-db.postgresql.username=komga",
        "komga.tasks-db.postgresql.password=secret-tasks",
        "komga.tasks-db.busy-timeout=30s",
      ).run { context ->
        assertThat(context).hasFailed()
        assertThat(context.startupFailure.stackTraceToString())
          .contains("PostgreSQL backend does not support SQLite-only properties")
          .contains("komga.database.pragmas")
          .contains("komga.tasks-db.busy-timeout")
          .doesNotContain("secret-main")
          .doesNotContain("secret-tasks")
          .doesNotContain("jdbc:postgresql://127.0.0.1:5432/komga")
      }
  }

  @Test
  fun `given SQLite backend with PostgreSQL-only properties when creating datasource beans then startup fails fast with redacted message`() {
    datasourceContextRunner
      .withPropertyValues(
        "komga.database.backend=SQLITE",
        "komga.database.postgresql.url=jdbc:postgresql://db.example.org:5432/komga?password=secret-main",
        "komga.database.postgresql.username=main-user",
        "komga.database.postgresql.password=secret-main",
        "komga.tasks-db.backend=SQLITE",
        "komga.tasks-db.postgresql.url=jdbc:postgresql://tasks-user:secret-tasks@db.example.org:5432/komga",
      ).run { context ->
        assertThat(context).hasFailed()
        assertThat(context.startupFailure.stackTraceToString())
          .contains("SQLite backend does not support PostgreSQL-only properties")
          .contains("komga.database.postgresql.url")
          .contains("komga.database.postgresql.username")
          .contains("komga.database.postgresql.password")
          .contains("komga.tasks-db.postgresql.url")
          .doesNotContain("secret-main")
          .doesNotContain("secret-tasks")
          .doesNotContain("main-user")
          .doesNotContain("tasks-user")
          .doesNotContain("jdbc:postgresql://")
      }
  }

  @Test
  fun `given mixed database backends when creating datasource beans then startup fails fast`() {
    datasourceContextRunner
      .withPropertyValues(
        "komga.database.backend=POSTGRESQL",
        "komga.database.postgresql.url=jdbc:postgresql://127.0.0.1:5432/komga",
        "komga.database.postgresql.username=komga",
        "komga.database.postgresql.password=komga",
      ).run { context ->
        assertThat(context).hasFailed()
        assertThat(context.startupFailure.stackTraceToString())
          .contains("must use the same backend family")
      }
  }

  @Test
  fun `given actuator sanitization when applying database PostgreSQL properties then credential values are masked`() {
    datasourceContextRunner.run { context ->
      assertThat(context).hasNotFailed()
      val sanitizer = context.getBean(SanitizingFunction::class.java)
      val propertySource = MapPropertySource("test", emptyMap())

      assertThat(sanitizer.apply(SanitizableData(propertySource, "komga.database.postgresql.url", "jdbc:postgresql://user:secret@db.example.org:5432/komga?password=secret")).value)
        .isEqualTo(SanitizableData.SANITIZED_VALUE)
      assertThat(sanitizer.apply(SanitizableData(propertySource, "komga.tasks-db.postgresql.username", "komga-user")).value)
        .isEqualTo(SanitizableData.SANITIZED_VALUE)
      assertThat(sanitizer.apply(SanitizableData(propertySource, "komga.tasks-db.postgresql.password", "secret")).value)
        .isEqualTo(SanitizableData.SANITIZED_VALUE)
      assertThat(sanitizer.apply(SanitizableData(propertySource, "komga.database.backend", "POSTGRESQL")).value)
        .isEqualTo("POSTGRESQL")
    }
  }

  @Test
  fun `given database backends when resolving Flyway metadata then vendor locations and history tables are isolated`() {
    assertThat(DatabaseBackend.SQLITE.flywayLocation(DatabaseScope.MAIN)).isEqualTo("classpath:db/migration/sqlite")
    assertThat(DatabaseBackend.SQLITE.flywayLocation(DatabaseScope.TASKS)).isEqualTo("classpath:tasks/migration/sqlite")
    assertThat(DatabaseBackend.SQLITE.flywayHistoryTable(DatabaseScope.MAIN)).isNull()
    assertThat(DatabaseBackend.SQLITE.flywayHistoryTable(DatabaseScope.TASKS)).isNull()

    assertThat(DatabaseBackend.POSTGRESQL.flywayLocation(DatabaseScope.MAIN)).isEqualTo("classpath:db/migration/postgresql")
    assertThat(DatabaseBackend.POSTGRESQL.flywayLocation(DatabaseScope.TASKS)).isEqualTo("classpath:tasks/migration/postgresql")
    assertThat(DatabaseBackend.POSTGRESQL.flywayHistoryTable(DatabaseScope.MAIN)).isEqualTo("flyway_schema_history_main")
    assertThat(DatabaseBackend.POSTGRESQL.flywayHistoryTable(DatabaseScope.TASKS)).isEqualTo("flyway_schema_history_tasks")
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "KOMGA_POSTGRESQL_TESTS", matches = "true")
  fun `given Docker PostgreSQL when connecting explicit PostgreSQL datasource then connection succeeds`() {
    datasourceContextRunner
      .withPropertyValues(
        "komga.database.backend=POSTGRESQL",
        "komga.database.postgresql.url=jdbc:postgresql://127.0.0.1:5432/komga",
        "komga.database.postgresql.username=komga",
        "komga.database.postgresql.password=komga",
        "komga.tasks-db.backend=POSTGRESQL",
        "komga.tasks-db.postgresql.url=jdbc:postgresql://127.0.0.1:5432/komga",
        "komga.tasks-db.postgresql.username=komga",
        "komga.tasks-db.postgresql.password=komga",
      ).run { context ->
        assertThat(context).hasNotFailed()

        val dataSource = context.getBean("dataSourceRW", DataSource::class.java)
        dataSource.connection.use {
          assertThat(it.metaData.url).startsWith("jdbc:postgresql:")
        }
      }
  }
}
