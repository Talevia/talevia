package io.talevia.core.logging

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the key=value wire format + helper semantics so downstream consumers
 * (grep, log ingestion) can rely on the shape.
 */
class LoggerTest {

    @AfterTest fun reset() {
        Loggers.install { ConsoleLogger(it) }
        Loggers.setMinLevel(LogLevel.INFO)
    }

    private class Captured(val level: LogLevel, val message: String, val fields: Map<String, Any?>, val cause: Throwable?)

    private fun capturing(): Pair<Logger, MutableList<Captured>> {
        val log = mutableListOf<Captured>()
        val sink = object : Logger {
            override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, cause: Throwable?) {
                log += Captured(level, message, fields, cause)
            }
        }
        return sink to log
    }

    @Test fun helpersAttachFields() {
        val (sink, log) = capturing()
        Loggers.install { sink }
        Loggers.get("x").info("hello", "k1" to "v1", "k2" to 42)
        assertEquals(1, log.size)
        assertEquals("hello", log[0].message)
        assertEquals(LogLevel.INFO, log[0].level)
        assertEquals(mapOf("k1" to "v1", "k2" to 42), log[0].fields)
    }

    @Test fun errorCarriesCause() {
        val (sink, log) = capturing()
        Loggers.install { sink }
        val boom = RuntimeException("bad")
        Loggers.get("x").error("fail", boom, "k" to "v")
        assertEquals(boom, log[0].cause)
    }

    @Test fun warnCarriesCauseWhenProvided() {
        val (sink, log) = capturing()
        Loggers.install { sink }
        val boom = RuntimeException("w")
        Loggers.get("x").warn("trouble", "k" to "v", cause = boom)
        assertEquals(boom, log[0].cause)
    }

    @Test fun renderProducesPrefixAndFields() {
        val out = render("my.logger", LogLevel.INFO, "hi", mapOf("a" to 1, "b" to "two"))
        assertEquals("[INFO] my.logger: hi a=1 b=two", out)
    }

    @Test fun renderQuotesSpaces() {
        val out = render("x", LogLevel.INFO, "m", mapOf("path" to "/a b/c"))
        assertContains(out, "path=\"/a b/c\"")
    }

    @Test fun renderEscapesNewlineAndQuote() {
        val out = render("x", LogLevel.WARN, "m", mapOf("msg" to "line1\nline2", "q" to "a\"b"))
        assertContains(out, "msg=\"line1\\nline2\"")
        assertContains(out, "q=\"a\\\"b\"")
    }

    @Test fun renderNullField() {
        val out = render("x", LogLevel.INFO, "m", mapOf("x" to null))
        assertContains(out, "x=null")
    }

    @Test fun renderEmptyStringIsQuoted() {
        // Without quoting an empty string vanishes and the line parses as
        // `key=` (ambiguous with "missing value"). Always quote.
        val out = render("x", LogLevel.INFO, "m", mapOf("s" to ""))
        assertContains(out, "s=\"\"")
    }

    @Test fun renderNonStringRendersViaToString() {
        val out = render("x", LogLevel.INFO, "m", mapOf("n" to 7, "b" to true))
        assertContains(out, "n=7")
        assertContains(out, "b=true")
    }

    @Test fun levelOrdinalsAllowOrderedFiltering() {
        assertTrue(LogLevel.DEBUG.ordinal < LogLevel.INFO.ordinal)
        assertTrue(LogLevel.INFO.ordinal < LogLevel.WARN.ordinal)
        assertTrue(LogLevel.WARN.ordinal < LogLevel.ERROR.ordinal)
    }

    @Test fun setMinLevelIsReadable() {
        Loggers.setMinLevel(LogLevel.WARN)
        assertEquals(LogLevel.WARN, Loggers.minLevel())
    }
}
