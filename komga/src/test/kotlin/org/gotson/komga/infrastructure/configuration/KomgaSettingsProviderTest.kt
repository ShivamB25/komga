package org.gotson.komga.infrastructure.configuration

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gotson.komga.infrastructure.jooq.main.ServerSettingsDao
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher

@SpringBootTest
class KomgaSettingsProviderTest(
  @Autowired private val serverSettingsDao: ServerSettingsDao,
) {
  private val eventPublisher =
    object : ApplicationEventPublisher {
      override fun publishEvent(event: Any) = Unit
    }

  @AfterEach
  fun cleanup() {
    serverSettingsDao.deleteAll()
  }

  @Test
  fun `given no jpeg quality setting when loading settings then quality uses encoder default`() {
    val settingsProvider = KomgaSettingsProvider(serverSettingsDao, eventPublisher)

    assertThat(settingsProvider.thumbnailJpegQuality).isNull()
  }

  @Test
  fun `given jpeg quality boundary values when updating settings then values are persisted`() {
    val settingsProvider = KomgaSettingsProvider(serverSettingsDao, eventPublisher)

    settingsProvider.thumbnailJpegQuality = 1
    assertThat(settingsProvider.thumbnailJpegQuality).isEqualTo(1)
    assertThat(serverSettingsDao.getSettingByKey("THUMBNAIL_JPEG_QUALITY", Int::class.java)).isEqualTo(1)

    settingsProvider.thumbnailJpegQuality = 100
    assertThat(settingsProvider.thumbnailJpegQuality).isEqualTo(100)
    assertThat(serverSettingsDao.getSettingByKey("THUMBNAIL_JPEG_QUALITY", Int::class.java)).isEqualTo(100)
  }

  @Test
  fun `given invalid jpeg quality values when updating settings then values are rejected`() {
    val settingsProvider = KomgaSettingsProvider(serverSettingsDao, eventPublisher)

    assertThatThrownBy { settingsProvider.thumbnailJpegQuality = 0 }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessage("Thumbnail JPEG quality must be between 1 and 100")
    assertThatThrownBy { settingsProvider.thumbnailJpegQuality = 101 }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessage("Thumbnail JPEG quality must be between 1 and 100")
  }

  @Test
  fun `given invalid persisted jpeg quality setting when loading settings then value is rejected`() {
    serverSettingsDao.saveSetting("THUMBNAIL_JPEG_QUALITY", 0)

    assertThatThrownBy { KomgaSettingsProvider(serverSettingsDao, eventPublisher) }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessage("Thumbnail JPEG quality must be between 1 and 100")
  }
}
