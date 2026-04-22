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
 */
class Renderer(
    private val terminal: Terminal,
    private val markdownEnabled: Boolean = true,
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

    private val mordant: MordantTerminal by lazy {
        MordantTerminal(
            ansiLevel = if (markdownEnabled) AnsiLevel.TRUECOLOR else AnsiLevel.NONE,
            width = terminal.width.coerceIn(40, 200),
        )
    }

    suspend fun streamAssistantDelta(partId: PartId, delta: String) = mutex.withLock {
        if (delta.isEmpty() || finalised.contains(partId)) return@withLock
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
    }

    suspend fun println(text: String) = mutex.withLock {
        breakAssistantLineLocked()
        markAllPartsUnrepaintableLocked()
        terminal.writer().println(text)
        terminal.writer().flush()
    }

    suspend fun error(text: String) = mutex.withLock {
        // stderr bypasses the terminal writer on purpose so it stays visibly
        // separate from assistant text even when stdout is redirected.
        breakAssistantLineLocked()
        markAllPartsUnrepaintableLocked()
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
        terminal.writer().println("  ${Styles.running("⟳")} ${Styles.toolId(toolId)}")
        terminal.writer().flush()
    }

    suspend fun toolCompleted(partId: PartId, toolId: String, summary: String) = mutex.withLock {
        if (!finalisedTools.add(partId)) return@withLock
        breakAssistantLineLocked()
        markAllPartsUnrepaintableLocked()
        val oneLine = summary.lineSequence().firstOrNull()?.take(120).orEmpty()
        val suffix = if (oneLine.isBlank()) "" else " ${Styles.meta("· $oneLine")}"
        terminal.writer().println("  ${Styles.ok("✓")} ${Styles.toolId(toolId)}$suffix")
        terminal.writer().flush()
    }

    suspend fun toolFailed(partId: PartId, toolId: String, message: String) = mutex.withLock {
        if (!finalisedTools.add(partId)) return@withLock
        breakAssistantLineLocked()
        markAllPartsUnrepaintableLocked()
        terminal.writer().println("  ${Styles.fail("✗")} ${Styles.toolId(toolId)} ${Styles.meta("· $message")}")
        terminal.writer().flush()
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
}
