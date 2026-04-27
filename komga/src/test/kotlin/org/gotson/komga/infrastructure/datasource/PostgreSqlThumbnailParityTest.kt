package org.gotson.komga.infrastructure.datasource

import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.Dimension
import org.gotson.komga.domain.model.MarkSelectedPreference
import org.gotson.komga.domain.model.Media
import org.gotson.komga.domain.model.ThumbnailBook
import org.gotson.komga.domain.model.ThumbnailGenerationProfile
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.model.makeLibrary
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.gotson.komga.domain.persistence.ThumbnailBookRepository
import org.gotson.komga.domain.service.BookLifecycle
import org.gotson.komga.infrastructure.configuration.KomgaSettingsProvider
import org.gotson.komga.infrastructure.jooq.main.ServerSettingsDao
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.pathString
import kotlin.io.path.readBytes
import kotlin.io.path.toPath

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
    "komga.thumbnails.storage.mode=filesystem",
  ],
)
@ContextConfiguration(initializers = [PostgreSqlThumbnailParityTest.PostgreSqlSchemaReset::class])
class PostgreSqlThumbnailParityTest(
  @Autowired private val libraryRepository: LibraryRepository,
  @Autowired private val seriesRepository: SeriesRepository,
  @Autowired private val bookRepository: BookRepository,
  @Autowired private val mediaRepository: MediaRepository,
  @Autowired private val thumbnailBookRepository: ThumbnailBookRepository,
  @Autowired private val serverSettingsDao: ServerSettingsDao,
  @Autowired private val komgaSettingsProvider: KomgaSettingsProvider,
  @Autowired private val bookLifecycle: BookLifecycle,
) {
  @BeforeEach
  fun setup() {
    deleteAllBooks()
    serverSettingsDao.deleteAll()
    storageDirectory.toFile().deleteRecursively()
    storageDirectory.createDirectories()
  }

  @AfterEach
  fun tearDown() {
    deleteAllBooks()
    serverSettingsDao.deleteAll()
  }

  @Test
  fun `given PostgreSQL settings provider when updating thumbnail jpeg quality then setting is persisted`() {
    komgaSettingsProvider.thumbnailJpegQuality = 37

    assertThat(komgaSettingsProvider.thumbnailJpegQuality).isEqualTo(37)
    assertThat(serverSettingsDao.getSettingByKey("THUMBNAIL_JPEG_QUALITY", Int::class.java)).isEqualTo(37)

    komgaSettingsProvider.thumbnailJpegQuality = null

    assertThat(komgaSettingsProvider.thumbnailJpegQuality).isNull()
    assertThat(serverSettingsDao.getSettingByKey("THUMBNAIL_JPEG_QUALITY", Int::class.java)).isNull()
  }

  @Test
  fun `given PostgreSQL filesystem storage when adding generated thumbnail then profile and storage metadata are persisted`() {
    val context = insertBookContext("postgres thumbnail")
    val bytes = byteArrayOf(1, 2, 3, 4, 5)
    val profile =
      ThumbnailGenerationProfile(
        format = "image/jpeg",
        targetSize = 300,
        jpegQuality = 73,
      )

    val added =
      bookLifecycle.addThumbnailForBook(
        ThumbnailBook(
          thumbnail = bytes,
          type = ThumbnailBook.Type.GENERATED,
          mediaType = "image/jpeg",
          fileSize = bytes.size.toLong(),
          dimension = Dimension(3, 2),
          generationProfile = profile,
          bookId = context.bookId,
        ),
        MarkSelectedPreference.YES,
      )

    val stored = thumbnailBookRepository.findByIdOrNull(added.id)!!
    val storedPath = stored.url!!.toURI().toPath()

    assertThat(stored.thumbnail).isNull()
    assertThat(stored.generationProfile)
      .isEqualTo(profile.copy(storageMode = ThumbnailGenerationProfile.StorageMode.FILESYSTEM))
    assertThat(stored.generationProfile?.version).isEqualTo(ThumbnailGenerationProfile.CURRENT_VERSION)
    assertThat(storedPath.normalize().pathString).startsWith(storageDirectory.normalize().pathString)
    assertThat(storedPath.exists()).isTrue()
    assertThat(storedPath.readBytes()).isEqualTo(bytes)
    assertThat(thumbnailBookRepository.findSelectedByBookIdOrNull(context.bookId)?.id).isEqualTo(added.id)
    assertThat(bookLifecycle.getThumbnailBytes(context.bookId)?.bytes).isEqualTo(bytes)
  }

  @Test
  fun `given PostgreSQL filesystem storage when generated file is removed then repository metadata remains readable`() {
    val context = insertBookContext("postgres metadata")
    val added =
      bookLifecycle.addThumbnailForBook(
        generatedThumbnail(context.bookId, byteArrayOf(9, 8, 7)),
        MarkSelectedPreference.YES,
      )
    val storedPath =
      thumbnailBookRepository
        .findByIdOrNull(added.id)!!
        .url!!
        .toURI()
        .toPath()

    storedPath.deleteIfExists()

    val stored = thumbnailBookRepository.findByIdOrNull(added.id)!!
    assertThat(stored.url).isNotNull
    assertThat(stored.thumbnail).isNull()
    assertThat(stored.generationProfile?.storageMode).isEqualTo(ThumbnailGenerationProfile.StorageMode.FILESYSTEM)
    assertThat(stored.generationProfile?.jpegQuality).isEqualTo(85)
  }

  private fun insertBookContext(name: String): BookContext {
    val library = makeLibrary(name)
    val series = makeSeries(name, libraryId = library.id)
    val book = makeBook("$name.cbz", libraryId = library.id, seriesId = series.id)

    libraryRepository.insert(library)
    seriesRepository.insert(series)
    bookRepository.insert(book)
    mediaRepository.insert(Media(status = Media.Status.READY, bookId = book.id))

    return BookContext(library.id, series.id, book.id)
  }

  private fun generatedThumbnail(
    bookId: String,
    bytes: ByteArray,
  ): ThumbnailBook =
    ThumbnailBook(
      thumbnail = bytes,
      type = ThumbnailBook.Type.GENERATED,
      mediaType = "image/jpeg",
      fileSize = bytes.size.toLong(),
      dimension = Dimension(2, 2),
      generationProfile =
        ThumbnailGenerationProfile(
          format = "image/jpeg",
          targetSize = 300,
          jpegQuality = 85,
        ),
      bookId = bookId,
    )

  private fun deleteAllBooks() {
    bookRepository.findAll().forEach { book ->
      thumbnailBookRepository.deleteByBookId(book.id)
      mediaRepository.findByIdOrNull(book.id)?.let { mediaRepository.delete(book.id) }
      bookRepository.delete(book.id)
    }
    seriesRepository.findAll().forEach { seriesRepository.delete(it.id) }
    libraryRepository.findAll().forEach { libraryRepository.delete(it.id) }
  }

  private data class BookContext(
    val libraryId: String,
    val seriesId: String,
    val bookId: String,
  )

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

  companion object {
    private const val JDBC_URL = "jdbc:postgresql://127.0.0.1:5432/komga"
    private const val USERNAME = "komga"
    private const val PASSWORD = "komga"
    private val storageDirectory = Files.createTempDirectory("komga-postgres-thumbnail-storage-test")

    @JvmStatic
    @DynamicPropertySource
    fun thumbnailStorageProperties(registry: DynamicPropertyRegistry) {
      registry.add("komga.thumbnails.storage.directory") { storageDirectory.toString() }
    }

    @JvmStatic
    @AfterAll
    fun deleteStorageDirectory() {
      storageDirectory.toFile().deleteRecursively()
    }
  }
}
