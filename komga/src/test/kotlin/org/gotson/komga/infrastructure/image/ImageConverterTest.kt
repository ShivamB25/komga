package org.gotson.komga.infrastructure.image

import org.apache.tika.config.TikaConfig
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.infrastructure.mediacontainer.ContentDetector
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class ImageConverterTest {
  private val imageConverter = ImageConverter(ImageAnalyzer(), ContentDetector(TikaConfig.getDefaultConfig()))

  @Test
  fun `given jpeg quality values when resizing image then generated thumbnails are valid jpeg and size changes`() {
    val imageBytes = deterministicImageBytes("PNG")

    val lowQuality = imageConverter.resizeImageToByteArray(imageBytes, ImageType.JPEG, 96, jpegQuality = 10)
    val highQuality = imageConverter.resizeImageToByteArray(imageBytes, ImageType.JPEG, 96, jpegQuality = 95)

    val lowQualityDecoded = ImageIO.read(lowQuality.inputStream())
    val highQualityDecoded = ImageIO.read(highQuality.inputStream())
    assertThat(lowQualityDecoded.width).isLessThanOrEqualTo(96)
    assertThat(lowQualityDecoded.height).isLessThanOrEqualTo(96)
    assertThat(highQualityDecoded.width).isLessThanOrEqualTo(96)
    assertThat(highQualityDecoded.height).isLessThanOrEqualTo(96)
    assertThat(lowQuality).isNotEqualTo(highQuality)
    assertThat(highQuality.size).isGreaterThan(lowQuality.size)
  }

  @Test
  fun `given default jpeg quality when resizing image then encoder default is preserved`() {
    val imageBytes = deterministicImageBytes("PNG")

    val defaultQuality = imageConverter.resizeImageToByteArray(imageBytes, ImageType.JPEG, 96)
    val explicitEncoderDefault = imageConverter.resizeImageToByteArray(imageBytes, ImageType.JPEG, 96, jpegQuality = null)

    assertThat(defaultQuality).isEqualTo(explicitEncoderDefault)
    assertThat(ImageIO.read(defaultQuality.inputStream())).isNotNull
  }

  @Test
  fun `given smaller same-format image when resizing with jpeg quality then original bytes are returned`() {
    val imageBytes = deterministicImageBytes("JPEG", 64)

    val result = imageConverter.resizeImageToByteArray(imageBytes, ImageType.JPEG, 96, jpegQuality = 10)

    assertThat(result).isEqualTo(imageBytes)
  }

  private fun deterministicImageBytes(
    format: String,
    size: Int = 256,
  ): ByteArray {
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
    for (x in 0 until image.width) {
      for (y in 0 until image.height) {
        image.setRGB(x, y, Color((x * 37 + y * 13) % 256, (x * 19 + y * 29) % 256, (x * 11 + y * 7) % 256).rgb)
      }
    }

    return ByteArrayOutputStream().use {
      ImageIO.write(image, format, it)
      it.toByteArray()
    }
  }
}
