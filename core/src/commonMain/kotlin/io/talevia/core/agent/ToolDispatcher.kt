package io.talevia.core.agent

import io.talevia.core.ProjectId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.logging.Loggers
import io.talevia.core.logging.info
import io.talevia.core.logging.warn
import io.talevia.core.metrics.MetricsRegistry
import io.talevia.core.permission.PermissionRequest
import io.talevia.core.permission.PermissionService
import io.talevia.core.provider.LlmEvent
import io.talevia.core.session.Message
import io.talevia.core.session.MessageWithParts
import io.talevia.core.session.Part
import io.talevia.core.session.SessionStore
import io.talevia.core.session.ToolState
import io.talevia.core.tool.RegisteredTool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Runs a single tool call through the permission gate + registry + store
 * side-effects + metrics counters, emitting the corresponding bus events
 * on state transitions.
 *
 * Extracted from [AgentTurnExecutor] so the turn-loop driver stays focused
 * on stream plumbing; this function owns the "one tool call from fire to
 * persisted result" slice. Behaviour is byte-identical to the previous
 * inline `dispatchTool` method — same side-effects, same bus sequence
 * (`AgentRunStateChanged(AwaitingTool)` → tool body → `Completed|Failed`
 * part upsert → `AgentRunStateChanged(Generating)`), same metric names
 * (`tool.<toolId>.ms` histogram), same log keys
 * (`tool.ok` / `tool.fail` with `tool` + `callId` + `ms` + `error`).
 *
 * Package-private because this seam is tested implicitly through
 * `AgentLoopTest` / `AgentPermissionIntegrationTest` / `AgentRetryTest` —
 * lifting it to public would grow the Core export surface without any
 * third-party caller needing it.
 */
private val toolLog = Loggers.get("agent")

internal suspend fun dispatchToolCall(
    registry: ToolRegistry,
    permissions: PermissionService,
    store: SessionStore,
    bus: EventBus,
    clock: Clock,
    metrics: MetricsRegistry?,
    asstMsg: Message.Assistant,
    history: List<MessageWithParts>,
    input: RunInput,
    event: LlmEvent.ToolCallReady,
    handle: PendingToolCall,
    currentProjectId: ProjectId?,
    spendCapCents: Long?,
    permissionMutex: Mutex,
    retryAttempt: Int? = null,
) {
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
    bus.publish(
        BusEvent.AgentRunStateChanged(asstMsg.sessionId, AgentRunState.AwaitingTool, retryAttempt = retryAttempt),
    )

    if (tool == null) {
        store.upsertPart(basePart.copy(state = ToolState.Failed(event.input, "Unknown tool: ${event.toolId}")))
        bus.publish(
            BusEvent.AgentRunStateChanged(
                asstMsg.sessionId,
                AgentRunState.Generating,
                retryAttempt = retryAttempt,
            ),
        )
        return
    }

    val inputJson = event.input.toString()
    val pattern = tool.permission.patternFrom(inputJson)
    val resolvedPermission = tool.permission.permissionFrom(inputJson)
    // Serialise permission checks across concurrent dispatches so
    // interactive prompts (ask-once-per-tool) never race for the same
    // terminal. The tool body itself still runs concurrently.
    val decision = permissionMutex.withLock {
        permissions.check(
            rules = input.permissionRules,
            request = PermissionRequest(
                sessionId = asstMsg.sessionId,
                permission = resolvedPermission,
                pattern = pattern,
                metadata = mapOf("toolId" to event.toolId),
            ),
        )
    }
    if (!decision.granted) {
        store.upsertPart(
            basePart.copy(
                state = ToolState.Failed(event.input, "Permission denied: $resolvedPermission"),
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
        publishEvent = { e -> bus.publish(e) },
        spendCapCents = spendCapCents,
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
                    state = ToolState.Completed(
                        input = event.input,
                        outputForLlm = result.outputForLlm,
                        data = data,
                        estimatedTokens = result.estimatedTokens,
                    ),
                    title = result.title,
                ),
            )
            toolLog.info("tool.ok", "tool" to event.toolId, "callId" to event.callId.value, "ms" to toolMs)
        },
        onFailure = { e ->
            store.upsertPart(
                basePart.copy(
                    state = ToolState.Failed(event.input, e.message ?: e::class.simpleName ?: "tool error"),
                ),
            )
            toolLog.warn(
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
    bus.publish(
        BusEvent.AgentRunStateChanged(asstMsg.sessionId, AgentRunState.Generating, retryAttempt = retryAttempt),
    )
}
