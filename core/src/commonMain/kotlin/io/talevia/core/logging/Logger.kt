package io.talevia.core.logging

/**
 * Structured logger for core + apps. Field-per-pair key=value format keeps log
 * lines grep-able without bringing a real logging framework into commonMain.
 * Platforms can swap [Loggers.install] to redirect output (Android logcat,
 * iOS os_log, JVM slf4j) without touching call sites.
 */
interface Logger {
    fun log(level: LogLevel, message: String, fields: Map<String, Any?> = emptyMap(), cause: Throwable? = null)
}

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

fun Logger.debug(message: String, vararg fields: Pair<String, Any?>) =
    log(LogLevel.DEBUG, message, fields.toMap())

fun Logger.info(message: String, vararg fields: Pair<String, Any?>) =
    log(LogLevel.INFO, message, fields.toMap())

fun Logger.warn(message: String, vararg fields: Pair<String, Any?>, cause: Throwable? = null) =
    log(LogLevel.WARN, message, fields.toMap(), cause)

fun Logger.error(message: String, cause: Throwable? = null, vararg fields: Pair<String, Any?>) =
    log(LogLevel.ERROR, message, fields.toMap(), cause)

/**
 * Global logger factory. Default is [ConsoleLogger]; apps can call
 * [install] at startup to redirect (e.g. JVM servers to slf4j).
 */
object Loggers {
    private var factory: (String) -> Logger = { name -> ConsoleLogger(name) }
    private var minLevel: LogLevel = LogLevel.INFO

    fun install(factory: (String) -> Logger) { this.factory = factory }
    fun setMinLevel(level: LogLevel) { this.minLevel = level }
    fun minLevel(): LogLevel = minLevel

    fun get(name: String): Logger = factory(name)
}

/**
 * Default sink: prints `[LEVEL] name: message k1=v1 k2=v2`. String values with
 * whitespace, `=`, `"`, or newlines get JSON-ish quoting so fields remain
 * parseable. Level filtering goes through [Loggers.minLevel] so tests can
 * silence noise without mocking the whole logger.
 */
class ConsoleLogger(val name: String) : Logger {
    override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, cause: Throwable?) {
        if (level.ordinal < Loggers.minLevel().ordinal) return
        println(render(name, level, message, fields))
        cause?.printStackTrace()
    }
}

fun render(loggerName: String, level: LogLevel, message: String, fields: Map<String, Any?>): String {
    val kv = if (fields.isEmpty()) "" else " " + fields.entries.joinToString(" ") { (k, v) -> "$k=${formatValue(v)}" }
    return "[${level.name}] $loggerName: $message$kv"
}

private fun formatValue(v: Any?): String = when (v) {
    null -> "null"
    is String -> if (needsQuoting(v)) "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"" else v
    else -> v.toString()
}

private fun needsQuoting(s: String): Boolean =
    s.isEmpty() || s.any { it == ' ' || it == '=' || it == '"' || it == '\n' || it == '\t' }
