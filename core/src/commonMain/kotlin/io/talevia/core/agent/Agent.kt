package io.talevia.core.agent

import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.compaction.Compactor
import io.talevia.core.compaction.DEFAULT_COMPACTION_TOKEN_THRESHOLD
import io.talevia.core.logging.Loggers
import io.talevia.core.logging.info
import io.talevia.core.metrics.MetricsRegistry
import io.talevia.core.permission.PermissionRule
import io.talevia.core.permission.PermissionService
import io.talevia.core.provider.LlmProvider
import io.talevia.core.provider.ProviderOptions
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
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
 *
 * Per-turn streaming and tool dispatch live in [AgentTurnExecutor] so this
 * file stays focused on multi-step orchestration (compaction, retry, cancel,
 * state-machine transitions). See
 * `docs/decisions/2026-04-21-debt-split-agent-kt.md`.
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
     * Ordered fallback providers. When the primary ([provider]) exhausts its
     * retry budget within a turn AND has streamed zero content, the Agent
     * advances to the first fallback and restarts the retry loop fresh.
     * Empty list → retry-only behavior (unchanged from pre-fallback). The
     * composition root typically passes `registry.all() - provider` so every
     * configured provider becomes a fallback automatically.
     *
     * Mid-stream failures do NOT trigger fallback — preserving the partial
     * output the user already saw is better than switching providers and
     * emitting a second half that mentions different prior context.
     */
    private val fallbackProviders: List<LlmProvider> = emptyList(),
    /**
     * Optional compaction hook. When set, the Agent estimates the current history
     * before each LLM turn and calls [Compactor.process] once the estimate crosses
     * the per-model threshold returned by [compactionThreshold].
     */
    private val compactor: Compactor? = null,
    /**
     * Resolves the auto-compaction threshold for the model being used on a
     * given turn. Invoked with `input.model` on every step. Default returns
     * [DEFAULT_COMPACTION_TOKEN_THRESHOLD] (legacy one-size-fits-all behavior);
     * composition roots that want per-model thresholds pass a
     * [io.talevia.core.compaction.PerModelCompactionThreshold] resolver keyed
     * off the wired provider registry, which scales to
     * `contextWindow × 0.85` per OpenCode's convention.
     */
    private val compactionThreshold: (ModelRef) -> Int = { DEFAULT_COMPACTION_TOKEN_THRESHOLD },
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
    /**
     * Optional project store used by the turn executor to enrich
     * [io.talevia.core.tool.ToolAvailabilityContext] with asset-presence info so
     * [io.talevia.core.tool.ToolApplicability.RequiresAssets] tools can be hidden when
     * the bound project is empty. Composition roots usually wire this when they already
     * have a ProjectStore singleton; null is safe (asset-scoped tools stay hidden).
     */
    private val projects: io.talevia.core.domain.ProjectStore? = null,
    /**
     * Ordering policy applied to [fallbackProviders] at construction time.
     * Default [ProviderRoutingPolicy.Default] preserves pre-policy
     * registry order so existing composition roots are unchanged.
     * Composition roots that want cost-aware fallback pass
     * [CheapestFirstPolicy] — M2 exit summary §3.1 follow-up #3.
     *
     * Fallback ordering is a build-time decision (not per-turn): the
     * fallback chain doesn't change mid-run, and re-sorting on every
     * turn would silently shuffle the chain if [LlmPricing] ever grew a
     * mutable surface. Constructor-time application keeps the policy
     * cacheable and test-friendly.
     */
    private val routingPolicy: ProviderRoutingPolicy = ProviderRoutingPolicy.Default,
) : AutoCloseable {

    private val log = Loggers.get("agent")

    private val executor = AgentTurnExecutor(
        providers = buildList {
            add(provider)
            addAll(routingPolicy.orderFallbacks(provider, fallbackProviders))
        },
        registry = registry,
        permissions = permissions,
        store = store,
        bus = bus,
        clock = clock,
        metrics = metrics,
        systemPrompt = systemPrompt,
        projects = projects,
    )

    private val compactionGate = CompactionGate(
        compactor = compactor,
        compactionThreshold = compactionThreshold,
        store = store,
        bus = bus,
    )

    private val retryLoop = RetryLoop(
        executor = executor,
        retryPolicy = retryPolicy,
        store = store,
        bus = bus,
        clock = clock,
        log = log,
    )

    private val inflight = AgentInflightRegistry()

    /**
     * Routes [BusEvent.SessionCancelRequested] → [cancel] so any publisher
     * (CLI signal handler, HTTP handler, IDE abort button) can cancel a
     * session without holding an Agent reference. See [AgentBusCancelWatcher].
     */
    private val cancelWatcher = AgentBusCancelWatcher(
        bus = bus,
        scope = backgroundScope,
        cancelSession = { sessionId -> cancel(sessionId) },
    )

    /** Test hook — suspends until the bus-driven cancel subscription is live. */
    suspend fun awaitCancelSubscriptionReady() {
        cancelWatcher.awaitReady()
    }

    /**
     * Cancel any in-flight [run] for [sessionId]. Returns true if a run was
     * found and cancelled; false if no run was in flight. Safe to call from
     * any coroutine.
     */
    suspend fun cancel(sessionId: SessionId): Boolean =
        inflight.cancel(sessionId, "Agent.cancel($sessionId)")

    /** True while [run] for [sessionId] is executing. */
    suspend fun isRunning(sessionId: SessionId): Boolean = inflight.isRunning(sessionId)

    /**
     * Release background resources owned by this Agent. Idempotent and safe
     * to call from any thread.
     *
     * Cancels the [backgroundScope] — kills the bus-driven cancel-watcher
     * subscription, any in-flight session-titler launches, and any future
     * fire-and-forget work the Agent had outstanding. In-flight [run] calls
     * are NOT cancelled by `close` (use [cancel] for that); they execute on
     * the caller's coroutine context, so cancelling them is the caller's
     * responsibility.
     *
     * Composition roots (CLI / Desktop / Server / Android / iOS containers)
     * call this in their shutdown path so a process exit doesn't leave a
     * SupervisorJob worth of background coroutines orphaned. Tests that
     * spin up a throwaway Agent should also call it (or wrap the Agent in
     * `use { … }`) to avoid leaking subscriptions across runs.
     */
    override fun close() {
        backgroundScope.cancel(CancellationException("Agent.close()"))
    }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun run(input: RunInput): Message.Assistant {
        val thisJob = currentCoroutineContext()[Job]
            ?: error("Agent.run must be called from a coroutine with a Job")
        val handle = inflight.register(input.sessionId, AgentRunHandle(thisJob))

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
            bus.publish(
                BusEvent.AgentRunStateChanged(input.sessionId, terminal, retryAttempt = handle.lastRetryAttempt),
            )
            return result
        } catch (e: CancellationException) {
            finalizeCancelledRun(store, bus, input.sessionId, handle, e)
            log.info("run.cancelled", "session" to input.sessionId.value, "reason" to e.message)
            throw e
        } catch (t: Throwable) {
            finalizeFailedRun(bus, input.sessionId, handle, t)
            throw t
        } finally {
            metrics?.observe("agent.run.ms", (clock.now() - runStart).inWholeMilliseconds)
            inflight.unregister(input.sessionId)
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun runLoop(input: RunInput, handle: AgentRunHandle): Message.Assistant {
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

            // Compaction hook: before asking the provider for another turn,
            // estimate token usage and let the Compactor prune + summarise
            // if we are over budget. Returns the post-process history (re-
            // read from the store after compaction) or the input history
            // unchanged if under budget / no compactor configured.
            history = compactionGate.maybeCompact(input, handle, history)

            // Re-read the session every step so a `switch_project` invoked in the
            // previous turn is reflected in the next turn's system prompt and in
            // the ToolContext seen by freshly dispatched tools (VISION §5.4).
            // Same re-read captures `spendCapCents` so a mid-run
            // `set_session_spend_cap` takes effect on the very next tool dispatch.
            val sessionSnapshot = store.getSession(input.sessionId)
            val snapshot = RetryLoop.SessionSnapshot(
                currentProjectId = sessionSnapshot?.currentProjectId,
                spendCapCents = sessionSnapshot?.spendCapCents,
                disabledToolIds = sessionSnapshot?.disabledToolIds ?: emptySet(),
                systemPromptOverride = sessionSnapshot?.systemPromptOverride,
            )

            val (asstMsg, turnResult) = retryLoop.runStepWithRetry(
                input = input,
                handle = handle,
                history = history,
                parentUserMsgId = userMsg.id,
                snapshot = snapshot,
            )

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
}

data class RunInput(
    val sessionId: SessionId,
    val text: String,
    val model: ModelRef,
    val agentName: String = "default",
    val permissionRules: List<PermissionRule> = emptyList(),
    val options: ProviderOptions = ProviderOptions(),
)
