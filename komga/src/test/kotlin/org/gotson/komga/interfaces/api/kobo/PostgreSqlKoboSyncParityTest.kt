package org.gotson.komga.interfaces.api.kobo

import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.BookPage
import org.gotson.komga.domain.model.KomgaUser
import org.gotson.komga.domain.model.Media
import org.gotson.komga.domain.model.MediaExtensionEpub
import org.gotson.komga.domain.model.MediaFile
import org.gotson.komga.domain.model.R2Locator
import org.gotson.komga.domain.model.UserRoles
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.model.makeLibrary
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.KomgaUserRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.domain.persistence.ReadProgressRepository
import org.gotson.komga.domain.service.KomgaUserLifecycle
import org.gotson.komga.domain.service.LibraryLifecycle
import org.gotson.komga.domain.service.SeriesLifecycle
import org.gotson.komga.infrastructure.kobo.KoboHeaders
import org.gotson.komga.infrastructure.kobo.KomgaSyncTokenGenerator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.sql.DriverManager
import java.time.ZonedDateTime
import java.util.UUID
import org.gotson.komga.domain.model.MediaType as KomgaMediaType

@EnabledIfEnvironmentVariable(named = "KOMGA_POSTGRESQL_TESTS", matches = "true")
@SpringBootTest(
  properties = [
    "komga.database.backend=POSTGRESQL",
    "komga.database.postgresql.url=jdbc:postgresql://127.0.0.1:5432/komga",
    "komga.database.postgresql.username=komga",
    "komga.database.postgresql.password=komga",
    "komga.database.pool-size=4",
    "komga.tasks-db.backend=POSTGRESQL",
    "komga.tasks-db.postgresql.url=jdbc:postgresql://127.0.0.1:5432/komga",
    "komga.tasks-db.postgresql.username=komga",
    "komga.tasks-db.postgresql.password=komga",
    "komga.tasks-db.pool-size=4",
    "komga.kobo.sync-item-limit=50",
  ],
)
@ContextConfiguration(initializers = [PostgreSqlSchemaReset::class])
@AutoConfigureMockMvc(printOnlyOnFailure = false)
class PostgreSqlKoboSyncParityTest(
  @Autowired libraryRepository: LibraryRepository,
  @Autowired libraryLifecycle: LibraryLifecycle,
  @Autowired userRepository: KomgaUserRepository,
  @Autowired komgaUserLifecycle: KomgaUserLifecycle,
  @Autowired seriesLifecycle: SeriesLifecycle,
  @Autowired bookRepository: BookRepository,
  @Autowired mediaRepository: MediaRepository,
  @Autowired readProgressRepository: ReadProgressRepository,
  @Autowired private val mockMvc: MockMvc,
  @Autowired private val komgaSyncTokenGenerator: KomgaSyncTokenGenerator,
) : AbstractPostgreSqlSyncParityContract(
    libraryRepository,
    libraryLifecycle,
    userRepository,
    komgaUserLifecycle,
    seriesLifecycle,
    bookRepository,
    mediaRepository,
    readProgressRepository,
  ) {
  @Test
  fun `given Kobo user when syncing for the first time then EPUB books and sync token are returned`() {
    val (_, apiKey) = createUserAndApiKey(UserRoles.KOBO_SYNC)
    val book = seedReadyEpubBook("Kobo first sync", "kobo-first-sync-book")

    val result =
      mockMvc
        .get("/kobo/$apiKey/v1/library/sync")
        .andExpect {
          status { isOk() }
          jsonPath("$.length()") { value(1) }
          jsonPath("$[0].NewEntitlement.BookEntitlement.Id") { value(book.id) }
          jsonPath("$[0].NewEntitlement.BookMetadata.EntitlementId") { value(book.id) }
          header { exists(KoboHeaders.X_KOBO_SYNCTOKEN) }
          header { doesNotExist(KoboHeaders.X_KOBO_SYNC) }
        }.andReturn()

    val syncToken = komgaSyncTokenGenerator.fromBase64(result.response.getHeaderValue(KoboHeaders.X_KOBO_SYNCTOKEN) as String)
    assertThat(syncToken.ongoingSyncPointId).isNull()
    assertThat(syncToken.lastSuccessfulSyncPointId).isNotBlank()
  }

  @Test
  fun `given Kobo user with sync token when syncing incrementally then only new EPUB books are returned`() {
    val (_, apiKey) = createUserAndApiKey(UserRoles.KOBO_SYNC)
    val alreadySyncedBook = seedReadyEpubBook("Already synced", "kobo-incremental-existing-book")

    val firstSync =
      mockMvc
        .get("/kobo/$apiKey/v1/library/sync")
        .andExpect {
          status { isOk() }
          jsonPath("$.length()") { value(1) }
          jsonPath("$[0].NewEntitlement.BookEntitlement.Id") { value(alreadySyncedBook.id) }
        }.andReturn()

    val syncToken = firstSync.response.getHeaderValue(KoboHeaders.X_KOBO_SYNCTOKEN) as String
    val newBook = seedReadyEpubBook("Incremental addition", "kobo-incremental-new-book")

    mockMvc
      .get("/kobo/$apiKey/v1/library/sync") {
        header(KoboHeaders.X_KOBO_SYNCTOKEN, syncToken)
      }.andExpect {
        status { isOk() }
        jsonPath("$.length()") { value(1) }
        jsonPath("$[0].NewEntitlement.BookEntitlement.Id") { value(newBook.id) }
        jsonPath("$[0].NewEntitlement.BookMetadata.EntitlementId") { value(newBook.id) }
        header { exists(KoboHeaders.X_KOBO_SYNCTOKEN) }
        header { doesNotExist(KoboHeaders.X_KOBO_SYNC) }
      }
  }

  @Test
  fun `given Kobo user when putting and getting book state then EPUB read progress persists`() {
    val (user, apiKey) = createUserAndApiKey(UserRoles.KOBO_SYNC)
    val book = seedReadyEpubBook("Kobo progress", "kobo-state-book")
    val timestamp = "2026-04-28T00:00:00Z"

    mockMvc
      .put("/kobo/$apiKey/v1/library/${book.id}/state") {
        contentType = MediaType.APPLICATION_JSON
        content =
          """
          {
            "ReadingStates": [
              {
                "EntitlementId": "${book.id}",
                "Created": "$timestamp",
                "LastModified": "$timestamp",
                "PriorityTimestamp": "$timestamp",
                "CurrentBookmark": {
                  "LastModified": "$timestamp",
                  "ProgressPercent": 25.0,
                  "ContentSourceProgressPercent": 50.0,
                  "Location": {
                    "Source": "EPUB/chapter-1.xhtml",
                    "Type": "KoboSpan",
                    "Value": "kobo.1.1"
                  }
                },
                "Statistics": {
                  "LastModified": "$timestamp"
                },
                "StatusInfo": {
                  "LastModified": "$timestamp",
                  "Status": "Reading",
                  "TimesStartedReading": 1
                }
              }
            ]
          }
          """.trimIndent()
      }.andExpect {
        status { isOk() }
        jsonPath("RequestResult") { value("Success") }
        jsonPath("UpdateResults[0].EntitlementId") { value(book.id) }
        jsonPath("UpdateResults[0].CurrentBookmarkResult.Result") { value("Success") }
        jsonPath("UpdateResults[0].StatusInfoResult.Result") { value("Success") }
      }

    mockMvc
      .get("/kobo/$apiKey/v1/library/${book.id}/state")
      .andExpect {
        status { isOk() }
        jsonPath("$.length()") { value(1) }
        jsonPath("$[0].EntitlementId") { value(book.id) }
        jsonPath("$[0].CurrentBookmark.ContentSourceProgressPercent") { value(50.0) }
        jsonPath("$[0].CurrentBookmark.ProgressPercent") { value(25.0) }
        jsonPath("$[0].CurrentBookmark.Location.Source") { value("EPUB/chapter-1.xhtml") }
        jsonPath("$[0].CurrentBookmark.Location.Value") { value("kobo.1.1") }
        jsonPath("$[0].StatusInfo.Status") { value("Reading") }
      }

    val progress = readProgressRepository.findByBookIdAndUserIdOrNull(book.id, user.id)
    assertThat(progress).isNotNull
    assertThat(progress!!.locator?.href).isEqualTo("EPUB/chapter-1.xhtml")
    assertThat(progress.locator?.locations?.progression).isEqualTo(0.5F)
  }
}

@EnabledIfEnvironmentVariable(named = "KOMGA_POSTGRESQL_TESTS", matches = "true")
@SpringBootTest(
  properties = [
    "komga.database.backend=POSTGRESQL",
    "komga.database.postgresql.url=jdbc:postgresql://127.0.0.1:5432/komga",
    "komga.database.postgresql.username=komga",
    "komga.database.postgresql.password=komga",
    "komga.database.pool-size=4",
    "komga.tasks-db.backend=POSTGRESQL",
    "komga.tasks-db.postgresql.url=jdbc:postgresql://127.0.0.1:5432/komga",
    "komga.tasks-db.postgresql.username=komga",
    "komga.tasks-db.postgresql.password=komga",
    "komga.tasks-db.pool-size=4",
    "komga.kobo.sync-item-limit=50",
  ],
)
@ContextConfiguration(initializers = [PostgreSqlSchemaReset::class])
@AutoConfigureMockMvc(printOnlyOnFailure = false)
class PostgreSqlKoreaderSyncParityTest(
  @Autowired libraryRepository: LibraryRepository,
  @Autowired libraryLifecycle: LibraryLifecycle,
  @Autowired userRepository: KomgaUserRepository,
  @Autowired komgaUserLifecycle: KomgaUserLifecycle,
  @Autowired seriesLifecycle: SeriesLifecycle,
  @Autowired bookRepository: BookRepository,
  @Autowired mediaRepository: MediaRepository,
  @Autowired readProgressRepository: ReadProgressRepository,
  @Autowired private val mockMvc: MockMvc,
) : AbstractPostgreSqlSyncParityContract(
    libraryRepository,
    libraryLifecycle,
    userRepository,
    komgaUserLifecycle,
    seriesLifecycle,
    bookRepository,
    mediaRepository,
    readProgressRepository,
  ) {
  @Test
  fun `given KOReader user when posting and getting EPUB progress then progress persists`() {
    val (user, apiKey) = createUserAndApiKey(UserRoles.KOREADER_SYNC)
    val book = seedReadyEpubBook("KOReader progress", "koreader-progress-book", koreaderHash = "koreader-progress-hash")

    mockMvc
      .post("/koreader/syncs/progress") {
        header("x-auth-user", apiKey)
        contentType = MediaType.APPLICATION_JSON
        content =
          """
          {
            "document": "koreader-progress-hash",
            "percentage": 0.42,
            "progress": "/body/DocFragment[2].0",
            "device": "KOReader",
            "device_id": "device-1"
          }
          """.trimIndent()
      }.andExpect {
        status { isOk() }
      }

    mockMvc
      .get("/koreader/syncs/progress/koreader-progress-hash") {
        header("x-auth-user", apiKey)
      }.andExpect {
        status { isOk() }
        jsonPath("document") { value("koreader-progress-hash") }
        jsonPath("percentage") { value(0.42) }
        jsonPath("progress") { value("/body/DocFragment[2].0") }
        jsonPath("device") { value("KOReader") }
        jsonPath("device_id") { value("device-1") }
      }

    val progress = readProgressRepository.findByBookIdAndUserIdOrNull(book.id, user.id)
    assertThat(progress).isNotNull
    assertThat(progress!!.locator?.href).isEqualTo("EPUB/chapter-2.xhtml")
    assertThat(progress.locator?.locations?.totalProgression).isEqualTo(0.42F)
  }
}

abstract class AbstractPostgreSqlSyncParityContract(
  private val libraryRepository: LibraryRepository,
  private val libraryLifecycle: LibraryLifecycle,
  private val userRepository: KomgaUserRepository,
  private val komgaUserLifecycle: KomgaUserLifecycle,
  private val seriesLifecycle: SeriesLifecycle,
  private val bookRepository: BookRepository,
  private val mediaRepository: MediaRepository,
  protected val readProgressRepository: ReadProgressRepository,
) {
  private val users = mutableListOf<KomgaUser>()

  @AfterEach
  fun cleanupFixture() {
    libraryRepository.findAll().forEach {
      libraryLifecycle.deleteLibrary(it)
    }
    users.forEach {
      komgaUserLifecycle.deleteUser(it)
    }
    users.clear()
    assertThat(bookRepository.findAll()).isEmpty()
  }

  protected fun createUserAndApiKey(vararg roles: UserRoles): Pair<KomgaUser, String> {
    val user =
      KomgaUser(
        email = "sync-${UUID.randomUUID()}@example.org",
        password = "",
        roles = roles.toSet(),
      )
    userRepository.insert(user)
    users += user
    return user to komgaUserLifecycle.createApiKey(user, "sync parity")!!.key
  }

  protected fun seedReadyEpubBook(
    title: String,
    bookId: String,
    koreaderHash: String = "",
  ): Book {
    val library = makeLibrary(name = "$title Library", id = "$bookId-library")
    libraryRepository.insert(library)
    val series = seriesLifecycle.createSeries(makeSeries(name = "$title Series", libraryId = library.id))
    seriesLifecycle.addBooks(series, listOf(makeBook(title, libraryId = library.id, id = bookId)))

    val book =
      bookRepository
        .findByIdOrNull(bookId)!!
        .let {
          if (koreaderHash.isBlank()) {
            it
          } else {
            it.copy(fileHashKoreader = koreaderHash).also(bookRepository::update)
          }
        }

    mediaRepository.findById(bookId).let { media ->
      mediaRepository.update(
        media.copy(
          status = Media.Status.READY,
          mediaType = KomgaMediaType.EPUB.type,
          pageCount = 4,
          pages =
            listOf(
              BookPage("EPUB/chapter-1.xhtml", "application/xhtml+xml"),
              BookPage("EPUB/chapter-2.xhtml", "application/xhtml+xml"),
            ),
          files = epubResources,
          extension = MediaExtensionEpub(positions = epubPositions),
        ),
      )
    }

    return book
  }

  private companion object {
    val epubResources =
      listOf(
        MediaFile("EPUB/chapter-1.xhtml", "application/xhtml+xml", MediaFile.SubType.EPUB_PAGE, 301),
        MediaFile("EPUB/chapter-2.xhtml", "application/xhtml+xml", MediaFile.SubType.EPUB_PAGE, 302),
        MediaFile("EPUB/styles.css", "text/css", MediaFile.SubType.EPUB_ASSET, 303),
      )

    val epubPositions =
      listOf(
        R2Locator(
          "EPUB/chapter-1.xhtml",
          "application/xhtml+xml",
          locations = R2Locator.Location(position = 1, progression = 0F, totalProgression = 0.1F),
          koboSpan = "kobo.1.0",
        ),
        R2Locator(
          "EPUB/chapter-1.xhtml",
          "application/xhtml+xml",
          locations = R2Locator.Location(position = 2, progression = 0.5F, totalProgression = 0.25F),
          koboSpan = "kobo.1.1",
        ),
        R2Locator(
          "EPUB/chapter-2.xhtml",
          "application/xhtml+xml",
          locations = R2Locator.Location(position = 3, progression = 0F, totalProgression = 0.42F),
          koboSpan = "kobo.2.0",
        ),
        R2Locator(
          "EPUB/chapter-2.xhtml",
          "application/xhtml+xml",
          locations = R2Locator.Location(position = 4, progression = 0.5F, totalProgression = 0.6F),
          koboSpan = "kobo.2.1",
        ),
      )
  }
}

class PostgreSqlSchemaReset : ApplicationContextInitializer<ConfigurableApplicationContext> {
  override fun initialize(applicationContext: ConfigurableApplicationContext) {
    DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD).use { connection ->
      connection.createStatement().use {
        it.execute("DROP SCHEMA public CASCADE")
        it.execute("CREATE SCHEMA public")
      }
    }
  }
}

private const val JDBC_URL = "jdbc:postgresql://127.0.0.1:5432/komga"
private const val USERNAME = "komga"
private const val PASSWORD = "komga"
