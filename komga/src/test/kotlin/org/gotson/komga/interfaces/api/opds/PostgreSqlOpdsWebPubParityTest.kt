package org.gotson.komga.interfaces.api.opds

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.BookPage
import org.gotson.komga.domain.model.Dimension
import org.gotson.komga.domain.model.EpubTocEntry
import org.gotson.komga.domain.model.MarkSelectedPreference
import org.gotson.komga.domain.model.Media
import org.gotson.komga.domain.model.MediaExtensionEpub
import org.gotson.komga.domain.model.MediaFile
import org.gotson.komga.domain.model.ThumbnailBook
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.model.makeLibrary
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.gotson.komga.domain.service.BookLifecycle
import org.gotson.komga.domain.service.LibraryLifecycle
import org.gotson.komga.domain.service.SeriesLifecycle
import org.gotson.komga.interfaces.api.dto.MEDIATYPE_DIVINA_JSON_VALUE
import org.gotson.komga.interfaces.api.dto.MEDIATYPE_OPDS_PUBLICATION_JSON_VALUE
import org.gotson.komga.interfaces.api.dto.MEDIATYPE_WEBPUB_JSON_VALUE
import org.gotson.komga.interfaces.api.dto.PROFILE_DIVINA
import org.gotson.komga.interfaces.api.dto.PROFILE_EPUB
import org.gotson.komga.interfaces.api.dto.PROFILE_PDF
import org.gotson.komga.interfaces.api.rest.WithMockCustomUser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import java.io.ByteArrayInputStream
import java.sql.DriverManager
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

@SpringBootTest
@AutoConfigureMockMvc(printOnlyOnFailure = false)
class SqliteOpdsWebPubParityTest(
  @Autowired libraryRepository: LibraryRepository,
  @Autowired libraryLifecycle: LibraryLifecycle,
  @Autowired seriesLifecycle: SeriesLifecycle,
  @Autowired seriesMetadataRepository: SeriesMetadataRepository,
  @Autowired bookRepository: BookRepository,
  @Autowired mediaRepository: MediaRepository,
  @Autowired bookLifecycle: BookLifecycle,
  @Autowired mockMvc: MockMvc,
  @Autowired objectMapper: ObjectMapper,
) : AbstractOpdsWebPubParityContract(
    libraryRepository,
    libraryLifecycle,
    seriesLifecycle,
    seriesMetadataRepository,
    bookRepository,
    mediaRepository,
    bookLifecycle,
    mockMvc,
    objectMapper,
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
@ContextConfiguration(initializers = [PostgreSqlOpdsWebPubParityTest.PostgreSqlSchemaReset::class])
@AutoConfigureMockMvc(printOnlyOnFailure = false)
class PostgreSqlOpdsWebPubParityTest(
  @Autowired libraryRepository: LibraryRepository,
  @Autowired libraryLifecycle: LibraryLifecycle,
  @Autowired seriesLifecycle: SeriesLifecycle,
  @Autowired seriesMetadataRepository: SeriesMetadataRepository,
  @Autowired bookRepository: BookRepository,
  @Autowired mediaRepository: MediaRepository,
  @Autowired bookLifecycle: BookLifecycle,
  @Autowired mockMvc: MockMvc,
  @Autowired objectMapper: ObjectMapper,
) : AbstractOpdsWebPubParityContract(
    libraryRepository,
    libraryLifecycle,
    seriesLifecycle,
    seriesMetadataRepository,
    bookRepository,
    mediaRepository,
    bookLifecycle,
    mockMvc,
    objectMapper,
  ) {
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

  private companion object {
    const val JDBC_URL = "jdbc:postgresql://127.0.0.1:5432/komga"
    const val USERNAME = "komga"
    const val PASSWORD = "komga"
  }
}

abstract class AbstractOpdsWebPubParityContract(
  private val libraryRepository: LibraryRepository,
  private val libraryLifecycle: LibraryLifecycle,
  private val seriesLifecycle: SeriesLifecycle,
  private val seriesMetadataRepository: SeriesMetadataRepository,
  private val bookRepository: BookRepository,
  private val mediaRepository: MediaRepository,
  private val bookLifecycle: BookLifecycle,
  private val mockMvc: MockMvc,
  private val objectMapper: ObjectMapper,
) {
  @AfterEach
  fun cleanupFixture() {
    clearLibraries()
  }

  @Test
  @WithMockCustomUser
  fun `given representative media when requesting OPDS v1 feeds then normalized facts match`() {
    val fixture = seedRepresentativeFixture()

    assertThat(opdsV1SeriesFacts()).isEqualTo(
      SeriesFeedFacts(
        titles = listOf(fixture.seriesTitle),
        ids = listOf(fixture.seriesId),
      ),
    )

    assertThat(opdsV1SeriesBooksFacts(fixture.seriesId)).isEqualTo(
      BookFeedFacts(
        entries =
          listOf(
            BookFeedEntryFacts(
              title = fixture.zip.title,
              id = fixture.zip.id,
              acquisitionType = "application/zip",
              pageStreamingType = "image/jpeg",
              pageStreamingCount = 2,
              hasThumbnail = true,
              hasCover = true,
            ),
            BookFeedEntryFacts(
              title = fixture.pdf.title,
              id = fixture.pdf.id,
              acquisitionType = "application/pdf",
              pageStreamingType = "image/jpeg",
              pageStreamingCount = 4,
              hasThumbnail = true,
              hasCover = true,
            ),
            BookFeedEntryFacts(
              title = fixture.epub.title,
              id = fixture.epub.id,
              acquisitionType = "application/epub+zip",
              pageStreamingType = "image/jpeg",
              pageStreamingCount = 3,
              hasThumbnail = true,
              hasCover = true,
            ),
          ),
      ),
    )

    listOf(fixture.zip.id, fixture.pdf.id, fixture.epub.id).forEach { bookId ->
      mockMvc
        .get("/opds/v1.2/books/$bookId/thumbnail/small")
        .andExpect { status { isOk() } }
    }
  }

  @Test
  @WithMockCustomUser
  fun `given representative media when requesting OPDS v2 and Readium manifests then normalized facts match`() {
    val fixture = seedRepresentativeFixture()

    assertPublicationFacts(
      fixture.zip,
      expected =
        PublicationFacts(
          title = fixture.zip.title,
          conformsTo = PROFILE_DIVINA,
          numberOfPages = 2,
          acquisitionTypes = listOf("application/vnd.comicbook+zip"),
          readingOrderTypes = listOf("image/jpeg", "image/png"),
          resourceTypes = listOf("image/jpeg"),
          hasThumbnailResource = true,
          tocTitles = emptyList(),
        ),
      webPubContentType = MEDIATYPE_DIVINA_JSON_VALUE,
    )

    assertPublicationFacts(
      fixture.pdf,
      expected =
        PublicationFacts(
          title = fixture.pdf.title,
          conformsTo = PROFILE_PDF,
          numberOfPages = 4,
          acquisitionTypes = listOf("application/pdf"),
          readingOrderTypes = listOf("application/pdf", "application/pdf", "application/pdf", "application/pdf"),
          resourceTypes = listOf("image/jpeg"),
          hasThumbnailResource = true,
          tocTitles = emptyList(),
        ),
      webPubContentType = MEDIATYPE_WEBPUB_JSON_VALUE,
    )

    assertPublicationFacts(
      fixture.epub,
      expected =
        PublicationFacts(
          title = fixture.epub.title,
          conformsTo = PROFILE_EPUB,
          numberOfPages = 3,
          acquisitionTypes = listOf("application/epub+zip"),
          readingOrderTypes = listOf("application/xhtml+xml", "application/xhtml+xml"),
          resourceTypes = listOf("image/jpeg", "text/css", "image/png"),
          hasThumbnailResource = true,
          tocTitles = listOf("Chapter 1"),
        ),
      webPubContentType = MEDIATYPE_WEBPUB_JSON_VALUE,
    )
  }

  @Test
  @WithMockCustomUser(sharedAllLibraries = false, sharedLibraries = ["opds-parity-shared-library"], allowAgeUnder = 12)
  fun `given restricted user when requesting OPDS and WebPub surfaces then only permitted content is visible`() {
    val fixture = seedRestrictedFixture()

    assertThat(opdsV1SeriesFacts()).isEqualTo(
      SeriesFeedFacts(
        titles = listOf(fixture.allowedSeriesTitle),
        ids = listOf(fixture.allowedSeriesId),
      ),
    )

    mockMvc
      .get("/opds/v1.2/series/${fixture.allowedSeriesId}")
      .andExpect { status { isOk() } }
    mockMvc
      .get("/opds/v1.2/series/${fixture.matureSeriesId}")
      .andExpect { status { isForbidden() } }
    mockMvc
      .get("/opds/v1.2/series/${fixture.otherLibrarySeriesId}")
      .andExpect { status { isForbidden() } }

    mockMvc
      .get("/opds/v2/books/${fixture.allowedBookId}/manifest")
      .andExpect { status { isOk() } }
    mockMvc
      .get("/api/v1/books/${fixture.allowedBookId}/manifest")
      .andExpect { status { isOk() } }
    mockMvc
      .get("/opds/v2/books/${fixture.matureBookId}/manifest")
      .andExpect { status { isForbidden() } }
    mockMvc
      .get("/api/v1/books/${fixture.matureBookId}/manifest")
      .andExpect { status { isForbidden() } }
    mockMvc
      .get("/opds/v2/books/${fixture.otherLibraryBookId}/manifest")
      .andExpect { status { isForbidden() } }
  }

  private fun assertPublicationFacts(
    book: SeededBook,
    expected: PublicationFacts,
    webPubContentType: String,
  ) {
    val opdsFacts = publicationFacts("/opds/v2/books/${book.id}/manifest", MEDIATYPE_OPDS_PUBLICATION_JSON_VALUE)
    val webPubFacts = publicationFacts("/api/v1/books/${book.id}/manifest", webPubContentType)

    assertThat(opdsFacts).isEqualTo(expected)
    assertThat(webPubFacts).isEqualTo(expected)
  }

  private fun seedRepresentativeFixture(): RepresentativeFixture {
    clearLibraries()

    val library = makeLibrary(name = "OPDS Parity", id = "opds-parity-library")
    libraryRepository.insert(library)

    val series = seriesLifecycle.createSeries(makeSeries(name = "OPDS Parity Series", libraryId = library.id))
    val zip = SeededBook(id = "opds-parity-zip", title = "01 ZIP Comic")
    val pdf = SeededBook(id = "opds-parity-pdf", title = "02 PDF Book")
    val epub = SeededBook(id = "opds-parity-epub", title = "03 EPUB Book")

    seriesLifecycle.addBooks(
      series,
      listOf(
        makeBook(zip.title, libraryId = library.id, id = zip.id),
        makeBook(pdf.title, libraryId = library.id, id = pdf.id),
        makeBook(epub.title, libraryId = library.id, id = epub.id),
      ),
    )
    seriesLifecycle.sortBooks(series)

    makeZipReady(zip.id)
    makePdfReady(pdf.id)
    makeEpubReady(epub.id)
    listOf(zip.id, pdf.id, epub.id).forEach(::addGeneratedThumbnail)

    return RepresentativeFixture(series.id, series.name, zip, pdf, epub)
  }

  private fun seedRestrictedFixture(): RestrictedFixture {
    clearLibraries()

    val sharedLibrary = makeLibrary(name = "Shared", id = "opds-parity-shared-library")
    val otherLibrary = makeLibrary(name = "Other", id = "opds-parity-other-library")
    libraryRepository.insert(sharedLibrary)
    libraryRepository.insert(otherLibrary)

    val allowedSeries = createReadyZipSeries("Allowed Series", sharedLibrary.id, "restricted-allowed-book", 10)
    val matureSeries = createReadyZipSeries("Mature Series", sharedLibrary.id, "restricted-mature-book", 16)
    val otherSeries = createReadyZipSeries("Other Library Series", otherLibrary.id, "restricted-other-book", 10)

    return RestrictedFixture(
      allowedSeriesId = allowedSeries.seriesId,
      allowedSeriesTitle = allowedSeries.seriesTitle,
      allowedBookId = allowedSeries.bookId,
      matureSeriesId = matureSeries.seriesId,
      matureBookId = matureSeries.bookId,
      otherLibrarySeriesId = otherSeries.seriesId,
      otherLibraryBookId = otherSeries.bookId,
    )
  }

  private fun createReadyZipSeries(
    title: String,
    libraryId: String,
    bookId: String,
    ageRating: Int,
  ): RestrictedSeries {
    val series = seriesLifecycle.createSeries(makeSeries(name = title, libraryId = libraryId))
    seriesLifecycle.addBooks(series, listOf(makeBook("$title Book", libraryId = libraryId, id = bookId)))
    seriesMetadataRepository.findById(series.id).let {
      seriesMetadataRepository.update(it.copy(ageRating = ageRating))
    }
    makeZipReady(bookId)
    addGeneratedThumbnail(bookId)

    return RestrictedSeries(series.id, series.name, bookId)
  }

  private fun makeZipReady(bookId: String) {
    updateMedia(
      bookId,
      mediaType = "application/zip",
      pages =
        listOf(
          BookPage("001.jpg", "image/jpeg", Dimension(800, 1200), fileSize = 101),
          BookPage("002.png", "image/png", Dimension(810, 1210), fileSize = 102),
        ),
      pageCount = 2,
    )
  }

  private fun makePdfReady(bookId: String) {
    updateMedia(
      bookId,
      mediaType = "application/pdf",
      pages = emptyList(),
      pageCount = 4,
    )
  }

  private fun makeEpubReady(bookId: String) {
    updateMedia(
      bookId,
      mediaType = "application/epub+zip",
      pages =
        listOf(
          BookPage("page-1.xhtml", "image/jpeg", Dimension(700, 1000), fileSize = 201),
          BookPage("page-2.xhtml", "image/jpeg", Dimension(700, 1000), fileSize = 202),
          BookPage("page-3.xhtml", "image/jpeg", Dimension(700, 1000), fileSize = 203),
        ),
      pageCount = 3,
      files =
        listOf(
          MediaFile("EPUB/chapter-1.xhtml", "application/xhtml+xml", MediaFile.SubType.EPUB_PAGE, 301),
          MediaFile("EPUB/chapter-2.xhtml", "application/xhtml+xml", MediaFile.SubType.EPUB_PAGE, 302),
          MediaFile("EPUB/styles.css", "text/css", MediaFile.SubType.EPUB_ASSET, 303),
          MediaFile("EPUB/image.png", "image/png", MediaFile.SubType.EPUB_ASSET, 304),
        ),
      extension = MediaExtensionEpub(toc = listOf(EpubTocEntry("Chapter 1", "EPUB/chapter-1.xhtml"))),
      epubDivinaCompatible = true,
    )
  }

  private fun updateMedia(
    bookId: String,
    mediaType: String,
    pages: List<BookPage>,
    pageCount: Int,
    files: List<MediaFile> = emptyList(),
    extension: MediaExtensionEpub? = null,
    epubDivinaCompatible: Boolean = false,
  ) {
    mediaRepository.findById(bookId).let { media ->
      mediaRepository.update(
        media.copy(
          status = Media.Status.READY,
          mediaType = mediaType,
          pages = pages,
          pageCount = pageCount,
          files = files,
          extension = extension,
          epubDivinaCompatible = epubDivinaCompatible,
        ),
      )
    }
  }

  private fun addGeneratedThumbnail(bookId: String) {
    bookLifecycle.addThumbnailForBook(
      ThumbnailBook(
        thumbnail = byteArrayOf(1, 2, 3, 4),
        bookId = bookId,
        selected = true,
        type = ThumbnailBook.Type.GENERATED,
        mediaType = "image/jpeg",
        fileSize = 4,
        dimension = Dimension(2, 2),
      ),
      MarkSelectedPreference.YES,
    )
  }

  private fun opdsV1SeriesFacts(): SeriesFeedFacts {
    val document = getXmlDocument("/opds/v1.2/series")
    return SeriesFeedFacts(
      titles = strings(document, "/feed/entry/title"),
      ids = strings(document, "/feed/entry/id"),
    )
  }

  private fun opdsV1SeriesBooksFacts(seriesId: String): BookFeedFacts {
    val document = getXmlDocument("/opds/v1.2/series/$seriesId")
    val count = number(document, "count(/feed/entry)").toInt()

    return BookFeedFacts(
      entries =
        (1..count).map { index ->
          BookFeedEntryFacts(
            title = string(document, "/feed/entry[$index]/title"),
            id = string(document, "/feed/entry[$index]/id"),
            acquisitionType = string(document, "/feed/entry[$index]/link[@rel='http://opds-spec.org/acquisition']/@type"),
            pageStreamingType = string(document, "/feed/entry[$index]/link[@rel='http://vaemendis.net/opds-pse/stream']/@type"),
            pageStreamingCount = string(document, "/feed/entry[$index]/link[@rel='http://vaemendis.net/opds-pse/stream']/@*[local-name()='count']").toInt(),
            hasThumbnail = boolean(document, "/feed/entry[$index]/link[@rel='http://opds-spec.org/image/thumbnail']"),
            hasCover = boolean(document, "/feed/entry[$index]/link[@rel='http://opds-spec.org/image']"),
          )
        },
    )
  }

  private fun publicationFacts(
    path: String,
    contentType: String,
  ): PublicationFacts {
    val result =
      mockMvc
        .get(path)
        .andExpect {
          status { isOk() }
          content { contentType(contentType) }
        }.andReturn()
    val json = objectMapper.readTree(result.response.contentAsString)
    val resources = json.array("resources")

    return PublicationFacts(
      title = json.at("/metadata/title").asText(),
      conformsTo = json.at("/metadata/conformsTo").asText(),
      numberOfPages = json.at("/metadata/numberOfPages").asInt(),
      acquisitionTypes = json.array("links").filter { it.relValues().contains("http://opds-spec.org/acquisition") }.mapNotNull { it["type"]?.asText() },
      readingOrderTypes = json.array("readingOrder").mapNotNull { it["type"]?.asText() },
      resourceTypes = resources.mapNotNull { it["type"]?.asText() },
      hasThumbnailResource = resources.any { it["href"]?.asText()?.endsWith("/thumbnail") == true },
      tocTitles = json.array("toc").mapNotNull { it["title"]?.asText() },
    )
  }

  private fun getXmlDocument(path: String) =
    mockMvc
      .get(path)
      .andExpect { status { isOk() } }
      .andReturn()
      .xmlDocument()

  private fun MvcResult.xmlDocument() =
    DocumentBuilderFactory
      .newInstance()
      .newDocumentBuilder()
      .parse(ByteArrayInputStream(response.contentAsByteArray))

  private fun strings(
    document: org.w3c.dom.Document,
    expression: String,
  ): List<String> {
    val nodes = xpath.evaluate(expression, document, XPathConstants.NODESET) as org.w3c.dom.NodeList
    return (0 until nodes.length).map { nodes.item(it).textContent }
  }

  private fun string(
    document: org.w3c.dom.Document,
    expression: String,
  ): String = xpath.evaluate(expression, document)

  private fun boolean(
    document: org.w3c.dom.Document,
    expression: String,
  ): Boolean = xpath.evaluate("boolean($expression)", document, XPathConstants.BOOLEAN) as Boolean

  private fun number(
    document: org.w3c.dom.Document,
    expression: String,
  ): Double = xpath.evaluate(expression, document, XPathConstants.NUMBER) as Double

  private fun JsonNode.array(name: String): List<JsonNode> = get(name)?.toList().orEmpty()

  private fun JsonNode.relValues(): List<String> =
    when (val rel = get("rel")) {
      null -> emptyList()
      else -> if (rel.isArray) rel.map { it.asText() } else listOf(rel.asText())
    }

  private fun clearLibraries() {
    libraryRepository.findAll().forEach {
      libraryLifecycle.deleteLibrary(it)
    }
    assertThat(bookRepository.findAll()).isEmpty()
  }

  private companion object {
    val xpath = XPathFactory.newInstance().newXPath()
  }

  data class SeededBook(
    val id: String,
    val title: String,
  )

  data class RepresentativeFixture(
    val seriesId: String,
    val seriesTitle: String,
    val zip: SeededBook,
    val pdf: SeededBook,
    val epub: SeededBook,
  )

  data class RestrictedFixture(
    val allowedSeriesId: String,
    val allowedSeriesTitle: String,
    val allowedBookId: String,
    val matureSeriesId: String,
    val matureBookId: String,
    val otherLibrarySeriesId: String,
    val otherLibraryBookId: String,
  )

  data class RestrictedSeries(
    val seriesId: String,
    val seriesTitle: String,
    val bookId: String,
  )

  data class SeriesFeedFacts(
    val titles: List<String>,
    val ids: List<String>,
  )

  data class BookFeedFacts(
    val entries: List<BookFeedEntryFacts>,
  )

  data class BookFeedEntryFacts(
    val title: String,
    val id: String,
    val acquisitionType: String,
    val pageStreamingType: String,
    val pageStreamingCount: Int,
    val hasThumbnail: Boolean,
    val hasCover: Boolean,
  )

  data class PublicationFacts(
    val title: String,
    val conformsTo: String,
    val numberOfPages: Int,
    val acquisitionTypes: List<String>,
    val readingOrderTypes: List<String>,
    val resourceTypes: List<String>,
    val hasThumbnailResource: Boolean,
    val tocTitles: List<String>,
  )
}
