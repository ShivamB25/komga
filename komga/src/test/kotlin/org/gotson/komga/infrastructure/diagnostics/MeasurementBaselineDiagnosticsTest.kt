package org.gotson.komga.infrastructure.diagnostics

import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.Dimension
import org.gotson.komga.domain.model.ThumbnailBook
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.model.makeLibrary
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.gotson.komga.infrastructure.image.ImageConverter
import org.gotson.komga.infrastructure.image.ImageType
import org.gotson.komga.infrastructure.jooq.main.BookDao
import org.gotson.komga.infrastructure.jooq.main.ServerSettingsDao
import org.gotson.komga.infrastructure.jooq.main.ThumbnailBookDao
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.sql.DataSource
import kotlin.io.path.readText

@SpringBootTest
class MeasurementBaselineDiagnosticsTest(
  @Autowired private val environment: Environment,
  @Autowired @Qualifier("sqliteDataSourceRW") private val mainDataSourceRW: DataSource,
  @Autowired @Qualifier("sqliteDataSourceRO") private val mainDataSourceRO: DataSource,
  @Autowired @Qualifier("tasksDataSourceRW") private val tasksDataSourceRW: DataSource,
  @Autowired @Qualifier("tasksDataSourceRO") private val tasksDataSourceRO: DataSource,
  @Autowired @Qualifier("dslContextRW") private val mainDslContextRW: DSLContext,
  @Autowired @Qualifier("dslContextRO") private val mainDslContextRO: DSLContext,
  @Autowired @Qualifier("tasksDslContextRW") private val tasksDslContextRW: DSLContext,
  @Autowired @Qualifier("tasksDslContextRO") private val tasksDslContextRO: DSLContext,
  @Autowired private val libraryRepository: LibraryRepository,
  @Autowired private val seriesRepository: SeriesRepository,
  @Autowired private val bookDao: BookDao,
  @Autowired private val serverSettingsDao: ServerSettingsDao,
  @Autowired private val thumbnailBookDao: ThumbnailBookDao,
  @Autowired private val imageConverter: ImageConverter,
) {
  @Test
  fun `given default configuration when inspecting baseline gates then SQLite and jOOQ remain default access paths`() {
    assertThat(environment.getProperty("komga.database.file")).endsWith(".sqlite")
    assertThat(environment.getProperty("komga.tasks-db.file")).endsWith(".sqlite")

    listOf(mainDataSourceRW, mainDataSourceRO, tasksDataSourceRW, tasksDataSourceRO)
      .map { dataSource ->
        dataSource.connection.use { it.metaData.url }
      }.forEach { jdbcUrl ->
        assertThat(jdbcUrl).startsWith("jdbc:sqlite:")
        assertThat(jdbcUrl).doesNotContain("postgresql")
      }

    listOf(mainDslContextRW, mainDslContextRO, tasksDslContextRW, tasksDslContextRO)
      .map { it.configuration().dialect().family() }
      .forEach {
        assertThat(it).isEqualTo(SQLDialect.SQLITE)
      }

    val buildScript = Path.of("build.gradle.kts").readText()
    assertThat(buildScript).contains("spring-boot-starter-jooq")
    assertThat(buildScript).contains("org.xerial:sqlite-jdbc")
    assertThat(buildScript).doesNotContain("spring-boot-starter-data-jpa")
    assertThat(buildScript).doesNotContain("hibernate-core")
    assertThat(buildScript).doesNotContain("jakarta.persistence-api")
  }

  @Test
  fun `given SQLite default when running measurement baseline then DB and thumbnail timings are reported`(testReporter: TestReporter) {
    val backend =
      mainDslContextRW
        .configuration()
        .dialect()
        .family()
        .name
        .lowercase()
    val library = makeLibrary("measurement baseline")
    val series = makeSeries("measurement baseline", libraryId = library.id)
    val book = makeBook("measurement baseline", libraryId = library.id, seriesId = series.id)
    val settingKey = "measurement.baseline.${book.id}"
    var generatedThumbnail = ByteArray(0)
    var storedThumbnailId = ""

    libraryRepository.insert(library)
    seriesRepository.insert(series)

    try {
      val records =
        listOf(
          timed(backend, "database", "book_insert_find_by_series") {
            bookDao.insert(book)
            assertThat(bookDao.findByIdOrNull(book.id)).isNotNull
            assertThat(bookDao.findAllIdsBySeriesId(series.id)).contains(book.id)
          },
          timed(backend, "database", "settings_save_fetch_delete") {
            serverSettingsDao.saveSetting(settingKey, "enabled")
            assertThat(serverSettingsDao.getSettingByKey(settingKey, String::class.java)).isEqualTo("enabled")
            serverSettingsDao.deleteSetting(settingKey)
            assertThat(serverSettingsDao.getSettingByKey(settingKey, String::class.java)).isNull()
          },
          timed(backend, "thumbnail_cpu", "thumbnail_generation_resize_encode") {
            generatedThumbnail = imageConverter.resizeImageToByteArray(deterministicImageBytes(), ImageType.JPEG, 64)
            val decoded = ImageIO.read(generatedThumbnail.inputStream())
            assertThat(decoded.width).isLessThanOrEqualTo(64)
            assertThat(decoded.height).isLessThanOrEqualTo(64)
            assertThat(generatedThumbnail).isNotEmpty
          },
          timed(backend, "thumbnail_storage", "thumbnail_storage_write") {
            val thumbnail = generatedThumbnail.toThumbnailBook(book.id)
            storedThumbnailId = thumbnail.id
            thumbnailBookDao.insert(thumbnail)
          },
          timed(backend, "thumbnail_storage", "thumbnail_storage_readback") {
            val stored = thumbnailBookDao.findByIdOrNull(storedThumbnailId)
            assertThat(stored).isNotNull
            assertThat(stored?.thumbnail).isEqualTo(generatedThumbnail)
          },
        )
      val reportRecords =
        records +
          TimingRecord(
            backend = backend,
            category = "thumbnail_total",
            scenario = "thumbnail_total_generate_write_read",
            durationNanos = records.filter { it.category.startsWith("thumbnail") }.sumOf { it.durationNanos },
          )
      val report = reportRecords.render()

      testReporter.publishEntry("measurementBaseline", report)
      println(report)

      assertThat(report).contains("format=komga-measurement-baseline-v1")
      assertThat(report).contains("backend=sqlite")
      assertThat(report).contains("scenario=book_insert_find_by_series")
      assertThat(report).contains("scenario=settings_save_fetch_delete")
      assertThat(report).contains("scenario=thumbnail_generation_resize_encode")
      assertThat(report).contains("scenario=thumbnail_storage_write")
      assertThat(report).contains("scenario=thumbnail_storage_readback")
      assertThat(report).contains("scenario=thumbnail_total_generate_write_read")
      assertThat(report).contains("durationUnit=nanoseconds")

      reportRecords.forEach {
        assertThat(it.backend).isEqualTo("sqlite")
        assertThat(it.scenario).isNotBlank
        assertThat(it.durationNanos).isPositive()
      }
    } finally {
      if (storedThumbnailId.isNotBlank()) thumbnailBookDao.delete(storedThumbnailId)
      thumbnailBookDao.deleteByBookId(book.id)
      bookDao.delete(book.id)
      serverSettingsDao.deleteSetting(settingKey)
      seriesRepository.delete(series.id)
      libraryRepository.delete(library.id)
    }
  }

  private fun timed(
    backend: String,
    category: String,
    scenario: String,
    block: () -> Unit,
  ): TimingRecord {
    val start = System.nanoTime()
    block()
    val duration = (System.nanoTime() - start).coerceAtLeast(1)
    return TimingRecord(backend, category, scenario, duration)
  }

  private fun deterministicImageBytes(): ByteArray {
    val image = BufferedImage(128, 96, BufferedImage.TYPE_INT_RGB)
    for (x in 0 until image.width) {
      for (y in 0 until image.height) {
        image.setRGB(x, y, Color((x * 13) % 256, (y * 17) % 256, ((x + y) * 7) % 256).rgb)
      }
    }

    return ByteArrayOutputStream().use {
      ImageIO.write(image, "png", it)
      it.toByteArray()
    }
  }

  private fun ByteArray.toThumbnailBook(bookId: String): ThumbnailBook =
    ThumbnailBook(
      thumbnail = this,
      selected = true,
      type = ThumbnailBook.Type.GENERATED,
      mediaType = ImageType.JPEG.mediaType,
      fileSize = size.toLong(),
      dimension = Dimension(64, 48),
      bookId = bookId,
    )

  private fun Collection<TimingRecord>.render(): String =
    sortedWith(compareBy<TimingRecord> { it.category }.thenBy { it.scenario })
      .joinToString(separator = "\n") {
        "measurement format=komga-measurement-baseline-v1 backend=${it.backend} category=${it.category} " +
          "scenario=${it.scenario} duration=${it.durationNanos} durationUnit=nanoseconds"
      }

  private data class TimingRecord(
    val backend: String,
    val category: String,
    val scenario: String,
    val durationNanos: Long,
  )
}
