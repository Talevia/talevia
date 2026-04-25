package io.talevia.core.bus

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.agent.AgentRunState
import io.talevia.core.session.Message
import io.talevia.core.session.Part

/**
 * Events the Core publishes to UI / persistence subscribers. Taxonomy mirrors
 * OpenCode (`packages/opencode/src/bus/index.ts:19-41` +
 * `session/message-v2.ts:488-496`).
 */
sealed interface BusEvent {

    sealed interface SessionEvent : BusEvent {
        val sessionId: SessionId
    }

    data class SessionCreated(override val sessionId: SessionId) : SessionEvent
    data class SessionUpdated(override val sessionId: SessionId) : SessionEvent
    data class SessionDeleted(override val sessionId: SessionId) : SessionEvent

    /**
     * `SqlDelightSessionStore.appendMessage` rejected because count would
     * exceed `maxMessages` cap. Backstops the `Compactor`-trigger gate when
     * compaction can't keep up. Fired alongside the rejection's
     * `IllegalStateException` so UIs can surface "fork or revert" without
     * polling. `messageCount` is pre-rejection; usually `== cap`.
     */
    data class SessionFull(
        override val sessionId: SessionId,
        val messageCount: Int,
        val cap: Int,
    ) : SessionEvent

    data class MessageUpdated(
        override val sessionId: SessionId,
        val messageId: MessageId,
        val message: Message,
    ) : SessionEvent

    /** Fired by `SessionRevert.revertToMessage` after truncating later turns. */
    data class MessageDeleted(
        override val sessionId: SessionId,
        val messageId: MessageId,
    ) : SessionEvent

    /**
     * `SessionRevert` pass committed: messages after `anchorMessageId` removed,
     * project timeline rolled back to `appliedSnapshotPartId` if non-null.
     * Fires once per revert AFTER the per-message `MessageDeleted` events —
     * UI can use it as the refresh-the-whole-turn-list signal.
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

    /** In-flight `Agent.run` for this session was cancelled via `Agent.cancel`. */
    data class SessionCancelled(override val sessionId: SessionId) : SessionEvent

    /**
     * Request that the Agent cancel an in-flight run. Any subscriber (CLI signal
     * handler, HTTP endpoint, IDE abort button) can publish without holding an
     * `Agent` reference; the Agent subscribes on construction. No-op against
     * idle sessions. Observable effects come via `SessionCancelled` +
     * `FinishReason.CANCELLED`, not this request event.
     */
    data class SessionCancelRequested(override val sessionId: SessionId) : SessionEvent

    /**
     * Agent caught a transient provider error and scheduled a retry. UI
     * surfaces "Retrying in {waitMs}ms…" instead of frozen turn. `attempt`
     * is 1-based (the upcoming attempt). `providerId` defaulted null for
     * pre-cycle-91 emitters that didn't thread the provider chain.
     */
    data class AgentRetryScheduled(
        override val sessionId: SessionId,
        val attempt: Int,
        val waitMs: Long,
        val reason: String,
        val providerId: String? = null,
    ) : SessionEvent

    /**
     * Primary provider exhausted retry budget with no streamed content; Agent
     * advances to next provider in `Agent.fallbackProviders`. Pair with
     * `AgentRetryScheduled` (same-provider transient) vs this (cross-provider).
     * Mid-stream failures do NOT trigger fallback — partial output preserved,
     * turn marked ERROR.
     */
    data class AgentProviderFallback(
        override val sessionId: SessionId,
        val fromProviderId: String,
        val toProviderId: String,
        val reason: String,
    ) : SessionEvent

    /**
     * Background agent runs (server's fire-and-forget `agent.run`) historically
     * swallowed failures, leaving SSE clients stuck on a 202. Publish this
     * instead so subscribers see the failure and the client can recover.
     */
    data class AgentRunFailed(
        override val sessionId: SessionId,
        val correlationId: String,
        val message: String,
    ) : SessionEvent

    /**
     * Agent auto-triggered context compaction at turn start because estimated
     * history tokens crossed the per-model threshold. Fires exactly once per
     * auto-pass, BEFORE Compactor re-reads. Manual `/compact` does NOT emit
     * this — it's specifically the overflow-auto-trigger. Pair with
     * `AgentRetryScheduled` for the two visible "stuck" pauses long sessions
     * exhibit.
     */
    data class SessionCompactionAuto(
        override val sessionId: SessionId,
        val historyTokensBefore: Int,
        val thresholdTokens: Int,
    ) : SessionEvent

    /**
     * Compaction pass FINISHED + summary part written. Fires once per
     * `Compactor.process()` returning `Compactor.Result.Compacted`. Pairs with
     * `SessionCompactionAuto` (which fires BEFORE the pass). Manual `/compact`
     * also emits this — it's the post-pass result, not the trigger. UI renders
     * "compacted N tool outputs, summary M chars" notice. `summaryLength` is
     * char count not tokens (no tokenizer dep needed).
     */
    data class SessionCompacted(
        override val sessionId: SessionId,
        val prunedCount: Int,
        val summaryLength: Int,
    ) : SessionEvent

    /**
     * `Agent.run` state transition (see `AgentRunState`). Emits on every edge
     * including run-entry (`Idle → Generating`) and terminal
     * (`→ Idle / Cancelled / Failed`). Coarse "what phase?" signal — finer
     * signals (`PartUpdated`, `SessionCompactionAuto`, `AgentRetryScheduled`)
     * still fire independently.
     *
     * `retryAttempt`: null until the first retry; thereafter echoes the most
     * recent `AgentRetryScheduled.attempt` and persists across subsequent
     * transitions for the rest of the run. Pairs each follow-up transition with
     * the retry that preceded it — subscribers can answer "did retry #N
     * succeed?" by watching the terminal transition. Monotonically increasing;
     * provider fallbacks do NOT reset (`AgentProviderFallback` carries that).
     */
    data class AgentRunStateChanged(
        override val sessionId: SessionId,
        val state: AgentRunState,
        val retryAttempt: Int? = null,
    ) : SessionEvent

    /**
     * Session's `currentProjectId` changed — fired by `switch_project` after
     * persisting. `previousProjectId` null = first-time bind. Same-id rebind
     * does NOT fire (early-return in tool). Fork does not fire either —
     * inheritance comes through `SessionCreated`.
     */
    data class SessionProjectBindingChanged(
        override val sessionId: SessionId,
        val previousProjectId: ProjectId?,
        val newProjectId: ProjectId,
    ) : SessionEvent

    /**
     * AIGC dispatch completed; lockfile entry recorded. `costCents` is best-
     * effort integer USD cents per `AigcPricing`; null = no rule for
     * provider+model. Subscribers (metrics, UI spend) sum non-null and surface
     * "unknown" separately. Fires only on cache MISS — hits re-serve already-
     * billed assets.
     */
    data class AigcCostRecorded(
        override val sessionId: SessionId,
        val projectId: ProjectId,
        val toolId: String,
        val assetId: String,
        val costCents: Long?,
    ) : SessionEvent

    /**
     * Soft warning when cumulative AIGC spend hits 0.8 × `spendCapCents` but
     * still strictly below cap. Fires before the hard `aigc.budget` ASK so
     * users get an "at 80 %" signal between "no awareness" and "hard stop".
     *
     * `scope` = `aigc` (in-session AigcBudgetGuard) or `export`
     * (ExportToolBudgetGuard) — same cap, different "current" measures.
     * Subscribers MAY rate-limit display (CLI debounces ~30s); the guard fires
     * every qualifying call (dedupe in the guard would couple guard to
     * subscriber lifecycle, breaking "publishers don't know subscribers").
     */
    data class SpendCapApproaching(
        override val sessionId: SessionId,
        val capCents: Long,
        val currentCents: Long,
        val ratio: Double,
        val scope: String,
        val toolId: String,
    ) : SessionEvent

    /**
     * Cache-lookup outcome from `AigcPipeline.findCached`. Fires once per AIGC
     * tool invocation BEFORE the provider call; hit short-circuits without
     * `AigcCostRecorded`, miss pairs later with one on success. Not a
     * `SessionEvent` — probe is project-scoped, doesn't care about session.
     */
    data class AigcCacheProbe(
        val toolId: String,
        val hit: Boolean,
    ) : BusEvent

    /**
     * Project load detected DAG issues (dangling parent refs, cycles).
     * Non-throwing — project still returned. `FileProjectStore.get` emits on
     * every read; issues come from a lightweight subset of
     * `project_query(select=validation)`. Each `issues` entry is human-readable
     * + includes the offending node id (matches the validation tool's `Issue`
     * shape so UIs render uniformly).
     */
    data class ProjectValidationWarning(
        val projectId: ProjectId,
        val issues: List<String>,
    ) : BusEvent

    /**
     * Cross-machine bundle-open failure: ≥ 1 asset references a
     * `MediaSource.File` absolute path that doesn't exist on this machine.
     * UI shows "relink your source footage" panel; CLI can block / warn before
     * `export`. Only fires when at least one asset is missing.
     * `relink_asset(assetId, newPath)` cascades to every other asset sharing
     * the same original path so the user only relinks once per source file.
     * Not fired for `BundleFile` / `Http` / `Platform` sources.
     */
    data class AssetsMissing(
        val projectId: ProjectId,
        val missing: List<MissingAsset>,
    ) : BusEvent

    data class MissingAsset(
        val assetId: String,
        val originalPath: String,
    )

    /**
     * AIGC provider warmup signal — the connection / model load / seed-pinning
     * handshake that precedes the first useful byte. Published in
     * `Starting → Ready` pairs around the first HTTP round trip; later calls in
     * the same dispatch don't re-emit (cold-start signal, not per-poll).
     *
     * Why: M2 exit summary §3.1 follow-up #4 — first image used to sit silent
     * for 2-20s with no UI signal. Every other visible pause has a bus event;
     * warmup was the last unvoiced one. CLI / Desktop render `Starting` as
     * `warming up <providerId>…` and suppress `Ready` (redundant once
     * streaming resumes).
     *
     * Phase is a 2-state enum (not a sealed payload) — neither edge carries
     * extra data; epochMs + providerId + sessionId are sufficient for the
     * "time to first useful byte" metric. Replicate-backed engines emit;
     * OpenAI's synchronous `/v1/images/generations` doesn't (no warmup/streaming
     * split).
     */
    data class ProviderWarmup(
        override val sessionId: SessionId,
        val providerId: String,
        val phase: Phase,
        val epochMs: Long,
    ) : SessionEvent {
        enum class Phase { Starting, Ready }
    }

    /**
     * AIGC dispatch progress signal. Fires from `AigcPipeline.withProgress` at
     * `Phase.Started` (ratio=0), terminal `Phase.Completed` (ratio=1) or
     * `Phase.Failed` (ratio=0). Same lifecycle as `Part.RenderProgress` but on
     * the bus instead of session-history.
     *
     * Why duplicate progress across Part + BusEvent: `Part.RenderProgress` is
     * session-history persistent (every tick lands in `messages_parts.data` so
     * revert / replay / cross-machine re-runs see it; CLI Renderer paints from
     * those parts). `AigcJobProgress` is ephemeral with no session-store write
     * — subscribers that don't want every tick polluting Part history (metrics,
     * desktop in-flight panel, server SSE, ops dashboards) listen here instead.
     * CLI does NOT subscribe (the existing Part path drives its progress
     * line; double-subscribing would render twice).
     *
     * `ratio` 0..1 clamped at publisher; null reserved for opaque "still
     * working" pings (none today). `etaSec` same null-when-unknown convention.
     * `providerId` non-null when engine knows it (cache hits short-circuit
     * before engine call).
     */
    data class AigcJobProgress(
        override val sessionId: SessionId,
        val callId: CallId,
        val toolId: String,
        val jobId: String,
        val phase: AigcProgressPhase,
        val ratio: Float? = null,
        val etaSec: Int? = null,
        val message: String? = null,
        val providerId: String? = null,
    ) : SessionEvent

    enum class AigcProgressPhase { Started, Progress, Completed, Failed }
}
