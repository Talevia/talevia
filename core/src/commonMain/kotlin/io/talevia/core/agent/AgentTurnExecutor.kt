package io.talevia.core.agent

import io.talevia.core.CallId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.metrics.MetricsRegistry
import io.talevia.core.permission.PermissionService
import io.talevia.core.provider.LlmEvent
import io.talevia.core.provider.LlmProvider
import io.talevia.core.provider.LlmRequest
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.MessageWithParts
import io.talevia.core.session.Part
import io.talevia.core.session.SessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.session.ToolState
import io.talevia.core.tool.ToolAvailabilityContext
import io.talevia.core.tool.ToolRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.datetime.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Result of a single provider turn (one streamed request/response cycle).
 * Produced by [AgentTurnExecutor.streamTurn] and consumed by `Agent.runLoop`
 * to decide whether to loop for tool results, retry on transient errors, or
 * exit. Extracted from `Agent.kt` alongside [PendingToolCall] when that file
 * crossed the 500-line long-file threshold — see
 * `docs/decisions/2026-04-21-debt-split-agent-kt.md`.
 */
internal data class TurnResult(
    val finish: FinishReason,
    val usage: TokenUsage,
    val error: String?,
    val retriable: Boolean = false,
    val retryAfterMs: Long? = null,
    val emittedContent: Boolean = false,
)

/** Bookkeeping for a tool call whose arguments are still streaming in. */
internal data class PendingToolCall(val partId: PartId, val toolId: String)

/**
 * Drives a single provider turn plus the tool dispatches it fans out.
 * `Agent.runLoop` holds the multi-step orchestration (history snapshot,
 * compaction, retry, finish handling); this executor only owns one turn
 * worth of work.
 *
 * Split out from `Agent.kt` so each file stays under R.5.3's 500-line
 * threshold — see `docs/decisions/2026-04-21-debt-split-agent-kt.md`.
 * Behavior is byte-identical to the old private methods; no LLM-visible
 * contract changes.
 */
internal class AgentTurnExecutor(
    /**
     * Ordered provider chain. Index 0 is the primary — the Agent's retry
     * loop iterates this list after exhausting retries on the current
     * provider (provider-auto-fallback). Must be non-empty.
     */
    private val providers: List<LlmProvider>,
    private val registry: ToolRegistry,
    private val permissions: PermissionService,
    private val store: SessionStore,
    private val bus: EventBus,
    private val clock: Clock,
    private val metrics: MetricsRegistry?,
    private val systemPrompt: String?,
    /**
     * Optional project store used to enrich [io.talevia.core.tool.ToolAvailabilityContext]
     * for [io.talevia.core.tool.ToolApplicability.RequiresAssets] checks. When null,
     * asset-dependent tools stay hidden whenever a project is bound but we can't cheaply
     * confirm it has assets — conservative but correct.
     */
    private val projects: io.talevia.core.domain.ProjectStore? = null,
) {
    init {
        require(providers.isNotEmpty()) { "AgentTurnExecutor requires at least one provider" }
    }

    /** Exposed so the Agent can emit fallback events with meaningful provider ids. */
    fun providerIdAt(index: Int): String = providers[index].id

    /** Chain size — Agent's retry loop uses this to know when fallback is exhausted. */
    val providerCount: Int get() = providers.size

    @OptIn(ExperimentalUuidApi::class)
    suspend fun streamTurn(
        asstMsg: Message.Assistant,
        history: List<MessageWithParts>,
        input: RunInput,
        currentProjectId: ProjectId?,
        providerIndex: Int = 0,
        spendCapCents: Long? = null,
        disabledToolIds: Set<String> = emptySet(),
        /**
         * Most-recent retry attempt number that fired during the enclosing
         * `Agent.run` (see `RunHandle.lastRetryAttempt`), or null if no retry
         * has fired yet. Threaded onto every [BusEvent.AgentRunStateChanged]
         * this turn emits so SSE / CLI subscribers can pair the coarse
         * `Generating → AwaitingTool → Generating` sequence with the retry
         * that preceded it.
         */
        retryAttempt: Int? = null,
    ): TurnResult {
        val projectSnapshot = currentProjectId?.let { projects?.get(it) }
        val projectHasAssets = projectSnapshot?.assets?.isNotEmpty() == true
        // Greenfield = no authored structure yet (no tracks, no source nodes).
        // Imported assets alone don't disqualify — the onboarding lane's
        // advice (scaffold a style_bible first) still applies when the user
        // has imported raw footage but hasn't yet declared creative intent.
        val projectIsGreenfield = projectSnapshot != null &&
            projectSnapshot.timeline.tracks.isEmpty() &&
            projectSnapshot.source.nodes.isEmpty()
        val request = LlmRequest(
            model = input.model,
            messages = history,
            tools = registry.specs(
                ToolAvailabilityContext(
                    currentProjectId = currentProjectId,
                    projectHasAssets = projectHasAssets,
                    disabledToolIds = disabledToolIds,
                ),
            ),
            systemPrompt = buildSystemPrompt(
                base = systemPrompt,
                currentProjectId = currentProjectId,
                sessionId = input.sessionId,
                projectIsGreenfield = projectIsGreenfield,
            ),
            // Seed the OpenAI cache-routing hint from the session id so every
            // turn in a session hits the same replica — unless the caller
            // already picked a key.
            options = input.options.copy(
                openaiPromptCacheKey = input.options.openaiPromptCacheKey ?: input.sessionId.value,
            ),
        )

        var finish: FinishReason? = null
        var usage = TokenUsage.ZERO
        var error: String? = null
        var retriable = false
        var retryAfterMs: Long? = null
        var emittedContent = false
        val pending = mutableMapOf<CallId, PendingToolCall>()

        // Parallel tool dispatch (§5.2 / §5.4). Providers emit multiple
        // `ToolCallReady` events per step when the model requests several
        // independent tool calls; serialising them inflates turn latency to
        // sum(tool_i) instead of max(tool_i). We launch each dispatch as a
        // child coroutine of `supervisorScope` so one tool's failure doesn't
        // cancel siblings, then `joinAll` before reporting TurnResult. The
        // permission-check phase is serialised by `permissionMutex` so
        // interactive prompts never race for the same terminal.
        val dispatchJobs = mutableListOf<Job>()
        val permissionMutex = Mutex()

        supervisorScope {
            providers[providerIndex].stream(request).collect { event ->
                when (event) {
                    is LlmEvent.TextStart -> {
                        emittedContent = true
                        store.upsertPart(
                            Part.Text(event.partId, asstMsg.id, asstMsg.sessionId, clock.now(), text = ""),
                        )
                    }
                    is LlmEvent.TextDelta -> bus.publish(
                        BusEvent.PartDelta(asstMsg.sessionId, asstMsg.id, event.partId, "text", event.text),
                    )
                    is LlmEvent.TextEnd -> store.upsertPart(
                        Part.Text(event.partId, asstMsg.id, asstMsg.sessionId, clock.now(), text = event.finalText),
                    )

                    is LlmEvent.ReasoningStart -> {
                        emittedContent = true
                        store.upsertPart(
                            Part.Reasoning(event.partId, asstMsg.id, asstMsg.sessionId, clock.now(), text = ""),
                        )
                    }
                    is LlmEvent.ReasoningDelta -> bus.publish(
                        BusEvent.PartDelta(asstMsg.sessionId, asstMsg.id, event.partId, "text", event.text),
                    )
                    is LlmEvent.ReasoningEnd -> store.upsertPart(
                        Part.Reasoning(event.partId, asstMsg.id, asstMsg.sessionId, clock.now(), text = event.finalText),
                    )

                    is LlmEvent.ToolCallStart -> {
                        emittedContent = true
                        pending[event.callId] = PendingToolCall(event.partId, event.toolId)
                        store.upsertPart(
                            Part.Tool(
                                id = event.partId,
                                messageId = asstMsg.id,
                                sessionId = asstMsg.sessionId,
                                createdAt = clock.now(),
                                callId = event.callId,
                                toolId = event.toolId,
                                state = ToolState.Pending,
                            ),
                        )
                    }
                    is LlmEvent.ToolCallInputDelta -> bus.publish(
                        BusEvent.PartDelta(asstMsg.sessionId, asstMsg.id, event.partId, "input", event.jsonDelta),
                    )
                    is LlmEvent.ToolCallReady -> {
                        // Resolve the pending handle on the stream thread so
                        // dispatchTool never touches the shared `pending` map
                        // from a launched child (avoids an MT map race).
                        val handle = pending[event.callId]
                            ?: PendingToolCall(event.partId, event.toolId).also {
                                pending[event.callId] = it
                            }
                        dispatchJobs += launch {
                            dispatchToolCall(
                                registry = registry,
                                permissions = permissions,
                                store = store,
                                bus = bus,
                                clock = clock,
                                metrics = metrics,
                                asstMsg = asstMsg,
                                history = history,
                                input = input,
                                event = event,
                                handle = handle,
                                currentProjectId = currentProjectId,
                                spendCapCents = spendCapCents,
                                permissionMutex = permissionMutex,
                                retryAttempt = retryAttempt,
                            )
                        }
                    }

                    LlmEvent.StepStart -> store.upsertPart(
                        Part.StepStart(
                            id = PartId(Uuid.random().toString()),
                            messageId = asstMsg.id,
                            sessionId = asstMsg.sessionId,
                            createdAt = clock.now(),
                        ),
                    )
                    is LlmEvent.StepFinish -> {
                        finish = event.finish
                        usage = event.usage
                        store.upsertPart(
                            Part.StepFinish(
                                id = PartId(Uuid.random().toString()),
                                messageId = asstMsg.id,
                                sessionId = asstMsg.sessionId,
                                createdAt = clock.now(),
                                tokens = event.usage,
                                finish = event.finish,
                            ),
                        )
                        metrics?.let { m ->
                            val pid = input.model.providerId
                            m.increment("provider.$pid.tokens.input", event.usage.input)
                            m.increment("provider.$pid.tokens.output", event.usage.output)
                            m.increment("provider.$pid.tokens.cache_read", event.usage.cacheRead)
                            m.increment("provider.$pid.tokens.cache_write", event.usage.cacheWrite)
                        }
                    }
                    is LlmEvent.Error -> {
                        error = event.message
                        retriable = event.retriable
                        retryAfterMs = event.retryAfterMs
                        finish = FinishReason.ERROR
                    }
                }
            }
            // Await every launched dispatch before reporting the turn. The
            // outer Agent loop relies on `TurnResult` signalling that *all*
            // tool Parts for this turn have landed in the SessionStore, so
            // the next turn's history snapshot sees tool results rather than
            // unresolved Pending parts.
            dispatchJobs.joinAll()
        }

        return TurnResult(
            finish = finish ?: FinishReason.STOP,
            usage = usage,
            error = error,
            retriable = retriable,
            retryAfterMs = retryAfterMs,
            emittedContent = emittedContent,
        )
    }
}
