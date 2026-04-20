package io.talevia.cli.repl

import io.talevia.core.PartId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jline.terminal.Terminal

/**
 * Mutex-guarded writer for the REPL. Every terminal write locks briefly so
 * streaming assistant deltas and tool-state cards don't interleave mid-line.
 *
 * The renderer tracks the running length of every assistant text part it has
 * already emitted; [streamAssistantDelta] is incremental, [ensureAssistantText]
 * is idempotent (used as a fallback when a non-streaming provider upserts a
 * finalised [io.talevia.core.session.Part.Text] without firing deltas first).
 */
class Renderer(private val terminal: Terminal) {
    private val mutex = Mutex()
    private val emittedLen: MutableMap<PartId, Int> = mutableMapOf()

    /** True iff any assistant text has been streamed in the current turn. */
    private var assistantOpen: Boolean = false

    suspend fun streamAssistantDelta(partId: PartId, delta: String) = mutex.withLock {
        if (delta.isEmpty()) return@withLock
        assistantOpen = true
        emittedLen[partId] = (emittedLen[partId] ?: 0) + delta.length
        terminal.writer().print(delta)
        terminal.writer().flush()
    }

    /**
     * Fallback path for providers that don't emit per-token deltas. When the
     * full [text] for [partId] is available, emit whatever hasn't been printed
     * yet. Idempotent — calling with the same text twice is a no-op.
     */
    suspend fun ensureAssistantText(partId: PartId, text: String) = mutex.withLock {
        val already = emittedLen[partId] ?: 0
        if (text.length <= already) return@withLock
        val tail = text.substring(already)
        assistantOpen = true
        emittedLen[partId] = text.length
        terminal.writer().print(tail)
        terminal.writer().flush()
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
    }

    suspend fun println(text: String) = mutex.withLock {
        terminal.writer().println(text)
        terminal.writer().flush()
    }

    suspend fun error(text: String) = mutex.withLock {
        // stderr bypasses the terminal writer on purpose — Mordant/ANSI colour
        // comes later; for now we just need it visibly separate from assistant text.
        System.err.println(text)
    }
}
