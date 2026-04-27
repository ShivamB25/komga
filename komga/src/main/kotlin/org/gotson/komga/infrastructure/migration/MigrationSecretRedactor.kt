package org.gotson.komga.infrastructure.migration

object MigrationSecretRedactor {
  private val credentialInAuthority = Regex("""(?i)(jdbc:postgresql://)[^/@\s:]+:[^/@\s]+@""")
  private val sensitiveName = """password|passwd|pwd|api[_-]?key|apikey|token|secret|remember[-_]?me[-_]?key|remembermekey"""
  private val sensitiveQueryParameter = Regex("""(?i)($sensitiveName)=([^&\s]+)""")
  private val assignment = Regex("""(?i)\b($sensitiveName)\s*[:=]\s*([^,&\s}]+)""")

  fun redact(value: String): String =
    value
      .replace(credentialInAuthority, "$1<redacted>@")
      .replace(sensitiveQueryParameter) { "${it.groupValues[1]}=<redacted>" }
      .replace(assignment) { "${it.groupValues[1]}=<redacted>" }
}
