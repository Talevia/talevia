package io.talevia.core.session

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.SessionId
import io.talevia.core.domain.Timeline
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
sealed class Part {
    abstract val id: PartId
    abstract val messageId: MessageId
    abstract val sessionId: SessionId
    abstract val createdAt: Instant
    abstract val compactedAt: Instant?

    @Serializable @SerialName("text")
    data class Text(
        override val id: PartId,
        override val messageId: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant,
        override val compactedAt: Instant? = null,
        val text: String,
        val synthetic: Boolean = false,
        /** Forward-compat discriminator. See [PartSchema]. */
        val schemaVersion: Int = PartSchema.CURRENT,
    ) : Part()

    @Serializable @SerialName("reasoning")
    data class Reasoning(
        override val id: PartId,
        override val messageId: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant,
        override val compactedAt: Instant? = null,
        val text: String,
        /** Forward-compat discriminator. See [PartSchema]. */
        val schemaVersion: Int = PartSchema.CURRENT,
    ) : Part()

    @Serializable @SerialName("tool")
    data class Tool(
        override val id: PartId,
        override val messageId: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant,
        override val compactedAt: Instant? = null,
        val callId: CallId,
        val toolId: String,
        val state: ToolState,
        val title: String? = null,
        /** Forward-compat discriminator. See [PartSchema]. */
        val schemaVersion: Int = PartSchema.CURRENT,
    ) : Part()

    @Serializable @SerialName("media")
    data class Media(
        override val id: PartId,
        override val messageId: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant,
        override val compactedAt: Instant? = null,
        val assetId: AssetId,
        /** Forward-compat discriminator. See [PartSchema]. */
        val schemaVersion: Int = PartSchema.CURRENT,
    ) : Part()

    @Serializable @SerialName("timeline-snapshot")
    data class TimelineSnapshot(
        override val id: PartId,
        override val messageId: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant,
        override val compactedAt: Instant? = null,
        val timeline: Timeline,
        // null = "before tool", non-null = "after tool callId"
        val producedByCallId: CallId? = null,
        /** Forward-compat discriminator. See [PartSchema]. */
        val schemaVersion: Int = PartSchema.CURRENT,
    ) : Part()

    @Serializable @SerialName("render-progress")
    data class RenderProgress(
        override val id: PartId,
        override val messageId: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant,
        override val compactedAt: Instant? = null,
        val jobId: String,
        val ratio: Float,
        val message: String? = null,
        /**
         * Optional path to a small JPEG snapshot of the in-progress render
         * (VISION §5.4 — expert path can see mid-render output). Populated
         * when the engine emitted a [io.talevia.core.platform.RenderProgress.Preview]
         * event; null otherwise. The file at this path is overwritten by
         * subsequent preview ticks and deleted once the render completes —
         * UIs that want to keep a historical frame must copy the bytes out
         * before the next render-progress part lands.
         */
        val thumbnailPath: String? = null,
        /** Forward-compat discriminator. See [PartSchema]. */
        val schemaVersion: Int = PartSchema.CURRENT,
    ) : Part()

    @Serializable @SerialName("step-start")
    data class StepStart(
        override val id: PartId,
        override val messageId: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant,
        override val compactedAt: Instant? = null,
        /** Forward-compat discriminator. See [PartSchema]. */
        val schemaVersion: Int = PartSchema.CURRENT,
    ) : Part()

    @Serializable @SerialName("step-finish")
    data class StepFinish(
        override val id: PartId,
        override val messageId: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant,
        override val compactedAt: Instant? = null,
        val tokens: TokenUsage,
        val finish: FinishReason,
        /** Forward-compat discriminator. See [PartSchema]. */
        val schemaVersion: Int = PartSchema.CURRENT,
    ) : Part()

    @Serializable @SerialName("compaction")
    data class Compaction(
        override val id: PartId,
        override val messageId: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant,
        override val compactedAt: Instant? = null,
        val replacedFromMessageId: MessageId,
        val replacedToMessageId: MessageId,
        val summary: String,
        /** Forward-compat discriminator. See [PartSchema]. */
        val schemaVersion: Int = PartSchema.CURRENT,
    ) : Part()

    /**
     * Agent scratchpad for multi-step work. Emitted by the `todowrite` tool; each
     * call supersedes the previous list — the most recent `Part.Todos` in the
     * session is the current plan. OpenCode stores the same shape in its
     * `TodoTable` (`packages/opencode/src/session/todo.ts`); we ride the existing
     * Parts JSON-blob schema instead of minting a new table.
     */
    @Serializable @SerialName("todos")
    data class Todos(
        override val id: PartId,
        override val messageId: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant,
        override val compactedAt: Instant? = null,
        val todos: List<TodoInfo>,
        /** Forward-compat discriminator. See [PartSchema]. */
        val schemaVersion: Int = PartSchema.CURRENT,
    ) : Part()

    /**
     * Structured pre-commit plan of tool dispatches — VISION §5.4 dry-run-before-
     * execute UX. Emitted by the `draft_plan` tool with the agent-composed list
     * of steps it intends to take. The plan is **not** executed by persisting
     * this Part; it sits on the turn as a contract for the user to inspect.
     * Approval flows through the regular next-message loop: the user replies
     * "go ahead" and the agent proceeds with the underlying tool calls, updating
     * step [PlanStep.status] via subsequent `draft_plan` emissions as each step
     * moves PENDING → IN_PROGRESS → COMPLETED / FAILED.
     *
     * Distinct from [Todos] because each step carries a structured
     * `(toolName, inputSummary)` — the UI can render a reviewable "I'm about
     * to dispatch these 7 tool calls" panel rather than a free-text checklist.
     */
    @Serializable @SerialName("plan")
    data class Plan(
        override val id: PartId,
        override val messageId: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant,
        override val compactedAt: Instant? = null,
        val goalDescription: String,
        val steps: List<PlanStep>,
        val approvalStatus: PlanApprovalStatus = PlanApprovalStatus.PENDING_APPROVAL,
        /** Forward-compat discriminator. See [PartSchema]. */
        val schemaVersion: Int = PartSchema.CURRENT,
    ) : Part()
}

@Serializable
data class TodoInfo(
    val content: String,
    val status: TodoStatus = TodoStatus.PENDING,
    val priority: TodoPriority = TodoPriority.MEDIUM,
)

@Serializable
enum class TodoStatus {
    @SerialName("pending") PENDING,
    @SerialName("in_progress") IN_PROGRESS,
    @SerialName("completed") COMPLETED,
    @SerialName("cancelled") CANCELLED,
}

@Serializable
enum class TodoPriority {
    @SerialName("high") HIGH,
    @SerialName("medium") MEDIUM,
    @SerialName("low") LOW,
}

/**
 * One planned tool dispatch inside a [Part.Plan]. The agent populates
 * [toolName] + [inputSummary] (human-readable) so the user can scan the plan
 * without reading raw JSON; [status] tracks execution progress across
 * follow-up `draft_plan` emissions.
 *
 * @property step 1-based step number for readability (UI "Step 3 of 7").
 * @property toolName the tool id the agent intends to dispatch (e.g.
 *   `"generate_image"`, `"add_clip"`).
 * @property inputSummary a 1-line human summary of the key inputs
 *   (e.g. `"generate_image prompt=\"a dog\" 1024×1024"`). Callers
 *   shouldn't stuff the whole JSON here; the full inputs are re-derived
 *   when the agent actually dispatches the tool.
 * @property status tri+-state — mirrors [TodoStatus] plus an explicit
 *   `FAILED` so a broken step doesn't silently disappear under
 *   "cancelled". Default `PENDING`.
 * @property note optional free-text annotation; the agent may fill this
 *   with a reason ("skipping because asset already imported") that the
 *   user can see alongside the step.
 */
@Serializable
data class PlanStep(
    val step: Int,
    val toolName: String,
    val inputSummary: String,
    val status: PlanStepStatus = PlanStepStatus.PENDING,
    val note: String? = null,
    /**
     * VISION §5.4 expert-path batch-approval: the executable Input JSON
     * for this step. When non-null, `execute_plan(planId)` can dispatch
     * this step directly through [io.talevia.core.tool.ToolRegistry]
     * without going back to the LLM for input reconstruction. When null,
     * the step remains a preview-only recommendation — `execute_plan`
     * skips it with a diagnostic note. Agents drafting plans that should
     * be agent-executable should populate this with a concrete payload;
     * agents drafting plans for human review or for cases where inputs
     * depend on intermediate results can leave it null.
     */
    val input: JsonObject? = null,
)

@Serializable
enum class PlanStepStatus {
    @SerialName("pending") PENDING,
    @SerialName("in_progress") IN_PROGRESS,
    @SerialName("completed") COMPLETED,
    @SerialName("failed") FAILED,
    @SerialName("cancelled") CANCELLED,
}

@Serializable
enum class PlanApprovalStatus {
    /** Freshly drafted; waiting for the user to ack before execution. */
    @SerialName("pending_approval") PENDING_APPROVAL,

    /** User approved verbatim. Agent may proceed. */
    @SerialName("approved") APPROVED,

    /**
     * User approved after editing at least one step. Same semantics as
     * [APPROVED] for the agent; distinct state so UI can surface "this plan
     * diverges from the original draft".
     */
    @SerialName("approved_with_edits") APPROVED_WITH_EDITS,

    /** User rejected the plan; agent must not execute any step. */
    @SerialName("rejected") REJECTED,
}

@Serializable
sealed class ToolState {
    @Serializable @SerialName("pending")
    data object Pending : ToolState()

    @Serializable @SerialName("running")
    data class Running(val input: JsonElement) : ToolState()

    @Serializable @SerialName("completed")
    data class Completed(
        val input: JsonElement,
        val outputForLlm: String,
        val data: JsonElement,
        /**
         * Optional tool-author estimate of this result's LLM-context cost.
         * Mirrors [io.talevia.core.tool.ToolResult.estimatedTokens]; stamped
         * here so the value survives round-trips through the session store
         * and is available to [io.talevia.core.compaction.TokenEstimator.forPart].
         * Defaults to null so pre-existing serialised blobs still decode.
         */
        val estimatedTokens: Int? = null,
    ) : ToolState()

    @Serializable @SerialName("error")
    data class Failed(val input: JsonElement?, val message: String) : ToolState()

    /**
     * The tool dispatch was cancelled mid-flight (Agent.run cancelled via
     * `agent.cancel(sessionId)` / CLI Ctrl-C / `BusEvent.SessionCancelRequested`).
     * Distinct from [Failed] so downstream consumers can render
     * "cancelled" differently from "errored" (UI emoji, agent post-mortem
     * reasoning, audit logs) without parsing the [Failed.message] prefix.
     *
     * Stamped by [io.talevia.core.agent.finalizeCancelled] for any
     * `Pending` / `Running` Tool part on the cancelled assistant message.
     * The optional [input] preserves whatever the LLM had streamed up to
     * the cancel point (null when the part was still in `Pending` —
     * arguments hadn't streamed yet); [message] carries the cancel
     * reason from `CancellationException.message` when available.
     */
    @Serializable @SerialName("cancelled")
    data class Cancelled(val input: JsonElement?, val message: String) : ToolState()
}
