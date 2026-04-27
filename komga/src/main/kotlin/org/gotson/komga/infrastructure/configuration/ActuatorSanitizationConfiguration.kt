package org.gotson.komga.infrastructure.configuration

import org.springframework.boot.actuate.endpoint.SanitizableData
import org.springframework.boot.actuate.endpoint.SanitizingFunction
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ActuatorSanitizationConfiguration {
  @Bean
  fun komgaDatabaseSanitizingFunction(): SanitizingFunction =
    SanitizingFunction { data ->
      if (data.shouldSanitizePostgresqlCredential()) data.withSanitizedValue() else data
    }

  private fun SanitizableData.shouldSanitizePostgresqlCredential(): Boolean =
    lowerCaseKey.endsWith(".postgresql.url") ||
      lowerCaseKey.endsWith(".postgresql.username") ||
      lowerCaseKey.endsWith(".postgresql.password") ||
      ((value as? String)?.contains("jdbc:postgresql:", ignoreCase = true) == true && (value as String).containsCredential())

  private fun String.containsCredential(): Boolean =
    contains("://") && substringAfter("://").substringBefore('/').contains('@') ||
      contains("password=", ignoreCase = true) ||
      contains("apikey=", ignoreCase = true) ||
      contains("api_key=", ignoreCase = true)
}
