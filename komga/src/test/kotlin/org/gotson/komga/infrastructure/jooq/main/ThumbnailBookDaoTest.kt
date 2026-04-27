package org.gotson.komga.infrastructure.jooq.main

import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.Dimension
import org.gotson.komga.domain.model.MarkSelectedPreference
import org.gotson.komga.domain.model.ThumbnailBook
import org.gotson.komga.domain.model.ThumbnailGenerationProfile
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.model.makeLibrary
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.gotson.komga.domain.service.BookLifecycle
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class ThumbnailBookDaoTest(
  @Autowired private val libraryRepository: LibraryRepository,
  @Autowired private val seriesRepository: SeriesRepository,
  @Autowired private val bookDao: BookDao,
  @Autowired private val thumbnailBookDao: ThumbnailBookDao,
  @Autowired private val bookLifecycle: BookLifecycle,
) {
  @Test
  fun `given generated thumbnail profile when saving thumbnail then profile is persisted`() {
    val context = insertBookContext("thumbnail profile")
    val profile =
      ThumbnailGenerationProfile(
        format = "image/jpeg",
        targetSize = 300,
        jpegQuality = 85,
      )

    try {
      val thumbnail =
        ThumbnailBook(
          thumbnail = byteArrayOf(1, 2, 3),
          type = ThumbnailBook.Type.GENERATED,
          mediaType = "image/jpeg",
          fileSize = 3,
          dimension = Dimension(300, 200),
          generationProfile = profile,
          bookId = context.bookId,
        )

      thumbnailBookDao.insert(thumbnail)

      assertThat(thumbnailBookDao.findByIdOrNull(thumbnail.id)?.generationProfile).isEqualTo(profile)
    } finally {
      deleteBookContext(context)
    }
  }

  @Test
  fun `given older generated thumbnail row when saving thumbnail then missing profile remains readable`() {
    val context = insertBookContext("thumbnail legacy profile")

    try {
      val thumbnail =
        ThumbnailBook(
          thumbnail = byteArrayOf(1, 2, 3),
          type = ThumbnailBook.Type.GENERATED,
          mediaType = "image/jpeg",
          fileSize = 3,
          dimension = Dimension(300, 200),
          bookId = context.bookId,
        )

      thumbnailBookDao.insert(thumbnail)

      assertThat(thumbnailBookDao.findByIdOrNull(thumbnail.id)?.generationProfile).isNull()
    } finally {
      deleteBookContext(context)
    }
  }

  @Test
  fun `given user uploaded thumbnail when adding thumbnail then bytes are preserved without generation profile`() {
    val context = insertBookContext("thumbnail uploaded cover")
    val uploadedBytes = byteArrayOf(9, 8, 7, 6, 5)

    try {
      val added =
        bookLifecycle.addThumbnailForBook(
          ThumbnailBook(
            thumbnail = uploadedBytes,
            type = ThumbnailBook.Type.USER_UPLOADED,
            mediaType = "image/jpeg",
            fileSize = uploadedBytes.size.toLong(),
            dimension = Dimension(2, 2),
            bookId = context.bookId,
          ),
          MarkSelectedPreference.YES,
        )

      val stored = thumbnailBookDao.findByIdOrNull(added.id)
      assertThat(stored?.thumbnail).isEqualTo(uploadedBytes)
      assertThat(stored?.generationProfile).isNull()
    } finally {
      deleteBookContext(context)
    }
  }

  @Test
  fun `given default storage when adding generated thumbnail then bytes remain database backed`() {
    val context = insertBookContext("thumbnail default storage")
    val thumbnailBytes = byteArrayOf(1, 2, 3)

    try {
      val added =
        bookLifecycle.addThumbnailForBook(
          ThumbnailBook(
            thumbnail = thumbnailBytes,
            type = ThumbnailBook.Type.GENERATED,
            mediaType = "image/jpeg",
            fileSize = thumbnailBytes.size.toLong(),
            dimension = Dimension(2, 2),
            generationProfile =
              ThumbnailGenerationProfile(
                format = "image/jpeg",
                targetSize = 300,
                jpegQuality = 85,
              ),
            bookId = context.bookId,
          ),
          MarkSelectedPreference.YES,
        )

      val stored = thumbnailBookDao.findByIdOrNull(added.id)
      assertThat(stored?.thumbnail).isEqualTo(thumbnailBytes)
      assertThat(stored?.url).isNull()
      assertThat(stored?.generationProfile?.storageMode).isEqualTo(ThumbnailGenerationProfile.StorageMode.DATABASE)
    } finally {
      deleteBookContext(context)
    }
  }

  @Test
  fun `given generated and uploaded thumbnails when finding stale profile then only stale generated thumbnails are returned`() {
    val currentContext = insertBookContext("thumbnail current profile")
    val staleContext = insertBookContext("thumbnail stale profile")
    val uploadedContext = insertBookContext("thumbnail uploaded profile")
    val currentProfile =
      ThumbnailGenerationProfile(
        format = "image/jpeg",
        targetSize = 300,
        jpegQuality = 85,
      )

    try {
      bookLifecycle.addThumbnailForBook(
        ThumbnailBook(
          thumbnail = byteArrayOf(1),
          type = ThumbnailBook.Type.GENERATED,
          mediaType = "image/jpeg",
          fileSize = 1,
          dimension = Dimension(200, 300),
          generationProfile = currentProfile,
          bookId = currentContext.bookId,
        ),
        MarkSelectedPreference.YES,
      )
      bookLifecycle.addThumbnailForBook(
        ThumbnailBook(
          thumbnail = byteArrayOf(2),
          type = ThumbnailBook.Type.GENERATED,
          mediaType = "image/jpeg",
          fileSize = 1,
          dimension = Dimension(300, 300),
          generationProfile = currentProfile.copy(jpegQuality = 40),
          bookId = staleContext.bookId,
        ),
        MarkSelectedPreference.YES,
      )
      bookLifecycle.addThumbnailForBook(
        ThumbnailBook(
          thumbnail = byteArrayOf(3),
          type = ThumbnailBook.Type.USER_UPLOADED,
          mediaType = "image/jpeg",
          fileSize = 1,
          dimension = Dimension(300, 300),
          bookId = uploadedContext.bookId,
        ),
        MarkSelectedPreference.YES,
      )

      assertThat(thumbnailBookDao.findAllBookIdsByStaleGeneratedProfile(currentProfile))
        .contains(staleContext.bookId)
        .doesNotContain(currentContext.bookId, uploadedContext.bookId)
    } finally {
      deleteBookContext(currentContext)
      deleteBookContext(staleContext)
      deleteBookContext(uploadedContext)
    }
  }

  private fun insertBookContext(name: String): BookContext {
    val library = makeLibrary(name)
    val series = makeSeries(name, libraryId = library.id)
    val book = makeBook("$name.cbz", libraryId = library.id, seriesId = series.id)

    libraryRepository.insert(library)
    seriesRepository.insert(series)
    bookDao.insert(book)

    return BookContext(library.id, series.id, book.id)
  }

  private fun deleteBookContext(context: BookContext) {
    thumbnailBookDao.deleteByBookId(context.bookId)
    bookDao.delete(context.bookId)
    seriesRepository.delete(context.seriesId)
    libraryRepository.delete(context.libraryId)
  }

  private data class BookContext(
    val libraryId: String,
    val seriesId: String,
    val bookId: String,
  )
}
