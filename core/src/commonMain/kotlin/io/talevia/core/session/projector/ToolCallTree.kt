package io.talevia.core.session.projector

import io.talevia.core.SessionId
import io.talevia.core.session.Message
import io.talevia.core.session.Part
import io.talevia.core.session.SessionStore
import io.talevia.core.session.ToolState
import kotlinx.serialization.Serializable

/**
 * Turn-oriented projection of a session: each user prompt + its answering
 * assistant turn + the tool calls that turn fanned out into. The natural
 * shape for a chat/agent UI that wants to render:
 *
 *   user: "make me a title sequence"
 *     assistant (STOP):
 *       ├─ generate_music ✓
 *       ├─ synthesize_speech ✓
 *       └─ add_clip ✓
 *   user: "swap in Mei's portrait"
 *     assistant (TOOL_CALLS, still running):
 *       └─ clip_action(action="replace") ⏳
 *
 * Built by walking `listMessagesWithParts` once: assistant messages are the
 * turn anchors; `Part.Tool` rows on each assistant message become the
 * children; the immediately-preceding user message (if any) is paired via
 * `Message.Assistant.parentId`. Messages without matching anchors (e.g. the
 * very first assistant message in a session seeded without user input) get
 * `userMessageId = null`.
 */
@Serializable
data class ToolCallTree(
    val sessionId: String,
    val turns: List<TurnNode>,
)

@Serializable
data class TurnNode(
    /** Preceding user prompt id, `null` when the assistant turn has no user anchor. */
    val userMessageId: String? = null,
    val assistantMessageId: String,
    val createdAtEpochMs: Long,
    /** `"stop"` | `"tool_calls"` | `"length"` | `"error"` | `"cancelled"` | null (still in-flight). */
    val finish: String? = null,
    val tokensInput: Long = 0,
    val tokensOutput: Long = 0,
    val toolCalls: List<ToolCallNode> = emptyList(),
)

@Serializable
data class ToolCallNode(
    val partId: String,
    val callId: String,
    val toolId: String,
    /** `"pending"` | `"running"` | `"completed"` | `"error"`. */
    val state: String,
    val title: String? = null,
    val createdAtEpochMs: Long,
)

/**
 * Default [SessionProjector] implementation for [ToolCallTree]. Deterministic
 * output for a given session — the store ordering is used without an extra
 * sort — which makes the projection trivially memoisable from the UI side.
 */
class ToolCallTreeProjector(
    private val sessions: SessionStore,
) : SessionProjector<ToolCallTree> {

    override suspend fun project(sessionId: SessionId): ToolCallTree {
        val mwps = sessions.listMessagesWithParts(sessionId, includeCompacted = true)
        val turns = mwps.mapNotNull { mwp ->
            val assistant = mwp.message as? Message.Assistant ?: return@mapNotNull null
            val toolCalls = mwp.parts
                .filterIsInstance<Part.Tool>()
                .sortedBy { it.createdAt.toEpochMilliseconds() }
                .map { p ->
                    ToolCallNode(
                        partId = p.id.value,
                        callId = p.callId.value,
                        toolId = p.toolId,
                        state = p.state.stateLabel(),
                        title = p.title,
                        createdAtEpochMs = p.createdAt.toEpochMilliseconds(),
                    )
                }
            TurnNode(
                userMessageId = assistant.parentId.value,
                assistantMessageId = assistant.id.value,
                createdAtEpochMs = assistant.createdAt.toEpochMilliseconds(),
                finish = assistant.finish?.name?.lowercase(),
                tokensInput = assistant.tokens.input,
                tokensOutput = assistant.tokens.output,
                toolCalls = toolCalls,
            )
        }
        return ToolCallTree(
            sessionId = sessionId.value,
            turns = turns,
        )
    }
}

private fun ToolState.stateLabel(): String = when (this) {
    is ToolState.Pending -> "pending"
    is ToolState.Running -> "running"
    is ToolState.Completed -> "completed"
    is ToolState.Failed -> "error"
}
