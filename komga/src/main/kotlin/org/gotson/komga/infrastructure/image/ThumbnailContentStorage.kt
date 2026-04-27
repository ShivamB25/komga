package org.gotson.komga.infrastructure.image

import org.gotson.komga.domain.model.ThumbnailBook
import org.gotson.komga.domain.model.ThumbnailGenerationProfile
import org.gotson.komga.infrastructure.configuration.KomgaProperties
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.pathString
import kotlin.io.path.walk

@Component
class ThumbnailContentStorage(
  private val komgaProperties: KomgaProperties,
) {
  val generatedStorageMode: ThumbnailGenerationProfile.StorageMode
    get() =
      when (komgaProperties.thumbnails.storage.mode) {
        KomgaProperties.ThumbnailStorageMode.DATABASE -> ThumbnailGenerationProfile.StorageMode.DATABASE
        KomgaProperties.ThumbnailStorageMode.FILESYSTEM -> ThumbnailGenerationProfile.StorageMode.FILESYSTEM
        KomgaProperties.ThumbnailStorageMode.HYBRID -> ThumbnailGenerationProfile.StorageMode.HYBRID
      }

  fun storeGenerated(thumbnail: ThumbnailBook): ThumbnailBook {
    require(thumbnail.type == ThumbnailBook.Type.GENERATED) { "Only generated thumbnails can be stored as cache content" }

    val profile = thumbnail.generationProfile?.copy(storageMode = generatedStorageMode)

    if (generatedStorageMode == ThumbnailGenerationProfile.StorageMode.DATABASE)
      return thumbnail.copy(generationProfile = profile)

    val bytes = thumbnail.thumbnail ?: read(thumbnail) ?: return thumbnail.copy(generationProfile = profile)
    val destination = generatedPath(thumbnail)
    destination.parent.createDirectories()
    ensureSafeOwnedPath(destination)

    val temporary = destination.resolveSibling(".${destination.name}.${UUID.randomUUID()}.tmp")
    try {
      temporary.outputStream().use { it.write(bytes) }
      Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: Exception) {
      temporary.deleteIfExists()
      throw e
    }

    return thumbnail.copy(
      thumbnail = null,
      url = destination.toUri().toURL(),
      generationProfile = profile,
    )
  }

  fun read(thumbnail: ThumbnailBook): ByteArray? =
    when {
      thumbnail.thumbnail != null -> thumbnail.thumbnail
      thumbnail.url != null && thumbnail.type == ThumbnailBook.Type.GENERATED -> {
        val path = safeOwnedPathOrNull(thumbnail) ?: return null
        if (path.exists()) path.inputStream().use { it.readBytes() } else null
      }
      thumbnail.url != null -> Paths.get(thumbnail.url.toURI()).inputStream().use { it.readBytes() }
      else -> null
    }

  fun exists(thumbnail: ThumbnailBook): Boolean =
    when {
      thumbnail.thumbnail != null -> true
      thumbnail.url != null && thumbnail.type == ThumbnailBook.Type.GENERATED -> safeOwnedPathOrNull(thumbnail)?.exists() == true
      thumbnail.url != null -> thumbnail.exists()
      else -> false
    }

  fun delete(thumbnail: ThumbnailBook) {
    if (thumbnail.type != ThumbnailBook.Type.GENERATED) return
    safeOwnedPathOrNull(thumbnail)?.deleteIfExists()
  }

  fun cleanupOrphanedGeneratedFiles(referencedUrls: Collection<String>): Int {
    val generatedRoot = generatedRoot()
    if (!generatedRoot.exists()) return 0

    val referenced =
      referencedUrls
        .mapNotNull { safeOwnedPathOrNull(it) }
        .map { it.pathForComparison() }
        .toSet()

    return generatedRoot
      .walk()
      .filter { it.isRegularFile(LinkOption.NOFOLLOW_LINKS) }
      .filterNot { it.name.startsWith(".") && it.name.endsWith(".tmp") }
      .filter { it.toRealPath(LinkOption.NOFOLLOW_LINKS) !in referenced }
      .count {
        it.deleteIfExists()
      }
  }

  private fun generatedPath(thumbnail: ThumbnailBook): Path =
    generatedRoot()
      .resolve(thumbnail.bookId)
      .resolve("${thumbnail.id}.${extension(thumbnail.mediaType)}")
      .normalize()

  private fun generatedRoot(): Path =
    root()
      .resolve("books")
      .resolve("generated")
      .normalize()

  private fun root(): Path =
    komgaProperties.thumbnails.storage.directory
      ?.let { Path(it) }
      ?: Path(komgaProperties.configDir ?: System.getProperty("user.home"), "cache", "thumbnails")

  private fun safeOwnedPathOrNull(thumbnail: ThumbnailBook): Path? = thumbnail.url?.toString()?.let { safeOwnedPathOrNull(it) }

  private fun safeOwnedPathOrNull(url: String): Path? =
    try {
      Paths.get(java.net.URI(url)).normalize().also { ensureSafeOwnedPath(it) }
    } catch (_: Exception) {
      null
    }

  private fun ensureSafeOwnedPath(path: Path) {
    val normalizedRoot = root().normalize()
    val normalizedPath = path.normalize()
    require(normalizedPath.startsWith(generatedRoot())) { "Thumbnail storage path escapes configured root: ${normalizedPath.pathString}" }
    require(!Files.isSymbolicLink(normalizedPath)) { "Thumbnail storage path is a symbolic link: ${normalizedPath.pathString}" }

    var current = normalizedPath.parent
    while (current != null && current.startsWith(normalizedRoot)) {
      require(!Files.isSymbolicLink(current)) { "Thumbnail storage path contains a symbolic link: ${current.pathString}" }
      if (current == normalizedRoot) break
      current = current.parent
    }
  }

  private fun Path.pathForComparison(): Path =
    if (exists(LinkOption.NOFOLLOW_LINKS))
      toRealPath(LinkOption.NOFOLLOW_LINKS)
    else
      normalize()

  private fun extension(mediaType: String): String =
    when (mediaType) {
      ImageType.PNG.mediaType -> "png"
      else -> "jpg"
    }
}
