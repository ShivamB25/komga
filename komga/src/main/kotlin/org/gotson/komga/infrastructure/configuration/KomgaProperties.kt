package org.gotson.komga.infrastructure.configuration

import jakarta.annotation.PostConstruct
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.gotson.komga.infrastructure.datasource.DatabaseBackend
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.convert.DurationUnit
import org.springframework.stereotype.Component
import org.springframework.validation.annotation.Validated
import org.sqlite.SQLiteConfig.JournalMode
import java.time.Duration
import java.time.temporal.ChronoUnit
import kotlin.io.path.Path
import kotlin.io.path.createDirectories

@Component
@ConfigurationProperties(prefix = "komga")
@Validated
class KomgaProperties {
  @PostConstruct
  private fun validateAndMakeDirs() {
    validateDatabaseBackendConfiguration()
    try {
      if (database.backend == DatabaseBackend.SQLITE) Path(database.file).parent.createDirectories()
      if (tasksDb.backend == DatabaseBackend.SQLITE) Path(tasksDb.file).parent.createDirectories()
    } catch (_: Exception) {
    }
  }

  private fun validateDatabaseBackendConfiguration() {
    require(database.backend == tasksDb.backend) {
      "komga.database.backend and komga.tasks-db.backend must use the same backend family; configure both as SQLITE or POSTGRESQL"
    }

    when (database.backend) {
      DatabaseBackend.SQLITE -> {
        val unsupportedProperties =
          database.postgresqlOnlyProperties("komga.database") +
            tasksDb.postgresqlOnlyProperties("komga.tasks-db")

        require(unsupportedProperties.isEmpty()) {
          "SQLite backend does not support PostgreSQL-only properties: ${unsupportedProperties.joinToString()}. Remove them or set komga.database.backend and komga.tasks-db.backend to POSTGRESQL. Values are redacted."
        }
      }
      DatabaseBackend.POSTGRESQL -> {
        val missingProperties =
          database.missingPostgresqlProperties("komga.database") +
            tasksDb.missingPostgresqlProperties("komga.tasks-db")

        require(missingProperties.isEmpty()) {
          "PostgreSQL backend requires explicit non-empty properties: ${missingProperties.joinToString()}"
        }

        val unsupportedProperties =
          database.sqliteOnlyProperties("komga.database") +
            tasksDb.sqliteOnlyProperties("komga.tasks-db")

        require(unsupportedProperties.isEmpty()) {
          "PostgreSQL backend does not support SQLite-only properties: ${unsupportedProperties.joinToString()}. Remove them or set komga.database.backend and komga.tasks-db.backend to SQLITE. Values are redacted."
        }
      }
    }
  }

  @Positive
  var pageHashing: Int = 3

  @Positive
  var epubDivinaLetterCountThreshold: Int = 15

  var oauth2AccountCreation: Boolean = false

  var oidcEmailVerification: Boolean = true

  var database = Database()

  var tasksDb = Database()

  var cors = Cors()

  var lucene = Lucene()

  var configDir: String? = null

  var thumbnails = Thumbnails()

  var kobo = Kobo()

  val fonts = Fonts()

  class Cors {
    var allowedOrigins: List<String> = emptyList()
  }

  class Database {
    var backend: DatabaseBackend = DatabaseBackend.SQLITE

    @get:NotBlank
    var file: String = ""

    @get:Positive
    var batchChunkSize: Int = 1000

    @get:Positive
    var poolSize: Int? = null

    @get:Positive
    var maxPoolSize: Int = 1

    var journalMode: JournalMode? = JournalMode.WAL

    @DurationUnit(ChronoUnit.SECONDS)
    var busyTimeout: Duration? = null

    var pragmas: Map<String, String> = emptyMap()

    var checkLocalFilesystem: Boolean = true

    var postgresql = PostgreSql()

    fun postgresqlOnlyProperties(propertyPrefix: String): List<String> =
      listOfNotNull(
        "$propertyPrefix.postgresql.url".takeIf { !postgresql.url.isNullOrBlank() },
        "$propertyPrefix.postgresql.username".takeIf { !postgresql.username.isNullOrBlank() },
        "$propertyPrefix.postgresql.password".takeIf { !postgresql.password.isNullOrBlank() },
      )

    fun missingPostgresqlProperties(propertyPrefix: String): List<String> =
      listOfNotNull(
        "$propertyPrefix.postgresql.url".takeIf { postgresql.url.isNullOrBlank() },
        "$propertyPrefix.postgresql.username".takeIf { postgresql.username.isNullOrBlank() },
        "$propertyPrefix.postgresql.password".takeIf { postgresql.password.isNullOrBlank() },
      )

    fun sqliteOnlyProperties(propertyPrefix: String): List<String> =
      listOfNotNull(
        "$propertyPrefix.pragmas".takeIf { pragmas.isNotEmpty() },
        "$propertyPrefix.busy-timeout".takeIf { busyTimeout != null },
      )

    class PostgreSql {
      var url: String? = null

      var username: String? = null

      var password: String? = null
    }
  }

  class Fonts {
    @get:NotBlank
    var dataDirectory: String = ""
  }

  class Lucene {
    @get:NotBlank
    var dataDirectory: String = ""

    var indexAnalyzer = IndexAnalyzer()

    @DurationUnit(ChronoUnit.SECONDS)
    var commitDelay: Duration = Duration.ofSeconds(2)

    class IndexAnalyzer {
      @get:Positive
      var minGram: Int = 3

      @get:Positive
      var maxGram: Int = 10

      var preserveOriginal: Boolean = true
    }
  }

  class Thumbnails {
    var storage = Storage()

    class Storage {
      var mode: ThumbnailStorageMode = ThumbnailStorageMode.DATABASE

      var directory: String? = null
    }
  }

  enum class ThumbnailStorageMode {
    DATABASE,
    FILESYSTEM,
    HYBRID,
  }

  class Kobo {
    @get:Positive
    var syncItemLimit: Int = 100

    var kepubifyPath: String? = null
  }
}
