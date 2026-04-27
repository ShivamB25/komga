package org.gotson.komga.domain.service

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.BookWithMedia
import org.gotson.komga.domain.model.Dimension
import org.gotson.komga.domain.model.MarkSelectedPreference
import org.gotson.komga.domain.model.Media
import org.gotson.komga.domain.model.PageHashKnown
import org.gotson.komga.domain.model.ReadList
import org.gotson.komga.domain.model.SeriesCollection
import org.gotson.komga.domain.model.ThumbnailBook
import org.gotson.komga.domain.model.ThumbnailGenerationProfile
import org.gotson.komga.domain.model.ThumbnailReadList
import org.gotson.komga.domain.model.ThumbnailSeriesCollection
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.model.makeLibrary
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.domain.persistence.ReadListRepository
import org.gotson.komga.domain.persistence.SeriesCollectionRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.gotson.komga.domain.persistence.ThumbnailBookRepository
import org.gotson.komga.domain.persistence.ThumbnailReadListRepository
import org.gotson.komga.domain.persistence.ThumbnailSeriesCollectionRepository
import org.gotson.komga.infrastructure.image.ThumbnailContentStorage
import org.gotson.komga.infrastructure.jooq.main.PageHashDao
import org.gotson.komga.jooq.main.Tables.THUMBNAIL_COLLECTION
import org.gotson.komga.jooq.main.Tables.THUMBNAIL_READLIST
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.Pageable
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.outputStream
import kotlin.io.path.pathString
import kotlin.io.path.readBytes
import kotlin.io.path.toPath

@SpringBootTest
class BookThumbnailStorageTest(
  @Autowired private val libraryRepository: LibraryRepository,
  @Autowired private val seriesRepository: SeriesRepository,
  @Autowired private val bookRepository: BookRepository,
  @Autowired private val mediaRepository: MediaRepository,
  @Autowired private val collectionRepository: SeriesCollectionRepository,
  @Autowired private val readListRepository: ReadListRepository,
  @Autowired private val thumbnailBookRepository: ThumbnailBookRepository,
  @Autowired private val thumbnailCollectionRepository: ThumbnailSeriesCollectionRepository,
  @Autowired private val thumbnailReadListRepository: ThumbnailReadListRepository,
  @Autowired private val pageHashDao: PageHashDao,
  @Autowired private val bookLifecycle: BookLifecycle,
) {
  @MockkBean
  private lateinit var bookAnalyzer: BookAnalyzer

  @BeforeEach
  fun cleanup() {
    deleteAllBooks()
    storageDirectory.toFile().deleteRecursively()
    storageDirectory.createDirectories()
  }

  @AfterEach
  fun tearDown() {
    deleteAllBooks()
  }

  @Test
  fun `given filesystem storage when adding generated thumbnail then bytes are stored under configured root`() {
    val context = insertBookContext("filesystem generated")
    val bytes = byteArrayOf(1, 2, 3, 4)

    val added =
      bookLifecycle.addThumbnailForBook(
        generatedThumbnail(context.bookId, bytes),
        MarkSelectedPreference.YES,
      )

    val stored = thumbnailBookRepository.findByIdOrNull(added.id)!!
    val storedPath = stored.url!!.toURI().toPath()
    assertThat(stored.thumbnail).isNull()
    assertThat(stored.generationProfile?.storageMode).isEqualTo(ThumbnailGenerationProfile.StorageMode.FILESYSTEM)
    assertThat(storedPath.normalize().pathString).startsWith(storageDirectory.normalize().pathString)
    assertThat(storedPath.exists()).isTrue()
    assertThat(storedPath.readBytes()).isEqualTo(bytes)
    assertThat(bookLifecycle.getThumbnailBytes(context.bookId)?.bytes).isEqualTo(bytes)
  }

  @Test
  fun `given filesystem storage when adding uploaded thumbnail then durable bytes remain database backed`() {
    val context = insertBookContext("filesystem uploaded")
    val bytes = byteArrayOf(9, 8, 7)

    val added =
      bookLifecycle.addThumbnailForBook(
        ThumbnailBook(
          thumbnail = bytes,
          type = ThumbnailBook.Type.USER_UPLOADED,
          mediaType = "image/jpeg",
          fileSize = bytes.size.toLong(),
          dimension = Dimension(3, 1),
          bookId = context.bookId,
        ),
        MarkSelectedPreference.YES,
      )

    val stored = thumbnailBookRepository.findByIdOrNull(added.id)!!
    assertThat(stored.thumbnail).isEqualTo(bytes)
    assertThat(stored.url).isNull()
    assertThat(stored.generationProfile).isNull()
  }

  @Test
  fun `given filesystem storage when adding collection thumbnail then bytes remain database backed`() {
    val context = insertBookContext("filesystem collection")
    val collection = SeriesCollection("collection", seriesIds = listOf(context.seriesId))
    val bytes = byteArrayOf(2, 4, 6)
    collectionRepository.insert(collection)

    thumbnailCollectionRepository.insert(
      ThumbnailSeriesCollection(
        thumbnail = bytes,
        selected = true,
        type = ThumbnailSeriesCollection.Type.USER_UPLOADED,
        mediaType = "image/jpeg",
        fileSize = bytes.size.toLong(),
        dimension = Dimension(3, 1),
        collectionId = collection.id,
      ),
    )

    val stored = thumbnailCollectionRepository.findSelectedByCollectionIdOrNull(collection.id)!!
    assertThat(stored.thumbnail).isEqualTo(bytes)
    assertThat(storageDirectory.listRegularFiles()).isEmpty()
  }

  @Test
  fun `given filesystem storage when adding read list thumbnail then bytes remain database backed`() {
    val context = insertBookContext("filesystem readlist")
    val readList = ReadList("readlist", bookIds = sortedMapOf(0 to context.bookId))
    val bytes = byteArrayOf(3, 6, 9)
    readListRepository.insert(readList)

    thumbnailReadListRepository.insert(
      ThumbnailReadList(
        thumbnail = bytes,
        selected = true,
        type = ThumbnailReadList.Type.USER_UPLOADED,
        mediaType = "image/jpeg",
        fileSize = bytes.size.toLong(),
        dimension = Dimension(3, 1),
        readListId = readList.id,
      ),
    )

    val stored = thumbnailReadListRepository.findSelectedByReadListIdOrNull(readList.id)!!
    assertThat(stored.thumbnail).isEqualTo(bytes)
    assertThat(storageDirectory.listRegularFiles()).isEmpty()
  }

  @Test
  fun `given filesystem storage when adding page hash thumbnail then bytes remain database backed`() {
    val context = insertBookContext("filesystem page hash")
    val bytes = byteArrayOf(5, 10, 15)
    val pageHash =
      PageHashKnown(
        hash = "hash-${context.bookId}",
        size = bytes.size.toLong(),
        action = PageHashKnown.Action.DELETE_AUTO,
      )

    pageHashDao.insert(pageHash, bytes)

    assertThat(pageHashDao.getKnownThumbnail(pageHash.hash)).isEqualTo(bytes)
    assertThat(storageDirectory.listRegularFiles()).isEmpty()
  }

  @Test
  fun `thumbnail content storage only routes generated book thumbnails to configurable storage`() {
    val storeGeneratedParameterTypes =
      ThumbnailContentStorage::class.java.declaredMethods
        .filter { it.name == "storeGenerated" }
        .flatMap { it.parameterTypes.asIterable() }

    assertThat(storeGeneratedParameterTypes).containsExactly(ThumbnailBook::class.java)
    assertThat(storeGeneratedParameterTypes).doesNotContain(
      ThumbnailSeriesCollection::class.java,
      ThumbnailReadList::class.java,
    )
  }

  @Test
  fun `collection and read list thumbnail schemas have non nullable bytes and no storage url`() {
    assertThat(THUMBNAIL_COLLECTION.fields().map { it.name }).doesNotContain("URL")
    assertThat(THUMBNAIL_COLLECTION.THUMBNAIL.dataType.type).isEqualTo(ByteArray::class.java)
    assertThat(THUMBNAIL_COLLECTION.THUMBNAIL.dataType.nullable()).isFalse()

    assertThat(THUMBNAIL_READLIST.fields().map { it.name }).doesNotContain("URL")
    assertThat(THUMBNAIL_READLIST.THUMBNAIL.dataType.type).isEqualTo(ByteArray::class.java)
    assertThat(THUMBNAIL_READLIST.THUMBNAIL.dataType.nullable()).isFalse()

    val postgresqlSchema = postgresqlCurrentSchema()
    assertThat(postgresqlTableDefinition(postgresqlSchema, "THUMBNAIL_COLLECTION"))
      .contains("\"THUMBNAIL\" bytea NOT NULL")
      .doesNotContain("\"URL\"")
    assertThat(postgresqlTableDefinition(postgresqlSchema, "THUMBNAIL_READLIST"))
      .contains("\"THUMBNAIL\" bytea NOT NULL")
      .doesNotContain("\"URL\"")
  }

  @Test
  fun `given existing database generated thumbnail when filesystem storage is enabled then thumbnail remains readable and migration is idempotent`() {
    val context = insertBookContext("filesystem migration")
    val bytes = byteArrayOf(1, 1, 2, 3, 5)
    val thumbnail = generatedThumbnail(context.bookId, bytes)
    thumbnailBookRepository.insert(thumbnail.copy(selected = true))

    assertThat(bookLifecycle.getThumbnailBytes(context.bookId)?.bytes).isEqualTo(bytes)

    val migrated = bookLifecycle.migrateGeneratedThumbnailStorageForBook(context.bookId)!!
    val stored = thumbnailBookRepository.findByIdOrNull(migrated.id)!!
    val filesAfterFirstMigration = ownedFiles()

    assertThat(stored.thumbnail).isNull()
    assertThat(stored.url).isNotNull
    assertThat(stored.generationProfile?.storageMode).isEqualTo(ThumbnailGenerationProfile.StorageMode.FILESYSTEM)
    assertThat(bookLifecycle.getThumbnailBytes(context.bookId)?.bytes).isEqualTo(bytes)

    assertThat(bookLifecycle.migrateGeneratedThumbnailStorageForBook(context.bookId)).isNull()
    assertThat(ownedFiles()).containsExactlyInAnyOrderElementsOf(filesAfterFirstMigration)
  }

  @Test
  fun `given filesystem storage when cleanup runs then only unreferenced owned generated files are removed`() {
    val context = insertBookContext("filesystem cleanup")
    val bytes = byteArrayOf(4, 3, 2, 1)
    val added =
      bookLifecycle.addThumbnailForBook(
        generatedThumbnail(context.bookId, bytes),
        MarkSelectedPreference.YES,
      )
    val referencedPath =
      thumbnailBookRepository
        .findByIdOrNull(added.id)!!
        .url!!
        .toURI()
        .toPath()
    val orphanPath = storageDirectory.resolve("books/generated/orphan/orphan.jpg")
    orphanPath.parent.createDirectories()
    orphanPath.outputStream().use { it.write(byteArrayOf(0)) }
    val unrelatedPath = storageDirectory.resolve("unrelated.txt")
    unrelatedPath.outputStream().use { it.write(byteArrayOf(0)) }

    val deleted = bookLifecycle.cleanupOrphanedGeneratedThumbnailFiles()

    assertThat(deleted).isEqualTo(1)
    assertThat(referencedPath.exists()).isTrue()
    assertThat(orphanPath.exists()).isFalse()
    assertThat(unrelatedPath.exists()).isTrue()
  }

  @Test
  fun `given filesystem generated thumbnail points to symlink then bytes are not read`() {
    val context = insertBookContext("filesystem symlink")
    val outsidePath = Files.createTempFile("komga-thumbnail-outside", ".jpg")
    outsidePath.outputStream().use { it.write(byteArrayOf(9, 9, 9)) }
    val symlinkPath = storageDirectory.resolve("books/generated/${context.bookId}/symlink.jpg")
    symlinkPath.parent.createDirectories()
    Files.createSymbolicLink(symlinkPath, outsidePath)
    val thumbnail =
      generatedThumbnail(context.bookId, byteArrayOf())
        .copy(
          thumbnail = null,
          url = symlinkPath.toUri().toURL(),
          generationProfile =
            ThumbnailGenerationProfile(
              format = "image/jpeg",
              targetSize = 300,
              jpegQuality = 85,
              storageMode = ThumbnailGenerationProfile.StorageMode.FILESYSTEM,
            ),
        )
    thumbnailBookRepository.insert(thumbnail)

    try {
      assertThat(bookLifecycle.getThumbnailBytesByThumbnailId(thumbnail.id)).isNull()
    } finally {
      outsidePath.deleteIfExists()
    }
  }

  @Test
  fun `given generated thumbnail file is missing when thumbnail is requested then cache is regenerated`() {
    val context = insertBookContext("filesystem regenerate")
    val originalBytes = byteArrayOf(1)
    val regeneratedBytes = byteArrayOf(2, 3)
    val added =
      bookLifecycle.addThumbnailForBook(
        generatedThumbnail(context.bookId, originalBytes),
        MarkSelectedPreference.YES,
      )
    val storedPath =
      thumbnailBookRepository
        .findByIdOrNull(added.id)!!
        .url!!
        .toURI()
        .toPath()
    storedPath.deleteIfExists()
    every { bookAnalyzer.generateThumbnail(any<BookWithMedia>()) } returns generatedThumbnail(context.bookId, regeneratedBytes)

    val regenerated = bookLifecycle.getThumbnailBytes(context.bookId)

    assertThat(regenerated?.bytes).isEqualTo(regeneratedBytes)
    assertThat(thumbnailBookRepository.findSelectedByBookIdOrNull(context.bookId)?.type)
      .isEqualTo(ThumbnailBook.Type.GENERATED)
    assertThat(ownedFiles().single().readBytes()).isEqualTo(regeneratedBytes)
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

  private fun ownedFiles(): List<Path> =
    storageDirectory
      .resolve("books/generated")
      .takeIf { it.exists() }
      ?.listRegularFiles()
      ?: emptyList()

  private fun Path.listRegularFiles(): List<Path> =
    listDirectoryEntries().flatMap {
      when {
        it.isDirectory() -> it.listRegularFiles()
        Files.isRegularFile(it) -> listOf(it)
        else -> emptyList()
      }
    }

  private fun deleteAllBooks() {
    val readListIds = readListRepository.findAll(pageable = Pageable.unpaged()).content.map { it.id }
    if (readListIds.isNotEmpty()) thumbnailReadListRepository.deleteByReadListIds(readListIds)
    readListRepository.deleteAll()

    val collectionIds = collectionRepository.findAll(pageable = Pageable.unpaged()).content.map { it.id }
    if (collectionIds.isNotEmpty()) thumbnailCollectionRepository.deleteByCollectionIds(collectionIds)
    collectionRepository.deleteAll()

    bookRepository.findAll().forEach { book ->
      thumbnailBookRepository.deleteByBookId(book.id)
      mediaRepository.findByIdOrNull(book.id)?.let { mediaRepository.delete(book.id) }
      bookRepository.delete(book.id)
    }
    seriesRepository.findAll().forEach { seriesRepository.delete(it.id) }
    libraryRepository.findAll().forEach { libraryRepository.delete(it.id) }
  }

  private fun postgresqlCurrentSchema(): String =
    BookThumbnailStorageTest::class.java
      .getResource("/db/migration/postgresql/V20260427090000__current_schema.sql")!!
      .readText()

  private fun postgresqlTableDefinition(
    schema: String,
    tableName: String,
  ): String =
    Regex("""CREATE TABLE "$tableName" \((.*?)\);""", RegexOption.DOT_MATCHES_ALL)
      .find(schema)!!
      .value

  private data class BookContext(
    val libraryId: String,
    val seriesId: String,
    val bookId: String,
  )

  companion object {
    private val storageDirectory = Files.createTempDirectory("komga-thumbnail-storage-test")

    @JvmStatic
    @DynamicPropertySource
    fun thumbnailStorageProperties(registry: DynamicPropertyRegistry) {
      registry.add("komga.thumbnails.storage.mode") { "filesystem" }
      registry.add("komga.thumbnails.storage.directory") { storageDirectory.toString() }
    }

    @JvmStatic
    @AfterAll
    fun deleteStorageDirectory() {
      storageDirectory.toFile().deleteRecursively()
    }
  }
}
