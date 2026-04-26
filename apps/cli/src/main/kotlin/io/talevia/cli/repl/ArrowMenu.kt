package io.talevia.cli.repl

import org.jline.terminal.Terminal

/**
 * Inline arrow-key navigable single-choice menu, claude-code-style. Renders
 * the items below the current cursor position, lets the user move with ↑/↓,
 * picks with Enter, cancels with ESC / Ctrl-C / `q`.
 *
 * When the terminal is dumb (no TTY / no ANSI), falls back to a numbered
 * prompt that reads a line via the supplied [readDumbLine] hook (which the
 * REPL wires to the JLine reader so history + Ctrl-D still work).
 *
 * Pure UI — no business logic. Returns the picked item or null on cancel.
 */
internal object ArrowMenu {

    /** Render-row callback. `selected` is true for the row under the cursor. */
    fun interface RowFormatter<T> {
        fun render(item: T, index: Int, selected: Boolean): String
    }

    fun <T> pick(
        terminal: Terminal,
        title: String,
        items: List<T>,
        initialIndex: Int = 0,
        renderRow: RowFormatter<T>,
        readDumbLine: ((prompt: String) -> String?)? = null,
    ): T? {
        if (items.isEmpty()) return null
        val isDumb = terminal.type.isNullOrEmpty() || terminal.type == "dumb"
        return if (isDumb) {
            numberedFallback(terminal, title, items, renderRow, readDumbLine)
        } else {
            arrowDriven(terminal, title, items, initialIndex.coerceIn(0, items.size - 1), renderRow)
        }
    }

    private fun <T> arrowDriven(
        terminal: Terminal,
        title: String,
        items: List<T>,
        startIndex: Int,
        renderRow: RowFormatter<T>,
    ): T? {
        val writer = terminal.writer()
        var idx = startIndex
        val rowsRendered = items.size + 2 // title + blank + N items

        val originalAttrs = terminal.enterRawMode()
        writer.print(CURSOR_HIDE)
        writer.flush()
        try {
            // Initial render — print at current cursor position, no clear-up needed yet.
            drawMenu(writer, title, items, idx, renderRow, firstDraw = true)

            val reader = terminal.reader()
            loop@ while (true) {
                val key = readKey(reader)
                when (key) {
                    KEY_ENTER, KEY_RETURN -> return items[idx]
                    KEY_CANCEL, KEY_CTRL_C, KEY_Q -> return null
                    KEY_UP -> idx = (idx - 1 + items.size) % items.size
                    KEY_DOWN -> idx = (idx + 1) % items.size
                    KEY_HOME -> idx = 0
                    KEY_END -> idx = items.size - 1
                    KEY_EOF -> return null
                    else -> continue@loop // ignore unknown keys, no redraw
                }
                // Move cursor up over the previous render and clear from there to end
                // of screen. CSI <N> F = move up N lines AND go to column 0.
                // CSI J = clear from cursor to end of screen.
                writer.print("\u001B[${rowsRendered}F\u001B[J")
                drawMenu(writer, title, items, idx, renderRow, firstDraw = false)
            }
        } finally {
            terminal.attributes = originalAttrs
            writer.print(CURSOR_SHOW)
            writer.flush()
        }
    }

    private fun <T> drawMenu(
        writer: java.io.PrintWriter,
        title: String,
        items: List<T>,
        idx: Int,
        renderRow: RowFormatter<T>,
        firstDraw: Boolean,
    ) {
        if (!firstDraw) writer.print("\r")
        writer.print(title)
        writer.print(NEWLINE)
        writer.print(NEWLINE)
        for ((i, item) in items.withIndex()) {
            writer.print(renderRow.render(item, i, i == idx))
            writer.print(NEWLINE)
        }
        writer.flush()
    }

    private fun <T> numberedFallback(
        terminal: Terminal,
        title: String,
        items: List<T>,
        renderRow: RowFormatter<T>,
        readDumbLine: ((prompt: String) -> String?)?,
    ): T? {
        val writer = terminal.writer()
        writer.println(title)
        writer.println()
        for ((i, item) in items.withIndex()) {
            writer.println("  ${i + 1}. ${renderRow.render(item, i, false)}")
        }
        writer.println()
        writer.flush()
        val raw = readDumbLine?.invoke("Pick [1-${items.size}, empty=cancel]: ")
            ?: return null
        val n = raw.trim().toIntOrNull() ?: return null
        return items.getOrNull(n - 1)
    }

    /**
     * Read one logical key from the terminal. Handles the ESC[A/B/C/D arrow
     * sequences plus a few singletons. Returns one of the `KEY_*` constants.
     */
    private fun readKey(reader: org.jline.utils.NonBlockingReader): Int {
        val first = reader.read(IDLE_READ_MS)
        if (first < 0) return KEY_EOF
        if (first != ESC) return mapSingleByte(first)
        // ESC seen — could be a CSI prefix or a bare ESC (cancel).
        val second = reader.read(ESC_FOLLOWUP_MS)
        if (second < 0) return KEY_CANCEL // bare ESC
        if (second != '['.code && second != 'O'.code) return KEY_CANCEL
        val third = reader.read(ESC_FOLLOWUP_MS)
        return when (third) {
            'A'.code -> KEY_UP
            'B'.code -> KEY_DOWN
            'H'.code -> KEY_HOME
            'F'.code -> KEY_END
            else -> KEY_CANCEL
        }
    }

    private fun mapSingleByte(b: Int): Int = when (b) {
        '\r'.code -> KEY_RETURN
        '\n'.code -> KEY_ENTER
        0x03 -> KEY_CTRL_C
        'q'.code, 'Q'.code -> KEY_Q
        else -> b
    }

    // -- key codes (negative numbers so they don't collide with raw bytes) --
    private const val ESC = 0x1B
    private const val KEY_ENTER = -1001
    private const val KEY_RETURN = -1002
    private const val KEY_UP = -1003
    private const val KEY_DOWN = -1004
    private const val KEY_HOME = -1005
    private const val KEY_END = -1006
    private const val KEY_CANCEL = -1007
    private const val KEY_CTRL_C = -1008
    private const val KEY_Q = -1009
    private const val KEY_EOF = -1010

    private const val IDLE_READ_MS = -1L // block forever
    private const val ESC_FOLLOWUP_MS = 50L

    private const val CURSOR_HIDE = "\u001B[?25l"
    private const val CURSOR_SHOW = "\u001B[?25h"
    // Use \r\n explicitly — the terminal is in raw mode, so a bare \n won't
    // return cursor to column 0.
    private const val NEWLINE = "\r\n"
}
