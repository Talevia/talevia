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
 * VISION §5.2 — user-set AIGC spend cap for this session. Sets (or
 * clears) [Session.spendCapCents]. The cap is consulted by
 * [io.talevia.core.tool.builtin.aigc.AigcBudgetGuard] on every AIGC
 * dispatch; once cumulative session spend reaches the cap the guard
 * raises an `aigc.budget` permission ASK and the user decides whether
 * to continue, stop, or persist an override for the rest of the
 * session.
 *
 * Upsert semantics (§3a rule 2: no `define_/update_` split). A single
 * tool flips the cap:
 *   - `capCents = 100` — set or raise cap to \$1.00.
 *   - `capCents = 0`  — set cap to "spend nothing"; every subsequent
 *                       AIGC call ASKs.
 *   - `capCents = null` — clear the cap; no budget gating.
 *
 * Permission: `session.write`, same scope as `rename_session` and
 * friends. The gate this tool configures is itself an `aigc.budget`
 * permission; setting the cap is a non-destructive metadata write.
 */
class SetSessionSpendCapTool(
    private val sessions: SessionStore,
    private val clock: Clock = Clock.System,
) : Tool<SetSessionSpendCapTool.Input, SetSessionSpendCapTool.Output> {

    @Serializable data class Input(
        /**
         * Optional — omit to configure this session (context-resolved via
         * `ctx.resolveSessionId`). Explicit id to configure a different
         * session.
         */
        val sessionId: String? = null,
        /**
         * Cents cap. `null` clears the cap (no budget gating). `0` means
         * "spend nothing" (every paid AIGC call ASKs). Positive values
         * are cents. Reject negative values — a cap < 0 is nonsensical
         * and the most common shape of that mistake is the user
         * confusing dollars with cents (-5 cents instead of -500).
         */
        val capCents: Long? = null,
    )

    @Serializable data class Output(
        val sessionId: String,
        val previousCapCents: Long?,
        val capCents: Long?,
    )

    override val id: String = "set_session_spend_cap"
    override val helpText: String =
        "Set or clear the AIGC spend cap (cents) for a session. Once cumulative session spend hits " +
            "the cap, every subsequent AIGC tool call raises an aigc.budget permission ASK so the " +
            "user can stop or continue. capCents=null clears the cap (no budget gating). 0 blocks " +
            "all paid AIGC calls. Inspect current spend via session_query(select=spend)."
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
                    "Optional — omit to configure the current session. Explicit id to configure " +
                        "a different session.",
                )
            }
            putJsonObject("capCents") {
                put(
                    "description",
                    "AIGC spend cap in cents. null clears the cap. 0 blocks all paid calls. " +
                        "Positive cents sets the budget (e.g. 500 = \$5.00). Must be ≥ 0 when non-null.",
                )
                // integer | null
                put(
                    "type",
                    JsonArray(listOf(JsonPrimitive("integer"), JsonPrimitive("null"))),
                )
            }
        }
        // No required fields — both are optional; cap defaults to null (clear).
        put("required", JsonArray(emptyList()))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val capCents = input.capCents
        if (capCents != null) {
            require(capCents >= 0) {
                "capCents must be ≥ 0 (or null to clear); got $capCents. If you meant dollars, " +
                    "multiply by 100: \$5.00 = 500."
            }
        }
        val sid = ctx.resolveSessionId(input.sessionId)
        val session = sessions.getSession(sid)
            ?: error(
                "Session ${sid.value} not found. Call session_query(select=sessions) to discover " +
                    "valid session ids.",
            )

        val previous = session.spendCapCents
        if (previous == capCents) {
            return ToolResult(
                title = "set_session_spend_cap (no-op)",
                outputForLlm = "Session ${sid.value} already has cap=${formatCap(capCents)}; nothing to do.",
                data = Output(
                    sessionId = sid.value,
                    previousCapCents = previous,
                    capCents = capCents,
                ),
            )
        }
        sessions.updateSession(
            session.copy(
                spendCapCents = capCents,
                updatedAt = clock.now(),
            ),
        )
        return ToolResult(
            title = "set session spend cap ${sid.value}",
            outputForLlm = "Session ${sid.value} spend cap: ${formatCap(previous)} → ${formatCap(capCents)}.",
            data = Output(
                sessionId = sid.value,
                previousCapCents = previous,
                capCents = capCents,
            ),
        )
    }

    private fun formatCap(cents: Long?): String = when {
        cents == null -> "none"
        cents == 0L -> "0¢ (block all)"
        else -> "${cents}¢"
    }
}
