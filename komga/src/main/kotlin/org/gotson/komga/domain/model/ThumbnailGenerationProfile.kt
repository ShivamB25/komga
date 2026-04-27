package org.gotson.komga.domain.model

data class ThumbnailGenerationProfile(
  val version: Int = CURRENT_VERSION,
  val format: String,
  val targetSize: Int,
  val jpegQuality: Int?,
  val storageMode: StorageMode = StorageMode.DATABASE,
) {
  enum class StorageMode {
    DATABASE,
    FILESYSTEM,
    HYBRID,
  }

  companion object {
    const val CURRENT_VERSION = 1
  }
}
