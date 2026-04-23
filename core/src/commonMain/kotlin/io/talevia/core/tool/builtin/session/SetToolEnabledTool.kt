package io.talevia.core.tool.builtin.session

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
 * Enable / disable a specific tool for the current session (VISION §5.4).
 *
 * Flips a single [io.talevia.core.session.Session.disabledToolIds] entry.
 * Disabled tools are filtered out at `ToolRegistry.specs(ctx)` — **before**
 * the LLM ever sees them — so "stop using generate_video, it's too expensive"
 * translates into `set_tool_enabled(toolId="generate_video", enabled=false)`
 * and the model genuinely has no way to dispatch it for the rest of the
 * session. Re-enable with the mirror call.
 *
 * Upsert shape (§3a rule 2: no define_/update_ split):
 *  - `enabled=false` adds `toolId` to the disabled set (no-op when already disabled).
 *  - `enabled=true`  removes `toolId` from the disabled set (no-op when already enabled).
 *
 * Does NOT validate that [toolId] is currently registered — the set is
 * per-session persisted state, and a session may reasonably disable a tool
 * that isn't loaded right now (e.g. an env-gated AIGC engine). If a disabled
 * tool is ever loaded it will still be filtered out.
 *
 * Permission: `session.write` — same tier as `rename_session` and
 * `set_session_spend_cap`.
 */
class SetToolEnabledTool(
    private val sessions: SessionStore,
    private val clock: Clock = Clock.System,
) : Tool<SetToolEnabledTool.Input, SetToolEnabledTool.Output> {

    @Serializable data class Input(
        /** Optional — defaults to the current session via [ToolContext.resolveSessionId]. */
        val sessionId: String? = null,
        val toolId: String,
        val enabled: Boolean,
    )

    @Serializable data class Output(
        val sessionId: String,
        val toolId: String,
        /** State after the write. */
        val enabled: Boolean,
        /** True when the call actually mutated the session (toggle); false when it was a no-op. */
        val changed: Boolean,
    )

    override val id: String = "set_tool_enabled"
    override val helpText: String =
        "Enable (enabled=true) or disable (enabled=false) a specific tool for the current session. " +
            "Disabled tools are filtered out of the LLM's tool spec — the model cannot dispatch them " +
            "until re-enabled. Use when the user says 'stop using <tool>, too expensive / noisy / " +
            "off-topic'. No-op when the tool is already in the requested state."
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
                    "Optional — omit to configure the current session. Explicit id to configure a different session.",
                )
            }
            putJsonObject("toolId") {
                put("type", "string")
                put("description", "Tool id to enable/disable, e.g. 'generate_video'.")
            }
            putJsonObject("enabled") {
                put("type", "boolean")
                put("description", "true = enable (remove from disabled set); false = disable (add).")
            }
        }
        put(
            "required",
            JsonArray(listOf(JsonPrimitive("toolId"), JsonPrimitive("enabled"))),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.toolId.isNotBlank()) { "toolId must not be blank" }
        val sid = ctx.resolveSessionId(input.sessionId)
        val session = sessions.getSession(sid)
            ?: error(
                "Session ${sid.value} not found. Call session_query(select=sessions) to discover " +
                    "valid session ids.",
            )
        val currentlyDisabled = input.toolId in session.disabledToolIds
        val shouldDisable = !input.enabled
        val changed = currentlyDisabled != shouldDisable

        if (!changed) {
            return ToolResult(
                title = "set_tool_enabled (no-op)",
                outputForLlm = "Session ${sid.value}: ${input.toolId} already " +
                    (if (input.enabled) "enabled" else "disabled") + "; nothing to do.",
                data = Output(
                    sessionId = sid.value,
                    toolId = input.toolId,
                    enabled = input.enabled,
                    changed = false,
                ),
            )
        }

        val nextDisabled = if (shouldDisable) {
            session.disabledToolIds + input.toolId
        } else {
            session.disabledToolIds - input.toolId
        }
        sessions.updateSession(
            session.copy(
                disabledToolIds = nextDisabled,
                updatedAt = clock.now(),
            ),
        )
        val verb = if (input.enabled) "enabled" else "disabled"
        return ToolResult(
            title = "$verb ${input.toolId} for ${sid.value}",
            outputForLlm = "Session ${sid.value}: ${input.toolId} → $verb. " +
                "${if (input.enabled) "Visible in next turn's tool spec." else "Hidden from next turn's tool spec."}",
            data = Output(
                sessionId = sid.value,
                toolId = input.toolId,
                enabled = input.enabled,
                changed = true,
            ),
        )
    }
}
