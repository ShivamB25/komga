package org.gotson.komga.infrastructure.migration

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.Application
import org.gotson.komga.domain.model.ThumbnailGenerationProfile
import org.gotson.komga.domain.persistence.ThumbnailBookRepository
import org.gotson.komga.domain.service.BookLifecycle
import org.gotson.komga.infrastructure.kobo.KoboHeaders
import org.gotson.komga.infrastructure.search.SearchIndexLifecycle
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.pathString
import kotlin.io.path.readBytes

@EnabledIfEnvironmentVariable(named = "KOMGA_POSTGRESQL_TESTS", matches = "true")
class PostgreSqlMigrationApiSmokeTest {
  @TempDir
  lateinit var tempDir: Path

  @Test
  fun `given migrated PostgreSQL data when search index is rebuilt then search API matches SQLite baseline`() {
    val fixture = PostgreSqlMigrationFixture(tempDir)
    val sourceMain = fixture.createMigratedSourceMain()
    val sourceTasks = fixture.createMigratedSourceTasks()

    val sqliteSearchFacts =
      startApplication(sqliteProperties(sourceMain, sourceTasks)).use { context ->
        context.rebuildSearchIndex()
        context.mockMvc().searchApiFacts(context.objectMapper())
      }

    PostgreSqlMigrationFixture.resetAndMigrateTarget()

    val report =
      MigrationPreflight().run(
        MigrationRequest(
          sourceMain = JdbcEndpoint(sourceMain),
          sourceTasks = JdbcEndpoint(sourceTasks),
          target =
            JdbcEndpoint(
              url = PostgreSqlMigrationFixture.JDBC_URL,
              username = PostgreSqlMigrationFixture.USERNAME,
              password = PostgreSqlMigrationFixture.PASSWORD,
            ),
          mode = MigrationMode.MIGRATE,
        ),
      )

    assertThat(report.status).isEqualTo(MigrationStatus.SUCCESS)

    startApplication(postgreSqlProperties).use { context ->
      context.rebuildSearchIndex()
      val postgreSqlSearchFacts = context.mockMvc().searchApiFacts(context.objectMapper())

      assertThat(postgreSqlSearchFacts).isEqualTo(sqliteSearchFacts)
      assertThat(postgreSqlSearchFacts)
        .isEqualTo(
          SearchApiFacts(
            seriesByUnicodeAccentTitle = listOf("series-1"),
            seriesByAuthor = listOf("series-1"),
            seriesByTag = listOf("series-1"),
            seriesByCollection = listOf("series-1"),
            collectionsByName = listOf("collection-1"),
            readListsByName = listOf("readlist-1"),
            booksByUnicodeAccentTitle = listOf("book-1"),
            booksByAuthor = listOf("book-1"),
            booksByTag = listOf("book-1"),
            booksByReadList = listOf("book-1"),
            adminDeniedSeriesSearch = listOf("series-denied"),
            adminDeniedReadListSearch = listOf("readlist-denied"),
            restrictedDeniedSeriesSearch = emptyList(),
            restrictedDeniedBookSearch = emptyList(),
            restrictedDeniedReadListSearch = emptyList(),
          ),
        )
    }
  }

  @Test
  fun `given migrated PostgreSQL data when application restarts then API auth browse settings progress and sync flows work`() {
    val fixture = PostgreSqlMigrationFixture(tempDir)
    PostgreSqlMigrationFixture.resetAndMigrateTarget()

    val report =
      MigrationPreflight().run(
        MigrationRequest(
          sourceMain = JdbcEndpoint(fixture.createMigratedSourceMain()),
          sourceTasks = JdbcEndpoint(fixture.createMigratedSourceTasks()),
          target =
            JdbcEndpoint(
              url = PostgreSqlMigrationFixture.JDBC_URL,
              username = PostgreSqlMigrationFixture.USERNAME,
              password = PostgreSqlMigrationFixture.PASSWORD,
            ),
          mode = MigrationMode.MIGRATE,
        ),
      )

    assertThat(report.status).isEqualTo(MigrationStatus.SUCCESS)
    assertThat(report.failure).isNull()
    assertThat(report.counts).allSatisfy { assertThat(it.status).isEqualTo(ValidationStatus.PASS) }
    assertThat(report.digests).allSatisfy { assertThat(it.status).isEqualTo(ValidationStatus.PASS) }
    assertThat(report.integrityChecks).allSatisfy { assertThat(it.status).isEqualTo(ValidationStatus.PASS) }
    assertThat(report.counts)
      .anySatisfy {
        assertThat(it.source).isEqualTo(MigrationSource.MAIN)
        assertThat(it.table).isEqualTo("MEDIA_FILE")
        assertThat(it.sourceRows).isGreaterThanOrEqualTo(2)
        assertThat(it.targetRows).isEqualTo(it.sourceRows)
      }
    assertThat(report.digests)
      .anySatisfy {
        assertThat(it.source).isEqualTo(MigrationSource.MAIN)
        assertThat(it.table).isEqualTo("MEDIA_FILE")
        assertThat(it.targetDigest).isEqualTo(it.sourceDigest)
      }
    assertThat(report.integrityChecks)
      .anySatisfy {
        assertThat(it.name).isEqualTo("MEDIA_FILE.BOOK_ID references BOOK.ID")
        assertThat(it.status).isEqualTo(ValidationStatus.PASS)
        assertThat(it.details).isEqualTo("No orphaned references.")
      }.anySatisfy {
        assertThat(it.name).isEqualTo("MEDIA_FILE primary key uniqueness")
        assertThat(it.status).isEqualTo(ValidationStatus.PASS)
        assertThat(it.details).isEqualTo("No primary key declared; duplicate primary key check is not applicable.")
      }

    startApplication(postgreSqlProperties).use { context ->
      val mockMvc = context.mockMvc()
      mockMvc.assertMigratedAuthenticationWorks()
      mockMvc.assertMigratedBrowseAndAuthorizationWorks()
      mockMvc.assertMigratedMediaAndThumbnailApisWork()
      mockMvc.assertMigratedSettingsAreReadableAndWritable()
      mockMvc.assertMigratedApiKeysCanBeCreatedAndListed(context.objectMapper())
      mockMvc.assertMigratedKoboAndKoreaderApisWork()
      mockMvc.performPersistedWritesBeforeRestart()
    }

    startApplication(postgreSqlProperties).use { context ->
      context.mockMvc().assertApiWritesSurvivedRestart()
    }
  }

  @Test
  fun `given migrated PostgreSQL thumbnails when filesystem storage is enabled then public APIs survive storage switch and restart`() {
    val fixture = PostgreSqlMigrationFixture(tempDir)
    PostgreSqlMigrationFixture.resetAndMigrateTarget()
    val storageDirectory = tempDir.resolve("thumbnail-storage")
    val sourceMediaUrl =
      ClassPathResource("archives/zip.zip")
        .file
        .toURI()
        .toURL()
        .toString()
    val sourceMain =
      fixture.createMigratedSourceMain(
        sourceBookUrl = sourceMediaUrl,
        sourceMediaPageFileName = "komga.png",
        sourceMediaPageMediaType = "image/png",
      )
    val unsafeReferences = createUnsafeGeneratedThumbnailReferences(storageDirectory)
    unsafeReferences.forEachIndexed { index, reference ->
      fixture.insertMigratedGeneratedThumbnailBook(
        sourceMain = sourceMain,
        bookId = reference.bookId,
        thumbnailId = reference.thumbnailId,
        thumbnailUrl = reference.thumbnailUrl,
        bookUrl = sourceMediaUrl,
        number = index + 2,
      )
    }

    val report =
      MigrationPreflight().run(
        MigrationRequest(
          sourceMain = JdbcEndpoint(sourceMain),
          sourceTasks = JdbcEndpoint(fixture.createMigratedSourceTasks()),
          target =
            JdbcEndpoint(
              url = PostgreSqlMigrationFixture.JDBC_URL,
              username = PostgreSqlMigrationFixture.USERNAME,
              password = PostgreSqlMigrationFixture.PASSWORD,
            ),
          mode = MigrationMode.MIGRATE,
        ),
      )

    assertThat(report.status).isEqualTo(MigrationStatus.SUCCESS)

    val filesystemStorageProperties =
      postgreSqlProperties +
        mapOf(
          "komga.thumbnails.storage.mode" to "filesystem",
          "komga.thumbnails.storage.directory" to storageDirectory.toString(),
        )

    var regeneratedBookThumbnailBytes = byteArrayOf()

    startApplication(filesystemStorageProperties).use { context ->
      val mockMvc = context.mockMvc()

      mockMvc.assertMigratedThumbnailPublicApisWork()

      val migrated =
        context
          .getBean(BookLifecycle::class.java)
          .migrateGeneratedThumbnailStorageForBook("book-1")

      assertThat(migrated).isNotNull

      val stored =
        context
          .getBean(ThumbnailBookRepository::class.java)
          .findByIdOrNull("thumbnail-1")!!
      val storedPath = Path.of(stored.url!!.toURI())

      assertThat(stored.thumbnail).isNull()
      assertThat(stored.generationProfile?.storageMode)
        .isEqualTo(ThumbnailGenerationProfile.StorageMode.FILESYSTEM)
      assertThat(storedPath.normalize().pathString)
        .startsWith(storageDirectory.normalize().pathString)
      assertThat(storedPath.exists()).isTrue()
      assertThat(storedPath.readBytes()).isEqualTo(byteArrayOf(1, 2, 3, 4))
      assertThat(storageDirectory.listRegularFiles()).containsExactly(storedPath)

      storedPath.toFile().delete()
      val regenerated = mockMvc.getBookThumbnailFromPublicApi("book-1")
      regeneratedBookThumbnailBytes = regenerated
      val regeneratedStored =
        context
          .getBean(ThumbnailBookRepository::class.java)
          .findSelectedByBookIdOrNull("book-1")!!
      val regeneratedPath = Path.of(regeneratedStored.url!!.toURI())

      assertThat(regenerated).isNotEmpty
      assertThat(regenerated.copyOfRange(0, 2)).containsExactly(0xff.toByte(), 0xd8.toByte())
      assertThat(regenerated).isNotEqualTo(byteArrayOf(1, 2, 3, 4))
      assertThat(regeneratedStored.thumbnail).isNull()
      assertThat(regeneratedStored.generationProfile?.storageMode)
        .isEqualTo(ThumbnailGenerationProfile.StorageMode.FILESYSTEM)
      assertThat(regeneratedPath.normalize().pathString)
        .startsWith(storageDirectory.normalize().pathString)
      assertThat(regeneratedPath.exists()).isTrue()
      assertThat(regeneratedPath.readBytes()).isEqualTo(regenerated)
      assertThat(storedPath.exists()).isFalse()

      unsafeReferences.forEach { reference ->
        val unsafeRegenerated = mockMvc.getBookThumbnailFromPublicApi(reference.bookId)

        assertThat(unsafeRegenerated).isNotEmpty
        assertThat(unsafeRegenerated.copyOfRange(0, 2)).containsExactly(0xff.toByte(), 0xd8.toByte())
        assertThat(unsafeRegenerated).isNotEqualTo(reference.sentinelBytes)
        assertThat(reference.sentinelPath.readBytes()).isEqualTo(reference.sentinelBytes)

        val unsafeStored = context.getBean(ThumbnailBookRepository::class.java).findSelectedByBookIdOrNull(reference.bookId)!!
        val unsafeStoredPath = Path.of(unsafeStored.url!!.toURI())

        assertThat(unsafeStored.id).isNotEqualTo(reference.thumbnailId)
        assertThat(unsafeStored.thumbnail).isNull()
        assertThat(unsafeStored.generationProfile?.storageMode)
          .isEqualTo(ThumbnailGenerationProfile.StorageMode.FILESYSTEM)
        assertThat(unsafeStoredPath.normalize().pathString)
          .startsWith(storageDirectory.normalize().pathString)
        assertThat(unsafeStoredPath.exists()).isTrue()
        assertThat(unsafeStoredPath.readBytes()).isEqualTo(unsafeRegenerated)
        assertThat(context.getBean(ThumbnailBookRepository::class.java).findByIdOrNull(reference.thumbnailId)).isNull()
      }

      assertThat(storageDirectory.listRegularFiles())
        .allSatisfy {
          assertThat(it.normalize().pathString).startsWith(storageDirectory.normalize().pathString)
          assertThat(Files.isRegularFile(it)).isTrue()
        }
    }

    startApplication(filesystemStorageProperties).use { restartedContext ->
      assertThat(restartedContext.mockMvc().getBookThumbnailFromPublicApi("book-1"))
        .isEqualTo(regeneratedBookThumbnailBytes)
      val stored =
        restartedContext
          .getBean(ThumbnailBookRepository::class.java)
          .findSelectedByBookIdOrNull("book-1")!!

      assertThat(stored.thumbnail).isNull()
      assertThat(stored.generationProfile?.storageMode)
        .isEqualTo(ThumbnailGenerationProfile.StorageMode.FILESYSTEM)
      assertThat(storageDirectory.listRegularFiles()).allSatisfy {
        assertThat(it.normalize().pathString).startsWith(storageDirectory.normalize().pathString)
        assertThat(Files.isRegularFile(it)).isTrue()
      }
    }
  }

  private fun createUnsafeGeneratedThumbnailReferences(storageDirectory: Path): List<UnsafeGeneratedThumbnailReference> {
    val absoluteOutsidePath = tempDir.resolve("absolute-outside-thumbnail.jpg")
    Files.write(absoluteOutsidePath, byteArrayOf(91, 91, 91))

    val traversalPath = storageDirectory.resolve("books/generated/book-unsafe-traversal/../../../../traversal-thumbnail.jpg")
    Files.createDirectories(traversalPath.normalize().parent)
    Files.write(traversalPath.normalize(), byteArrayOf(92, 92, 92))

    val symlinkOutsideDirectory = tempDir.resolve("symlink-outside")
    Files.createDirectories(symlinkOutsideDirectory)
    val symlinkOutsidePath = symlinkOutsideDirectory.resolve("symlink-thumbnail.jpg")
    Files.write(symlinkOutsidePath, byteArrayOf(93, 93, 93))
    val symlinkDirectory = storageDirectory.resolve("books/generated/symlink-escape")
    Files.createDirectories(symlinkDirectory.parent)
    Files.createSymbolicLink(symlinkDirectory, symlinkOutsideDirectory)
    val symlinkPath = symlinkDirectory.resolve("symlink-thumbnail.jpg")

    return listOf(
      UnsafeGeneratedThumbnailReference(
        bookId = "book-unsafe-absolute",
        thumbnailId = "thumbnail-unsafe-absolute",
        thumbnailUrl = absoluteOutsidePath.toUri().toURL().toString(),
        sentinelPath = absoluteOutsidePath,
        sentinelBytes = byteArrayOf(91, 91, 91),
      ),
      UnsafeGeneratedThumbnailReference(
        bookId = "book-unsafe-traversal",
        thumbnailId = "thumbnail-unsafe-traversal",
        thumbnailUrl = "file:${traversalPath.pathString}",
        sentinelPath = traversalPath.normalize(),
        sentinelBytes = byteArrayOf(92, 92, 92),
      ),
      UnsafeGeneratedThumbnailReference(
        bookId = "book-unsafe-symlink",
        thumbnailId = "thumbnail-unsafe-symlink",
        thumbnailUrl = symlinkPath.toUri().toURL().toString(),
        sentinelPath = symlinkOutsidePath,
        sentinelBytes = byteArrayOf(93, 93, 93),
      ),
    )
  }

  private fun startApplication(properties: Map<String, String>): ConfigurableApplicationContext {
    val previousProperties = properties.mapValues { System.getProperty(it.key) }
    properties.forEach { (key, value) -> System.setProperty(key, value) }
    return try {
      SpringApplicationBuilder(Application::class.java)
        .web(WebApplicationType.SERVLET)
        .profiles("test")
        .run()
    } finally {
      previousProperties.forEach { (key, value) ->
        if (value == null)
          System.clearProperty(key)
        else
          System.setProperty(key, value)
      }
    }
  }

  private fun ConfigurableApplicationContext.mockMvc(): MockMvc =
    MockMvcBuilders
      .webAppContextSetup(this as WebApplicationContext)
      .apply<DefaultMockMvcBuilder>(springSecurity())
      .build()

  private fun ConfigurableApplicationContext.objectMapper(): ObjectMapper = getBean(ObjectMapper::class.java)

  private fun ConfigurableApplicationContext.rebuildSearchIndex() {
    getBean(SearchIndexLifecycle::class.java).rebuildIndex()
  }

  private fun MockMvc.searchApiFacts(objectMapper: ObjectMapper): SearchApiFacts =
    SearchApiFacts(
      seriesByUnicodeAccentTitle = postPageIds(objectMapper, "/api/v1/series/list?unpaged=true", """{"fullTextSearch":"ecole"}"""),
      seriesByAuthor = postPageIds(objectMapper, "/api/v1/series/list?unpaged=true", """{"fullTextSearch":"author:alice"}"""),
      seriesByTag = postPageIds(objectMapper, "/api/v1/series/list?unpaged=true", """{"fullTextSearch":"tag:kid-safe"}"""),
      seriesByCollection = postPageIds(objectMapper, "/api/v1/series/list?unpaged=true", """{"condition":{"collectionId":{"operator":"is","value":"collection-1"}}}"""),
      collectionsByName = getPageIds(objectMapper, "/api/v1/collections?search=collection&unpaged=true"),
      readListsByName = getPageIds(objectMapper, "/api/v1/readlists?search=Read&unpaged=true"),
      booksByUnicodeAccentTitle = postPageIds(objectMapper, "/api/v1/books/list?unpaged=true", """{"fullTextSearch":"cafe"}"""),
      booksByAuthor = postPageIds(objectMapper, "/api/v1/books/list?unpaged=true", """{"fullTextSearch":"author:alice"}"""),
      booksByTag = postPageIds(objectMapper, "/api/v1/books/list?unpaged=true", """{"fullTextSearch":"tag:kid-safe"}"""),
      booksByReadList = postPageIds(objectMapper, "/api/v1/books/list?unpaged=true", """{"condition":{"readListId":{"operator":"is","value":"readlist-1"}}}"""),
      adminDeniedSeriesSearch = getPageIdsWithBasic(objectMapper, "/api/v1/series?search=denied&unpaged=true"),
      adminDeniedReadListSearch = getPageIdsWithBasic(objectMapper, "/api/v1/readlists?search=denied&unpaged=true"),
      restrictedDeniedSeriesSearch = getPageIds(objectMapper, "/api/v1/series?search=denied&unpaged=true"),
      restrictedDeniedBookSearch = getPageIds(objectMapper, "/api/v1/books?search=denied&unpaged=true"),
      restrictedDeniedReadListSearch = getPageIds(objectMapper, "/api/v1/readlists?search=denied&unpaged=true"),
    )

  private fun MockMvc.getPageIds(
    objectMapper: ObjectMapper,
    url: String,
    apiKey: String = PostgreSqlMigrationFixture.RESTRICTED_API_KEY,
  ): List<String> {
    val response =
      get(url) {
        header("x-api-key", apiKey)
      }.andExpect {
        status { isOk() }
        content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
      }.andReturn()

    return objectMapper.readTree(response.response.contentAsString)["content"].map { it["id"].asText() }
  }

  private fun MockMvc.postPageIds(
    objectMapper: ObjectMapper,
    url: String,
    body: String,
    apiKey: String = PostgreSqlMigrationFixture.RESTRICTED_API_KEY,
  ): List<String> {
    val response =
      post(url) {
        header("x-api-key", apiKey)
        contentType = MediaType.APPLICATION_JSON
        content = body
      }.andExpect {
        status { isOk() }
        content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
      }.andReturn()

    return objectMapper.readTree(response.response.contentAsString)["content"].map { it["id"].asText() }
  }

  private fun MockMvc.getPageIdsWithBasic(
    objectMapper: ObjectMapper,
    url: String,
  ): List<String> {
    val response =
      get(url) {
        with(httpBasic(PostgreSqlMigrationFixture.ADMIN_EMAIL, PostgreSqlMigrationFixture.ADMIN_PASSWORD))
      }.andExpect {
        status { isOk() }
        content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
      }.andReturn()

    return objectMapper.readTree(response.response.contentAsString)["content"].map { it["id"].asText() }
  }

  private fun MockMvc.assertMigratedAuthenticationWorks() {
    get("/api/v2/users/me") {
      with(httpBasic(PostgreSqlMigrationFixture.ADMIN_EMAIL, PostgreSqlMigrationFixture.ADMIN_PASSWORD))
    }.andExpect {
      status { isOk() }
      content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
      jsonPath("id") { value("admin") }
      jsonPath("email") { value(PostgreSqlMigrationFixture.ADMIN_EMAIL) }
    }

    get("/api/v2/users/me") {
      header("x-api-key", PostgreSqlMigrationFixture.RESTRICTED_API_KEY)
    }.andExpect {
      status { isOk() }
      content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
      jsonPath("id") { value("restricted") }
      jsonPath("email") { value(PostgreSqlMigrationFixture.RESTRICTED_EMAIL) }
    }
  }

  private fun MockMvc.assertMigratedBrowseAndAuthorizationWorks() {
    get("/api/v1/libraries") {
      header("x-api-key", PostgreSqlMigrationFixture.RESTRICTED_API_KEY)
    }.andExpect {
      status { isOk() }
      content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
      jsonPath("$.length()") { value(1) }
      jsonPath("$[0].id") { value("library-1") }
      jsonPath("$[0].name") { value("Migrated Library") }
    }

    get("/api/v1/series/series-1") {
      header("x-api-key", PostgreSqlMigrationFixture.RESTRICTED_API_KEY)
    }.andExpect {
      status { isOk() }
      content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
      jsonPath("id") { value("series-1") }
      jsonPath("name") { value("École Adventures") }
    }

    get("/api/v1/books/book-1") {
      header("x-api-key", PostgreSqlMigrationFixture.RESTRICTED_API_KEY)
    }.andExpect {
      status { isOk() }
      content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
      jsonPath("id") { value("book-1") }
      jsonPath("readProgress.page") { value(3) }
    }

    get("/api/v1/books/book-denied") {
      header("x-api-key", PostgreSqlMigrationFixture.RESTRICTED_API_KEY)
    }.andExpect {
      status { isForbidden() }
      content { string("") }
    }
  }

  private fun MockMvc.assertMigratedMediaAndThumbnailApisWork() {
    get("/api/v1/books/book-1/pages") {
      header("x-api-key", PostgreSqlMigrationFixture.RESTRICTED_API_KEY)
    }.andExpect {
      status { isOk() }
      content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
      jsonPath("$.length()") { value(1) }
      jsonPath("$[0].number") { value(1) }
      jsonPath("$[0].mediaType") { value("image/jpeg") }
    }

    val thumbnail =
      get("/api/v1/books/book-1/thumbnail") {
        header("x-api-key", PostgreSqlMigrationFixture.RESTRICTED_API_KEY)
      }.andExpect {
        status { isOk() }
        content { contentTypeCompatibleWith(MediaType.IMAGE_JPEG) }
        header { string(HttpHeaders.CONTENT_TYPE, containsString(MediaType.IMAGE_JPEG_VALUE)) }
      }.andReturn()

    assertThat(thumbnail.response.contentAsByteArray).containsExactly(1, 2, 3, 4)
  }

  private fun MockMvc.assertMigratedThumbnailPublicApisWork() {
    assertThumbnailBytes("/api/v1/books/book-1/thumbnail", byteArrayOf(1, 2, 3, 4))
    assertThumbnailBytes("/api/v1/books/book-1/thumbnails/thumbnail-1", byteArrayOf(1, 2, 3, 4))
    assertThumbnailBytes("/api/v1/books/book-1/thumbnails/thumbnail-uploaded-1", byteArrayOf(21, 22, 23))
    get("/api/v1/books/book-1/thumbnails") {
      header("x-api-key", PostgreSqlMigrationFixture.RESTRICTED_API_KEY)
    }.andExpect {
      status { isOk() }
      content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
      jsonPath("$.length()") { value(2) }
      jsonPath("$[?(@.id == 'thumbnail-1')].bookId") { value(contains("book-1")) }
      jsonPath("$[?(@.id == 'thumbnail-1')].type") { value(contains("GENERATED")) }
      jsonPath("$[?(@.id == 'thumbnail-1')].selected") { value(contains(true)) }
      jsonPath("$[?(@.id == 'thumbnail-1')].mediaType") { value(contains("image/jpeg")) }
      jsonPath("$[?(@.id == 'thumbnail-1')].fileSize") { value(contains(4)) }
      jsonPath("$[?(@.id == 'thumbnail-1')].width") { value(contains(1)) }
      jsonPath("$[?(@.id == 'thumbnail-1')].height") { value(contains(1)) }
    }

    assertThumbnailBytes("/api/v1/series/series-1/thumbnail", byteArrayOf(5, 6, 7))
    assertThumbnailBytes("/api/v1/series/series-1/thumbnails/series-thumbnail-1", byteArrayOf(5, 6, 7))
    get("/api/v1/series/series-1/thumbnails") {
      header("x-api-key", PostgreSqlMigrationFixture.RESTRICTED_API_KEY)
    }.andExpect {
      status { isOk() }
      content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
      jsonPath("$.length()") { value(1) }
      jsonPath("$[0].id") { value("series-thumbnail-1") }
      jsonPath("$[0].seriesId") { value("series-1") }
      jsonPath("$[0].type") { value("USER_UPLOADED") }
      jsonPath("$[0].selected") { value(true) }
      jsonPath("$[0].mediaType") { value("image/jpeg") }
      jsonPath("$[0].fileSize") { value(3) }
      jsonPath("$[0].width") { value(3) }
      jsonPath("$[0].height") { value(1) }
    }

    assertThumbnailBytes("/api/v1/collections/collection-1/thumbnail", byteArrayOf(8, 9, 10), expectPrivateCache = true)
    assertThumbnailBytes("/api/v1/collections/collection-1/thumbnails/collection-thumbnail-1", byteArrayOf(8, 9, 10))
    get("/api/v1/collections/collection-1/thumbnails") {
      header("x-api-key", PostgreSqlMigrationFixture.RESTRICTED_API_KEY)
    }.andExpect {
      status { isOk() }
      content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
      jsonPath("$.length()") { value(1) }
      jsonPath("$[0].id") { value("collection-thumbnail-1") }
      jsonPath("$[0].collectionId") { value("collection-1") }
      jsonPath("$[0].type") { value("USER_UPLOADED") }
      jsonPath("$[0].selected") { value(true) }
      jsonPath("$[0].mediaType") { value("image/jpeg") }
      jsonPath("$[0].fileSize") { value(3) }
      jsonPath("$[0].width") { value(3) }
      jsonPath("$[0].height") { value(1) }
    }

    assertThumbnailBytes("/api/v1/readlists/readlist-1/thumbnail", byteArrayOf(11, 12, 13), expectPrivateCache = true)
    assertThumbnailBytes("/api/v1/readlists/readlist-1/thumbnails/readlist-thumbnail-1", byteArrayOf(11, 12, 13))
    get("/api/v1/readlists/readlist-1/thumbnails") {
      header("x-api-key", PostgreSqlMigrationFixture.RESTRICTED_API_KEY)
    }.andExpect {
      status { isOk() }
      content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
      jsonPath("$.length()") { value(1) }
      jsonPath("$[0].id") { value("readlist-thumbnail-1") }
      jsonPath("$[0].readListId") { value("readlist-1") }
      jsonPath("$[0].type") { value("USER_UPLOADED") }
      jsonPath("$[0].selected") { value(true) }
      jsonPath("$[0].mediaType") { value("image/jpeg") }
      jsonPath("$[0].fileSize") { value(3) }
      jsonPath("$[0].width") { value(3) }
      jsonPath("$[0].height") { value(1) }
    }

    assertThumbnailBytes(
      url = "/api/v1/page-hashes/page-hash-1/thumbnail",
      expected = byteArrayOf(14, 15, 16),
      useAdminBasic = true,
    )
  }

  private fun MockMvc.assertThumbnailBytes(
    url: String,
    expected: ByteArray,
    useAdminBasic: Boolean = false,
    expectPrivateCache: Boolean = false,
  ) {
    val result =
      get(url) {
        if (useAdminBasic)
          with(httpBasic(PostgreSqlMigrationFixture.ADMIN_EMAIL, PostgreSqlMigrationFixture.ADMIN_PASSWORD))
        else
          header("x-api-key", PostgreSqlMigrationFixture.RESTRICTED_API_KEY)
      }.andExpect {
        status { isOk() }
        content { contentTypeCompatibleWith(MediaType.IMAGE_JPEG) }
        header { string(HttpHeaders.CONTENT_TYPE, containsString(MediaType.IMAGE_JPEG_VALUE)) }
        if (expectPrivateCache) {
          header { string(HttpHeaders.CACHE_CONTROL, containsString("private")) }
        }
      }.andReturn()

    assertThat(result.response.contentAsByteArray).isEqualTo(expected)
  }

  private fun MockMvc.getBookThumbnailFromPublicApi(bookId: String): ByteArray =
    get("/api/v1/books/$bookId/thumbnail") {
      header("x-api-key", PostgreSqlMigrationFixture.RESTRICTED_API_KEY)
    }.andExpect {
      status { isOk() }
      content { contentTypeCompatibleWith(MediaType.IMAGE_JPEG) }
      header { string(HttpHeaders.CONTENT_TYPE, containsString(MediaType.IMAGE_JPEG_VALUE)) }
    }.andReturn()
      .response
      .contentAsByteArray

  private fun MockMvc.assertMigratedSettingsAreReadableAndWritable() {
    get("/api/v1/settings") {
      with(httpBasic(PostgreSqlMigrationFixture.ADMIN_EMAIL, PostgreSqlMigrationFixture.ADMIN_PASSWORD))
    }.andExpect {
      status { isOk() }
      content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
      jsonPath("deleteEmptyCollections") { value(true) }
      jsonPath("serverPort.databaseSource") { value(1234) }
    }

    patch("/api/v1/settings") {
      with(httpBasic(PostgreSqlMigrationFixture.ADMIN_EMAIL, PostgreSqlMigrationFixture.ADMIN_PASSWORD))
      contentType = MediaType.APPLICATION_JSON
      content =
        """
        {
          "deleteEmptyCollections": false,
          "serverPort": 5678
        }
        """.trimIndent()
    }.andExpect {
      status { isNoContent() }
    }
  }

  private fun MockMvc.assertMigratedApiKeysCanBeCreatedAndListed(objectMapper: ObjectMapper) {
    val created =
      post("/api/v2/users/me/api-keys") {
        with(httpBasic(PostgreSqlMigrationFixture.ADMIN_EMAIL, PostgreSqlMigrationFixture.ADMIN_PASSWORD))
        contentType = MediaType.APPLICATION_JSON
        content = """{"comment":"created through migrated API smoke"}"""
      }.andExpect {
        status { isOk() }
        content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
        jsonPath("userId") { value("admin") }
        jsonPath("comment") { value("created through migrated API smoke") }
      }.andReturn()

    assertThat(objectMapper.readTree(created.response.contentAsString)["key"].asText()).doesNotContain("*")

    get("/api/v2/users/me/api-keys") {
      with(httpBasic(PostgreSqlMigrationFixture.ADMIN_EMAIL, PostgreSqlMigrationFixture.ADMIN_PASSWORD))
    }.andExpect {
      status { isOk() }
      content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
      jsonPath("$.length()") { value(1) }
      jsonPath("$[0].userId") { value("admin") }
      jsonPath("$[0].comment") { value("created through migrated API smoke") }
      jsonPath("$[0].key") { value(containsString("*")) }
    }
  }

  private fun MockMvc.assertMigratedKoboAndKoreaderApisWork() {
    get("/kobo/${PostgreSqlMigrationFixture.RESTRICTED_API_KEY}/v1/library/sync")
      .andExpect {
        status { isOk() }
        content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
        header { exists(KoboHeaders.X_KOBO_SYNCTOKEN) }
      }

    get("/koreader/users/auth") {
      header("x-auth-user", PostgreSqlMigrationFixture.RESTRICTED_API_KEY)
      header(HttpHeaders.ACCEPT, "application/vnd.koreader.v1+json")
    }.andExpect {
      status { isOk() }
      content { contentTypeCompatibleWith(MediaType("application", "vnd.koreader.v1+json")) }
      jsonPath("authorized") { value("OK") }
    }
  }

  private fun MockMvc.performPersistedWritesBeforeRestart() {
    patch("/api/v1/books/book-1/read-progress") {
      header("x-api-key", PostgreSqlMigrationFixture.RESTRICTED_API_KEY)
      contentType = MediaType.APPLICATION_JSON
      content = """{"page":4}"""
    }.andExpect {
      status { isNoContent() }
    }
  }

  private fun MockMvc.assertApiWritesSurvivedRestart() {
    get("/api/v1/settings") {
      with(httpBasic(PostgreSqlMigrationFixture.ADMIN_EMAIL, PostgreSqlMigrationFixture.ADMIN_PASSWORD))
    }.andExpect {
      status { isOk() }
      content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
      jsonPath("deleteEmptyCollections") { value(false) }
      jsonPath("serverPort.databaseSource") { value(5678) }
    }

    get("/api/v2/users/me/api-keys") {
      with(httpBasic(PostgreSqlMigrationFixture.ADMIN_EMAIL, PostgreSqlMigrationFixture.ADMIN_PASSWORD))
    }.andExpect {
      status { isOk() }
      content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
      jsonPath("$.length()") { value(1) }
      jsonPath("$[0].comment") { value("created through migrated API smoke") }
    }

    get("/api/v1/books/book-1") {
      header("x-api-key", PostgreSqlMigrationFixture.RESTRICTED_API_KEY)
    }.andExpect {
      status { isOk() }
      content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
      jsonPath("readProgress.page") { value(4) }
    }

    get("/koreader/syncs/progress/${PostgreSqlMigrationFixture.KOREADER_HASH}") {
      header("x-auth-user", PostgreSqlMigrationFixture.RESTRICTED_API_KEY)
    }.andExpect {
      status { isOk() }
      content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
      jsonPath("document") { value(PostgreSqlMigrationFixture.KOREADER_HASH) }
      jsonPath("progress") { value("4") }
    }
  }

  private fun Path.listRegularFiles(): List<Path> =
    if (exists()) {
      listDirectoryEntries().flatMap {
        when {
          Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) -> it.listRegularFiles()
          Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) -> listOf(it)
          else -> emptyList()
        }
      }
    } else {
      emptyList()
    }

  private companion object {
    val postgreSqlProperties =
      mapOf(
        "komga.database.backend" to "POSTGRESQL",
        "komga.database.postgresql.url" to PostgreSqlMigrationFixture.JDBC_URL,
        "komga.database.postgresql.username" to PostgreSqlMigrationFixture.USERNAME,
        "komga.database.postgresql.password" to PostgreSqlMigrationFixture.PASSWORD,
        "komga.database.pool-size" to "4",
        "komga.tasks-db.backend" to "POSTGRESQL",
        "komga.tasks-db.postgresql.url" to PostgreSqlMigrationFixture.JDBC_URL,
        "komga.tasks-db.postgresql.username" to PostgreSqlMigrationFixture.USERNAME,
        "komga.tasks-db.postgresql.password" to PostgreSqlMigrationFixture.PASSWORD,
        "komga.tasks-db.pool-size" to "4",
        "server.port" to "0",
      )

    fun sqliteProperties(
      sourceMain: String,
      sourceTasks: String,
    ): Map<String, String> =
      mapOf(
        "komga.database.file" to sourceMain.removePrefix("jdbc:sqlite:"),
        "komga.database.pool-size" to "4",
        "komga.tasks-db.file" to sourceTasks.removePrefix("jdbc:sqlite:"),
        "komga.tasks-db.pool-size" to "4",
        "server.port" to "0",
      )
  }

  private data class SearchApiFacts(
    val seriesByUnicodeAccentTitle: List<String>,
    val seriesByAuthor: List<String>,
    val seriesByTag: List<String>,
    val seriesByCollection: List<String>,
    val collectionsByName: List<String>,
    val readListsByName: List<String>,
    val booksByUnicodeAccentTitle: List<String>,
    val booksByAuthor: List<String>,
    val booksByTag: List<String>,
    val booksByReadList: List<String>,
    val adminDeniedSeriesSearch: List<String>,
    val adminDeniedReadListSearch: List<String>,
    val restrictedDeniedSeriesSearch: List<String>,
    val restrictedDeniedBookSearch: List<String>,
    val restrictedDeniedReadListSearch: List<String>,
  )

  private data class UnsafeGeneratedThumbnailReference(
    val bookId: String,
    val thumbnailId: String,
    val thumbnailUrl: String,
    val sentinelPath: Path,
    val sentinelBytes: ByteArray,
  )
}
