package io.talevia.cli.repl

import io.talevia.core.tool.builtin.meta.ListToolsTool
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Coverage for [formatToolsTable] — the `/tools` slash command's
 * pure-function renderer over `ListToolsTool.Summary` rows. Tests
 * substitute deterministic Summary lists.
 */
class ToolsTableFormatterTest {

    private fun stripAnsi(s: String): String =
        s.replace(Regex("\\u001B\\[[0-9;?]*[A-Za-z]"), "")

    private fun summary(
        id: String,
        helpText: String = "Tool $id help.",
        permission: String = "project.read",
        avgCostCents: Long? = null,
        costedCalls: Long? = null,
    ) = ListToolsTool.Summary(
        id = id,
        helpText = helpText,
        permission = permission,
        avgCostCents = avgCostCents,
        costedCalls = costedCalls,
    )

    @Test fun emptyListRendersFreshHint() {
        Styles.setEnabled(false)
        val out = stripAnsi(formatToolsTable(emptyList()))
        assertTrue(
            "no LLM tools registered" in out,
            "expected the empty-state hint; got: $out",
        )
        assertTrue(
            "DefaultBuiltinRegistrations" in out,
            "hint should point operators at the registration site",
        )
    }

    @Test fun rendersAllToolsSortedAscending() {
        Styles.setEnabled(false)
        val tools = listOf(
            summary("zebra_tool"),
            summary("apple_tool"),
            summary("mango_tool"),
        )
        val out = stripAnsi(formatToolsTable(tools))
        assertTrue(out.startsWith("tools 3 of 3 tool(s)"), "header missing count; got: ${out.lineSequence().firstOrNull()}")
        // Ascending id sort.
        val idxApple = out.indexOf("apple_tool")
        val idxMango = out.indexOf("mango_tool")
        val idxZebra = out.indexOf("zebra_tool")
        assertTrue(idxApple in 0..idxMango, "apple before mango")
        assertTrue(idxMango in 0..idxZebra, "mango before zebra")
    }

    @Test fun prefixFilterScopesAndAdvertisesScope() {
        Styles.setEnabled(false)
        val tools = listOf(
            summary("generate_image"),
            summary("generate_video"),
            summary("synthesize_speech"),
        )
        val out = stripAnsi(formatToolsTable(tools, prefix = "generate_"))
        // Header shows scope hint.
        assertTrue("filtered by prefix `generate_`" in out, "scope note missing; got: $out")
        // Header reflects post-filter count + total.
        assertTrue("2 of 3 tool(s)" in out, "count mismatch; got: $out")
        // Filtered tool absent.
        assertFalse("synthesize_speech" in out, "non-matching tool should drop; got: $out")
    }

    @Test fun unknownPrefixHintsAtFullSet() {
        // Operator typo (`/tools nope`) shouldn't render a blank screen.
        Styles.setEnabled(false)
        val tools = listOf(summary("generate_image"))
        val out = stripAnsi(formatToolsTable(tools, prefix = "no_match"))
        assertTrue(
            "no LLM tools registered" in out,
            "filtered-empty path should reuse the empty hint; got: $out",
        )
        assertTrue(
            "matching `no_match`" in out,
            "hint should echo the failing prefix; got: $out",
        )
    }

    @Test fun longHelpTextIsTruncated() {
        Styles.setEnabled(false)
        val long = "x".repeat(200)
        val tools = listOf(summary("long_help", helpText = long))
        val out = stripAnsi(formatToolsTable(tools))
        // 80-char display cap + ellipsis.
        assertTrue("x".repeat(80) in out, "first 80 chars should appear")
        assertFalse("x".repeat(81) in out, "truncation cap should hold; got: $out")
        assertTrue("…" in out, "truncation ellipsis missing")
    }

    @Test fun costHintRendersOnlyForPricedTools() {
        Styles.setEnabled(false)
        val tools = listOf(
            summary("generate_image", avgCostCents = 12, costedCalls = 5L),
            summary("read_file"), // no cost
        )
        val out = stripAnsi(formatToolsTable(tools))
        val genLine = out.lineSequence().single { "generate_image" in it }
        assertTrue("12¢/call" in genLine, "priced tool should show cost; got: $genLine")
        val readLine = out.lineSequence().single { "read_file" in it }
        assertFalse("¢/call" in readLine, "non-priced tool should NOT show cost; got: $readLine")
    }

    @Test fun blankPrefixIsTreatedAsNoFilter() {
        // The dispatcher passes `args.trim().takeIf { isNotBlank() }`,
        // but we verify the formatter's own contract: blank / null
        // prefix renders the unfiltered set without a "filtered by"
        // header note.
        Styles.setEnabled(false)
        val tools = listOf(summary("a"), summary("b"))
        val out = stripAnsi(formatToolsTable(tools, prefix = ""))
        assertTrue("2 of 2 tool(s)" in out)
        assertFalse("filtered by" in out, "blank prefix must NOT render scope note; got: $out")
    }
}
