package io.talevia.core.agent

import io.talevia.core.CallId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.logging.Loggers
import io.talevia.core.logging.info
import io.talevia.core.logging.warn
import io.talevia.core.metrics.MetricsRegistry
import io.talevia.core.permission.PermissionRequest
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
import io.talevia.core.tool.RegisteredTool
import io.talevia.core.tool.ToolAvailabilityContext
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import kotlinx.coroutines.flow.collect
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
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
 * Prepend a two-line identity banner ("Current project" + "Current session")
 * to the configured system prompt so every turn reminds the model of the
 * session's current binding (VISION §5.4) *and* the session id it should pass
 * to tools that require one (switch_project, fork_session, etc.). Without the
 * session line the model tends to invent ids like "current" or
 * "session-unknown", which every session-scoped tool correctly rejects.
 *
 * The project line comes first so existing `startsWith("Current project: …")`
 * assertions keep matching. A null project renders explicitly so the agent
 * knows to pick one before dispatching timeline tools rather than guessing.
 *
 * Pure function — package-private so both [Agent] and [AgentTurnExecutor] can
 * reach it without exposing it on the public surface.
 */
internal fun buildSystemPrompt(
    base: String?,
    currentProjectId: ProjectId?,
    sessionId: SessionId?,
): String? {
    val projectLine = if (currentProjectId != null) {
        "Current project: ${currentProjectId.value} (from session binding; call switch_project to change)"
    } else {
        "Current project: <none> (session not yet bound; call list_projects / create_project / switch_project before running timeline tools)"
    }
    val sessionLine = sessionId?.let {
        "Current session: ${it.value} (pass this exact id as `sessionId` whenever a tool requires one; never invent one)"
    }
    val banner = listOfNotNull(projectLine, sessionLine).joinToString("\n")
    return when {
        base == null -> banner
        base.isBlank() -> banner
        else -> "$banner\n\n$base"
    }
}

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
) {
    init {
        require(providers.isNotEmpty()) { "AgentTurnExecutor requires at least one provider" }
    }

    /** Exposed so the Agent can emit fallback events with meaningful provider ids. */
    fun providerIdAt(index: Int): String = providers[index].id

    /** Chain size — Agent's retry loop uses this to know when fallback is exhausted. */
    val providerCount: Int get() = providers.size
    private val log = Loggers.get("agent")

    @OptIn(ExperimentalUuidApi::class)
    suspend fun streamTurn(
        asstMsg: Message.Assistant,
        history: List<MessageWithParts>,
        input: RunInput,
        currentProjectId: ProjectId?,
        providerIndex: Int = 0,
    ): TurnResult {
        val request = LlmRequest(
            model = input.model,
            messages = history,
            tools = registry.specs(ToolAvailabilityContext(currentProjectId = currentProjectId)),
            systemPrompt = buildSystemPrompt(systemPrompt, currentProjectId, input.sessionId),
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

        providers[providerIndex].stream(request).collect { event ->
            when (event) {
                is LlmEvent.TextStart -> {
                    emittedContent = true
                    upsertEmptyText(asstMsg, event.partId)
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
                is LlmEvent.ToolCallReady -> dispatchTool(asstMsg, history, input, event, pending, currentProjectId)

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

        return TurnResult(
            finish = finish ?: FinishReason.STOP,
            usage = usage,
            error = error,
            retriable = retriable,
            retryAfterMs = retryAfterMs,
            emittedContent = emittedContent,
        )
    }

    private suspend fun upsertEmptyText(asstMsg: Message.Assistant, partId: PartId) {
        store.upsertPart(
            Part.Text(partId, asstMsg.id, asstMsg.sessionId, clock.now(), text = ""),
        )
    }

    private suspend fun dispatchTool(
        asstMsg: Message.Assistant,
        history: List<MessageWithParts>,
        input: RunInput,
        event: LlmEvent.ToolCallReady,
        pending: MutableMap<CallId, PendingToolCall>,
        currentProjectId: ProjectId?,
    ) {
        val handle = pending[event.callId]
            ?: PendingToolCall(event.partId, event.toolId).also { pending[event.callId] = it }

        val tool: RegisteredTool? = registry[event.toolId]
        val baseTime: Instant = clock.now()
        val basePart = Part.Tool(
            id = handle.partId,
            messageId = asstMsg.id,
            sessionId = asstMsg.sessionId,
            createdAt = baseTime,
            callId = event.callId,
            toolId = event.toolId,
            state = ToolState.Running(event.input),
        )
        store.upsertPart(basePart)
        // Coarse run-state: the Agent is suspended on a tool call. Fine-grained
        // per-call progress still flows through PartUpdated on the tool part.
        bus.publish(BusEvent.AgentRunStateChanged(asstMsg.sessionId, AgentRunState.AwaitingTool))

        if (tool == null) {
            store.upsertPart(basePart.copy(state = ToolState.Failed(event.input, "Unknown tool: ${event.toolId}")))
            bus.publish(BusEvent.AgentRunStateChanged(asstMsg.sessionId, AgentRunState.Generating))
            return
        }

        val pattern = tool.permission.patternFrom(event.input.toString())
        val decision = permissions.check(
            rules = input.permissionRules,
            request = PermissionRequest(
                sessionId = asstMsg.sessionId,
                permission = tool.permission.permission,
                pattern = pattern,
                metadata = mapOf("toolId" to event.toolId),
            ),
        )
        if (!decision.granted) {
            store.upsertPart(
                basePart.copy(
                    state = ToolState.Failed(event.input, "Permission denied: ${tool.permission.permission}"),
                ),
            )
            return
        }

        val ctx = ToolContext(
            sessionId = asstMsg.sessionId,
            messageId = asstMsg.id,
            callId = event.callId,
            askPermission = { req -> permissions.check(input.permissionRules, req) },
            emitPart = { p -> store.upsertPart(p) },
            messages = history,
            // A tool dispatched inside the same turn that `switch_project`
            // ran will observe the updated binding because we re-read the
            // session before each turn, not mid-turn; the next turn's
            // first tool call picks up the switch.
            currentProjectId = currentProjectId,
        )

        val toolStart = clock.now()
        val outcome = runCatching { tool.dispatch(event.input, ctx) }
        val toolMs = (clock.now() - toolStart).inWholeMilliseconds
        metrics?.observe("tool.${event.toolId}.ms", toolMs)
        outcome.fold(
            onSuccess = { result ->
                val data = tool.encodeOutput(result)
                store.upsertPart(
                    basePart.copy(
                        state = ToolState.Completed(event.input, result.outputForLlm, data),
                        title = result.title,
                    ),
                )
                log.info("tool.ok", "tool" to event.toolId, "callId" to event.callId.value, "ms" to toolMs)
            },
            onFailure = { e ->
                store.upsertPart(
                    basePart.copy(
                        state = ToolState.Failed(event.input, e.message ?: e::class.simpleName ?: "tool error"),
                    ),
                )
                log.warn(
                    "tool.fail",
                    "tool" to event.toolId,
                    "callId" to event.callId.value,
                    "ms" to toolMs,
                    "error" to (e.message ?: e::class.simpleName),
                    cause = e,
                )
            },
        )
        // Tool dispatch (success or failure) hands control back to the LLM —
        // next turn re-enters Generating. The terminal run-state is published
        // in run()'s outer try/finally.
        bus.publish(BusEvent.AgentRunStateChanged(asstMsg.sessionId, AgentRunState.Generating))
    }
}
