package io.talevia.cli.repl

import io.talevia.core.tool.builtin.session.query.CompactionRow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Coverage for [formatCompactionSummary] — the `/summary` slash
 * command's pure-function renderer over `session_query(select=compactions)`
 * rows. Tests the eyeball-friendly shape, the empty-session hint, and
 * that long summary bodies survive intact (compactions row carries the
 * full summary text — a regression that swapped to the truncated parts
 * preview would silently drop content).
 */
class CompactionSummaryFormatterTest {

    private fun stripAnsi(s: String): String =
        s.replace(Regex("\\u001B\\[[0-9;?]*[A-Za-z]"), "")

    @Test fun emptyRowsRendersFreshSessionHint() {
        Styles.setEnabled(false)
        val out = stripAnsi(formatCompactionSummary(emptyList()))
        assertTrue("no compactions on this session yet" in out, "got: $out")
        assertTrue("/compact" in out, "hint should point at /compact for manual trigger")
    }

    @Test fun nonEmptyRowRendersHeaderPlusSummary() {
        Styles.setEnabled(false)
        val row = CompactionRow(
            partId = "p-1",
            messageId = "m-current",
            fromMessageId = "m-12345678abcdef",
            toMessageId = "m-87654321abcdef",
            summaryText = """
            Goal: cut a 30s ad.
            Discoveries: imported 4 clips.
            """.trimIndent(),
            // Pin to a deterministic clock-readable epoch — Tue Nov 14 2023 22:13:20 UTC
            compactedAtEpochMs = 1_700_000_000_000L,
        )
        val out = stripAnsi(formatCompactionSummary(listOf(row)))
        assertTrue(out.startsWith("compaction "), "header missing 'compaction' label; got: ${out.lineSequence().firstOrNull()}")
        // Header carries the truncated message id tails (last 8 chars).
        assertTrue("12345678abcdef".takeLast(8) in out, "fromMessageId tail missing")
        assertTrue("87654321abcdef".takeLast(8) in out, "toMessageId tail missing")
        // Body renders verbatim — full summary text, not a 80-char preview.
        assertTrue("Goal: cut a 30s ad." in out)
        assertTrue("Discoveries: imported 4 clips." in out)
    }

    @Test fun longSummariesAreNotTruncated() {
        // The whole point of using SELECT_COMPACTIONS over SELECT_PARTS for
        // /summary is that the row's summaryText is the full body, not the
        // 80-char preview. Pin that contract: a 4 kB summary must arrive
        // intact through the formatter.
        Styles.setEnabled(false)
        val long = "Goal: " + "x".repeat(4_000)
        val row = CompactionRow(
            partId = "p",
            messageId = "m",
            fromMessageId = "from",
            toMessageId = "to",
            summaryText = long,
            compactedAtEpochMs = 0L,
        )
        val out = stripAnsi(formatCompactionSummary(listOf(row)))
        assertTrue(long in out, "long summary truncated unexpectedly (length ${long.length}, output length ${out.length})")
    }
}
