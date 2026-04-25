package io.talevia.core.tool.builtin.session

import io.talevia.core.ProjectId
import io.talevia.core.agent.AgentRunState
import io.talevia.core.agent.AgentRunStateTracker
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.datetime.Clock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Bind the session to a new `currentProjectId` (VISION §5.4 — the cwd-analogue
 * for multi-project workflows). The binding is injected into every subsequent
 * turn's system prompt and exposed on [ToolContext.currentProjectId], so the
 * agent doesn't need to re-derive "which project am I editing?" from the
 * transcript after every user message.
 *
 * Verifies the target project exists via [ProjectStore.get] **before**
 * committing — an unknown id would otherwise leave the session pointing at a
 * ghost and every downstream tool call would fail loudly with the same error
 * spread across N turns.
 *
 * Same-id is a no-op: the session's `currentProjectId` is already what was
 * requested, so we don't bump `updatedAt` or emit a SessionUpdated event.
 *
 * Companion: `list_projects` / `create_project` to pick or build a project;
 * `describe_session` to read the current binding back.
 */
class SwitchProjectTool(
    private val sessions: SessionStore,
    private val projects: ProjectStore,
    private val clock: Clock = Clock.System,
    /**
     * Optional event bus. When provided, a
     * [BusEvent.SessionProjectBindingChanged] is published after a successful
     * binding change. Null keeps the tool usable in test rigs that don't
     * subscribe. Production composition roots (CLI / Desktop / Server /
     * Android / iOS) all pass the app's bus.
     */
    private val bus: EventBus? = null,
    /**
     * Optional agent-run state tracker. When provided, the tool rejects a
     * project-binding change while the target session's agent is in a
     * non-terminal state (Generating / AwaitingTool / Compacting) — the
     * mid-run context would otherwise see a surprise-rebind on its next turn
     * and render confused state. Null (test rigs / legacy compositions) skips
     * the guard, preserving the pre-guard behaviour. Production composition
     * roots (CLI / Desktop / Server / Android) all pass the tracker.
     * (`session-project-rebind-mid-run-guard` — VISION §5.6.)
     */
    private val agentStates: AgentRunStateTracker? = null,
) : Tool<SwitchProjectTool.Input, SwitchProjectTool.Output> {

    @Serializable data class Input(
        /**
         * Optional — omit to default to the tool's owning session
         * (`ToolContext.sessionId`). Pass an explicit id only to rebind a
         * different session than the one currently dispatching. Matches the
         * [SessionActionTool] / [io.talevia.core.tool.builtin.session.ReadPartTool]
         * pattern so the agent never has to guess the current sessionId.
         */
        val sessionId: String? = null,
        val projectId: String,
    )

    @Serializable data class Output(
        val sessionId: String,
        val previousProjectId: String?,
        val currentProjectId: String,
        val changed: Boolean,
    )

    override val id: String = "switch_project"
    override val helpText: String =
        "Set the session's currentProjectId — the cwd-analogue for multi-project work. " +
            "Verifies the project exists before committing; same-id is a no-op. The binding " +
            "is injected into each subsequent turn's system prompt and exposed on " +
            "ToolContext so tools can default projectId in the future. sessionId is " +
            "optional — omit to rebind the current session."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("session.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("sessionId") {
                put("type", "string")
                put(
                    "description",
                    "Optional — omit to rebind this session (context-resolved). Explicit id to " +
                        "rebind a different session. Never pass a placeholder like 'current' — " +
                        "the dispatching session's id is already available via context.",
                )
            }
            putJsonObject("projectId") {
                put("type", "string")
                put("description", "Id of the project to bind the session to. Must exist.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.projectId.isNotBlank()) { "projectId must not be blank" }
        // Blank explicit sessionId is almost certainly a model-side placeholder
        // (we saw "current" / "session-unknown" in production logs); reject it
        // up front rather than let resolveSessionId route to a bogus lookup.
        input.sessionId?.let {
            require(it.isNotBlank()) { "sessionId must not be blank" }
        }

        val sid = ctx.resolveSessionId(input.sessionId)
        val session = sessions.getSession(sid)
            ?: error(
                "Session ${sid.value} not found. Call session_query(select=sessions) to discover valid session ids.",
            )

        val pid = ProjectId(input.projectId)
        val previous = session.currentProjectId?.value

        if (previous == pid.value) {
            return ToolResult(
                title = "switch project (no-op)",
                outputForLlm =
                    "Session ${sid.value} is already bound to project ${pid.value} — nothing to do.",
                data = Output(
                    sessionId = sid.value,
                    previousProjectId = previous,
                    currentProjectId = pid.value,
                    changed = false,
                ),
            )
        }

        // Fail loud on unknown project id — otherwise downstream tool calls would
        // spray the same not-found error across every subsequent turn.
        projects.get(pid)
            ?: error(
                "Project ${pid.value} does not exist. Call list_projects to discover valid " +
                    "project ids, or create_project to make a new one.",
            )

        // Mid-run guard (VISION §5.6): reject a rebind while the agent is still
        // working on the session — the next turn's ToolContext.currentProjectId
        // would otherwise change under its feet. Terminal states
        // (Idle / Cancelled / Failed) and null (no run has started yet) all
        // pass. The same-id no-op path above already returned, so a gate-trip
        // here is always a genuine state change.
        //
        // Self-rebind exception: when the dispatching session IS the target
        // (sid == ctx.sessionId), the agent calling this tool is by definition
        // in `AwaitingTool` — it's executing this very dispatch. Blocking
        // would mean the model can never call `switch_project` from a tool
        // batch, which is the only way it CAN call switch_project. The
        // "surprise rebind" scenario the guard exists for is an EXTERNAL
        // initiator (UI button, REST endpoint, scheduled job, sibling agent)
        // racing the running session — that's still rejected via cross-
        // session calls (`input.sessionId` pointing at someone else's run).
        if (sid != ctx.sessionId) {
            agentStates?.currentState(sid)?.let { state ->
                val runTag = agentRunStateSlug(state)
                if (runTag != null) {
                    error(
                        "Cannot switch_project while agent is $runTag on session ${sid.value}. " +
                            "Wait for the current run to finish, or cancel it first, before rebinding.",
                    )
                }
            }
        }

        sessions.updateSession(
            session.copy(
                currentProjectId = pid,
                updatedAt = clock.now(),
            ),
        )

        bus?.publish(
            BusEvent.SessionProjectBindingChanged(
                sessionId = sid,
                previousProjectId = session.currentProjectId,
                newProjectId = pid,
            ),
        )

        return ToolResult(
            title = "switch project → ${pid.value}",
            outputForLlm =
                "Session ${sid.value} now bound to project ${pid.value}" +
                    if (previous != null) " (was: $previous)." else ".",
            data = Output(
                sessionId = sid.value,
                previousProjectId = previous,
                currentProjectId = pid.value,
                changed = true,
            ),
        )
    }

    /**
     * Returns a human slug for non-terminal run states, or null for terminal
     * ones (Idle / Cancelled / Failed) — those mean "no in-flight run", so the
     * rebind is safe. The three mid-run states all return a short tag suitable
     * for embedding in the error message.
     */
    private fun agentRunStateSlug(state: AgentRunState): String? = when (state) {
        is AgentRunState.Generating -> "generating"
        is AgentRunState.AwaitingTool -> "awaiting_tool"
        is AgentRunState.Compacting -> "compacting"
        is AgentRunState.Idle, is AgentRunState.Cancelled, is AgentRunState.Failed -> null
    }
}
