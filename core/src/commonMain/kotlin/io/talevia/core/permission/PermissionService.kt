package io.talevia.core.permission

import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Permission request raised by a Tool just before it performs a guarded action.
 * `pattern` is what the rule engine matches against; `metadata` is opaque context
 * shown to the user (e.g. file path, target URL).
 */
data class PermissionRequest(
    val sessionId: SessionId,
    val permission: String,
    val pattern: String = "*",
    val metadata: Map<String, String> = emptyMap(),
)

sealed class PermissionDecision {
    /** Approve this single request only. */
    data object Once : PermissionDecision()
    /** Approve and persist a matching rule for the session. */
    data object Always : PermissionDecision()
    /** Reject this request; the tool should fail or skip the action. */
    data object Reject : PermissionDecision()

    val granted: Boolean get() = this is Once || this is Always
}

interface PermissionService {
    /**
     * Returns a decision based on existing rules; if no rule matches and the action
     * defaults to ASK, raises a request and suspends until the user (or a default
     * policy) replies.
     */
    suspend fun check(rules: List<PermissionRule>, request: PermissionRequest): PermissionDecision

    /** UI side: respond to a previously raised request. */
    suspend fun reply(requestId: String, decision: PermissionDecision)
}

/**
 * Default service: evaluates rules; when ASK is required, publishes a
 * `BusEvent.PermissionAsked` and suspends until a matching `reply()` lands.
 */
@OptIn(ExperimentalUuidApi::class)
class DefaultPermissionService(private val bus: EventBus) : PermissionService {
    private data class Pending(val request: PermissionRequest, val deferred: CompletableDeferred<PermissionDecision>)
    private val pending = mutableMapOf<String, Pending>()
    private val mutex = Mutex()

    override suspend fun check(rules: List<PermissionRule>, request: PermissionRequest): PermissionDecision {
        val action = evaluate(rules, request)
        return when (action) {
            PermissionAction.ALLOW -> PermissionDecision.Once
            PermissionAction.DENY -> PermissionDecision.Reject
            PermissionAction.ASK -> ask(request)
        }
    }

    override suspend fun reply(requestId: String, decision: PermissionDecision) {
        val entry = mutex.withLock { pending.remove(requestId) } ?: return
        entry.deferred.complete(decision)
        bus.publish(
            BusEvent.PermissionReplied(
                sessionId = entry.request.sessionId,
                requestId = requestId,
                accepted = decision.granted,
                remembered = decision is PermissionDecision.Always,
            ),
        )
    }

    private suspend fun ask(request: PermissionRequest): PermissionDecision {
        val id = Uuid.random().toString()
        val deferred = CompletableDeferred<PermissionDecision>()
        mutex.withLock { pending[id] = Pending(request, deferred) }
        bus.publish(
            BusEvent.PermissionAsked(
                sessionId = request.sessionId,
                requestId = id,
                permission = request.permission,
                patterns = listOf(request.pattern),
            ),
        )
        return deferred.await()
    }

    private fun evaluate(rules: List<PermissionRule>, request: PermissionRequest): PermissionAction {
        // First explicit DENY wins, then explicit ALLOW, then ASK, otherwise default ASK.
        var found: PermissionAction? = null
        for (r in rules) {
            if (r.permission != request.permission) continue
            if (!matches(r.pattern, request.pattern)) continue
            when (r.action) {
                PermissionAction.DENY -> return PermissionAction.DENY
                PermissionAction.ALLOW -> if (found != PermissionAction.ASK) found = PermissionAction.ALLOW
                PermissionAction.ASK -> found = PermissionAction.ASK
            }
        }
        return found ?: PermissionAction.ASK
    }

    /** Glob match: `*` matches any substring, otherwise literal. */
    private fun matches(rulePattern: String, requestPattern: String): Boolean {
        if (rulePattern == "*" || rulePattern == requestPattern) return true
        if (!rulePattern.contains('*')) return false
        val regex = Regex(
            buildString {
                append('^')
                rulePattern.forEach { c ->
                    when (c) {
                        '*' -> append(".*")
                        '.', '+', '?', '(', ')', '[', ']', '{', '}', '\\', '|', '^', '$' -> append('\\').append(c)
                        else -> append(c)
                    }
                }
                append('$')
            },
        )
        return regex.matches(requestPattern)
    }
}

/** Test-only / dev fallback: every request resolves to [PermissionDecision.Once]. */
class AllowAllPermissionService : PermissionService {
    override suspend fun check(rules: List<PermissionRule>, request: PermissionRequest) = PermissionDecision.Once
    override suspend fun reply(requestId: String, decision: PermissionDecision) { /* no-op */ }
}
