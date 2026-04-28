package org.gotson.komga.infrastructure.migration

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.io.path.Path
import kotlin.io.path.writeText

private val logger = KotlinLogging.logger {}

object MigrationCommand {
  private val mapper =
    jacksonObjectMapper()
      .registerKotlinModule()
      .findAndRegisterModules()
      .writerWithDefaultPrettyPrinter()

  fun execute(args: Array<String>): Int {
    val parsed = parse(args)
    if (parsed.argumentError != null) {
      logger.warn { parsed.argumentError }
      logger.info { help() }
      return 2
    }
    if (parsed.mode == MigrationMode.HELP) {
      logger.info { help() }
      return 0
    }

    val request = parsed.request ?: return 2
    val report = MigrationPreflight().run(request)
    val reportJson = mapper.writeValueAsString(report.redacted())
    parsed.reportPath?.let { Path(it).writeText(reportJson) } ?: logger.info { reportJson }
    return if (report.status == MigrationStatus.SUCCESS) 0 else 1
  }

  fun help(): String =
    """
    Offline Komga SQLite-to-PostgreSQL migration command.

    Usage:
      preflight --source-config-dir=<dir> --target=<jdbc:postgresql://...> --target-user=<user> --target-password=<password> [--source-main=<jdbc:sqlite:main>] [--source-tasks=<jdbc:sqlite:tasks>] [--report=<file>] [--resume]
      migrate   --source-config-dir=<dir> --target=<jdbc:postgresql://...> --target-user=<user> --target-password=<password> [--source-main=<jdbc:sqlite:main>] [--source-tasks=<jdbc:sqlite:tasks>] [--report=<file>] [--resume]

    --source-config-dir infers database.sqlite and tasks.sqlite from the given Komga config directory.
    Use --source-main and --source-tasks directly for custom SQLite database locations.

    This command is explicit and offline. It does not start the HTTP server, schedulers, task processors, scanners, SSE, or search lifecycle.
    """.trimIndent()

  private fun parse(args: Array<String>): ParsedCommand {
    if (args.isEmpty() || args.any { it == "--help" || it == "-h" }) return ParsedCommand(MigrationMode.HELP)

    val mode =
      when (args.first()) {
        "preflight" -> MigrationMode.PREFLIGHT
        "migrate" -> MigrationMode.MIGRATE
        else -> {
          return ParsedCommand(
            mode = MigrationMode.PREFLIGHT,
            argumentError = "Unknown migration mode '${args.first()}'.",
          )
        }
      }

    val options =
      args
        .drop(1)
        .filter { it.startsWith("--") && it.contains("=") }
        .associate {
          val (key, value) = it.removePrefix("--").split("=", limit = 2)
          key to value
        }

    val sourceConfigDir = options["source-config-dir"]?.takeIf { it.isNotBlank() }
    val sourceMain = options["source-main"]?.takeIf { it.isNotBlank() } ?: sourceConfigDir?.let { sourceJdbcUrl(it, "database.sqlite") }
    val sourceTasks = options["source-tasks"]?.takeIf { it.isNotBlank() } ?: sourceConfigDir?.let { sourceJdbcUrl(it, "tasks.sqlite") }

    val missing =
      buildList {
        if (sourceMain.isNullOrBlank()) add("source-main")
        if (sourceTasks.isNullOrBlank()) add("source-tasks")
        if (options["target"].isNullOrBlank()) add("target")
      }

    if (missing.isNotEmpty()) {
      return ParsedCommand(
        mode = mode,
        argumentError = "Missing required migration arguments: ${missing.joinToString { "--$it" }}",
      )
    }

    return ParsedCommand(
      mode = mode,
      request =
        MigrationRequest(
          sourceMain = JdbcEndpoint(sourceMain!!),
          sourceTasks = JdbcEndpoint(sourceTasks!!),
          target =
            JdbcEndpoint(
              url = options.getValue("target"),
              username = options["target-user"],
              password = options["target-password"],
            ),
          mode = mode,
          resume = args.contains("--resume"),
        ),
      reportPath = options["report"],
    )
  }

  private data class ParsedCommand(
    val mode: MigrationMode,
    val request: MigrationRequest? = null,
    val reportPath: String? = null,
    val argumentError: String? = null,
  )

  private fun sourceJdbcUrl(
    sourceConfigDir: String,
    fileName: String,
  ): String = "jdbc:sqlite:${Path(sourceConfigDir).resolve(fileName)}"
}

fun main(args: Array<String>) {
  System.exit(MigrationCommand.execute(args))
}
