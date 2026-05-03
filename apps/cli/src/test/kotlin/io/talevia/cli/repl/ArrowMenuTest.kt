package io.talevia.cli.repl

import org.jline.terminal.Terminal
import org.jline.terminal.impl.DumbTerminal
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for [ArrowMenu] —
 * `apps/cli/src/main/kotlin/io/talevia/cli/repl/ArrowMenu.kt`. Cycle 237
 * audit: 0 direct test refs (the only matches were the file itself).
 *
 * Same audit-pattern fallback as cycles 207-236.
 *
 * Coverage scope: the **non-raw-mode** branches — empty-list
 * short-circuit and the dumb-terminal `numberedFallback`. The raw-mode
 * `arrowDriven` path requires an interactive PTY (raw mode + escape-
 * sequence reads + cursor math) and is not pinnable in a unit-test
 * harness without a real PTY; CLI e2e validation covers that lane.
 *
 * Three correctness contracts pinned:
 *
 *  1. **Empty-list short-circuit returns null BEFORE touching the
 *     terminal.** A future refactor that "always renders the title /
 *     prompt then handles empty-list later" would silently scribble
 *     output onto the user's screen for an effectively dead menu.
 *     The test verifies both the `null` return and that NO output
 *     was written to the terminal.
 *
 *  2. **`numberedFallback` produces canonical output for the
 *     dumb-terminal lane.** Drift in the `1-indexed numbered prompt`
 *     format would silently confuse users in `dumb` env (no TTY,
 *     `TERM=dumb`, CI logs, etc.). Pin: title line, blank, items
 *     prefixed `  N. `, blank, prompt `Pick [1-N, empty=cancel]: `.
 *
 *  3. **`numberedFallback` parsing edges**: the input is `1`-indexed,
 *     so `"1"` returns `items[0]` (NOT `items[1]`); `"0"`, blank,
 *     non-numeric, and out-of-range all return null (cancel). Drift
 *     to 0-indexed would silently mis-route picks (the user picks
 *     option `1` and gets the first ALLOW rule when they meant
 *     the second ALLOW rule, etc).
 *
 * Plus a `readDumbLine == null` arm pin: when the caller doesn't
 * supply a line reader, `numberedFallback` returns null without
 * blocking forever. Drift to "block on null reader" would hang the
 * dumb path indefinitely.
 */
class ArrowMenuTest {

    private fun dumbTerminal(out: ByteArrayOutputStream): Terminal {
        // Same DumbTerminal pattern banked across MarkdownRepaintTest /
        // StreamingToolOutputTest / AssetsMissingNoticeTest — JLine's
        // TerminalBuilder.builder() silently promotes to a PTY-backed
        // terminal which drops captured output on the floor. Construct
        // DumbTerminal directly so writes land in `out`.
        return DumbTerminal(
            "test",
            "dumb",
            ByteArrayInputStream(ByteArray(0)),
            out,
            StandardCharsets.UTF_8,
        ).also {
            it.setSize(org.jline.terminal.Size(120, 40))
        }
    }

    private fun trivialRenderer(): ArrowMenu.RowFormatter<String> =
        ArrowMenu.RowFormatter { item, _, selected -> if (selected) "> $item" else "  $item" }

    // ── 1. Empty-list short-circuit ─────────────────────────

    @Test fun emptyItemsReturnsNullWithoutWritingToTerminal() {
        // Marquee zero-side-effect pin: an empty menu MUST exit
        // without rendering ANY output. Drift to "render title then
        // bail" would scribble at the user's cursor for a dead menu.
        val out = ByteArrayOutputStream()
        val terminal = dumbTerminal(out)
        val pick = ArrowMenu.pick(
            terminal = terminal,
            title = "Pick a thing",
            items = emptyList<String>(),
            renderRow = trivialRenderer(),
            readDumbLine = { _ -> error("readDumbLine must NOT be called for empty items") },
        )
        assertNull(pick, "empty items must short-circuit to null")
        assertEquals(
            0,
            out.size(),
            "no bytes must be written to terminal on empty-items short-circuit; got: ${out.toString(StandardCharsets.UTF_8)}",
        )
    }

    // ── 2. numberedFallback canonical output ────────────────

    @Test fun numberedFallbackEmitsTitleNumberedItemsAndPrompt() {
        // Marquee dumb-terminal output pin: title + blank + items +
        // blank + prompt. Drift to a different layout (zero-indexed,
        // "Pick:" format, no blank lines) would silently break the
        // ASR-by-eye UX for `TERM=dumb` users.
        val out = ByteArrayOutputStream()
        val terminal = dumbTerminal(out)
        val items = listOf("alpha", "beta", "gamma")
        var promptSeen: String? = null

        ArrowMenu.pick(
            terminal = terminal,
            title = "Pick a Greek letter",
            items = items,
            renderRow = trivialRenderer(),
            readDumbLine = { prompt ->
                promptSeen = prompt
                null // cancel — return value tested in a separate case
            },
        )

        val rendered = out.toString(StandardCharsets.UTF_8)
        assertTrue(
            "Pick a Greek letter" in rendered,
            "title must appear in dumb-terminal output; got: $rendered",
        )
        // 1-indexed numbered list: `  1. alpha`, `  2. beta`, `  3. gamma`.
        // The renderRow callback's `selected=false` path ("  alpha") is
        // appended after the "  N. " prefix. Pin the number prefix
        // exactly to catch off-by-one drift.
        assertTrue(
            Regex("""\s+1\..*alpha""").containsMatchIn(rendered),
            "expected '  1. ...alpha' line; got: $rendered",
        )
        assertTrue(
            Regex("""\s+2\..*beta""").containsMatchIn(rendered),
            "expected '  2. ...beta' line; got: $rendered",
        )
        assertTrue(
            Regex("""\s+3\..*gamma""").containsMatchIn(rendered),
            "expected '  3. ...gamma' line; got: $rendered",
        )
        // Prompt format is canonical — `Pick [1-N, empty=cancel]: `
        // is what users see; drift here would surprise muscle memory.
        assertEquals(
            "Pick [1-3, empty=cancel]: ",
            promptSeen,
            "dumb-fallback prompt format MUST match canonical 'Pick [1-N, empty=cancel]: '",
        )
    }

    // ── 3. numberedFallback parsing edges ───────────────────

    @Test fun numberedFallbackOneReturnsFirstItemSinceOneIndexed() {
        // Marquee 1-indexed pin: `"1"` MUST return `items[0]`. Drift
        // to 0-indexed would silently mis-route every pick.
        val terminal = dumbTerminal(ByteArrayOutputStream())
        val items = listOf("first", "second", "third")
        val pick = ArrowMenu.pick(
            terminal = terminal,
            title = "t",
            items = items,
            renderRow = trivialRenderer(),
            readDumbLine = { "1" },
        )
        assertEquals("first", pick, "input '1' MUST map to items[0] (1-indexed)")
    }

    @Test fun numberedFallbackThreeReturnsThirdItem() {
        // Sibling pin to lock the indexing direction: bigger N must
        // map to larger index. Drift to "Nth from the end" would
        // pass the 1-indexed test but fail here.
        val terminal = dumbTerminal(ByteArrayOutputStream())
        val items = listOf("first", "second", "third")
        val pick = ArrowMenu.pick(
            terminal = terminal,
            title = "t",
            items = items,
            renderRow = trivialRenderer(),
            readDumbLine = { "3" },
        )
        assertEquals("third", pick)
    }

    @Test fun numberedFallbackZeroReturnsNull() {
        // Pin: `"0"` MUST return null (1-indexed → "0" is below the
        // valid range, behaves like cancel). Drift to "treat 0 as
        // first item" would silently mis-route picks.
        val terminal = dumbTerminal(ByteArrayOutputStream())
        val items = listOf("alpha", "beta")
        val pick = ArrowMenu.pick(
            terminal = terminal,
            title = "t",
            items = items,
            renderRow = trivialRenderer(),
            readDumbLine = { "0" },
        )
        assertNull(pick, "input '0' MUST cancel (below 1-indexed valid range)")
    }

    @Test fun numberedFallbackOutOfRangeReturnsNull() {
        // Pin: `items.getOrNull(n - 1)` returns null for N > size.
        // Drift to "wrap mod size" or "clamp to last" would silently
        // change which item the user picked.
        val terminal = dumbTerminal(ByteArrayOutputStream())
        val items = listOf("alpha", "beta")
        val pick = ArrowMenu.pick(
            terminal = terminal,
            title = "t",
            items = items,
            renderRow = trivialRenderer(),
            readDumbLine = { "999" },
        )
        assertNull(pick, "out-of-range input MUST cancel")
    }

    @Test fun numberedFallbackBlankInputReturnsNull() {
        // Pin: blank trims to "" → toIntOrNull → null → cancel.
        // Drift to "default to first item on blank" would silently
        // pick when the user pressed Enter to escape.
        val terminal = dumbTerminal(ByteArrayOutputStream())
        val items = listOf("alpha", "beta")
        for (blank in listOf("", "  ", "\t")) {
            val pick = ArrowMenu.pick(
                terminal = terminal,
                title = "t",
                items = items,
                renderRow = trivialRenderer(),
                readDumbLine = { blank },
            )
            assertNull(pick, "blank input '${blank.replace("\t", "\\t")}' MUST cancel")
        }
    }

    @Test fun numberedFallbackNonNumericInputReturnsNull() {
        // Pin: `"q"` / `"abc"` → toIntOrNull → null → cancel.
        // Drift to "any non-empty input picks first item" or
        // "throw NumberFormatException" would surface here.
        val terminal = dumbTerminal(ByteArrayOutputStream())
        val items = listOf("alpha", "beta")
        for (junk in listOf("q", "abc", "yes", "$%^")) {
            val pick = ArrowMenu.pick(
                terminal = terminal,
                title = "t",
                items = items,
                renderRow = trivialRenderer(),
                readDumbLine = { junk },
            )
            assertNull(pick, "non-numeric input '$junk' MUST cancel")
        }
    }

    @Test fun numberedFallbackNullReadDumbLineReturnsNullWithoutBlocking() {
        // Pin: when the caller doesn't supply a reader (e.g.
        // running headless, no stdin), `readDumbLine?.invoke(...) ?:
        // return null` short-circuits. Drift to "block on null
        // reader" would hang the dumb path forever.
        val terminal = dumbTerminal(ByteArrayOutputStream())
        val pick = ArrowMenu.pick(
            terminal = terminal,
            title = "t",
            items = listOf("alpha", "beta"),
            renderRow = trivialRenderer(),
            readDumbLine = null,
        )
        assertNull(pick, "null readDumbLine MUST return null (not block)")
    }

    @Test fun numberedFallbackTrimsWhitespaceAroundNumber() {
        // Pin: per `raw.trim().toIntOrNull()`, surrounding whitespace
        // is stripped before parsing. Drift to "no trim" would
        // surprise users who land on " 2 " from a copy-paste.
        val terminal = dumbTerminal(ByteArrayOutputStream())
        val items = listOf("alpha", "beta", "gamma")
        val pick = ArrowMenu.pick(
            terminal = terminal,
            title = "t",
            items = items,
            renderRow = trivialRenderer(),
            readDumbLine = { "  2  " },
        )
        assertEquals("beta", pick, "whitespace around the number MUST be trimmed")
    }

    @Test fun renderRowSelectedFlagAlwaysFalseInDumbFallback() {
        // Pin: dumb fallback enumerates items WITHOUT a "selected"
        // cursor — every renderRow callback must receive
        // `selected=false`. Drift to "always selected=true" or
        // "first row selected=true" would silently render arrow-
        // mode markers in the dumb path.
        val terminal = dumbTerminal(ByteArrayOutputStream())
        val seenSelections = mutableListOf<Boolean>()
        ArrowMenu.pick(
            terminal = terminal,
            title = "t",
            items = listOf("a", "b", "c"),
            renderRow = ArrowMenu.RowFormatter { item, _, selected ->
                seenSelections += selected
                item
            },
            readDumbLine = { null },
        )
        assertEquals(listOf(false, false, false), seenSelections)
    }

    @Test fun renderRowIndexMatchesItemPosition() {
        // Pin: per `for ((i, item) in items.withIndex())`, the
        // renderer's `index` arg matches the item's position in
        // the input list. Drift to "always 0" or "1-based" would
        // surface here — tools that rely on the index for
        // colorisation / shortcut hints would silently mis-align.
        val terminal = dumbTerminal(ByteArrayOutputStream())
        val seenIndices = mutableListOf<Int>()
        ArrowMenu.pick(
            terminal = terminal,
            title = "t",
            items = listOf("a", "b", "c"),
            renderRow = ArrowMenu.RowFormatter { item, index, _ ->
                seenIndices += index
                item
            },
            readDumbLine = { null },
        )
        assertEquals(listOf(0, 1, 2), seenIndices, "index arg MUST be 0-based and monotone")
    }
}
