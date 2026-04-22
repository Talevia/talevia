package io.talevia.cli.repl

import com.github.ajalt.mordant.markdown.Markdown
import com.github.ajalt.mordant.rendering.AnsiLevel
import io.talevia.core.PartId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jline.terminal.Terminal
import com.github.ajalt.mordant.terminal.Terminal as MordantTerminal

/**
 * Mutex-guarded writer for the REPL. Every terminal write locks briefly so
 * streaming assistant deltas and tool-state cards don't interleave mid-line.
 *
 * Tracks the running length of every assistant text part it has emitted so:
 *  - [streamAssistantDelta] is incremental (only flushes new tail bytes)
 *  - [ensureAssistantText] is idempotent (used as a fallback when a non-streaming
 *    provider upserts a finalised [io.talevia.core.session.Part.Text] without
 *    firing deltas first)
 *  - [finalizeAssistantText] (Phase 2) repaints the just-streamed part with
 *    Mordant's markdown widget so the final transcript has rendered bold,
 *    bullets and fenced code blocks instead of raw `**foo**` etc.
 *
 * Also owns the "bottom-of-buffer" state for in-place rewrites so that:
 *  - a tool's `⟳ running` line gets upgraded to `✓ completed` on the same row
 *  - consecutive [Part.RenderProgress] ticks on the same `jobId` overwrite one
 *    progress-bar line instead of spamming a new line per tick
 * Both behaviors require ANSI cursor control (`[ansiEnabled] = true`), which
 * is only safe on a real TTY. Non-interactive runs (piped stdout, dumb
 * terminals, `markdownEnabled = false` by default in tests) fall back to the
 * "one line per update" baseline so captured output stays grep-friendly.
 */
class Renderer(
    private val terminal: Terminal,
    private val markdownEnabled: Boolean = true,
    private val ansiEnabled: Boolean = markdownEnabled,
) {
    private val mutex = Mutex()
    private val emittedLen: MutableMap<PartId, Int> = mutableMapOf()
    private val streamedText: MutableMap<PartId, StringBuilder> = mutableMapOf()
    private val finalised: MutableSet<PartId> = mutableSetOf()
    // A part stays "repaintable" until anything else writes to the terminal —
    // a tool card, an error, a slash-command line. Once that happens the
    // streamed bytes for the part are no longer the bottom of the buffer and
    // we can't safely cursor-up past the interleaved content.
    private val repaintable: MutableMap<PartId, Boolean> = mutableMapOf()
    private val announcedTools: MutableSet<PartId> = mutableSetOf()
    private val finalisedTools: MutableSet<PartId> = mutableSetOf()

    /** True iff any assistant text has been streamed in the current turn. */
    private var assistantOpen: Boolean = false

    /**
     * PartId whose `⟳ running` announcement is the bottom-most row in the
     * buffer and can still be repainted in place by a completion/failure line.
     * Cleared by any other non-tool write.
     */
    private var bottomToolPartId: PartId? = null

    /**
     * `(jobId, message)` for the last [Part.RenderProgress] row printed at the
     * bottom of the buffer. A follow-up tick with the same `jobId` rewrites
     * that row; anything else invalidates the slot.
     */
    private var bottomRenderJobId: String? = null

    /** jobIds we've already printed a final "completed"/"failed" line for. */
    private val renderProgressTerminal: MutableSet<String> = mutableSetOf()

    private val mordant: MordantTerminal by lazy {
        MordantTerminal(
            ansiLevel = if (markdownEnabled) AnsiLevel.TRUECOLOR else AnsiLevel.NONE,
            width = terminal.width.coerceIn(40, 200),
        )
    }

    suspend fun streamAssistantDelta(partId: PartId, delta: String) = mutex.withLock {
        if (delta.isEmpty() || finalised.contains(partId)) return@withLock
        invalidateBottomLocked()
        assistantOpen = true
        emittedLen[partId] = (emittedLen[partId] ?: 0) + delta.length
        streamedText.getOrPut(partId) { StringBuilder() }.append(delta)
        repaintable.putIfAbsent(partId, true)
        terminal.writer().print(delta)
        terminal.writer().flush()
    }

    /**
     * Fallback path for providers that don't emit per-token deltas. When the
     * full [text] for [partId] is available, emit whatever hasn't been printed
     * yet. Idempotent — calling with the same text twice is a no-op.
     */
    suspend fun ensureAssistantText(partId: PartId, text: String) = mutex.withLock {
        if (finalised.contains(partId)) return@withLock
        val already = emittedLen[partId] ?: 0
        if (text.length <= already) return@withLock
        val tail = text.substring(already)
        invalidateBottomLocked()
        assistantOpen = true
        emittedLen[partId] = text.length
        streamedText.getOrPut(partId) { StringBuilder() }.append(tail)
        repaintable.putIfAbsent(partId, true)
        terminal.writer().print(tail)
        terminal.writer().flush()
    }

    /**
     * Phase 2: the part is final. If we can safely repaint (TTY enabled, no
     * intervening writes) and there's something that markdown rendering would
     * meaningfully improve, replace the streamed raw text in-place with a
     * Mordant-rendered version. Otherwise just append any tail still missing.
     */
    suspend fun finalizeAssistantText(partId: PartId, text: String) = mutex.withLock {
        if (finalised.contains(partId)) return@withLock
        // Make sure any tail not yet streamed reaches the terminal first. Mark
        // the part repaintable here too so the repaint check below also covers
        // the non-streaming providers (no PartDelta, only PartUpdated).
        val already = emittedLen[partId] ?: 0
        if (text.length > already) {
            val tail = text.substring(already)
            invalidateBottomLocked()
            assistantOpen = true
            emittedLen[partId] = text.length
            streamedText.getOrPut(partId) { StringBuilder() }.append(tail)
            repaintable.putIfAbsent(partId, true)
            terminal.writer().print(tail)
        }

        val rawStreamed = streamedText[partId]?.toString().orEmpty()
        val canRepaint = markdownEnabled &&
            assistantOpen &&
            (repaintable[partId] ?: false) &&
            rawStreamed.isNotEmpty() &&
            looksLikeMarkdown(rawStreamed)

        if (canRepaint) {
            val rows = visualRows(rawStreamed, terminal.width.coerceAtLeast(1))
            val maxRepaintRows = (terminal.height - 2).coerceAtLeast(8)
            if (rows in 1..maxRepaintRows) {
                // Move cursor to the start of the streamed region, clear from
                // there to end of screen, then re-render via Mordant Markdown.
                val clear = "\r" + "\u001B[${rows - 1}A" + "\u001B[0J"
                val rendered = renderMarkdown(text)
                terminal.writer().print(clear)
                terminal.writer().print(rendered)
                if (!rendered.endsWith('\n')) terminal.writer().println()
                assistantOpen = false
            }
        }
        terminal.writer().flush()
        finalised.add(partId)
    }

    /**
     * Close out a turn: emit a trailing newline if any assistant text streamed
     * this turn, and reset per-turn tracking. Safe to call on turns that
     * produced no text (the initial prompt echo already ended with a newline
     * from readLine).
     */
    suspend fun endTurn() = mutex.withLock {
        if (assistantOpen) {
            terminal.writer().println()
        }
        terminal.writer().println()
        terminal.writer().flush()
        assistantOpen = false
        emittedLen.clear()
        streamedText.clear()
        finalised.clear()
        repaintable.clear()
        announcedTools.clear()
        finalisedTools.clear()
        bottomToolPartId = null
        bottomRenderJobId = null
        renderProgressTerminal.clear()
    }

    suspend fun println(text: String) = mutex.withLock {
        breakAssistantLineLocked()
        markAllPartsUnrepaintableLocked()
        invalidateBottomLocked()
        terminal.writer().println(text)
        terminal.writer().flush()
    }

    suspend fun error(text: String) = mutex.withLock {
        // stderr bypasses the terminal writer on purpose so it stays visibly
        // separate from assistant text even when stdout is redirected.
        breakAssistantLineLocked()
        markAllPartsUnrepaintableLocked()
        invalidateBottomLocked()
        System.err.println(Styles.error(text))
    }

    /**
     * Announce a tool has started running. Idempotent per [partId] — multiple
     * `Running` transitions (e.g. retries) only print once.
     */
    suspend fun toolRunning(partId: PartId, toolId: String) = mutex.withLock {
        if (!announcedTools.add(partId)) return@withLock
        breakAssistantLineLocked()
        markAllPartsUnrepaintableLocked()
        invalidateBottomLocked()
        terminal.writer().println("  ${Styles.running("⟳")} ${Styles.toolId(toolId)}")
        terminal.writer().flush()
        bottomToolPartId = partId
    }

    suspend fun toolCompleted(partId: PartId, toolId: String, summary: String) = mutex.withLock {
        if (!finalisedTools.add(partId)) return@withLock
        val oneLine = summary.lineSequence().firstOrNull()?.take(120).orEmpty()
        val suffix = if (oneLine.isBlank()) "" else " ${Styles.meta("· $oneLine")}"
        val line = "  ${Styles.ok("✓")} ${Styles.toolId(toolId)}$suffix"
        if (ansiEnabled && bottomToolPartId == partId) {
            terminal.writer().print(CURSOR_UP_1 + CARRIAGE_RETURN + CLEAR_LINE)
            terminal.writer().println(line)
        } else {
            breakAssistantLineLocked()
            markAllPartsUnrepaintableLocked()
            invalidateBottomLocked()
            terminal.writer().println(line)
        }
        terminal.writer().flush()
        bottomToolPartId = null
    }

    suspend fun toolFailed(partId: PartId, toolId: String, message: String) = mutex.withLock {
        if (!finalisedTools.add(partId)) return@withLock
        val line = "  ${Styles.fail("✗")} ${Styles.toolId(toolId)} ${Styles.meta("· $message")}"
        if (ansiEnabled && bottomToolPartId == partId) {
            terminal.writer().print(CURSOR_UP_1 + CARRIAGE_RETURN + CLEAR_LINE)
            terminal.writer().println(line)
        } else {
            breakAssistantLineLocked()
            markAllPartsUnrepaintableLocked()
            invalidateBottomLocked()
            terminal.writer().println(line)
        }
        terminal.writer().flush()
        bottomToolPartId = null
    }

    /**
     * Render one tick of a `Part.RenderProgress` event. Consecutive ticks that
     * carry the same [jobId] repaint the bottom row in place (ANSI cursor-up +
     * clear-line) so a long export doesn't spam N lines of progress text; a
     * tick with a new `jobId`, or any other terminal write, fresh-lines.
     *
     * Ratio is clamped to `[0, 1]`. The emitted row has the shape:
     * `⟳ <jobId-short>  [=====>      ]  67%  · <message>  · preview=…/foo.jpg`.
     * Once a `jobId` emits a terminal tick (ratio = 1 or a "failed" message)
     * we swap the spinner for `✓`/`✗` and lock the slot — any future tick on
     * that same jobId fresh-lines again.
     */
    suspend fun renderProgress(
        jobId: String,
        ratio: Float,
        message: String?,
        thumbnailPath: String?,
    ) = mutex.withLock {
        val clampedRatio = ratio.coerceIn(0f, 1f)
        val isFailure = message != null && message.startsWith("failed")
        val isCompleted = !isFailure && (clampedRatio >= 0.999f || message == "completed")
        val alreadyTerminal = renderProgressTerminal.contains(jobId)

        val line = formatRenderProgressLine(
            jobId = jobId,
            ratio = clampedRatio,
            message = message,
            thumbnailPath = thumbnailPath,
            isCompleted = isCompleted,
            isFailure = isFailure,
            width = terminal.width.coerceAtLeast(40),
        )

        val canRepaint = ansiEnabled &&
            bottomRenderJobId == jobId &&
            !alreadyTerminal
        if (canRepaint) {
            terminal.writer().print(CURSOR_UP_1 + CARRIAGE_RETURN + CLEAR_LINE)
            terminal.writer().println(line)
        } else {
            breakAssistantLineLocked()
            markAllPartsUnrepaintableLocked()
            invalidateBottomLocked()
            terminal.writer().println(line)
        }
        terminal.writer().flush()

        if (isCompleted || isFailure) {
            renderProgressTerminal.add(jobId)
            bottomRenderJobId = null
        } else {
            bottomRenderJobId = jobId
        }
    }

    /**
     * Print a one-line notice that the Agent is about to sleep + retry a
     * transient provider error. Breaks any open assistant line first so the
     * notice sits on its own row, then leaves the line buffer unrepaintable
     * (the assistant text from the next attempt will stream as a fresh part).
     */
    suspend fun retryNotice(attempt: Int, waitMs: Long, reason: String) = mutex.withLock {
        breakAssistantLineLocked()
        markAllPartsUnrepaintableLocked()
        invalidateBottomLocked()
        val seconds = (waitMs / 1000.0)
        val formatted = if (seconds >= 1.0) "${seconds}s" else "${waitMs}ms"
        terminal.writer().println(
            "  ${Styles.running("⟳")} ${Styles.meta("retry #$attempt in $formatted — $reason")}",
        )
        terminal.writer().flush()
    }

    /**
     * If we're mid-assistant-line (no trailing newline emitted yet), close it
     * so the next printed line starts at column 0. We deliberately do not reset
     * [assistantOpen] — the next delta will still want to flow inline on its
     * own new line, and [emittedLen] must stay intact for the
     * [ensureAssistantText] fallback to remain idempotent.
     *
     * Caller must hold [mutex].
     */
    private fun breakAssistantLineLocked() {
        if (assistantOpen) {
            terminal.writer().println()
            assistantOpen = false
        }
    }

    private fun markAllPartsUnrepaintableLocked() {
        if (repaintable.isEmpty()) return
        for (k in repaintable.keys.toList()) repaintable[k] = false
    }

    /** Forget what's at the bottom of the buffer — the next write won't be in-place. */
    private fun invalidateBottomLocked() {
        bottomToolPartId = null
        bottomRenderJobId = null
    }

    private fun renderMarkdown(text: String): String =
        runCatching { mordant.render(Markdown(text)) }.getOrElse { text }

    /**
     * Cheap heuristic — only spend the repaint cost on text that actually
     * benefits. Pure conversational replies without markup look identical
     * before and after rendering, and skipping the repaint avoids any flicker.
     */
    private fun looksLikeMarkdown(text: String): Boolean {
        if (text.contains("```")) return true
        if (text.contains("**")) return true
        if (text.contains("__")) return true
        if (text.contains('`')) return true
        val lines = text.split('\n')
        // GFM table — header row, separator, data rows all use `|`.
        for (i in 0 until lines.size - 1) {
            val a = lines[i]
            val b = lines[i + 1]
            if (a.contains('|') && b.trim().matches(Regex("^\\|?[\\s:|-]+\\|?$")) && b.contains('-')) {
                return true
            }
        }
        // Bullet, numbered list, heading, or block quote at line start.
        return lines.any { line ->
            val trimmed = line.trimStart()
            trimmed.startsWith("- ") ||
                trimmed.startsWith("* ") ||
                trimmed.startsWith("> ") ||
                trimmed.startsWith("# ") ||
                trimmed.startsWith("## ") ||
                trimmed.startsWith("### ") ||
                Regex("^\\d+\\.\\s").containsMatchIn(trimmed)
        }
    }

    private fun visualRows(text: String, width: Int): Int {
        if (text.isEmpty() || width <= 0) return 0
        var rows = 0
        for (line in text.split('\n')) {
            val len = line.length
            rows += if (len == 0) 1 else (len + width - 1) / width
        }
        // The streamed text doesn't have a trailing newline, so the cursor sits
        // on the last printed row — that row counts once, no extra add needed.
        return rows
    }

    private fun formatRenderProgressLine(
        jobId: String,
        ratio: Float,
        message: String?,
        thumbnailPath: String?,
        isCompleted: Boolean,
        isFailure: Boolean,
        width: Int,
    ): String {
        val shortJob = jobId.take(16)
        val marker = when {
            isFailure -> Styles.fail("✗")
            isCompleted -> Styles.ok("✓")
            else -> Styles.running("⟳")
        }
        val pct = (ratio * 100f).toInt().coerceIn(0, 100)
        val bar = progressBar(ratio, barWidth = 20)
        val meta = buildString {
            if (!message.isNullOrBlank()) append("· ").append(message)
            if (!thumbnailPath.isNullOrBlank()) {
                if (isNotEmpty()) append(' ')
                append("· preview=").append(shortenPath(thumbnailPath, maxChars = 40))
            }
        }
        val metaStyled = if (meta.isNotEmpty()) " ${Styles.meta(meta)}" else ""
        val head = "  $marker ${Styles.toolId(shortJob)} $bar ${String.format("%3d", pct)}%"
        val line = head + metaStyled
        // The ANSI styling bytes don't take up visible columns; a width-based
        // truncation here would cut mid-escape. Keep the full line — terminals
        // wrap, but the in-place-rewrite path only over-clears one row, which
        // is the desired behaviour on narrow widths (worst case: the next tick
        // prints after a soft wrap).
        return line
    }

    private fun progressBar(ratio: Float, barWidth: Int): String {
        val filled = (ratio * barWidth).toInt().coerceIn(0, barWidth)
        val empty = (barWidth - filled).coerceAtLeast(0)
        val bar = buildString {
            append('[')
            repeat(filled) { append('=') }
            if (filled in 1 until barWidth) {
                // Replace the last '=' with '>' so the head is visible.
                setCharAt(length - 1, '>')
            }
            repeat(empty) { append(' ') }
            append(']')
        }
        return bar
    }

    private fun shortenPath(path: String, maxChars: Int): String {
        if (path.length <= maxChars) return path
        val slash = path.lastIndexOf('/')
        if (slash < 0 || slash >= path.length - 1) {
            return "…" + path.takeLast(maxChars - 1)
        }
        val base = path.substring(slash)
        return if (base.length + 1 <= maxChars) "…$base" else "…" + base.takeLast(maxChars - 1)
    }

    private companion object {
        private const val CURSOR_UP_1 = "\u001B[1A"
        private const val CARRIAGE_RETURN = "\r"
        private const val CLEAR_LINE = "\u001B[2K"
    }
}
