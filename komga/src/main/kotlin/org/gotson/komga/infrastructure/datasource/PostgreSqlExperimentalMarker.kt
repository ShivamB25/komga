package org.gotson.komga.infrastructure.datasource

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.infrastructure.configuration.KomgaProperties
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class PostgreSqlExperimentalMarker(
  private val komgaProperties: KomgaProperties,
) {
  fun marker(): String? =
    when (komgaProperties.database.backend) {
      DatabaseBackend.SQLITE -> null
      DatabaseBackend.POSTGRESQL -> POSTGRESQL_EXPERIMENTAL_MARKER
    }

  fun emitMarker() {
    marker()?.let { logger.warn { it } }
  }

  @EventListener(ApplicationReadyEvent::class)
  fun onApplicationReady() {
    emitMarker()
  }

  companion object {
    const val POSTGRESQL_EXPERIMENTAL_MARKER =
      "PostgreSQL backend is advanced, optional, and experimental until complete validation passes. SQLite remains the default backend."
  }
}
