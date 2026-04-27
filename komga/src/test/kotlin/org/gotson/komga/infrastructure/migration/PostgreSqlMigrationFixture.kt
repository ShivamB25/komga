package org.gotson.komga.infrastructure.migration

import org.flywaydb.core.Flyway
import org.gotson.komga.infrastructure.datasource.DatabaseBackend
import org.gotson.komga.infrastructure.datasource.DatabaseScope
import org.springframework.security.core.token.Sha512DigestUtils
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.absolutePathString

internal class PostgreSqlMigrationFixture(
  private val tempDir: Path,
) {
  fun createSourceMain(): String {
    val url = sqliteUrl("postgres-source-main")
    Flyway
      .configure()
      .dataSource(url, null, null)
      .locations(DatabaseBackend.SQLITE.flywayLocation(DatabaseScope.MAIN))
      .mixed(true)
      .placeholders(sqlitePlaceholders)
      .load()
      .migrate()
    return url
  }

  fun createSourceTasks(): String {
    val url = sqliteUrl("postgres-source-tasks")
    Flyway
      .configure()
      .dataSource(url, null, null)
      .locations(DatabaseBackend.SQLITE.flywayLocation(DatabaseScope.TASKS))
      .mixed(true)
      .load()
      .migrate()
    return url
  }

  fun createMigratedSourceMain(
    sourceBookUrl: String = "file:///tmp/komga-migrated/series/cafe-book.cbz",
    sourceMediaPageFileName: String = "page1.jpg",
    sourceMediaPageMediaType: String = "image/jpeg",
  ): String {
    val url = sqliteUrl("postgres-migrated-source-main")
    Flyway
      .configure()
      .dataSource(url, null, null)
      .locations(DatabaseBackend.SQLITE.flywayLocation(DatabaseScope.MAIN))
      .mixed(true)
      .placeholders(sqlitePlaceholders)
      .load()
      .migrate()

    DriverManager.getConnection(url).use { connection ->
      connection.createStatement().use { statement ->
        statement.execute(
          """
          insert into "USER" ("ID", "EMAIL", "PASSWORD", "SHARED_ALL_LIBRARIES", "AGE_RESTRICTION", "AGE_RESTRICTION_ALLOW_ONLY")
          values ('admin', '$ADMIN_EMAIL', '${passwordEncoder.encode(ADMIN_PASSWORD)}', true, null, null)
          """.trimIndent(),
        )
        statement.execute(
          """
          insert into "USER" ("ID", "EMAIL", "PASSWORD", "SHARED_ALL_LIBRARIES", "AGE_RESTRICTION", "AGE_RESTRICTION_ALLOW_ONLY")
          values ('restricted', '$RESTRICTED_EMAIL', '${passwordEncoder.encode(RESTRICTED_PASSWORD)}', false, 12, true)
          """.trimIndent(),
        )
        statement.execute("""insert into "USER_ROLE" ("USER_ID", "ROLE") values ('admin', 'ADMIN')""")
        statement.execute("""insert into "USER_ROLE" ("USER_ID", "ROLE") values ('restricted', 'FILE_DOWNLOAD')""")
        statement.execute("""insert into "USER_ROLE" ("USER_ID", "ROLE") values ('restricted', 'PAGE_STREAMING')""")
        statement.execute("""insert into "USER_ROLE" ("USER_ID", "ROLE") values ('restricted', 'KOBO_SYNC')""")
        statement.execute("""insert into "USER_ROLE" ("USER_ID", "ROLE") values ('restricted', 'KOREADER_SYNC')""")
        statement.execute("""insert into "LIBRARY" ("ID", "NAME", "ROOT") values ('library-1', 'Migrated Library', 'file:///tmp/komga-migrated')""")
        statement.execute("""insert into "LIBRARY" ("ID", "NAME", "ROOT") values ('library-denied', 'Denied Library', 'file:///tmp/komga-denied')""")
        statement.execute("""insert into "USER_LIBRARY_SHARING" ("USER_ID", "LIBRARY_ID") values ('restricted', 'library-1')""")
        statement.execute("""insert into "USER_SHARING" ("LABEL", "ALLOW", "USER_ID") values ('kid-safe', true, 'restricted')""")
        statement.execute("""insert or replace into "SERVER_SETTINGS" ("KEY", "VALUE") values ('SERVER_PORT', '1234')""")
        statement.execute("""insert or replace into "SERVER_SETTINGS" ("KEY", "VALUE") values ('DELETE_EMPTY_COLLECTIONS', 'true')""")
        statement.execute("""insert into "CLIENT_SETTINGS_GLOBAL" ("KEY", "VALUE", "ALLOW_UNAUTHORIZED") values ('theme', 'dark', false)""")
        statement.execute("""insert into "CLIENT_SETTINGS_USER" ("USER_ID", "KEY", "VALUE") values ('restricted', 'reader', 'paged')""")
        statement.execute(
          """
          insert into "SERIES" ("ID", "FILE_LAST_MODIFIED", "NAME", "URL", "LIBRARY_ID", "BOOK_COUNT")
          values ('series-1', CURRENT_TIMESTAMP, 'École Adventures', 'file:///tmp/komga-migrated/series', 'library-1', 1)
          """.trimIndent(),
        )
        statement.execute(
          """
          insert into "SERIES_METADATA" ("SERIES_ID", "STATUS", "TITLE", "TITLE_SORT", "SUMMARY", "AGE_RATING")
          values ('series-1', 'ENDED', 'École Adventures', 'Ecole Adventures', '', 10)
          """.trimIndent(),
        )
        statement.execute("""insert into "BOOK_METADATA_AGGREGATION" ("SERIES_ID", "SUMMARY", "SUMMARY_NUMBER") values ('series-1', '', '')""")
        statement.execute("""insert into "SERIES_METADATA_TAG" ("TAG", "SERIES_ID") values ('kid-safe', 'series-1')""")
        statement.execute(
          """
          insert into "BOOK" ("ID", "FILE_LAST_MODIFIED", "NAME", "URL", "SERIES_ID", "LIBRARY_ID", "FILE_SIZE", "NUMBER", "FILE_HASH_KOREADER")
          values ('book-1', CURRENT_TIMESTAMP, 'Café Book', '$sourceBookUrl', 'series-1', 'library-1', 42, 1, '$KOREADER_HASH')
          """.trimIndent(),
        )
        statement.execute("""insert into "BOOK_METADATA" ("BOOK_ID", "NUMBER", "NUMBER_SORT", "TITLE", "SUMMARY") values ('book-1', '1', 1.0, 'Café Book', '')""")
        statement.execute("""insert into "BOOK_METADATA_TAG" ("TAG", "BOOK_ID") values ('kid-safe', 'book-1')""")
        statement.execute("""insert into "BOOK_METADATA_AUTHOR" ("NAME", "ROLE", "BOOK_ID") values ('Alice Auteur', 'writer', 'book-1')""")
        statement.execute("""insert into "BOOK_METADATA_AGGREGATION_AUTHOR" ("NAME", "ROLE", "SERIES_ID") values ('Alice Auteur', 'writer', 'series-1')""")
        statement.execute(
          """
          insert into "MEDIA" ("BOOK_ID", "MEDIA_TYPE", "STATUS", "PAGE_COUNT")
          values ('book-1', 'application/zip', 'READY', 10)
          """.trimIndent(),
        )
        statement.execute("""insert into "MEDIA_FILE" ("FILE_NAME", "BOOK_ID", "MEDIA_TYPE", "SUB_TYPE", "FILE_SIZE") values ('EPUB/chapter-1.xhtml', 'book-1', 'application/xhtml+xml', 'EPUB_PAGE', 301)""")
        statement.execute("""insert into "MEDIA_FILE" ("FILE_NAME", "BOOK_ID", "MEDIA_TYPE", "SUB_TYPE", "FILE_SIZE") values ('EPUB/styles.css', 'book-1', 'text/css', 'EPUB_ASSET', 302)""")
        statement.execute("""insert into "MEDIA_PAGE" ("BOOK_ID", "NUMBER", "FILE_NAME", "MEDIA_TYPE", "width", "height", "FILE_SIZE") values ('book-1', 1, '$sourceMediaPageFileName', '$sourceMediaPageMediaType', 640, 480, 123)""")
        statement.execute("""insert into "READ_PROGRESS" ("BOOK_ID", "USER_ID", "PAGE", "COMPLETED") values ('book-1', 'restricted', 3, false)""")
        statement.execute("""insert into "USER_API_KEY" ("ID", "USER_ID", "API_KEY", "COMMENT") values ('api-key-1', 'restricted', '${Sha512DigestUtils.shaHex(RESTRICTED_API_KEY)}', 'migration smoke')""")
        statement.execute("""insert into "SYNC_POINT" ("ID", "USER_ID", "API_KEY_ID") values ('sync-1', 'restricted', 'api-key-1')""")
        statement.execute(
          """
          insert into "SYNC_POINT_BOOK" ("SYNC_POINT_ID", "BOOK_ID", "BOOK_CREATED_DATE", "BOOK_LAST_MODIFIED_DATE", "BOOK_FILE_LAST_MODIFIED", "BOOK_FILE_SIZE", "BOOK_FILE_HASH", "BOOK_METADATA_LAST_MODIFIED_DATE", "SYNCED", "BOOK_THUMBNAIL_ID")
          values ('sync-1', 'book-1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 42, '', CURRENT_TIMESTAMP, false, 'thumbnail-1')
          """.trimIndent(),
        )
        statement.execute("""insert into "COLLECTION" ("ID", "NAME", "ORDERED", "SERIES_COUNT") values ('collection-1', 'Collection', true, 1)""")
        statement.execute("""insert into "COLLECTION_SERIES" ("COLLECTION_ID", "SERIES_ID", "NUMBER") values ('collection-1', 'series-1', 1)""")
        statement.execute("""insert into "READLIST" ("ID", "NAME", "BOOK_COUNT") values ('readlist-1', 'Read List', 1)""")
        statement.execute("""insert into "READLIST_BOOK" ("READLIST_ID", "BOOK_ID", "NUMBER") values ('readlist-1', 'book-1', 1)""")
        statement.execute(
          """
          insert into "SERIES" ("ID", "FILE_LAST_MODIFIED", "NAME", "URL", "LIBRARY_ID", "BOOK_COUNT")
          values ('series-denied', CURRENT_TIMESTAMP, 'Denied Series', 'file:///tmp/komga-denied/series', 'library-denied', 1)
          """.trimIndent(),
        )
        statement.execute(
          """
          insert into "SERIES_METADATA" ("SERIES_ID", "STATUS", "TITLE", "TITLE_SORT", "SUMMARY", "AGE_RATING")
          values ('series-denied', 'ENDED', 'Denied Series', 'Denied Series', '', 18)
          """.trimIndent(),
        )
        statement.execute("""insert into "BOOK_METADATA_AGGREGATION" ("SERIES_ID", "SUMMARY", "SUMMARY_NUMBER") values ('series-denied', '', '')""")
        statement.execute(
          """
          insert into "BOOK" ("ID", "FILE_LAST_MODIFIED", "NAME", "URL", "SERIES_ID", "LIBRARY_ID", "FILE_SIZE", "NUMBER")
          values ('book-denied', CURRENT_TIMESTAMP, 'Denied Book', 'file:///tmp/komga-denied/series/book.cbz', 'series-denied', 'library-denied', 42, 1)
          """.trimIndent(),
        )
        statement.execute("""insert into "BOOK_METADATA" ("BOOK_ID", "NUMBER", "NUMBER_SORT", "TITLE", "SUMMARY") values ('book-denied', '1', 1.0, 'Denied Book', '')""")
        statement.execute("""insert into "MEDIA" ("BOOK_ID", "MEDIA_TYPE", "STATUS", "PAGE_COUNT") values ('book-denied', 'application/zip', 'READY', 1)""")
        statement.execute("""insert into "READLIST" ("ID", "NAME", "BOOK_COUNT") values ('readlist-denied', 'Denied Read List', 1)""")
        statement.execute("""insert into "READLIST_BOOK" ("READLIST_ID", "BOOK_ID", "NUMBER") values ('readlist-denied', 'book-denied', 1)""")
      }
      connection.prepareStatement("""insert into "THUMBNAIL_BOOK" ("ID", "THUMBNAIL", "SELECTED", "TYPE", "BOOK_ID", "WIDTH", "HEIGHT", "MEDIA_TYPE", "FILE_SIZE", "GENERATION_PROFILE") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""").use { statement ->
        statement.setString(1, "thumbnail-1")
        statement.setBytes(2, byteArrayOf(1, 2, 3, 4))
        statement.setBoolean(3, true)
        statement.setString(4, "GENERATED")
        statement.setString(5, "book-1")
        statement.setInt(6, 1)
        statement.setInt(7, 1)
        statement.setString(8, "image/jpeg")
        statement.setLong(9, 4)
        statement.setString(10, """{"version":1,"format":"image/jpeg","targetSize":300,"jpegQuality":null,"storageMode":"DATABASE"}""")
        statement.executeUpdate()
      }
      connection.prepareStatement("""insert into "THUMBNAIL_BOOK" ("ID", "THUMBNAIL", "SELECTED", "TYPE", "BOOK_ID", "WIDTH", "HEIGHT", "MEDIA_TYPE", "FILE_SIZE") values (?, ?, ?, ?, ?, ?, ?, ?, ?)""").use { statement ->
        statement.setString(1, "thumbnail-uploaded-1")
        statement.setBytes(2, byteArrayOf(21, 22, 23))
        statement.setBoolean(3, false)
        statement.setString(4, "USER_UPLOADED")
        statement.setString(5, "book-1")
        statement.setInt(6, 3)
        statement.setInt(7, 1)
        statement.setString(8, "image/jpeg")
        statement.setLong(9, 3)
        statement.executeUpdate()
      }
      connection.prepareStatement("""insert into "THUMBNAIL_SERIES" ("ID", "THUMBNAIL", "SELECTED", "TYPE", "SERIES_ID", "WIDTH", "HEIGHT", "MEDIA_TYPE", "FILE_SIZE") values (?, ?, ?, ?, ?, ?, ?, ?, ?)""").use { statement ->
        statement.setString(1, "series-thumbnail-1")
        statement.setBytes(2, byteArrayOf(5, 6, 7))
        statement.setBoolean(3, true)
        statement.setString(4, "USER_UPLOADED")
        statement.setString(5, "series-1")
        statement.setInt(6, 3)
        statement.setInt(7, 1)
        statement.setString(8, "image/jpeg")
        statement.setLong(9, 3)
        statement.executeUpdate()
      }
      connection.prepareStatement("""insert into "THUMBNAIL_COLLECTION" ("ID", "THUMBNAIL", "SELECTED", "TYPE", "COLLECTION_ID", "WIDTH", "HEIGHT", "MEDIA_TYPE", "FILE_SIZE") values (?, ?, ?, ?, ?, ?, ?, ?, ?)""").use { statement ->
        statement.setString(1, "collection-thumbnail-1")
        statement.setBytes(2, byteArrayOf(8, 9, 10))
        statement.setBoolean(3, true)
        statement.setString(4, "USER_UPLOADED")
        statement.setString(5, "collection-1")
        statement.setInt(6, 3)
        statement.setInt(7, 1)
        statement.setString(8, "image/jpeg")
        statement.setLong(9, 3)
        statement.executeUpdate()
      }
      connection.prepareStatement("""insert into "THUMBNAIL_READLIST" ("ID", "THUMBNAIL", "SELECTED", "TYPE", "READLIST_ID", "WIDTH", "HEIGHT", "MEDIA_TYPE", "FILE_SIZE") values (?, ?, ?, ?, ?, ?, ?, ?, ?)""").use { statement ->
        statement.setString(1, "readlist-thumbnail-1")
        statement.setBytes(2, byteArrayOf(11, 12, 13))
        statement.setBoolean(3, true)
        statement.setString(4, "USER_UPLOADED")
        statement.setString(5, "readlist-1")
        statement.setInt(6, 3)
        statement.setInt(7, 1)
        statement.setString(8, "image/jpeg")
        statement.setLong(9, 3)
        statement.executeUpdate()
      }
      connection.createStatement().use { statement ->
        statement.execute("""insert into "PAGE_HASH" ("HASH", "SIZE", "ACTION") values ('page-hash-1', 3, 'DELETE_AUTO')""")
      }
      connection.prepareStatement("""insert into "PAGE_HASH_THUMBNAIL" ("HASH", "THUMBNAIL") values (?, ?)""").use { statement ->
        statement.setString(1, "page-hash-1")
        statement.setBytes(2, byteArrayOf(14, 15, 16))
        statement.executeUpdate()
      }
    }
    return url
  }

  fun insertMigratedGeneratedThumbnailBook(
    sourceMain: String,
    bookId: String,
    thumbnailId: String,
    thumbnailUrl: String,
    bookUrl: String,
    number: Int,
  ) {
    DriverManager.getConnection(sourceMain).use { connection ->
      connection.createStatement().use { statement ->
        statement.execute(
          """
          insert into "BOOK" ("ID", "FILE_LAST_MODIFIED", "NAME", "URL", "SERIES_ID", "LIBRARY_ID", "FILE_SIZE", "NUMBER")
          values ('$bookId', CURRENT_TIMESTAMP, '$bookId.cbz', '$bookUrl', 'series-1', 'library-1', 42, $number)
          """.trimIndent(),
        )
        statement.execute("""insert into "BOOK_METADATA" ("BOOK_ID", "NUMBER", "NUMBER_SORT", "TITLE", "SUMMARY") values ('$bookId', '$number', $number.0, '$bookId', '')""")
        statement.execute("""insert into "BOOK_METADATA_TAG" ("TAG", "BOOK_ID") values ('kid-safe', '$bookId')""")
        statement.execute("""insert into "MEDIA" ("BOOK_ID", "MEDIA_TYPE", "STATUS", "PAGE_COUNT") values ('$bookId', 'application/zip', 'READY', 1)""")
        statement.execute("""insert into "MEDIA_PAGE" ("BOOK_ID", "NUMBER", "FILE_NAME", "MEDIA_TYPE", "width", "height", "FILE_SIZE") values ('$bookId', 1, 'komga.png', 'image/png', 48, 48, 3108)""")
      }
      connection.prepareStatement("""insert into "THUMBNAIL_BOOK" ("ID", "THUMBNAIL", "URL", "SELECTED", "TYPE", "BOOK_ID", "WIDTH", "HEIGHT", "MEDIA_TYPE", "FILE_SIZE", "GENERATION_PROFILE") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""").use { statement ->
        statement.setString(1, thumbnailId)
        statement.setNull(2, java.sql.Types.BINARY)
        statement.setString(3, thumbnailUrl)
        statement.setBoolean(4, true)
        statement.setString(5, "GENERATED")
        statement.setString(6, bookId)
        statement.setInt(7, 1)
        statement.setInt(8, 1)
        statement.setString(9, "image/jpeg")
        statement.setLong(10, 3)
        statement.setString(11, """{"version":1,"format":"image/jpeg","targetSize":300,"jpegQuality":null,"storageMode":"FILESYSTEM"}""")
        statement.executeUpdate()
      }
    }
  }

  fun createMigratedSourceTasks(): String {
    val url = sqliteUrl("postgres-migrated-source-tasks")
    Flyway
      .configure()
      .dataSource(url, null, null)
      .locations(DatabaseBackend.SQLITE.flywayLocation(DatabaseScope.TASKS))
      .mixed(true)
      .load()
      .migrate()
    return url
  }

  private fun sqliteUrl(prefix: String): String = "jdbc:sqlite:${tempDir.resolve("$prefix.sqlite").absolutePathString()}"

  internal companion object {
    const val JDBC_URL = "jdbc:postgresql://127.0.0.1:5432/komga"
    const val USERNAME = "komga"
    const val PASSWORD = "komga"
    const val ADMIN_EMAIL = "admin@example.org"
    const val ADMIN_PASSWORD = "admin-password"
    const val RESTRICTED_EMAIL = "restricted@example.org"
    const val RESTRICTED_PASSWORD = "restricted-password"
    const val RESTRICTED_API_KEY = "restricted-api-key"
    const val KOREADER_HASH = "koreader-hash-1"

    private val passwordEncoder = BCryptPasswordEncoder()
    private val sqlitePlaceholders =
      mapOf(
        "library-file-hashing" to "true",
        "library-scan-startup" to "false",
        "delete-empty-collections" to "true",
        "delete-empty-read-lists" to "true",
      )

    fun resetAndMigrateTarget() {
      DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD).use { connection ->
        connection.createStatement().use {
          it.execute("DROP SCHEMA public CASCADE")
          it.execute("CREATE SCHEMA public")
        }
      }

      migrateTarget(DatabaseScope.MAIN).migrate()
      migrateTarget(DatabaseScope.TASKS).migrate()
    }

    private fun migrateTarget(scope: DatabaseScope): Flyway =
      Flyway
        .configure()
        .dataSource(JDBC_URL, USERNAME, PASSWORD)
        .locations(DatabaseBackend.POSTGRESQL.flywayLocation(scope))
        .table(DatabaseBackend.POSTGRESQL.flywayHistoryTable(scope))
        .baselineOnMigrate(scope == DatabaseScope.TASKS)
        .load()
  }
}
