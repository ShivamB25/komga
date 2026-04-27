package org.gotson.komga.interfaces.api.rest

import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.Media
import org.gotson.komga.domain.model.Series
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.model.makeLibrary
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.gotson.komga.domain.service.LibraryLifecycle
import org.gotson.komga.domain.service.SeriesLifecycle
import org.hamcrest.Matchers.equalToIgnoringCase
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
import org.springframework.test.web.servlet.multipart
import java.net.URI
import java.sql.DriverManager

@SpringBootTest
@AutoConfigureMockMvc(printOnlyOnFailure = false)
class SqliteSortControllerParityTest(
  @Autowired libraryRepository: LibraryRepository,
  @Autowired libraryLifecycle: LibraryLifecycle,
  @Autowired seriesLifecycle: SeriesLifecycle,
  @Autowired seriesMetadataRepository: SeriesMetadataRepository,
  @Autowired bookRepository: BookRepository,
  @Autowired mediaRepository: MediaRepository,
  @Autowired mockMvc: MockMvc,
) : AbstractSortControllerParityContract(
    libraryRepository,
    libraryLifecycle,
    seriesLifecycle,
    seriesMetadataRepository,
    bookRepository,
    mediaRepository,
    mockMvc,
  )

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
  ],
)
@ContextConfiguration(initializers = [RestPostgreSqlSchemaReset::class])
@AutoConfigureMockMvc(printOnlyOnFailure = false)
class PostgreSqlSortControllerParityTest(
  @Autowired libraryRepository: LibraryRepository,
  @Autowired libraryLifecycle: LibraryLifecycle,
  @Autowired seriesLifecycle: SeriesLifecycle,
  @Autowired seriesMetadataRepository: SeriesMetadataRepository,
  @Autowired bookRepository: BookRepository,
  @Autowired mediaRepository: MediaRepository,
  @Autowired mockMvc: MockMvc,
) : AbstractSortControllerParityContract(
    libraryRepository,
    libraryLifecycle,
    seriesLifecycle,
    seriesMetadataRepository,
    bookRepository,
    mediaRepository,
    mockMvc,
  )

@SpringBootTest
@AutoConfigureMockMvc(printOnlyOnFailure = false)
class SqliteControllerRestrictionThumbnailParityTest(
  @Autowired libraryRepository: LibraryRepository,
  @Autowired libraryLifecycle: LibraryLifecycle,
  @Autowired seriesLifecycle: SeriesLifecycle,
  @Autowired seriesMetadataRepository: SeriesMetadataRepository,
  @Autowired bookRepository: BookRepository,
  @Autowired mockMvc: MockMvc,
) : AbstractControllerRestrictionThumbnailParityContract(
    libraryRepository,
    libraryLifecycle,
    seriesLifecycle,
    seriesMetadataRepository,
    bookRepository,
    mockMvc,
  )

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
  ],
)
@ContextConfiguration(initializers = [RestPostgreSqlSchemaReset::class])
@AutoConfigureMockMvc(printOnlyOnFailure = false)
class PostgreSqlControllerRestrictionThumbnailParityTest(
  @Autowired libraryRepository: LibraryRepository,
  @Autowired libraryLifecycle: LibraryLifecycle,
  @Autowired seriesLifecycle: SeriesLifecycle,
  @Autowired seriesMetadataRepository: SeriesMetadataRepository,
  @Autowired bookRepository: BookRepository,
  @Autowired mockMvc: MockMvc,
) : AbstractControllerRestrictionThumbnailParityContract(
    libraryRepository,
    libraryLifecycle,
    seriesLifecycle,
    seriesMetadataRepository,
    bookRepository,
    mockMvc,
  )

abstract class AbstractSortControllerParityContract(
  private val libraryRepository: LibraryRepository,
  private val libraryLifecycle: LibraryLifecycle,
  private val seriesLifecycle: SeriesLifecycle,
  private val seriesMetadataRepository: SeriesMetadataRepository,
  private val bookRepository: BookRepository,
  private val mediaRepository: MediaRepository,
  private val mockMvc: MockMvc,
) {
  @AfterEach
  fun cleanupFixture() {
    clearLibraries()
  }

  @Test
  @WithMockCustomUser
  fun `given mixed-case series titles when sorting by titleSort then order is case-insensitive`() {
    val library = createLibrary()
    listOf("a", "b", "B", "C")
      .map { name -> makeSeries(name, libraryId = library.id) }
      .forEach { seriesLifecycle.createSeries(it) }

    mockMvc
      .get("/api/v1/series") {
        param("sort", "metadata.titleSort,asc")
      }.andExpect {
        status { isOk() }
        jsonPath("$.content.length()") { value(4) }
        jsonPath("$.content[0].metadata.title") { value("a") }
        jsonPath("$.content[1].metadata.title") { value(equalToIgnoringCase("b")) }
        jsonPath("$.content[2].metadata.title") { value(equalToIgnoringCase("b")) }
        jsonPath("$.content[3].metadata.title") { value("C") }
      }
  }

  @Test
  @WithMockCustomUser
  fun `given books with mixed-case URL and media fields when sorting then order is case-insensitive`() {
    val library = createLibrary()
    val series = seriesLifecycle.createSeries(makeSeries("sort-series", libraryId = library.id))
    val books =
      listOf(
        makeBook("a", libraryId = library.id, url = URI.create("file:/sort/a.cbz").toURL()),
        makeBook("b", libraryId = library.id, url = URI.create("file:/sort/b.cbz").toURL()),
        makeBook("B", libraryId = library.id, url = URI.create("file:/sort/B.cbz").toURL()),
        makeBook("C", libraryId = library.id, url = URI.create("file:/sort/C.cbz").toURL()),
      )
    seriesLifecycle.addBooks(series, books)

    mockMvc
      .get("/api/v1/books") {
        param("sort", "url,asc")
      }.andExpect {
        status { isOk() }
        jsonPath("$.content.length()") { value(4) }
        jsonPath("$.content[0].name") { value("a") }
        jsonPath("$.content[1].name") { value(equalToIgnoringCase("b")) }
        jsonPath("$.content[2].name") { value(equalToIgnoringCase("b")) }
        jsonPath("$.content[3].name") { value("C") }
      }

    val booksByName = bookRepository.findAll().associateBy { it.name }
    setMedia(booksByName.getValue("a"), Media.Status.UNKNOWN, "Image/JPEG")
    setMedia(booksByName.getValue("b"), Media.Status.READY, "application/pdf")
    setMedia(booksByName.getValue("B"), Media.Status.OUTDATED, "Application/EPUB+ZIP")
    setMedia(booksByName.getValue("C"), Media.Status.ERROR, "application/cbz")

    mockMvc
      .get("/api/v1/books") {
        param("sort", "media.status,asc")
      }.andExpect {
        status { isOk() }
        jsonPath("$.content.length()") { value(4) }
        jsonPath("$.content[0].media.status") { value(Media.Status.ERROR.name) }
        jsonPath("$.content[1].media.status") { value(Media.Status.OUTDATED.name) }
        jsonPath("$.content[2].media.status") { value(Media.Status.READY.name) }
        jsonPath("$.content[3].media.status") { value(Media.Status.UNKNOWN.name) }
      }

    mockMvc
      .get("/api/v1/books") {
        param("sort", "media.mediaType,asc")
      }.andExpect {
        status { isOk() }
        jsonPath("$.content.length()") { value(4) }
        jsonPath("$.content[0].media.mediaType") { value("application/cbz") }
        jsonPath("$.content[1].media.mediaType") { value("Application/EPUB+ZIP") }
        jsonPath("$.content[2].media.mediaType") { value("application/pdf") }
        jsonPath("$.content[3].media.mediaType") { value("Image/JPEG") }
      }
  }

  private fun setMedia(
    book: Book,
    status: Media.Status,
    mediaType: String,
  ) {
    mediaRepository.findById(book.id).let {
      mediaRepository.update(it.copy(status = status, mediaType = mediaType))
    }
  }

  private fun createLibrary() =
    makeLibrary("controller-sort-parity-${System.nanoTime()}")
      .also { libraryRepository.insert(it) }

  private fun clearLibraries() {
    libraryRepository.findAll().forEach {
      libraryLifecycle.deleteLibrary(it)
    }
  }
}

abstract class AbstractControllerRestrictionThumbnailParityContract(
  private val libraryRepository: LibraryRepository,
  private val libraryLifecycle: LibraryLifecycle,
  private val seriesLifecycle: SeriesLifecycle,
  private val seriesMetadataRepository: SeriesMetadataRepository,
  private val bookRepository: BookRepository,
  private val mockMvc: MockMvc,
) {
  @AfterEach
  fun cleanupFixture() {
    clearLibraries()
  }

  @Test
  @WithMockCustomUser(allowAgeUnder = 10)
  fun `given age restricted user when getting books and series then only allowed content is visible`() {
    val library = createLibrary()
    val (series10, book10) = createSeriesWithBook(library.id, "series_10", "book_10", ageRating = 10)
    val (series5, book5) = createSeriesWithBook(library.id, "series_5", "book_5", ageRating = 5)
    val (series15, book15) = createSeriesWithBook(library.id, "series_15", "book_15", ageRating = 15)
    val (unratedSeries, unratedBook) = createSeriesWithBook(library.id, "series_no", "book")

    mockMvc.get("/api/v1/series/${series5.id}").andExpect { status { isOk() } }
    mockMvc.get("/api/v1/series/${series10.id}").andExpect { status { isOk() } }
    mockMvc.get("/api/v1/series/${series15.id}").andExpect { status { isForbidden() } }
    mockMvc.get("/api/v1/series/${unratedSeries.id}").andExpect { status { isForbidden() } }
    mockMvc.get("/api/v1/books/${book5.id}").andExpect { status { isOk() } }
    mockMvc.get("/api/v1/books/${book10.id}").andExpect { status { isOk() } }
    mockMvc.get("/api/v1/books/${book15.id}").andExpect { status { isForbidden() } }
    mockMvc.get("/api/v1/books/${unratedBook.id}").andExpect { status { isForbidden() } }

    mockMvc
      .get("/api/v1/series?sort=metadata.titleSort")
      .andExpect {
        status { isOk() }
        jsonPath("$.content.length()") { value(2) }
        jsonPath("$.content[0].name") { value(series10.name) }
        jsonPath("$.content[1].name") { value(series5.name) }
      }

    mockMvc
      .get("/api/v1/books?sort=metadata.title")
      .andExpect {
        status { isOk() }
        jsonPath("$.content.length()") { value(2) }
        jsonPath("$.content[0].name") { value(book10.name) }
        jsonPath("$.content[1].name") { value(book5.name) }
      }
  }

  @Test
  @WithMockCustomUser(allowLabels = ["kids", "cute"])
  fun `given label restricted user when getting books and series then only matching content is visible`() {
    val library = createLibrary()
    val (seriesKids, bookKids) = createSeriesWithBook(library.id, "series_kids", "book_kids", labels = setOf("kids"))
    val (seriesCute, bookCute) = createSeriesWithBook(library.id, "series_cute", "book_cute", labels = setOf("cute", "other"))
    val (seriesAdult, bookAdult) = createSeriesWithBook(library.id, "series_adult", "book_adult", labels = setOf("adult"))
    val (unlabeledSeries, unlabeledBook) = createSeriesWithBook(library.id, "series_no", "book")

    mockMvc.get("/api/v1/series/${seriesKids.id}").andExpect { status { isOk() } }
    mockMvc.get("/api/v1/series/${seriesCute.id}").andExpect { status { isOk() } }
    mockMvc.get("/api/v1/series/${seriesAdult.id}").andExpect { status { isForbidden() } }
    mockMvc.get("/api/v1/series/${unlabeledSeries.id}").andExpect { status { isForbidden() } }
    mockMvc.get("/api/v1/books/${bookKids.id}").andExpect { status { isOk() } }
    mockMvc.get("/api/v1/books/${bookCute.id}").andExpect { status { isOk() } }
    mockMvc.get("/api/v1/books/${bookAdult.id}").andExpect { status { isForbidden() } }
    mockMvc.get("/api/v1/books/${unlabeledBook.id}").andExpect { status { isForbidden() } }

    mockMvc
      .get("/api/v1/series?sort=metadata.titleSort")
      .andExpect {
        status { isOk() }
        jsonPath("$.content.length()") { value(2) }
        jsonPath("$.content[0].name") { value(seriesCute.name) }
        jsonPath("$.content[1].name") { value(seriesKids.name) }
      }

    mockMvc
      .get("/api/v1/books?sort=metadata.title")
      .andExpect {
        status { isOk() }
        jsonPath("$.content.length()") { value(2) }
        jsonPath("$.content[0].name") { value(bookCute.name) }
        jsonPath("$.content[1].name") { value(bookKids.name) }
      }
  }

  @Test
  @WithMockCustomUser(roles = ["ADMIN"])
  fun `given uploaded book thumbnail when retrieving selected thumbnail then bytes match`() {
    val library = createLibrary()
    val (_, book) = createSeriesWithBook(library.id, "thumbnail-series", "thumbnail-book")
    val bytes = uploadedJpegBytes()

    mockMvc
      .multipart("/api/v1/books/${book.id}/thumbnails") {
        file("file", bytes)
        param("selected", "true")
      }.andExpect {
        status { isOk() }
        jsonPath("$.type") { value("USER_UPLOADED") }
        jsonPath("$.selected") { value(true) }
        jsonPath("$.mediaType") { value(MediaType.IMAGE_JPEG_VALUE) }
      }

    mockMvc
      .get("/api/v1/books/${book.id}/thumbnails")
      .andExpect {
        status { isOk() }
        jsonPath("$.length()") { value(1) }
        jsonPath("$[0].type") { value("USER_UPLOADED") }
        jsonPath("$[0].selected") { value(true) }
      }

    mockMvc
      .get("/api/v1/books/${book.id}/thumbnail")
      .andExpect {
        status { isOk() }
        content { bytes(bytes) }
      }
  }

  private fun createSeriesWithBook(
    libraryId: String,
    seriesName: String,
    bookName: String,
    ageRating: Int? = null,
    labels: Set<String> = emptySet(),
  ): Pair<Series, Book> {
    val series =
      seriesLifecycle
        .createSeries(makeSeries(seriesName, libraryId = libraryId))
        .also { created ->
          seriesLifecycle.addBooks(created, listOf(makeBook(bookName, libraryId = libraryId)))
        }
    val book = bookRepository.findAll().first { it.seriesId == series.id }
    seriesMetadataRepository.findById(series.id).let {
      seriesMetadataRepository.update(it.copy(ageRating = ageRating, sharingLabels = labels))
    }
    return series to book
  }

  private fun createLibrary() =
    makeLibrary("controller-filter-parity-${System.nanoTime()}")
      .also { libraryRepository.insert(it) }

  private fun clearLibraries() {
    libraryRepository.findAll().forEach {
      libraryLifecycle.deleteLibrary(it)
    }
  }

  private fun uploadedJpegBytes(): ByteArray =
    requireNotNull(javaClass.getResourceAsStream("/barcode/page_384.jpg")) {
      "Missing JPEG test fixture"
    }.use { it.readBytes() }
}

class RestPostgreSqlSchemaReset : ApplicationContextInitializer<ConfigurableApplicationContext> {
  override fun initialize(applicationContext: ConfigurableApplicationContext) {
    DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD).use { connection ->
      connection.createStatement().use {
        it.execute("DROP SCHEMA public CASCADE")
        it.execute("CREATE SCHEMA public")
      }
    }
  }

  private companion object {
    const val JDBC_URL = "jdbc:postgresql://127.0.0.1:5432/komga"
    const val USERNAME = "komga"
    const val PASSWORD = "komga"
  }
}
