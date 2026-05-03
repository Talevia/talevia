package io.talevia.core.tool.builtin.session.action

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Direct tests for [parseExportFormat] —
 * `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/session/action/SessionExportHandler.kt:114`.
 * Cycle 256 audit: 0 test refs.
 *
 * Same audit-pattern fallback as cycles 207-255.
 *
 * `parseExportFormat(raw: String?)` is the lenient parser the
 * `session_action(action="export")` dispatcher uses to map the
 * agent-facing `format` string onto the internal [ExportFormat]
 * enum. The function's kdoc explicitly says "Unknown defaults to
 * JSON so a typo never silently strips the portable wire shape" —
 * pinning that fallback discipline is what enforces the promise.
 *
 * Drift signals:
 *   - Drift to "throw on unknown" would crash the dispatch on a
 *     typo (e.g. `format="josn"`) instead of falling through to
 *     the safe portable shape.
 *   - Drift to "case-sensitive" would reject `"JSON"` /
 *     `"Markdown"` that LLMs commonly emit.
 *   - Drift to "no trim" would let leading/trailing whitespace
 *     reroute every input to the unknown→JSON fallback.
 *   - Drift in the `"md"` alias would silently break the
 *     agent's natural shorthand for markdown.
 *
 * Pins three correctness contracts:
 *
 *  1. **Default-to-JSON safety**: null / blank / unknown all
 *     route to JSON. Marquee "typo never silently strips
 *     portable" pin.
 *
 *  2. **`markdown` + `md` alias** both map to MARKDOWN. Drift
 *     in either silently breaks the agent's natural shorthand
 *     for the human-readable transcript path.
 *
 *  3. **Case-insensitive + whitespace-trimmed**: `"JSON"` /
 *     `"Markdown"` / `" md "` all parse correctly. Drift to
 *     strict matching would reject inputs LLMs commonly emit.
 */
class ParseExportFormatTest {

    // ── 1. Default-to-JSON safety ───────────────────────────

    @Test fun nullInputReturnsJson() {
        // Marquee null-default pin: when the agent omits format,
        // the dispatcher gets JSON (the portable wire shape).
        assertEquals(ExportFormat.JSON, parseExportFormat(null))
    }

    @Test fun emptyInputReturnsJson() {
        // Pin: empty string after trim/lowercase falls into the
        // `""` arm explicitly. Drift to "throw on empty" would
        // crash dispatch.
        assertEquals(ExportFormat.JSON, parseExportFormat(""))
    }

    @Test fun blankInputReturnsJson() {
        // Pin: whitespace-only input is trimmed to empty → JSON.
        for (blank in listOf(" ", "  ", "\t", "\n", "\t  \n")) {
            assertEquals(
                ExportFormat.JSON,
                parseExportFormat(blank),
                "blank input '$blank' MUST default to JSON",
            )
        }
    }

    @Test fun unknownFormatReturnsJson() {
        // Marquee unknown-default pin: typos / invalid formats
        // route to JSON. The kdoc explicitly says "Unknown
        // defaults to JSON so a typo never silently strips the
        // portable wire shape." Drift to throw / return null
        // would surface here.
        for (junk in listOf("josn", "yaml", "xml", "csv", "html", "txt", "binary", "exe")) {
            assertEquals(
                ExportFormat.JSON,
                parseExportFormat(junk),
                "unknown format '$junk' MUST default to JSON",
            )
        }
    }

    @Test fun explicitJsonReturnsJson() {
        // Pin: explicit "json" returns JSON.
        assertEquals(ExportFormat.JSON, parseExportFormat("json"))
    }

    // ── 2. markdown + md alias ──────────────────────────────

    @Test fun markdownReturnsMarkdown() {
        assertEquals(ExportFormat.MARKDOWN, parseExportFormat("markdown"))
    }

    @Test fun mdAliasReturnsMarkdown() {
        // Marquee alias pin: `"md"` is the agent's natural
        // shorthand. Drift to drop the alias would silently
        // route `"md"` to the unknown→JSON fallback (silently
        // wrong format).
        assertEquals(ExportFormat.MARKDOWN, parseExportFormat("md"))
    }

    // ── 3. Case-insensitive + whitespace-trim ───────────────

    @Test fun jsonIsCaseInsensitive() {
        // Pin: lowercase via `.lowercase()` before matching.
        // LLMs commonly emit `"JSON"` / `"Json"` based on
        // training; drift to case-sensitive would reject these.
        for (variant in listOf("JSON", "Json", "JSon", "jSON")) {
            assertEquals(
                ExportFormat.JSON,
                parseExportFormat(variant),
                "case variant '$variant' MUST parse to JSON",
            )
        }
    }

    @Test fun markdownIsCaseInsensitive() {
        for (variant in listOf("MARKDOWN", "Markdown", "MarkDown", "markDOWN")) {
            assertEquals(
                ExportFormat.MARKDOWN,
                parseExportFormat(variant),
                "case variant '$variant' MUST parse to MARKDOWN",
            )
        }
    }

    @Test fun mdAliasIsCaseInsensitive() {
        for (variant in listOf("MD", "Md", "mD")) {
            assertEquals(
                ExportFormat.MARKDOWN,
                parseExportFormat(variant),
                "MD case variant '$variant' MUST parse to MARKDOWN",
            )
        }
    }

    @Test fun whitespaceAroundFormatIsTrimmed() {
        // Marquee trim pin: leading/trailing whitespace must NOT
        // route to the unknown→JSON fallback when the trimmed
        // form is a known value. Drift to "no trim" would
        // silently mis-route ` markdown ` (with stray spaces)
        // to JSON.
        for (input in listOf(
            " json",
            "json ",
            "  json  ",
            "\tjson\n",
            " markdown ",
            "\tmd\n",
        )) {
            val expected = if (input.trim().lowercase() in setOf("markdown", "md")) {
                ExportFormat.MARKDOWN
            } else {
                ExportFormat.JSON
            }
            assertEquals(
                expected,
                parseExportFormat(input),
                "whitespace-padded '$input' MUST trim then parse",
            )
        }
    }

    @Test fun innerWhitespaceIsNotStrippedAndDefaultsToJson() {
        // Pin: only LEADING/TRAILING whitespace is trimmed via
        // `.trim()`. Internal whitespace remains, making the
        // input unknown → JSON. Drift to "strip all whitespace"
        // would let `"mark down"` parse to MARKDOWN.
        assertEquals(
            ExportFormat.JSON,
            parseExportFormat("mark down"),
            "internal whitespace MUST NOT be stripped (drift to 'strip all' would mis-route 'mark down')",
        )
        assertEquals(
            ExportFormat.JSON,
            parseExportFormat("j son"),
        )
    }
}
