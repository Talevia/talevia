package io.talevia.core.agent

import io.talevia.core.CallId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.compaction.Compactor
import io.talevia.core.compaction.TokenEstimator
import io.talevia.core.logging.Loggers
import io.talevia.core.logging.info
import io.talevia.core.logging.warn
import io.talevia.core.metrics.MetricsRegistry
import io.talevia.core.permission.PermissionRequest
import io.talevia.core.permission.PermissionRule
import io.talevia.core.permission.PermissionService
import io.talevia.core.provider.LlmEvent
import io.talevia.core.provider.LlmProvider
import io.talevia.core.provider.LlmRequest
import io.talevia.core.provider.ProviderOptions
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.MessageWithParts
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.SessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.session.ToolState
import io.talevia.core.tool.RegisteredTool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Drives the LLM ↔ Tool conversation loop. Behaviour models OpenCode's `runLoop`
 * (`packages/opencode/src/session/prompt.ts:1305-1533`):
 *
 *  while step < maxSteps:
 *    1. snapshot history from the store
 *    2. if the latest assistant message has a non-tool-calls finish, stop
 *    3. spawn a new assistant message and stream events from the provider
 *    4. for every tool call: check permission, dispatch, persist result
 *    5. if finish == tool-calls, loop so the next assistant turn can consume results
 *
 * All persistence and event publication goes through [SessionStore]; the Agent
 * itself never touches the database or the bus directly.
 */
class Agent(
    private val provider: LlmProvider,
    private val registry: ToolRegistry,
    private val store: SessionStore,
    private val permissions: PermissionService,
    private val bus: EventBus,
    private val clock: Clock = Clock.System,
    private val maxSteps: Int = 25,
    private val systemPrompt: String? = null,
    private val json: Json = JsonConfig.default,
    /**
     * Optional compaction hook. When set, the Agent estimates the current history
     * before each LLM turn and calls [Compactor.process] once the estimate crosses
     * [compactionTokenThreshold] (OpenCode uses ~85 % of the model's context
     * window; ~120k for 200k-context Claude models is roughly equivalent).
     */
    private val compactor: Compactor? = null,
    private val compactionTokenThreshold: Int = 120_000,
    /**
     * Optional session titler. When set, the Agent fires title generation on the
     * [backgroundScope] the first time a session receives user input. A placeholder
     * title (`"Untitled"`, `"New session"`, blank) is required — caller-provided
     * titles are never overwritten.
     */
    private val titler: SessionTitler? = null,
    /**
     * Scope used for fire-and-forget work that must outlive a single [run] but
     * can be cancelled when the owning Agent is thrown away. Defaults to an
     * independent supervisor scope; composition roots that already manage a
     * long-lived scope may wire their own.
     */
    private val backgroundScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /**
     * Optional latency registry. When provided, [run] records total agent-run latency
     * under `agent.run.ms` and each tool dispatch under `tool.<id>.ms`.
     */
    private val metrics: MetricsRegistry? = null,
    /**
     * Retry policy for transient provider errors (HTTP 5xx, 429, overload, rate limit).
     * Mirrors OpenCode's `session/retry.ts`. Defaults to [RetryPolicy.Default] —
     * 4 attempts total with exponential backoff. Set to [RetryPolicy.None] to disable.
     */
    private val retryPolicy: RetryPolicy = RetryPolicy.Default,
) {

    private val log = Loggers.get("agent")

    /**
     * Per-session handle tracking an in-flight [run] so [cancel] can reach into
     * the running coroutine and finalise the current assistant message with
     * [FinishReason.CANCELLED] rather than letting it hang at `finish = null`.
     */
    private class RunHandle(val job: Job) {
        @Volatile var currentAssistantId: MessageId? = null
    }

    private val inflightMutex = Mutex()
    private val inflight = mutableMapOf<SessionId, RunHandle>()

    /**
     * Cancel any in-flight [run] for [sessionId]. Returns true if a run was
     * found and cancelled; false if no run was in flight. Safe to call from
     * any coroutine.
     */
    suspend fun cancel(sessionId: SessionId): Boolean {
        val handle = inflightMutex.withLock { inflight[sessionId] } ?: return false
        handle.job.cancel(CancellationException("Agent.cancel($sessionId)"))
        return true
    }

    /** True while [run] for [sessionId] is executing. */
    suspend fun isRunning(sessionId: SessionId): Boolean =
        inflightMutex.withLock { sessionId in inflight }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun run(input: RunInput): Message.Assistant {
        val thisJob = currentCoroutineContext()[Job]
            ?: error("Agent.run must be called from a coroutine with a Job")
        val handle = RunHandle(thisJob)
        inflightMutex.withLock {
            check(input.sessionId !in inflight) {
                "Session ${input.sessionId} already has an in-flight Agent.run; cancel it first"
            }
            inflight[input.sessionId] = handle
        }

        val runStart = clock.now()
        log.info(
            "run.start",
            "session" to input.sessionId.value,
            "model" to "${input.model.providerId}/${input.model.modelId}",
            "promptLen" to input.text.length,
        )
        bus.publish(BusEvent.AgentRunStateChanged(input.sessionId, AgentRunState.Generating))
        try {
            val result = runLoop(input, handle)
            log.info(
                "run.finish",
                "session" to input.sessionId.value,
                "finish" to result.finish,
                "input" to result.tokens.input,
                "output" to result.tokens.output,
                "ms" to (clock.now() - runStart).inWholeMilliseconds,
                "error" to result.error,
            )
            val terminal = if (result.error != null) AgentRunState.Failed(result.error!!) else AgentRunState.Idle
            bus.publish(BusEvent.AgentRunStateChanged(input.sessionId, terminal))
            return result
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                finalizeCancelled(handle, e.message)
                bus.publish(BusEvent.SessionCancelled(input.sessionId))
                bus.publish(BusEvent.AgentRunStateChanged(input.sessionId, AgentRunState.Cancelled))
            }
            log.info("run.cancelled", "session" to input.sessionId.value, "reason" to e.message)
            throw e
        } catch (t: Throwable) {
            bus.publish(
                BusEvent.AgentRunStateChanged(
                    input.sessionId,
                    AgentRunState.Failed(t.message ?: t::class.simpleName ?: "unknown"),
                ),
            )
            throw t
        } finally {
            metrics?.observe("agent.run.ms", (clock.now() - runStart).inWholeMilliseconds)
            inflightMutex.withLock { inflight.remove(input.sessionId) }
        }
    }

    private suspend fun finalizeCancelled(handle: RunHandle, reason: String?) {
        val mid = handle.currentAssistantId ?: return
        val existing = runCatching { store.getMessage(mid) }.getOrNull() as? Message.Assistant ?: return
        // Avoid overwriting a finish that already landed (race with streamTurn).
        if (existing.finish != null) return
        val cancelled = existing.copy(
            finish = FinishReason.CANCELLED,
            error = reason ?: "cancelled",
        )
        runCatching { store.updateMessage(cancelled) }
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun runLoop(input: RunInput, handle: RunHandle): Message.Assistant {
        val now = clock.now()

        // First-turn check has to read the store BEFORE we append this turn's user
        // message so "no prior user messages" really means "session is empty".
        val isFirstTurn = store.listMessages(input.sessionId).none { it is Message.User }

        // Append user message + a single text part to record the prompt.
        val userMsg = Message.User(
            id = MessageId(Uuid.random().toString()),
            sessionId = input.sessionId,
            createdAt = now,
            agent = input.agentName,
            model = input.model,
        )
        store.appendMessage(userMsg)
        store.upsertPart(
            Part.Text(
                id = PartId(Uuid.random().toString()),
                messageId = userMsg.id,
                sessionId = input.sessionId,
                createdAt = now,
                text = input.text,
            ),
        )

        if (isFirstTurn && titler != null) {
            backgroundScope.launch {
                runCatching { titler.generate(input.sessionId, input.text) }
            }
        }

        var latestAssistant: Message.Assistant? = null
        var step = 0

        while (step < maxSteps) {
            step++

            var history = store.listMessagesWithParts(input.sessionId, includeCompacted = false)
            log.info(
                "step",
                "session" to input.sessionId.value,
                "n" to step,
                "historyMessages" to history.size,
            )

            // Compaction hook: before asking the provider for another turn, estimate
            // token usage and let the Compactor prune + summarise if we are over budget.
            // The post-process history is re-read from the store because Compactor
            // writes a new CompactionPart and marks older parts compacted.
            if (compactor != null) {
                val estimated = TokenEstimator.forHistory(history)
                if (estimated > compactionTokenThreshold) {
                    // Publish before kicking off compaction — subscribers (UI, SSE clients)
                    // can render "compacting…" while the summarisation call is in flight,
                    // instead of watching the next turn look stuck.
                    bus.publish(
                        BusEvent.SessionCompactionAuto(
                            sessionId = input.sessionId,
                            historyTokensBefore = estimated,
                            thresholdTokens = compactionTokenThreshold,
                        ),
                    )
                    bus.publish(BusEvent.AgentRunStateChanged(input.sessionId, AgentRunState.Compacting))
                    compactor.process(input.sessionId, history, input.model)
                    history = store.listMessagesWithParts(input.sessionId, includeCompacted = false)
                    bus.publish(BusEvent.AgentRunStateChanged(input.sessionId, AgentRunState.Generating))
                }
            }

            // Re-read the session every step so a `switch_project` invoked in the
            // previous turn is reflected in the next turn's system prompt and in
            // the ToolContext seen by freshly dispatched tools (VISION §5.4).
            val currentProjectId = store.getSession(input.sessionId)?.currentProjectId

            var attempt = 0
            var asstMsg: Message.Assistant
            var turnResult: TurnResult
            while (true) {
                attempt++
                asstMsg = Message.Assistant(
                    id = MessageId(Uuid.random().toString()),
                    sessionId = input.sessionId,
                    createdAt = clock.now(),
                    parentId = userMsg.id,
                    model = input.model,
                )
                store.appendMessage(asstMsg)
                handle.currentAssistantId = asstMsg.id

                turnResult = streamTurn(asstMsg, history, input, currentProjectId)

                // Only retry when the turn failed and nothing useful was streamed.
                // Mid-stream errors (rare — an error event after text/tool_calls) are
                // preserved so the user sees the partial output.
                if (turnResult.finish != FinishReason.ERROR) break
                if (turnResult.emittedContent) break
                if (attempt >= retryPolicy.maxAttempts) break
                val reason = RetryClassifier.reason(turnResult.error, turnResult.retriable) ?: break

                val wait = retryPolicy.delayFor(attempt, turnResult.retryAfterMs)
                log.info(
                    "retry.scheduled",
                    "session" to input.sessionId.value,
                    "attempt" to attempt,
                    "waitMs" to wait,
                    "reason" to reason,
                )
                bus.publish(
                    BusEvent.AgentRetryScheduled(
                        sessionId = input.sessionId,
                        attempt = attempt,
                        waitMs = wait,
                        reason = reason,
                    ),
                )
                // Wipe the failed assistant message (+ its StepFinish(ERROR) part)
                // so the retry produces a clean single message per turn.
                runCatching { store.deleteMessage(asstMsg.id) }
                delay(wait)
            }

            val finalised = asstMsg.copy(
                tokens = turnResult.usage,
                finish = turnResult.finish,
                error = turnResult.error,
            )
            store.updateMessage(finalised)
            latestAssistant = finalised

            if (turnResult.finish != FinishReason.TOOL_CALLS) break
        }

        return latestAssistant ?: error("Agent.run produced no assistant message (maxSteps=$maxSteps)")
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun streamTurn(
        asstMsg: Message.Assistant,
        history: List<MessageWithParts>,
        input: RunInput,
        currentProjectId: ProjectId?,
    ): TurnResult {
        val request = LlmRequest(
            model = input.model,
            messages = history,
            tools = registry.specs(),
            systemPrompt = buildSystemPrompt(systemPrompt, currentProjectId),
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

        provider.stream(request).collect { event ->
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

    /**
     * Prepend a one-line "Current project" banner to the configured system prompt
     * so every turn reminds the model of the session's current binding (VISION §5.4).
     * A null binding renders explicitly so the agent knows to pick one before
     * dispatching timeline tools, rather than guessing silently.
     */
    private fun buildSystemPrompt(base: String?, currentProjectId: ProjectId?): String? {
        val banner = if (currentProjectId != null) {
            "Current project: ${currentProjectId.value} (from session binding; call switch_project to change)"
        } else {
            "Current project: <none> (session not yet bound; call list_projects / create_project / switch_project before running timeline tools)"
        }
        return when {
            base == null -> banner
            base.isBlank() -> banner
            else -> "$banner\n\n$base"
        }
    }

    private data class TurnResult(
        val finish: FinishReason,
        val usage: TokenUsage,
        val error: String?,
        val retriable: Boolean = false,
        val retryAfterMs: Long? = null,
        val emittedContent: Boolean = false,
    )
    private data class PendingToolCall(val partId: PartId, val toolId: String)
}

data class RunInput(
    val sessionId: SessionId,
    val text: String,
    val model: ModelRef,
    val agentName: String = "default",
    val permissionRules: List<PermissionRule> = emptyList(),
    val options: ProviderOptions = ProviderOptions(),
)
