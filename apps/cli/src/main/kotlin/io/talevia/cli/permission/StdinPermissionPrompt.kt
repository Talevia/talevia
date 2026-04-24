package io.talevia.cli.permission

import io.talevia.cli.repl.Renderer
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.permission.PermissionAction
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.permission.PermissionRule
import io.talevia.core.permission.PermissionRulesPersistence
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
    /**
     * Per-user persistence for "Always" replies. When non-null, every new
     * `[A]lways`-appended rule also gets saved (whole-list rewrite) so the
     * grant survives process restart — see
     * `docs/decisions/2026-04-23-permission-persistent-rules.md`.
     * [PermissionRulesPersistence.Noop] preserves the legacy in-memory-only
     * behaviour for tests / rigs that don't want a file on disk.
     */
    private val persistence: PermissionRulesPersistence = PermissionRulesPersistence.Noop,
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
        renderer.println("${io.talevia.cli.repl.Styles.warn("⚠ permission:")} ${ev.permission}")
        if (ev.patterns.isNotEmpty() && ev.patterns != listOf("*")) {
            ev.patterns.forEach { renderer.println(io.talevia.cli.repl.Styles.meta("  · $it")) }
        }
        val choice = runCatching {
            lineReader.readLine(io.talevia.cli.repl.Styles.prompt("  [a]llow once  [A]lways  [r]eject  > "))
        }.getOrNull()?.trim().orEmpty()

        val decision = when (choice.firstOrNull()) {
            'a' -> PermissionDecision.Once
            'A' -> {
                val pattern = ev.patterns.singleOrNull() ?: "*"
                val newRule = PermissionRule(ev.permission, pattern, PermissionAction.ALLOW)
                permissionRules += newRule
                // Persist the full current list so the new grant survives
                // process restart. save() internally runCatches, so a
                // read-only home directory doesn't block the interactive
                // path — in-memory list still has the rule, just the
                // "forever" part weakens to "this process only".
                persistence.save(permissionRules.toList())
                PermissionDecision.Always
            }
            'r', 'R' -> PermissionDecision.Reject
            // Empty line / EOF / unknown → safest default is reject.
            else -> PermissionDecision.Reject
        }
        permissions.reply(ev.requestId, decision)
        val verdict = when (decision) {
            is PermissionDecision.Once -> "allowed once"
            is PermissionDecision.Always -> "allowed always"
            is PermissionDecision.Reject -> "rejected"
        }
        renderer.println(io.talevia.cli.repl.Styles.meta("  → $verdict"))
    }
}
