package org.gotson.komga.domain.persistence

import org.gotson.komga.domain.model.ThumbnailBook
import org.gotson.komga.domain.model.ThumbnailGenerationProfile

interface ThumbnailBookRepository {
  fun findByIdOrNull(thumbnailId: String): ThumbnailBook?

  fun findSelectedByBookIdOrNull(bookId: String): ThumbnailBook?

  fun findAllByBookId(bookId: String): Collection<ThumbnailBook>

  fun findAllByBookIdAndType(
    bookId: String,
    type: Set<ThumbnailBook.Type>,
  ): Collection<ThumbnailBook>

  fun findAllByType(type: Set<ThumbnailBook.Type>): Collection<ThumbnailBook>

  fun findAllBookIdsByThumbnailTypeAndDimensionSmallerThan(
    type: ThumbnailBook.Type,
    size: Int,
  ): Collection<String>

  fun findAllBookIdsByStaleGeneratedProfile(profile: ThumbnailGenerationProfile): Collection<String>

  fun existsById(thumbnailId: String): Boolean

  fun insert(thumbnail: ThumbnailBook)

  fun update(thumbnail: ThumbnailBook)

  fun markSelected(thumbnail: ThumbnailBook)

  fun delete(thumbnailBookId: String)

  fun deleteByBookId(bookId: String)

  fun deleteByBookIdAndType(
    bookId: String,
    type: ThumbnailBook.Type,
  )

  fun deleteByBookIds(bookIds: Collection<String>)
}
