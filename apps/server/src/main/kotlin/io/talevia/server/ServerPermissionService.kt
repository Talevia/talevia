package io.talevia.server

import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.permission.PermissionAction
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.permission.PermissionRequest
import io.talevia.core.permission.PermissionRule
import io.talevia.core.permission.PermissionService
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Headless server permission policy: evaluate [PermissionRule]s just like
 * [io.talevia.core.permission.DefaultPermissionService], but treat any ASK as
 * [PermissionDecision.Reject] because no user is there to answer the prompt.
 *
 * This means a server caller MUST explicitly grant the permissions they want
 * their agent to use (via a session's `permissionRules` + the container's baseline),
 * otherwise tools fail closed. For visibility, still emits a
 * [BusEvent.PermissionAsked] immediately followed by a
 * [BusEvent.PermissionReplied] so SSE consumers can see the rejection.
 */
@OptIn(ExperimentalUuidApi::class)
class ServerPermissionService(private val bus: EventBus) : PermissionService {
    override suspend fun check(rules: List<PermissionRule>, request: PermissionRequest): PermissionDecision {
        return when (evaluate(rules, request)) {
            PermissionAction.ALLOW -> PermissionDecision.Once
            PermissionAction.DENY -> PermissionDecision.Reject
            PermissionAction.ASK -> {
                val id = Uuid.random().toString()
                bus.publish(
                    BusEvent.PermissionAsked(
                        sessionId = request.sessionId,
                        requestId = id,
                        permission = request.permission,
                        patterns = listOf(request.pattern),
                    ),
                )
                bus.publish(
                    BusEvent.PermissionReplied(
                        sessionId = request.sessionId,
                        requestId = id,
                        accepted = false,
                        remembered = false,
                    ),
                )
                PermissionDecision.Reject
            }
        }
    }

    override suspend fun reply(requestId: String, decision: PermissionDecision) {
        // No interactive replies in headless mode.
    }

    private fun evaluate(rules: List<PermissionRule>, request: PermissionRequest): PermissionAction {
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
