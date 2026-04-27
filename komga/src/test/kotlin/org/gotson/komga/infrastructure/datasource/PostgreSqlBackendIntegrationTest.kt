package org.gotson.komga.infrastructure.datasource

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ibm.icu.text.Collator
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.gotson.komga.application.tasks.Task
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.model.makeLibrary
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.infrastructure.configuration.KomgaProperties
import org.gotson.komga.infrastructure.jooq.TempTable.Companion.withTempTable
import org.gotson.komga.infrastructure.jooq.main.BookDao
import org.gotson.komga.infrastructure.jooq.main.LibraryDao
import org.gotson.komga.infrastructure.jooq.main.SeriesDao
import org.gotson.komga.infrastructure.jooq.main.ServerSettingsDao
import org.gotson.komga.infrastructure.jooq.noCase
import org.gotson.komga.infrastructure.jooq.tasks.TasksDao
import org.gotson.komga.infrastructure.jooq.udfStripAccents
import org.gotson.komga.jooq.main.Tables.BOOK
import org.gotson.komga.jooq.main.Tables.LIBRARY
import org.gotson.komga.jooq.main.Tables.PAGE_HASH_THUMBNAIL
import org.gotson.komga.jooq.main.Tables.SERIES
import org.gotson.komga.jooq.tasks.Tables.TASK
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@EnabledIfEnvironmentVariable(named = "KOMGA_POSTGRESQL_TESTS", matches = "true")
class PostgreSqlBackendIntegrationTest {
  @BeforeEach
  fun migrateFreshSchema() {
    resetSchema()
    migrate(DatabaseScope.MAIN).migrate()
    migrate(DatabaseScope.TASKS).migrate()
  }

  @Test
  fun `given Docker PostgreSQL when migrating twice then schema startup is repeatable and histories are isolated`() {
    migrate(DatabaseScope.MAIN).migrate()
    migrate(DatabaseScope.TASKS).migrate()

    connection().use { connection ->
      val dsl = DSL.using(connection, SQLDialect.POSTGRES)

      assertThat(dsl.fetchCount(DSL.table(DSL.name("flyway_schema_history_main")))).isEqualTo(1)
      assertThat(dsl.fetchCount(DSL.table(DSL.name("flyway_schema_history_tasks")))).isEqualTo(2)
      assertThat(tableNames(dsl))
        .contains(
          "BOOK",
          "LIBRARY",
          "SERIES",
          "SERVER_SETTINGS",
          "TASK",
          "THUMBNAIL_BOOK",
          "USER",
        )
    }
  }

  @Test
  fun `given PostgreSQL main schema when production tasks initializer runs then tasks migration history is isolated`() {
    resetSchema()
    migrate(DatabaseScope.MAIN).migrate()

    FlywaySecondaryMigrationInitializer(
      postgresqlDataSource(),
      KomgaProperties().apply {
        tasksDb.backend = DatabaseBackend.POSTGRESQL
      },
    ).afterPropertiesSet()

    connection().use { connection ->
      val dsl = DSL.using(connection, SQLDialect.POSTGRES)

      assertThat(tableNames(dsl)).contains("TASK")
      assertThat(dsl.fetchCount(DSL.table(DSL.name("flyway_schema_history_main")))).isEqualTo(1)
      assertThat(dsl.fetchCount(DSL.table(DSL.name("flyway_schema_history_tasks")))).isEqualTo(2)
    }
  }

  @Test
  fun `given PostgreSQL schema when using DAO paths then representative persistence results match expected shape`() {
    connection().use { connection ->
      val dsl = DSL.using(connection, SQLDialect.POSTGRES)
      val serverSettingsDao = ServerSettingsDao(dsl, dsl)
      val libraryDao = LibraryDao(dsl, dsl)
      val seriesDao = SeriesDao(dsl, dsl, 2)
      val bookDao = BookDao(dsl, dsl, 2)
      val tasksDao = TasksDao(dsl, dsl, 2, jacksonObjectMapper().findAndRegisterModules())

      serverSettingsDao.saveSetting("postgres.experimental", true)
      assertThat(serverSettingsDao.getSettingByKey("postgres.experimental", Boolean::class.java)).isTrue

      val library = makeLibrary("PostgreSQL")
      val series = makeSeries("École Series", libraryId = library.id)
      val books =
        listOf(
          makeBook("Book 1", libraryId = library.id, seriesId = series.id),
          makeBook("Book 2", libraryId = library.id, seriesId = series.id),
          makeBook("Book 3", libraryId = library.id, seriesId = series.id),
        )

      libraryDao.insert(library)
      seriesDao.insert(series)
      books.forEach(bookDao::insert)

      assertThat(libraryDao.findById(library.id).name).isEqualTo(library.name)
      assertThat(seriesDao.findAllByLibraryId(library.id).map { it.name }).containsExactly(series.name)
      assertThat(bookDao.findAllBySeriesIds(listOf(series.id)).map { it.name }).containsExactlyInAnyOrder("Book 1", "Book 2", "Book 3")

      tasksDao.save(
        listOf(
          Task.AnalyzeBook("book1", 5, "group1"),
          Task.ConvertBook("book2", 3, "group2"),
        ),
      )

      assertThat(tasksDao.takeFirst("worker1"))
        .isInstanceOf(Task.AnalyzeBook::class.java)
      assertThat(tasksDao.takeFirst("worker2"))
        .isInstanceOf(Task.ConvertBook::class.java)
      assertThat(tasksDao.takeFirst("worker3")).isNull()
      assertThat(tasksDao.findAllGroupedByOwner().keys).containsExactlyInAnyOrder("worker1", "worker2")
    }
  }

  @Test
  fun `given PostgreSQL independent DAO instances when taking ungrouped tasks concurrently then no task is claimed twice`() {
    withIndependentTaskDaos(20) { daos ->
      daos.first().save(
        buildList {
          (1..20).forEach { add(Task.HashBookPages("book$it", 5)) }
        },
      )

      val claimed = claimConcurrently(daos)

      assertThat(claimed).hasSize(20)
      assertThat(claimed.map { it.uniqueId }).doesNotHaveDuplicates()
      assertThat(daos.first().hasAvailable()).isFalse
    }
  }

  @Test
  fun `given PostgreSQL independent DAO instances when taking grouped tasks concurrently then group exclusion is preserved`() {
    withIndependentTaskDaos(20) { daos ->
      daos.first().save(
        buildList {
          (1..20).forEach { add(Task.AnalyzeBook("book$it", 5, "group1")) }
          (1..20).forEach { add(Task.ConvertBook("book$it", 3, "group2")) }
        },
      )

      val claimed = claimConcurrently(daos)

      assertThat(claimed).hasSize(2)
      assertThat(claimed.map { it.uniqueId }).doesNotHaveDuplicates()
      assertThat(claimed.map { it.groupId }).containsExactlyInAnyOrder("group1", "group2")
      assertThat(daos.first().hasAvailable()).isFalse
      assertThat(daos.first().findAllGroupedByOwner().filterKeys { it != null }).hasSize(2)
    }
  }

  @Test
  fun `given PostgreSQL grouped candidate becomes unavailable while waiting for group lock then candidate is skipped`() {
    withIndependentTaskDaos(1) { daos ->
      daos.first().save(
        listOf(
          Task.AnalyzeBook("book1", 5, "group1"),
          Task.AnalyzeBook("book2", 4, "group1"),
        ),
      )

      connection().use { lockConnection ->
        lockConnection.autoCommit = false
        DSL
          .using(lockConnection, SQLDialect.POSTGRES)
          .execute("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "group1")

        val executor = Executors.newSingleThreadExecutor()
        try {
          val future = executor.submit<Task?> { daos.first().takeFirst("worker") }
          Thread.sleep(500)
          assertThat(future.isDone).isFalse

          connection().use { competingConnection ->
            DSL
              .using(competingConnection, SQLDialect.POSTGRES)
              .update(TASK)
              .set(TASK.OWNER, "other-worker")
              .where(TASK.ID.eq("ANALYZE_BOOK_book2"))
              .execute()
          }

          lockConnection.commit()

          assertThat(future.get(10, TimeUnit.SECONDS)).isNull()
          assertThat(daos.first().findAllGroupedByOwner().filterKeys { it != null }).containsOnlyKeys("other-worker")
        } finally {
          lockConnection.rollback()
          executor.shutdownNow()
          assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue
        }
      }
    }
  }

  @Test
  fun `given PostgreSQL schema when using dialect helpers then temp tables search helpers and bytes round trip`() {
    connection().use { connection ->
      val dsl = DSL.using(connection, SQLDialect.POSTGRES)

      assertThat(dsl.select(DSL.value("École").udfStripAccents()).fetchOne(0, String::class.java)).isEqualTo("Ecole")
      assertThat(dsl.select(DSL.value("series").eq(DSL.value("SERIES").noCase())).fetchOne(0, Boolean::class.java)).isTrue

      dsl.withTempTable(2, listOf("book-1", "book-2", "book-3")).use { tempTable ->
        assertThat(dsl.selectFrom(tempTable.selectTempStrings()).fetch(0, String::class.java))
          .containsExactlyInAnyOrder("book-1", "book-2", "book-3")
      }

      val payload = byteArrayOf(1, 2, 3, 4)
      dsl
        .insertInto(PAGE_HASH_THUMBNAIL, PAGE_HASH_THUMBNAIL.HASH, PAGE_HASH_THUMBNAIL.THUMBNAIL)
        .values("hash", payload)
        .execute()

      assertThat(
        dsl
          .select(PAGE_HASH_THUMBNAIL.THUMBNAIL)
          .from(PAGE_HASH_THUMBNAIL)
          .where(PAGE_HASH_THUMBNAIL.HASH.eq("hash"))
          .fetchOne(PAGE_HASH_THUMBNAIL.THUMBNAIL),
      ).isEqualTo(payload)
    }
  }

  @Test
  fun `given PostgreSQL schema when using unicode collation then ICU tertiary normalized ordering matches SQLite collator`() {
    connection().use { connection ->
      val dsl = DSL.using(connection, SQLDialect.POSTGRES)

      val collation =
        dsl
          .select(
            DSL.field("collprovider", String::class.java),
            DSL.field("colliculocale", String::class.java),
            DSL.field("collisdeterministic", Boolean::class.java),
          ).from("pg_collation")
          .where(DSL.field("collname").eq(SqliteUdfDataSource.COLLATION_UNICODE_3))
          .fetchOne()

      assertThat(collation?.value1()).isEqualTo("i")
      assertThat(collation?.value2()).isEqualTo("und-u-kk-ks-level3")
      assertThat(collation?.value3()).isFalse

      val composed = "\u00e9clair"
      val decomposed = "e\u0301clair"
      assertThat(compareWithUnicodeCollation(dsl, composed, decomposed)).isZero()
      assertThat(equalsWithUnicodeCollation(dsl, composed, decomposed)).isTrue

      val values =
        listOf(
          "Zulu",
          "\u00e1baco",
          "E\u0301clair",
          "\u00c9cole",
          "album",
          "\u00c1lbum",
          "\u00e5ngstr\u00f6m",
          "\u6771\u4eac",
          "\u0391\u03b8\u03ae\u03bd\u03b1",
          "zulu",
        )
      val expected = values.sortedWith(sqliteUnicode3Collator())

      dsl
        .createTemporaryTable("unicode_sort_sample")
        .column("value", SQLDataType.VARCHAR)
        .execute()
      values.forEach {
        dsl
          .insertInto(DSL.table("unicode_sort_sample"), DSL.field("value"))
          .values(it)
          .execute()
      }

      assertThat(
        dsl
          .select(DSL.field("value", String::class.java))
          .from("unicode_sort_sample")
          .orderBy(DSL.field("value", String::class.java).collate(SqliteUdfDataSource.COLLATION_UNICODE_3))
          .fetch(0, String::class.java),
      ).containsExactlyElementsOf(expected)
    }
  }

  @Test
  fun `given PostgreSQL schema when inspecting columns then current schema contains expected parity columns`() {
    connection().use { connection ->
      val dsl = DSL.using(connection, SQLDialect.POSTGRES)

      assertThat(columnNames(dsl, BOOK.name))
        .contains("FILE_HASH", "FILE_HASH_KOREADER", "oneshot")
      assertThat(columnNames(dsl, LIBRARY.name))
        .contains("SCAN_CBX", "SCAN_PDF", "SCAN_EPUB", "HASH_KOREADER")
      assertThat(columnNames(dsl, SERIES.name))
        .contains("BOOK_COUNT", "DELETED_DATE", "oneshot")
      assertThat(columnNames(dsl, PAGE_HASH_THUMBNAIL.name))
        .contains("HASH", "THUMBNAIL")
    }
  }

  private fun resetSchema() {
    connection().use { connection ->
      connection.createStatement().use {
        it.execute("DROP SCHEMA public CASCADE")
        it.execute("CREATE SCHEMA public")
      }
    }
  }

  private fun migrate(scope: DatabaseScope): Flyway =
    Flyway
      .configure()
      .dataSource(JDBC_URL, USERNAME, PASSWORD)
      .locations(DatabaseBackend.POSTGRESQL.flywayLocation(scope))
      .table(DatabaseBackend.POSTGRESQL.flywayHistoryTable(scope))
      .baselineOnMigrate(scope == DatabaseScope.TASKS)
      .load()

  private fun connection() = DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD)

  private fun withIndependentTaskDaos(
    count: Int,
    block: (List<TasksDao>) -> Unit,
  ) {
    val connections = (1..count).map { connection() }
    try {
      val daos =
        connections.map { connection ->
          val dsl = DSL.using(connection, SQLDialect.POSTGRES)
          TasksDao(dsl, dsl, 2, jacksonObjectMapper().findAndRegisterModules())
        }
      block(daos)
    } finally {
      connections.forEach(Connection::close)
    }
  }

  private fun claimConcurrently(daos: List<TasksDao>): List<Task> {
    val executor = Executors.newFixedThreadPool(8)
    try {
      return executor
        .invokeAll(
          daos.mapIndexed { index, dao ->
            Callable {
              dao.takeFirst("worker$index")
            }
          },
        ).mapNotNull { it.get(10, TimeUnit.SECONDS) }
    } finally {
      executor.shutdownNow()
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue
    }
  }

  private fun postgresqlDataSource() =
    PGSimpleDataSource().apply {
      setURL(JDBC_URL)
      user = USERNAME
      password = PASSWORD
    }

  private fun tableNames(dsl: org.jooq.DSLContext): Set<String> =
    dsl
      .select(DSL.field("table_name", String::class.java))
      .from("information_schema.tables")
      .where(DSL.field("table_schema").eq("public"))
      .fetchSet(0, String::class.java)

  private fun columnNames(
    dsl: org.jooq.DSLContext,
    tableName: String,
  ): Set<String> =
    dsl
      .select(DSL.field("column_name", String::class.java))
      .from("information_schema.columns")
      .where(DSL.field("table_schema").eq("public"))
      .and(DSL.field("table_name").eq(tableName))
      .fetchSet(0, String::class.java)

  private fun sqliteUnicode3Collator(): Comparator<String> {
    val collator =
      Collator.getInstance().apply {
        strength = Collator.TERTIARY
        decomposition = Collator.CANONICAL_DECOMPOSITION
      }
    return Comparator { left, right -> collator.compare(left, right) }
  }

  private fun compareWithUnicodeCollation(
    dsl: org.jooq.DSLContext,
    left: String,
    right: String,
  ): Int =
    dsl
      .select(
        DSL.field(
          "{0} COLLATE \"COLLATION_UNICODE_3\" < {1} COLLATE \"COLLATION_UNICODE_3\"",
          Boolean::class.java,
          DSL.value(left),
          DSL.value(right),
        ),
        DSL.field(
          "{0} COLLATE \"COLLATION_UNICODE_3\" > {1} COLLATE \"COLLATION_UNICODE_3\"",
          Boolean::class.java,
          DSL.value(left),
          DSL.value(right),
        ),
      ).fetchOne()
      .let {
        when {
          it?.value1() == true -> -1
          it?.value2() == true -> 1
          else -> 0
        }
      }

  private fun equalsWithUnicodeCollation(
    dsl: org.jooq.DSLContext,
    left: String,
    right: String,
  ): Boolean =
    dsl
      .select(
        DSL.field(
          "{0} COLLATE \"COLLATION_UNICODE_3\" = {1} COLLATE \"COLLATION_UNICODE_3\"",
          Boolean::class.java,
          DSL.value(left),
          DSL.value(right),
        ),
      ).fetchOne(0, Boolean::class.java)
      ?: false

  private companion object {
    const val JDBC_URL = "jdbc:postgresql://127.0.0.1:5432/komga"
    const val USERNAME = "komga"
    const val PASSWORD = "komga"
  }
}
