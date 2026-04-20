package io.talevia.cli.permission

import io.talevia.cli.repl.Renderer
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.permission.PermissionAction
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.permission.PermissionRule
import io.talevia.core.permission.PermissionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.jline.reader.LineReader

/**
 * Subscribe to [BusEvent.PermissionAsked] and prompt the user for Allow/Always/
 * Reject via stdin. Works because the REPL loop is always suspended inside
 * [io.talevia.core.agent.Agent.run] when a permission event fires — there is
 * no parallel readLine for user prompts to contend with.
 *
 * On "Always" the matching [PermissionRule] is appended to [permissionRules];
 * subsequent same-permission requests in this session (and sessions picked up
 * in the same CLI process) resolve silently.
 */
class StdinPermissionPrompt(
    private val bus: EventBus,
    private val permissions: PermissionService,
    private val renderer: Renderer,
    private val lineReader: LineReader,
    private val permissionRules: MutableList<PermissionRule>,
    private val activeSessionId: () -> SessionId,
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch {
            bus.subscribe<BusEvent.PermissionAsked>()
                .filter { it.sessionId == activeSessionId() }
                .collect { ev -> handle(ev) }
        }
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
    }

    private suspend fun handle(ev: BusEvent.PermissionAsked) {
        renderer.println("")
        renderer.println("⚠ permission: ${ev.permission}")
        if (ev.patterns.isNotEmpty() && ev.patterns != listOf("*")) {
            ev.patterns.forEach { renderer.println("  · $it") }
        }
        val choice = runCatching {
            lineReader.readLine("  [a]llow once  [A]lways  [r]eject  > ")
        }.getOrNull()?.trim().orEmpty()

        val decision = when (choice.firstOrNull()) {
            'a' -> PermissionDecision.Once
            'A' -> {
                val pattern = ev.patterns.singleOrNull() ?: "*"
                permissionRules += PermissionRule(ev.permission, pattern, PermissionAction.ALLOW)
                PermissionDecision.Always
            }
            'r', 'R' -> PermissionDecision.Reject
            // Empty line / EOF / unknown → safest default is reject.
            else -> PermissionDecision.Reject
        }
        permissions.reply(ev.requestId, decision)
        renderer.println(
            "  → ${
                when (decision) {
                    is PermissionDecision.Once -> "allowed once"
                    is PermissionDecision.Always -> "allowed always"
                    is PermissionDecision.Reject -> "rejected"
                }
            }",
        )
    }
}
