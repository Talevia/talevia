package io.talevia.core.bus

import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.agent.AgentRunState
import io.talevia.core.session.Message
import io.talevia.core.session.Part

/**
 * Events the Core publishes to UI / persistence subscribers.
 *
 * Event taxonomy mirrors OpenCode (`packages/opencode/src/bus/index.ts:19-41` and
 * `session/message-v2.ts:488-496`) — UI consumes deltas for streaming updates and
 * full part snapshots once a part finalises.
 */
sealed interface BusEvent {

    sealed interface SessionEvent : BusEvent {
        val sessionId: SessionId
    }

    data class SessionCreated(override val sessionId: SessionId) : SessionEvent
    data class SessionUpdated(override val sessionId: SessionId) : SessionEvent
    data class SessionDeleted(override val sessionId: SessionId) : SessionEvent

    data class MessageUpdated(
        override val sessionId: SessionId,
        val messageId: MessageId,
        val message: Message,
    ) : SessionEvent

    /**
     * A message (and all of its parts) was deleted from the session — currently
     * fired by `SessionRevert.revertToMessage` after truncating later turns.
     * UI consumers should drop any cached state keyed on [messageId] (rendered
     * markdown, in-flight tool spinners, etc.) so the on-screen view matches
     * persistence.
     */
    data class MessageDeleted(
        override val sessionId: SessionId,
        val messageId: MessageId,
    ) : SessionEvent

    /**
     * A [io.talevia.core.session.SessionRevert] pass committed: messages after
     * [anchorMessageId] have been removed and, if [appliedSnapshotPartId] is
     * non-null, the project's timeline was rolled back to that snapshot.
     * Emitted once per revert, after the individual [MessageDeleted] events —
     * UI can treat it as a signal to refresh the whole turn list.
     */
    data class SessionReverted(
        override val sessionId: SessionId,
        val projectId: ProjectId,
        val anchorMessageId: MessageId,
        val deletedMessages: Int,
        val appliedSnapshotPartId: PartId?,
    ) : SessionEvent

    data class PartUpdated(
        override val sessionId: SessionId,
        val messageId: MessageId,
        val partId: PartId,
        val part: Part,
    ) : SessionEvent

    /** Streaming append: `field` identifies which property is being incrementally built. */
    data class PartDelta(
        override val sessionId: SessionId,
        val messageId: MessageId,
        val partId: PartId,
        val field: String,
        val delta: String,
    ) : SessionEvent

    data class PermissionAsked(
        override val sessionId: SessionId,
        val requestId: String,
        val permission: String,
        val patterns: List<String>,
    ) : SessionEvent

    data class PermissionReplied(
        override val sessionId: SessionId,
        val requestId: String,
        val accepted: Boolean,
        val remembered: Boolean,
    ) : SessionEvent

    /** An in-flight Agent.run for this session was cancelled via [io.talevia.core.agent.Agent.cancel]. */
    data class SessionCancelled(override val sessionId: SessionId) : SessionEvent

    /**
     * The Agent caught a transient provider error and scheduled a retry.
     * [attempt] is 1-based — the upcoming attempt number — and [waitMs] is
     * the backoff the Agent is about to sleep before re-streaming.
     * UI can use this to show "Retrying in 4s…" rather than leaving the turn
     * looking stuck.
     */
    data class AgentRetryScheduled(
        override val sessionId: SessionId,
        val attempt: Int,
        val waitMs: Long,
        val reason: String,
    ) : SessionEvent

    /**
     * Primary provider exhausted its retry budget with no streamed content;
     * the Agent is falling through to the next provider in the configured
     * chain ([Agent.fallbackProviders]). Pair with [AgentRetryScheduled]:
     * retry covers same-provider transient failures, fallback covers
     * cross-provider ones. Emitted once per chain advance.
     *
     * Mid-stream failures (content already delivered to the user) do NOT
     * trigger fallback — the partial output is preserved and the turn is
     * marked ERROR so the user sees what came through rather than two
     * different providers' truncated outputs stitched together.
     */
    data class AgentProviderFallback(
        override val sessionId: SessionId,
        val fromProviderId: String,
        val toProviderId: String,
        val reason: String,
    ) : SessionEvent

    /**
     * Background agent runs (e.g. the server's fire-and-forget `agent.run` launch)
     * historically swallowed failures, leaving clients stuck on a 202 with no signal
     * that the run died. Publish this event instead so SSE subscribers see the
     * failure and the client can recover.
     */
    data class AgentRunFailed(
        override val sessionId: SessionId,
        val correlationId: String,
        val message: String,
    ) : SessionEvent

    /**
     * The Agent auto-triggered context compaction at the start of a turn because
     * the estimated token footprint of the surviving history crossed the
     * configured threshold. Emitted exactly once per auto-compaction pass,
     * **before** the Compactor re-reads the session. UI consumers can show
     * "compacting…" instead of letting the next turn look stuck while the
     * summarisation call is in flight.
     *
     * Manual compaction calls (e.g. an explicit `/compact` from the operator)
     * do **not** emit this event — it's specifically the overflow-auto-trigger
     * signal. Pair with [AgentRetryScheduled]: together they cover the two
     * visible pauses a long session can exhibit before a turn's streaming
     * resumes.
     *
     * @property historyTokensBefore TokenEstimator.forHistory result that
     *   crossed the threshold and caused the trigger.
     * @property thresholdTokens The threshold the estimate exceeded.
     */
    data class SessionCompactionAuto(
        override val sessionId: SessionId,
        val historyTokensBefore: Int,
        val thresholdTokens: Int,
    ) : SessionEvent

    /**
     * Explicit `Agent.run` state transition. See [AgentRunState] for the state
     * diagram and invariants. Emitted on every transition (including
     * `Idle → Generating` at run entry and the terminal transition to
     * `Idle / Cancelled / Failed`), so UI subscribers can render a live
     * status bar without polling.
     *
     * The finer-grained signals — `PartUpdated` for streaming text deltas,
     * `SessionCompactionAuto` for the token crossing that drove a
     * `Generating → Compacting` edge, `AgentRetryScheduled` for transient-
     * error retries — still fire independently. This event is the
     * "what high-level phase is the agent in?" coarse signal.
     */
    data class AgentRunStateChanged(
        override val sessionId: SessionId,
        val state: AgentRunState,
    ) : SessionEvent

    /**
     * The session's `currentProjectId` binding changed — fired exclusively
     * by `switch_project` after it persists the new binding. [previousProjectId]
     * is null when the session was previously unbound (first-time bind);
     * [newProjectId] is always set because unbinding is not an operation
     * exposed on the session lane (to unbind, the user creates a new session).
     *
     * UI / metrics subscribers can consume this to refresh the "current
     * project" indicator without polling `describe_session`, and to log
     * per-session project switches for observability.
     *
     * A no-op same-id rebind does NOT fire this event — see [SwitchProjectTool]
     * where the same-id branch returns early before `updateSession`. Fork does
     * not fire this event either: a freshly forked session inherits its
     * parent's binding through [SessionCreated] (the binding is the session's
     * initial state, not a change).
     */
    data class SessionProjectBindingChanged(
        override val sessionId: SessionId,
        val previousProjectId: ProjectId?,
        val newProjectId: ProjectId,
    ) : SessionEvent

    /**
     * An AIGC dispatch (image / TTS / music / video / upscale) completed and a
     * lockfile entry was recorded. Carries a best-effort cost estimate in
     * integer USD cents; [costCents] is `null` when
     * [io.talevia.core.cost.AigcPricing] has no rule for the provider + model
     * combination. Subscribers (metrics sink, UI spend indicator) aggregate
     * non-null values and surface "unknown cost" as a separate signal rather
     * than silently summing zero.
     *
     * Fired exclusively on cache **misses** (new generations). Cache hits
     * re-serve the already-billed asset and do not emit this event — the cost
     * was accounted for on the original generation.
     */
    data class AigcCostRecorded(
        override val sessionId: SessionId,
        val projectId: ProjectId,
        val toolId: String,
        val assetId: String,
        val costCents: Long?,
    ) : SessionEvent

    /**
     * A project load detected structural issues in its source DAG
     * (dangling parent refs, parent cycles). Non-throwing warning — the
     * project is still returned to the caller, but subscribers (UI,
     * metrics sink, audit log) can surface the issue so it doesn't rot
     * unnoticed. Emitted by `SqlDelightProjectStore.get` right after the
     * blob decode on every read; issues are computed via a lightweight
     * sublsubset of `ValidateProjectTool`'s DAG check (no full
     * clip/asset/audio validation, see that tool for exhaustive linting).
     *
     * Not a [SessionEvent] — validation runs at project load time,
     * independent of any active session. Each issue in [issues] is a
     * human-readable message that already includes the offending node id,
     * matching `Issue.message` shape from `ValidateProjectTool` so UI
     * consumers can render either source uniformly.
     */
    data class ProjectValidationWarning(
        val projectId: ProjectId,
        val issues: List<String>,
    ) : BusEvent
}
