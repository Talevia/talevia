package io.talevia.cli.repl

import io.talevia.core.tool.builtin.session.query.BusTraceRow
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Direct tests for [formatBusTrace] — the `/trace` slash command's
 * eyeball-friendly bus event renderer. Cycle 87 audit found this
 * formatter had no direct test (zero references in any CLI test
 * file).
 *
 * The formatter is a pure function (input rows + filter → string),
 * so it's safely unit-testable without the full CLI e2e harness
 * the CLAUDE.md note reserves for Renderer / Repl / Dispatcher
 * changes. Toggling Styles off lets us assert on plain-text
 * substrings without ANSI codes leaking into expected values.
 */
class BusTraceFormatterTest {

    @BeforeTest fun disableAnsi() {
        Styles.setEnabled(false)
    }

    @AfterTest fun restoreAnsi() {
        Styles.setEnabled(true)
    }

    private fun row(kind: String, summary: String = "summary", epochMs: Long = 1_700_000_000_000L) =
        BusTraceRow(sessionId = "s", kind = kind, epochMs = epochMs, summary = summary)

    @Test fun emptyRowsReturnsNoEventsMessage() {
        val out = formatBusTrace(emptyList(), kindFilter = null)
        assertTrue("no bus events recorded" in out, "empty result must show the no-events message; got: $out")
        // No filter tail when filter is null.
        assertFalse("(kind=" in out)
    }

    @Test fun emptyRowsWithKindFilterIncludesFilterTail() {
        val out = formatBusTrace(emptyList(), kindFilter = "PartDelta")
        assertTrue("no bus events" in out)
        assertTrue("(kind=PartDelta)" in out, "filter must surface in empty-result message; got: $out")
    }

    @Test fun nonEmptyRowsRenderCountInHeader() {
        val rows = listOf(row("SessionCreated"), row("SessionUpdated"))
        val out = formatBusTrace(rows, kindFilter = null)
        assertTrue("trace 2 event(s)" in out, "header must show count; got: $out")
    }

    @Test fun nonEmptyRowsWithKindFilterShowsFilterInHeader() {
        val rows = listOf(row("PartDelta"))
        val out = formatBusTrace(rows, kindFilter = "PartDelta")
        assertTrue("(kind=PartDelta)" in out, "header must surface filter; got: $out")
    }

    @Test fun timestampFormatIsHHmmss() {
        // 1_700_000_000_000 ms = 2023-11-14 22:13:20 UTC.
        // Local-time conversion happens via TimeZone.currentSystemDefault();
        // exact HH varies per runner, so assert format pattern (^\d\d:\d\d:\d\d).
        val rows = listOf(row("SessionCreated", epochMs = 1_700_000_000_000L))
        val out = formatBusTrace(rows, kindFilter = null)
        val timeRegex = Regex("""\b\d{2}:\d{2}:\d{2}\b""")
        assertTrue(
            timeRegex.containsMatchIn(out),
            "output must contain HH:mm:ss timestamp; got: $out",
        )
    }

    @Test fun summaryLongerThanLimitIsTruncated() {
        // SUMMARY_DISPLAY_CHARS = 100. Build a summary that's clearly
        // longer; assert the overlap doesn't bleed past 100 chars per row.
        val longSummary = "x".repeat(150)
        val rows = listOf(row("X", summary = longSummary))
        val out = formatBusTrace(rows, kindFilter = null)
        // The summary line should contain some 'x' chars but not all 150.
        val xRun = Regex("x{50,}").find(out)?.value
        assertTrue(xRun != null, "long summary should appear in output; got: $out")
        // Pin the truncation point: at most 100 'x' chars in any one line.
        assertTrue(
            xRun!!.length <= 100,
            "summary truncated to ≤ 100 chars; observed ${xRun.length}",
        )
    }

    @Test fun newlinesInSummaryAreCollapsedToSpaces() {
        // The summary is joined into a single line — multi-line summaries
        // would break the table format. Pin: replace("\n", " ").
        val multiLine = "first\nsecond\nthird"
        val rows = listOf(row("X", summary = multiLine))
        val out = formatBusTrace(rows, kindFilter = null)
        // Find the line containing "first" — it should also contain
        // "second" and "third" (joined with spaces, not split by newline).
        val firstLine = out.lines().first { "first" in it }
        assertTrue(
            "second" in firstLine && "third" in firstLine,
            "multi-line summary collapses to one trace row; got line: $firstLine",
        )
    }

    @Test fun rowsRenderInProvidedOrder() {
        // Per the kdoc: "oldest first (matches the recorder's natural
        // insertion order)". Pin so a refactor accidentally sorting
        // doesn't silently invert the table.
        val rows = listOf(
            row("First", summary = "alpha"),
            row("Second", summary = "beta"),
            row("Third", summary = "gamma"),
        )
        val out = formatBusTrace(rows, kindFilter = null)
        // alpha appears before beta which appears before gamma.
        val ai = out.indexOf("alpha")
        val bi = out.indexOf("beta")
        val gi = out.indexOf("gamma")
        assertTrue(ai >= 0 && bi >= 0 && gi >= 0, "all three summaries appear")
        assertTrue(ai < bi, "alpha (first) must appear before beta")
        assertTrue(bi < gi, "beta must appear before gamma")
    }

    @Test fun kindColumnIsAtLeastTenCharsWideEvenForShortKinds() {
        // The formatter pads the kind column to max(longest, 10) so
        // the table stays aligned. Pin: a single 1-char kind should
        // still produce a 10-char column.
        val rows = listOf(row("X", summary = "s"))
        val out = formatBusTrace(rows, kindFilter = null)
        // Find the data line (skip the header). Its kind column starts
        // after the time + 2 spaces. Padding means there's whitespace
        // between the kind and the summary.
        val dataLine = out.lines().first { ":" in it } // first line with HH:mm:ss
        // Look for 'X' followed by ≥9 spaces and then 's'.
        assertTrue(
            Regex("""X\s{9,}s\b""").containsMatchIn(dataLine),
            "kind column padded to ≥10 chars; got line: '$dataLine'",
        )
    }

    @Test fun headerAndBodyAreLineSeparated() {
        // Rough structural pin: at least 2 lines in non-empty output
        // (header + ≥1 body row), and trailing whitespace stripped via
        // trimEnd.
        val rows = listOf(row("SessionCreated"))
        val out = formatBusTrace(rows, kindFilter = null)
        assertEquals(out, out.trimEnd(), "no trailing whitespace")
        assertTrue(out.lines().size >= 2, "header + at least one body line")
    }
}
