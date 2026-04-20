package io.talevia.cli

import io.talevia.core.logging.LogLevel
import io.talevia.core.logging.Logger
import io.talevia.core.logging.Loggers
import io.talevia.core.logging.render
import java.io.File
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * Route core/agent log output to a rotating-by-launch file under
 * `~/.talevia/cli.log` so the REPL terminal stays clean. Without this the
 * default [io.talevia.core.logging.ConsoleLogger] writes to stdout and tramples
 * the assistant text mid-stream.
 *
 * Min level reads from `TALEVIA_CLI_LOG_LEVEL` (DEBUG/INFO/WARN/ERROR), default
 * INFO. If the file can't be opened we silently drop logs — losing telemetry
 * is strictly preferable to crashing or polluting the UI.
 */
internal object CliLoggers {
    fun install(logFile: File, level: LogLevel) {
        Loggers.setMinLevel(level)
        val writer = runCatching {
            logFile.parentFile?.mkdirs()
            PrintWriter(logFile.outputStream().writer(StandardCharsets.UTF_8).buffered(), true)
        }.getOrNull()
        if (writer == null) {
            Loggers.install { _ -> NullLogger }
            return
        }
        Loggers.install { name -> FileLogger(name, writer) }
    }

    private class FileLogger(val name: String, val out: PrintWriter) : Logger {
        override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, cause: Throwable?) {
            if (level.ordinal < Loggers.minLevel().ordinal) return
            val line = "${Instant.now()} ${render(name, level, message, fields)}"
            synchronized(out) {
                out.println(line)
                cause?.printStackTrace(out)
                out.flush()
            }
        }
    }

    private object NullLogger : Logger {
        override fun log(
            level: LogLevel,
            message: String,
            fields: Map<String, Any?>,
            cause: Throwable?,
        ) = Unit
    }
}
