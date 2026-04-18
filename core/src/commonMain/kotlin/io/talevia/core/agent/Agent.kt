package io.talevia.core.agent

import io.talevia.core.CallId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
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
import kotlinx.coroutines.flow.collect
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
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
) {

    @OptIn(ExperimentalUuidApi::class)
    suspend fun run(input: RunInput): Message.Assistant {
        val now = clock.now()

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

        var latestAssistant: Message.Assistant? = null
        var step = 0

        while (step < maxSteps) {
            step++

            val history = store.listMessagesWithParts(input.sessionId)

            val asstMsg = Message.Assistant(
                id = MessageId(Uuid.random().toString()),
                sessionId = input.sessionId,
                createdAt = clock.now(),
                parentId = userMsg.id,
                model = input.model,
            )
            store.appendMessage(asstMsg)

            val turnResult = streamTurn(asstMsg, history, input)

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
    ): TurnResult {
        val request = LlmRequest(
            model = input.model,
            messages = history,
            tools = registry.specs(),
            systemPrompt = systemPrompt,
            options = input.options,
        )

        var finish: FinishReason? = null
        var usage = TokenUsage.ZERO
        var error: String? = null
        val pending = mutableMapOf<CallId, PendingToolCall>()

        provider.stream(request).collect { event ->
            when (event) {
                is LlmEvent.TextStart -> upsertEmptyText(asstMsg, event.partId)
                is LlmEvent.TextDelta -> bus.publish(
                    BusEvent.PartDelta(asstMsg.sessionId, asstMsg.id, event.partId, "text", event.text),
                )
                is LlmEvent.TextEnd -> store.upsertPart(
                    Part.Text(event.partId, asstMsg.id, asstMsg.sessionId, clock.now(), text = event.finalText),
                )

                is LlmEvent.ReasoningStart -> store.upsertPart(
                    Part.Reasoning(event.partId, asstMsg.id, asstMsg.sessionId, clock.now(), text = ""),
                )
                is LlmEvent.ReasoningDelta -> bus.publish(
                    BusEvent.PartDelta(asstMsg.sessionId, asstMsg.id, event.partId, "text", event.text),
                )
                is LlmEvent.ReasoningEnd -> store.upsertPart(
                    Part.Reasoning(event.partId, asstMsg.id, asstMsg.sessionId, clock.now(), text = event.finalText),
                )

                is LlmEvent.ToolCallStart -> {
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
                is LlmEvent.ToolCallReady -> dispatchTool(asstMsg, history, input, event, pending)

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
                }
                is LlmEvent.Error -> {
                    error = event.message
                    finish = FinishReason.ERROR
                }
            }
        }

        return TurnResult(
            finish = finish ?: FinishReason.STOP,
            usage = usage,
            error = error,
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

        if (tool == null) {
            store.upsertPart(basePart.copy(state = ToolState.Failed(event.input, "Unknown tool: ${event.toolId}")))
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
        )

        val outcome = runCatching { tool.dispatch(event.input, ctx) }
        outcome.fold(
            onSuccess = { result ->
                val data = tool.encodeOutput(result)
                store.upsertPart(
                    basePart.copy(
                        state = ToolState.Completed(event.input, result.outputForLlm, data),
                        title = result.title,
                    ),
                )
            },
            onFailure = { e ->
                store.upsertPart(
                    basePart.copy(
                        state = ToolState.Failed(event.input, e.message ?: e::class.simpleName ?: "tool error"),
                    ),
                )
            },
        )
    }

    private data class TurnResult(val finish: FinishReason, val usage: TokenUsage, val error: String?)
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
