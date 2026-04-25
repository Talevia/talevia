package io.talevia.core.session

import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.permission.PermissionRule
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Session(
    val id: SessionId,
    val projectId: ProjectId,
    val title: String,
    val parentId: SessionId? = null,
    val permissionRules: List<PermissionRule> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant,
    val compactingFrom: MessageId? = null,
    val archived: Boolean = false,
    /**
     * The project the agent is **currently** editing in this session — a
     * cwd-analogue for multi-project workflows (VISION §5.4). Distinct from
     * [projectId], which is the session's originating project and never
     * changes after creation.
     *
     * `null` means "not yet bound to a project"; the agent should pick one
     * (usually via `list_projects` → `create_project` or an explicit
     * `switch_project` call) before running timeline tools. Flipped by
     * `SwitchProjectTool`; injected into the per-turn system prompt and
     * exposed on `ToolContext.currentProjectId` so tools can default their
     * `projectId` arg from it.
     *
     * Defaulted to null so pre-binding sessions deserialize cleanly (§3a #7
     * — serialization compat).
     */
    val currentProjectId: ProjectId? = null,
    /**
     * VISION §5.2 spend-budget guard — user-configured hard stop on AIGC
     * cost in this session. Before every paid provider call an AIGC tool
     * compares `project.lockfile` spend attributed to this session against
     * [spendCapCents] and raises a `aigc.budget` permission ASK once the
     * cumulative total meets or exceeds the cap. Flipped by
     * `session_action(action="set_spend_cap")`.
     *
     * **Three-state:** `null` = no cap (silent default; the agent runs
     * without a spending guard, matching pre-cap behaviour). `0L` =
     * "spend nothing" — every AIGC call will ASK. Positive Long = cents
     * cap. Legacy sessions written before this field deserialize as null
     * (default), preserving no-cap semantics.
     */
    val spendCapCents: Long? = null,
    /**
     * Tools the agent is NOT allowed to dispatch in this session. Flipped by
     * `session_action(action="set_tool_enabled")`. Applied at request-assembly
     * time: `AgentTurnExecutor`
     * forwards the set into [io.talevia.core.tool.ToolAvailabilityContext] and
     * `ToolRegistry.specs(ctx)` filters them out before the provider sees the
     * tool bundle — so a disabled tool is invisible to the model, not merely
     * rejected on dispatch.
     *
     * Use cases: "stop using generate_video, it's too expensive", "don't
     * touch timeline tools for the rest of this session", etc. Empty set
     * (default) preserves pre-feature behavior. Legacy sessions deserialize
     * cleanly because kotlinx.serialization honors the default.
     */
    val disabledToolIds: Set<String> = emptySet(),
    /**
     * Per-session override of the Agent's default system prompt (VISION
     * §5.4 control surface). When non-null, [AgentTurnExecutor] feeds this
     * string into [io.talevia.core.agent.buildSystemPrompt] in place of the
     * Agent-level default — letting one Agent instance host sessions that
     * each carry their own persona / mode (e.g. "switch this session to
     * code-review mode") without the caller having to spin up a second
     * Agent.
     *
     * `null` (default) → fall back to the Agent's constructor-level
     * `systemPrompt`, preserving pre-feature behaviour. Empty string is a
     * legitimate override ("run with no system prompt at all"); it is NOT
     * conflated with null. Legacy sessions persisted before this field
     * deserialize cleanly because kotlinx.serialization honours the
     * default — §3a #7 serialization compat.
     *
     * Flipped by `session_action(action="set_system_prompt", systemPromptOverride=...)`.
     */
    val systemPromptOverride: String? = null,
)
