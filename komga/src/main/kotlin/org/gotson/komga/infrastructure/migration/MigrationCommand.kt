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
      preflight --source-main=<jdbc:sqlite:main> --source-tasks=<jdbc:sqlite:tasks> --target=<jdbc:postgresql://...> --target-user=<user> --target-password=<password> [--report=<file>] [--resume]
      migrate   --source-main=<jdbc:sqlite:main> --source-tasks=<jdbc:sqlite:tasks> --target=<jdbc:postgresql://...> --target-user=<user> --target-password=<password> [--report=<file>] [--resume]

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

    val missing =
      listOf("source-main", "source-tasks", "target")
        .filter { options[it].isNullOrBlank() }

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
          sourceMain = JdbcEndpoint(options.getValue("source-main")),
          sourceTasks = JdbcEndpoint(options.getValue("source-tasks")),
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
}

fun main(args: Array<String>) {
  System.exit(MigrationCommand.execute(args))
}
