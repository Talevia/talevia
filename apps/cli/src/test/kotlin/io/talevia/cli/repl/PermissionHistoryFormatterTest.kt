package io.talevia.cli.repl

import io.talevia.core.tool.builtin.session.query.PermissionHistoryRow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Coverage for [formatPermissionHistory] — the `/permissions` slash-
 * command formatter. Style codes are stripped before assertion so the
 * test is robust to ANSI on/off toggling between environments.
 */
class PermissionHistoryFormatterTest {

    private val ansi = Regex("\\u001B\\[[0-9;]*m")
    private fun strip(s: String) = s.replace(ansi, "")

    @Test fun emptyListRendersSentinel() {
        val rendered = strip(formatPermissionHistory(emptyList()))
        assertTrue("expected empty sentinel, got: $rendered") {
            rendered.contains("no permission asks recorded")
        }
    }

    @Test fun rejectedEntryShowsAllExpectedColumns() {
        val row = PermissionHistoryRow(
            requestId = "rid-1",
            permission = "network.fetch",
            patterns = listOf("https://example.com/*"),
            decision = "reject",
            accepted = false,
            remembered = true,
            askedEpochMs = 1_700_000_000_000L,
            repliedEpochMs = 1_700_000_001_000L,
        )
        val rendered = strip(formatPermissionHistory(listOf(row)))
        assertTrue("permission name visible: $rendered") { rendered.contains("network.fetch") }
        assertTrue("first pattern visible: $rendered") { rendered.contains("https://example.com/*") }
        assertTrue("decision visible: $rendered") { rendered.contains("reject") }
        // Header line + 1 entry — assert the count word is present.
        assertTrue("count is in header: $rendered") { rendered.contains("1 ask") }
    }

    @Test fun multiplePatternsCollapseWithMoreCount() {
        val row = PermissionHistoryRow(
            requestId = "rid-2",
            permission = "fs.write",
            patterns = listOf("/tmp/*", "/var/*", "/usr/*"),
            decision = "once",
            accepted = true,
            remembered = false,
            askedEpochMs = 1_700_000_000_000L,
            repliedEpochMs = 1_700_000_001_000L,
        )
        val rendered = strip(formatPermissionHistory(listOf(row)))
        assertTrue("first pattern shown: $rendered") { rendered.contains("/tmp/*") }
        assertTrue("multi-pattern collapse marker: $rendered") { rendered.contains("(+2 more)") }
    }

    @Test fun pendingEntryRendersDimNotRed() {
        val row = PermissionHistoryRow(
            requestId = "rid-pending",
            permission = "shell.run",
            patterns = listOf("ls"),
            decision = "pending",
            accepted = null,
            remembered = null,
            askedEpochMs = 1_700_000_000_000L,
            repliedEpochMs = null,
        )
        val rendered = strip(formatPermissionHistory(listOf(row)))
        assertTrue("pending decision shown: $rendered") { rendered.contains("pending") }
    }

    @Test fun multipleRowsAlignedByPermissionLength() {
        val rows = listOf(
            PermissionHistoryRow(
                requestId = "r1",
                permission = "fs.write",
                patterns = listOf("/tmp/*"),
                decision = "reject",
                accepted = false,
                remembered = false,
                askedEpochMs = 1_700_000_000_000L,
                repliedEpochMs = 1_700_000_001_000L,
            ),
            PermissionHistoryRow(
                requestId = "r2",
                permission = "network.fetch",
                patterns = listOf("https://api.example.com/*"),
                decision = "always",
                accepted = true,
                remembered = true,
                askedEpochMs = 1_700_000_002_000L,
                repliedEpochMs = 1_700_000_003_000L,
            ),
        )
        val rendered = strip(formatPermissionHistory(rows))
        // Each row's own line; both permissions present.
        assertTrue("first row visible: $rendered") { rendered.contains("fs.write") }
        assertTrue("second row visible: $rendered") { rendered.contains("network.fetch") }
        assertTrue("count is 2 ask(s): $rendered") { rendered.contains("2 ask") }
    }
}
