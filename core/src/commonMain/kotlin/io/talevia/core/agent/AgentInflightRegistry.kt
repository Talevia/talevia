package io.talevia.core.agent

import io.talevia.core.MessageId
import io.talevia.core.SessionId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * Per-session handle tracking an in-flight [Agent.run] so [Agent.cancel]
 * can reach into the running coroutine and finalise the current
 * assistant message with [io.talevia.core.session.FinishReason.CANCELLED]
 * rather than letting it hang at `finish = null`.
 *
 * Implements [RetryLoop.Handle] so the retry loop can mutate the same
 * `lastRetryAttempt` field [Agent.run]'s terminal state-change emit
 * reads back.
 */
internal class AgentRunHandle(val job: Job) : RetryLoop.Handle {
    @Volatile override var currentAssistantId: MessageId? = null

    /**
     * Most recent retry attempt number scheduled during this run, or
     * null until the first retry fires. Read by [Agent.run]'s terminal
     * [io.talevia.core.bus.BusEvent.AgentRunStateChanged] emits so
     * subscribers can correlate the terminal state with the preceding
     * retry. Set by [RetryLoop] after publishing
     * [io.talevia.core.bus.BusEvent.AgentRetryScheduled]; monotonically
     * non-decreasing across steps and provider fallbacks within one run.
     */
    @Volatile override var lastRetryAttempt: Int? = null
}

/**
 * In-flight registry: maps session → its current [AgentRunHandle]
 * and serialises modifications behind a [Mutex] so two concurrent
 * `run` / `cancel` calls don't interleave. Extracted from
 * [Agent] so the registry's small invariants (one run per
 * session at a time, register-on-start, remove-on-finally) live in
 * one place rather than scattered through [Agent.run].
 *
 * All operations are suspending because the mutex is suspending —
 * acquiring it inside `Agent.cancel()` from a non-coroutine signal
 * handler is a job for the caller's bus path
 * ([AgentBusCancelWatcher]), not this class.
 */
internal class AgentInflightRegistry {
    private val mutex = Mutex()
    private val byId = mutableMapOf<SessionId, AgentRunHandle>()

    /**
     * Register a fresh run for [sessionId]. Throws if the session
     * already has an in-flight run — the caller (Agent.run) wraps
     * that as a clean error to the caller-of-run rather than a stray
     * concurrency bug. Returns the registered handle so the caller
     * holds onto its mutable fields.
     */
    suspend fun register(sessionId: SessionId, handle: AgentRunHandle): AgentRunHandle =
        mutex.withLock {
            check(sessionId !in byId) {
                "Session $sessionId already has an in-flight Agent.run; cancel it first"
            }
            byId[sessionId] = handle
            handle
        }

    /**
     * Drop [sessionId]'s registration. No-op when the session isn't
     * registered (covers double-finally races). Always called from
     * [Agent.run]'s `finally`.
     */
    suspend fun unregister(sessionId: SessionId) {
        mutex.withLock { byId.remove(sessionId) }
    }

    /**
     * Cancel the in-flight run for [sessionId] if any. Returns true
     * when a run was found and a cancel token was sent; false when the
     * session is idle. Same fast-path semantics as [Agent.cancel] —
     * idempotent under concurrent triggers because [Job.cancel] is
     * itself idempotent.
     */
    suspend fun cancel(sessionId: SessionId, reason: String): Boolean {
        val handle = mutex.withLock { byId[sessionId] } ?: return false
        handle.job.cancel(CancellationException(reason))
        return true
    }

    /** True while a run is registered for [sessionId]. */
    suspend fun isRunning(sessionId: SessionId): Boolean =
        mutex.withLock { sessionId in byId }
}
