package io.talevia.core.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.metrics.MetricsRegistry
import io.talevia.core.permission.AllowAllPermissionService
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.permission.PermissionRequest
import io.talevia.core.permission.PermissionRule
import io.talevia.core.permission.PermissionService
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.provider.LlmEvent
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.ToolState
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for [dispatchToolCall] —
 * `core/agent/ToolDispatcher.kt`. The single-tool-call dispatch slice
 * extracted from [AgentTurnExecutor] in cycle … (see kdoc): permission
 * gate + registry lookup + store side-effects + metrics + bus
 * sequence. Cycle 223 audit: indirect coverage via `AgentLoopTest` /
 * `AgentPermissionIntegrationTest` / `AgentRetryTest`, but the
 * per-branch contract pins below were never asserted in isolation —
 * drift in any single branch is silently absorbed by the end-to-end
 * happy-path checks.
 *
 * Same audit-pattern fallback as cycles 207-222.
 *
 * Four correctness contracts pinned (one per dispatch branch):
 *
 *  1. **Unknown toolId** — registry miss. Behaviour:
 *     - basePart upserted with `ToolState.Running(input)` first
 *       (PartUpdated bus event).
 *     - `AgentRunStateChanged(AwaitingTool)` published.
 *     - Failed part upserted with `"Unknown tool: <toolId>"` message.
 *     - `AgentRunStateChanged(Generating)` published — DOES emit the
 *       Generating bookend.
 *     - permission check skipped; metrics NOT observed.
 *
 *  2. **Permission denied** — tool registered but
 *     `PermissionService.check(...)` returns [PermissionDecision.Reject].
 *     **Asymmetric bus sequence pin** — only `AwaitingTool` is
 *     published, NOT `Generating`. Drift to "publish Generating after
 *     permission denied" would mis-match the rest of the agent loop's
 *     turn-end finalisation; drift to "skip the Running upsert" would
 *     leave the UI without a tool-card to display the failure. Failed
 *     message format: `"Permission denied: <permission-string>"`.
 *
 *  3. **Successful dispatch** — tool returns ok.
 *     - `Completed(input, outputForLlm, data, estimatedTokens)` part
 *       state, with `result.title` propagated to the part.
 *     - Metrics histogram `tool.<toolId>.ms` observed (one entry).
 *     - Bus emits both `AwaitingTool` and `Generating` bookends.
 *
 *  4. **Failed dispatch** — tool throws.
 *     - `Failed(input, exception.message)` part state.
 *     - Metrics histogram STILL observed — failures count toward
 *       latency budget (drift to "skip on throw" would hide expensive
 *       crashes).
 *     - Bus still emits `Generating` — symmetric with success path.
 *     - `null` exception message falls back to `e::class.simpleName`.
 *
 * Plus pins:
 *   - `retryAttempt` propagated to BOTH `AgentRunStateChanged` events.
 *   - `basePart.state` initially `Running(event.input)` carrying the
 *     raw input JSON the LLM emitted.
 *   - the kdoc-claimed bus sequence shape `Running → AwaitingTool →
 *     Final → Generating` (PartUpdated interleaved between
 *     run-state changes) for the 3 paths that emit Generating.
 */
class ToolDispatcherTest {

    // ── 1. Unknown toolId ───────────────────────────────────

    @Test fun unknownToolFailsPartAndEmitsGeneratingBookend() = runTest {
        val fx = newFixture()
        // Registry has NO tools registered → lookup miss.
        val event = fx.makeEvent(toolId = "ghost_tool")

        val (ready, collected) = collectEventsAfterSubscribed(fx.bus, expected = 4)
        ready.await()
        fx.runDispatch(event = event)
        val events = collected.await()

        // Bus sequence: PartUpdated(Running), AwaitingTool, PartUpdated(Failed), Generating.
        assertEquals(4, events.size)
        val (e0, e1, e2, e3) = events
        val runningPart = (e0 as BusEvent.PartUpdated).part as Part.Tool
        assertTrue(runningPart.state is ToolState.Running)
        val running = (runningPart.state as ToolState.Running).input
        assertEquals(event.input, running, "Running carries the raw input JSON verbatim")
        assertTrue(
            e1 is BusEvent.AgentRunStateChanged && e1.state == AgentRunState.AwaitingTool,
            "AwaitingTool published before tool body",
        )
        val finalPart = (e2 as BusEvent.PartUpdated).part as Part.Tool
        val finalState = (finalPart.state as ToolState.Failed)
        assertEquals(
            "Unknown tool: ghost_tool",
            finalState.message,
            "unknown-tool message format must include the requested toolId",
        )
        assertTrue(
            e3 is BusEvent.AgentRunStateChanged && e3.state == AgentRunState.Generating,
            "unknown-tool path DOES emit Generating bookend (asymmetric vs permission-denied)",
        )

        // Metrics: NOT observed for unknown tool (no key was entered).
        val histograms = fx.metrics.histogramSnapshot()
        assertTrue(
            histograms.isEmpty(),
            "unknown tool must NOT record latency; got: ${histograms.keys}",
        )
    }

    // ── 2. Permission denied (asymmetric: NO Generating bookend) ──

    @Test fun permissionDeniedFailsPartButDoesNotEmitGenerating() = runTest {
        val fx = newFixture()
        fx.registry.register(SuccessTool)
        val event = fx.makeEvent(toolId = SuccessTool.id)

        // Marquee asymmetry pin: permission-denied path emits exactly
        // 3 bus events (PartUpdated(Running), AwaitingTool,
        // PartUpdated(Failed)) — NOT 4. Drift to "publish Generating
        // after permission denied" would let the test's take(3) hang
        // (or take(4) succeed where it shouldn't).
        val (ready, collected) = collectEventsAfterSubscribed(fx.bus, expected = 3)
        ready.await()
        fx.runDispatch(event = event, permissions = RejectAllPermissionService())
        val events = collected.await()

        assertEquals(3, events.size, "permission-denied path must publish exactly 3 events (NO Generating)")
        val (e0, e1, e2) = events
        val runningPart = (e0 as BusEvent.PartUpdated).part as Part.Tool
        assertTrue(runningPart.state is ToolState.Running)
        assertTrue(e1 is BusEvent.AgentRunStateChanged && e1.state == AgentRunState.AwaitingTool)
        val finalPart = (e2 as BusEvent.PartUpdated).part as Part.Tool
        val finalState = (finalPart.state as ToolState.Failed)
        assertTrue(
            "Permission denied: " in finalState.message,
            "expected 'Permission denied:' prefix; got: ${finalState.message}",
        )
        assertTrue(
            SuccessTool.permission.permissionFrom("{}") in finalState.message,
            "expected permission string cited; got: ${finalState.message}",
        )

        // Metrics: NOT observed.
        assertTrue(fx.metrics.histogramSnapshot().isEmpty())
    }

    // ── 3. Successful dispatch ──────────────────────────────

    @Test fun successfulDispatchUpsertsCompletedAndRecordsLatency() = runTest {
        val fx = newFixture()
        fx.registry.register(SuccessTool)
        val event = fx.makeEvent(toolId = SuccessTool.id)

        val (ready, collected) = collectEventsAfterSubscribed(fx.bus, expected = 4)
        ready.await()
        fx.runDispatch(event = event)
        val events = collected.await()

        assertEquals(4, events.size)
        val (e0, e1, e2, e3) = events
        val runningPart = (e0 as BusEvent.PartUpdated).part as Part.Tool
        assertTrue(runningPart.state is ToolState.Running)
        assertTrue(e1 is BusEvent.AgentRunStateChanged && e1.state == AgentRunState.AwaitingTool)
        val completedPart = (e2 as BusEvent.PartUpdated).part as Part.Tool
        val completed = (completedPart.state as ToolState.Completed)
        assertEquals(SuccessTool.OUTPUT_FOR_LLM, completed.outputForLlm)
        assertEquals(SuccessTool.ESTIMATED_TOKENS, completed.estimatedTokens)
        assertEquals(SuccessTool.TITLE, completedPart.title, "ToolResult.title flows to part.title")
        assertNotNull(completed.data, "encoded output JSON present on Completed state")
        assertTrue(
            e3 is BusEvent.AgentRunStateChanged && e3.state == AgentRunState.Generating,
            "success path emits Generating bookend",
        )

        // Metrics: histogram entry recorded under "tool.<toolId>.ms".
        val histograms = fx.metrics.histogramSnapshot()
        val key = "tool.${SuccessTool.id}.ms"
        assertTrue(
            key in histograms,
            "expected histogram key '$key'; got: ${histograms.keys}",
        )
        assertEquals(1L, histograms[key]!!.count, "exactly one observation per dispatch")
    }

    // ── 4. Failed dispatch (tool throws) ────────────────────

    @Test fun failedDispatchUpsertsFailedAndStillEmitsGeneratingAndRecordsLatency() = runTest {
        val fx = newFixture()
        fx.registry.register(ThrowingTool)
        val event = fx.makeEvent(toolId = ThrowingTool.id)

        val (ready, collected) = collectEventsAfterSubscribed(fx.bus, expected = 4)
        ready.await()
        fx.runDispatch(event = event)
        val events = collected.await()

        assertEquals(4, events.size)
        val (_, _, e2, e3) = events
        val failedPart = (e2 as BusEvent.PartUpdated).part as Part.Tool
        val failed = (failedPart.state as ToolState.Failed)
        assertEquals(
            ThrowingTool.MSG,
            failed.message,
            "Failed message must echo exception.message verbatim",
        )
        assertTrue(
            e3 is BusEvent.AgentRunStateChanged && e3.state == AgentRunState.Generating,
            "tool-throw path STILL emits Generating bookend (symmetric with success)",
        )
        // Metrics still recorded — failures count toward latency budget.
        val histograms = fx.metrics.histogramSnapshot()
        val key = "tool.${ThrowingTool.id}.ms"
        assertTrue(
            key in histograms,
            "tool-throw STILL records latency under '$key'; got: ${histograms.keys}",
        )
    }

    @Test fun failedDispatchWithNullExceptionMessageFallsBackToClassName() = runTest {
        // Pin: the `e.message ?: e::class.simpleName ?: "tool error"`
        // chain. Drift to "fail with null" would surface a nondescript
        // empty-string failure to the LLM.
        val fx = newFixture()
        fx.registry.register(ThrowingNullMessageTool)
        val event = fx.makeEvent(toolId = ThrowingNullMessageTool.id)

        val (ready, collected) = collectEventsAfterSubscribed(fx.bus, expected = 4)
        ready.await()
        fx.runDispatch(event = event)
        val events = collected.await()

        val failedPart = (events[2] as BusEvent.PartUpdated).part as Part.Tool
        val failed = (failedPart.state as ToolState.Failed)
        // The throw path uses `IllegalStateException(message=null)` so
        // exception.message is null; the fallback is the class's
        // simpleName ("IllegalStateException").
        assertEquals(
            "IllegalStateException",
            failed.message,
            "null exception.message → fallback to e::class.simpleName",
        )
    }

    // ── retryAttempt propagation ────────────────────────────

    @Test fun retryAttemptPropagatedToBothAgentRunStateChangedEvents() = runTest {
        val fx = newFixture()
        fx.registry.register(SuccessTool)
        val event = fx.makeEvent(toolId = SuccessTool.id)

        val (ready, collected) = collectEventsAfterSubscribed(fx.bus, expected = 4)
        ready.await()
        fx.runDispatch(event = event, retryAttempt = 3)
        val events = collected.await()

        val awaiting = events.filterIsInstance<BusEvent.AgentRunStateChanged>().single { it.state == AgentRunState.AwaitingTool }
        val generating = events.filterIsInstance<BusEvent.AgentRunStateChanged>().single { it.state == AgentRunState.Generating }
        assertEquals(3, awaiting.retryAttempt, "retryAttempt propagated to AwaitingTool")
        assertEquals(3, generating.retryAttempt, "retryAttempt propagated to Generating")
    }

    @Test fun retryAttemptNullByDefault() = runTest {
        val fx = newFixture()
        fx.registry.register(SuccessTool)
        val event = fx.makeEvent(toolId = SuccessTool.id)

        val (ready, collected) = collectEventsAfterSubscribed(fx.bus, expected = 4)
        ready.await()
        fx.runDispatch(event = event)
        val events = collected.await()

        val agentEvents = events.filterIsInstance<BusEvent.AgentRunStateChanged>()
        assertTrue(agentEvents.all { it.retryAttempt == null }, "default retryAttempt is null")
    }

    // ── Bus sequence shape ─────────────────────────────────

    @Test fun successPathBusSequenceMatchesKdocClaim() {
        // Pin: kdoc claims `Running → AwaitingTool → Completed →
        // Generating`. Drift to "AwaitingTool first, Running second"
        // (which would be more natural as a state-machine diagram)
        // would let UIs render "tool running" before any tool-card
        // exists in the store.
        runTest {
            val fx = newFixture()
            fx.registry.register(SuccessTool)
            val event = fx.makeEvent(toolId = SuccessTool.id)

            val (ready, collected) = collectEventsAfterSubscribed(fx.bus, expected = 4)
            ready.await()
            fx.runDispatch(event = event)
            val events = collected.await()
            val shape = events.map { describe(it) }
            assertEquals(
                listOf(
                    "Part:Running",
                    "Run:AwaitingTool",
                    "Part:Completed",
                    "Run:Generating",
                ),
                shape,
                "kdoc-claimed bus sequence must match: " +
                    "PartUpdated(Running) → AgentRunStateChanged(AwaitingTool) → " +
                    "PartUpdated(Completed) → AgentRunStateChanged(Generating)",
            )
        }
    }

    // ── Permission-denied path: skipping the Generating bookend ──

    @Test fun comparativePermissionVsUnknownToolBookendAsymmetry() {
        // Direct comparative pin: same fixture shape, SAME tool id, the
        // ONLY difference is whether it's registered. Result count:
        // unknown-tool → 4 events (Generating closes), permission-denied → 3
        // (no Generating). The asymmetry is deliberate, not an oversight.
        runTest {
            // Permission-denied: 3 events.
            val fx1 = newFixture()
            fx1.registry.register(SuccessTool)
            val ev1 = fx1.makeEvent(toolId = SuccessTool.id)
            val (r1, c1) = collectEventsAfterSubscribed(fx1.bus, expected = 3)
            r1.await()
            fx1.runDispatch(event = ev1, permissions = RejectAllPermissionService())
            val deniedEvents = c1.await()
            assertEquals(3, deniedEvents.size)
            assertNull(
                deniedEvents.filterIsInstance<BusEvent.AgentRunStateChanged>()
                    .firstOrNull { it.state == AgentRunState.Generating },
                "permission-denied path emits NO Generating event",
            )

            // Unknown-tool: 4 events.
            val fx2 = newFixture()
            val ev2 = fx2.makeEvent(toolId = "ghost_tool")
            val (r2, c2) = collectEventsAfterSubscribed(fx2.bus, expected = 4)
            r2.await()
            fx2.runDispatch(event = ev2)
            val unknownEvents = c2.await()
            assertEquals(4, unknownEvents.size)
            assertNotNull(
                unknownEvents.filterIsInstance<BusEvent.AgentRunStateChanged>()
                    .firstOrNull { it.state == AgentRunState.Generating },
                "unknown-tool path emits Generating bookend",
            )
        }
    }

    // ── Metrics-null safety ─────────────────────────────────

    @Test fun nullMetricsRegistrySkipsObserveWithoutThrowing() = runTest {
        // Pin: passing `metrics = null` is the production CLI default
        // (no JVM metrics scrape endpoint) — the code path uses
        // `metrics?.observe(...)` and MUST not throw.
        val fx = newFixture(metrics = null)
        fx.registry.register(SuccessTool)
        val event = fx.makeEvent(toolId = SuccessTool.id)

        val (ready, collected) = collectEventsAfterSubscribed(fx.bus, expected = 4)
        ready.await()
        fx.runDispatch(event = event, metrics = null)
        val events = collected.await()
        // Path completes normally — same 4 events as the metrics-on path.
        assertEquals(4, events.size)
        val completedPart = (events[2] as BusEvent.PartUpdated).part as Part.Tool
        val finalState = completedPart.state as ToolState.Completed
        assertEquals(SuccessTool.OUTPUT_FOR_LLM, finalState.outputForLlm)
    }

    // ── helpers ────────────────────────────────────────────

    private fun describe(e: BusEvent): String = when (e) {
        is BusEvent.PartUpdated -> {
            val p = e.part
            if (p is Part.Tool) "Part:${p.state::class.simpleName}" else "Part:${p::class.simpleName}"
        }
        is BusEvent.AgentRunStateChanged -> "Run:${e.state}"
        else -> e::class.simpleName ?: "?"
    }

    private fun CoroutineScope.collectEventsAfterSubscribed(
        bus: EventBus,
        expected: Int,
    ): Pair<CompletableDeferred<Unit>, Deferred<List<BusEvent>>> {
        val ready = CompletableDeferred<Unit>()
        val collected = async(Dispatchers.Default) {
            // `onSubscription` is a SharedFlow extension — apply
            // BEFORE filter / take so the barrier fires when the
            // underlying SharedFlow has registered the collector.
            // (Cycle 214 banked this pattern after a flake fix.)
            //
            // Filter to the bus events that dispatchToolCall ITSELF
            // produces (PartUpdated + AgentRunStateChanged); drops
            // collateral SessionUpdated emissions from touchSession
            // inside upsertPart so the per-test `expected` counts
            // measure dispatch's own contract, not its incidental
            // session-touching side-effect.
            bus.events
                .onSubscription { ready.complete(Unit) }
                .filter { it is BusEvent.PartUpdated || it is BusEvent.AgentRunStateChanged }
                .take(expected)
                .toList()
        }
        return ready to collected
    }

    private suspend fun newFixture(metrics: MetricsRegistry? = MetricsRegistry()): Fixture =
        withContext(Dispatchers.Default) {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            TaleviaDb.Schema.create(driver)
            val db = TaleviaDb(driver)
            val bus = EventBus()
            val store = SqlDelightSessionStore(db, bus)
            val sid = SessionId("dispatch-test-${randomTag()}")
            val now = Clock.System.now()
            store.createSession(
                Session(
                    id = sid,
                    projectId = ProjectId("origin-p"),
                    title = "dispatch test",
                    createdAt = now,
                    updatedAt = now,
                    currentProjectId = null,
                ),
            )
            val asstMsg = Message.Assistant(
                id = MessageId("a-${randomTag()}"),
                sessionId = sid,
                createdAt = now,
                parentId = MessageId("u-${randomTag()}"),
                model = ModelRef("fake", "test"),
                finish = FinishReason.TOOL_CALLS,
            )
            store.appendMessage(asstMsg)
            Fixture(
                bus = bus,
                store = store,
                registry = ToolRegistry(),
                metrics = metrics ?: MetricsRegistry(),
                asstMsg = asstMsg,
                sessionId = sid,
            )
        }

    private suspend fun Fixture.runDispatch(
        event: LlmEvent.ToolCallReady,
        permissions: PermissionService = AllowAllPermissionService(),
        metrics: MetricsRegistry? = this.metrics,
        retryAttempt: Int? = null,
    ) {
        val input = RunInput(
            sessionId = sessionId,
            text = "",
            model = ModelRef("fake", "test"),
        )
        dispatchToolCall(
            registry = registry,
            permissions = permissions,
            store = store,
            bus = bus,
            clock = Clock.System,
            metrics = metrics,
            asstMsg = asstMsg,
            history = emptyList(),
            input = input,
            event = event,
            handle = PendingToolCall(event.partId, event.toolId),
            currentProjectId = null,
            spendCapCents = null,
            permissionMutex = Mutex(),
            retryAttempt = retryAttempt,
        )
    }

    private fun Fixture.makeEvent(toolId: String): LlmEvent.ToolCallReady = LlmEvent.ToolCallReady(
        partId = PartId("part-${randomTag()}"),
        callId = CallId("call-${randomTag()}"),
        toolId = toolId,
        input = buildJsonObject { put("greet", "hi") },
    )

    private data class Fixture(
        val bus: EventBus,
        val store: SqlDelightSessionStore,
        val registry: ToolRegistry,
        val metrics: MetricsRegistry,
        val asstMsg: Message.Assistant,
        val sessionId: SessionId,
    )

    private fun randomTag(): String = (0..7).map { ('a'..'z').random() }.joinToString("")

    private operator fun <T> List<T>.component4(): T = this[3]

    /** Test tool: returns a deterministic ToolResult. */
    private object SuccessTool : Tool<SuccessTool.Input, SuccessTool.Output> {
        const val OUTPUT_FOR_LLM = "ok-result-for-llm"
        const val TITLE = "ok-title"
        const val ESTIMATED_TOKENS = 42

        @Serializable data class Input(val greet: String = "")

        @Serializable data class Output(val echoed: String)

        override val id: String = "test_success_tool"
        override val helpText: String = "test"
        override val inputSerializer: KSerializer<Input> = serializer()
        override val outputSerializer: KSerializer<Output> = serializer()
        override val permission: PermissionSpec = PermissionSpec.fixed("test.success")
        override val inputSchema: JsonObject = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
        }

        override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> = ToolResult(
            title = TITLE,
            outputForLlm = OUTPUT_FOR_LLM,
            data = Output(echoed = input.greet),
            estimatedTokens = ESTIMATED_TOKENS,
        )
    }

    /** Test tool: throws with a specific message. */
    private object ThrowingTool : Tool<ThrowingTool.Input, ThrowingTool.Output> {
        const val MSG = "deliberate test failure"

        @Serializable data class Input(val greet: String = "")

        @Serializable data class Output(val never: String = "")

        override val id: String = "test_throwing_tool"
        override val helpText: String = "test"
        override val inputSerializer: KSerializer<Input> = serializer()
        override val outputSerializer: KSerializer<Output> = serializer()
        override val permission: PermissionSpec = PermissionSpec.fixed("test.throws")
        override val inputSchema: JsonObject = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
        }

        override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
            error(MSG)
        }
    }

    /** Test tool: throws with a null message → exercises the simpleName fallback. */
    private object ThrowingNullMessageTool : Tool<ThrowingNullMessageTool.Input, ThrowingNullMessageTool.Output> {
        @Serializable data class Input(val greet: String = "")

        @Serializable data class Output(val never: String = "")

        override val id: String = "test_throwing_null_msg_tool"
        override val helpText: String = "test"
        override val inputSerializer: KSerializer<Input> = serializer()
        override val outputSerializer: KSerializer<Output> = serializer()
        override val permission: PermissionSpec = PermissionSpec.fixed("test.throws-null")
        override val inputSchema: JsonObject = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
        }

        override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
            throw IllegalStateException(null as String?)
        }
    }

    /** PermissionService that denies every check. */
    private class RejectAllPermissionService : PermissionService {
        override suspend fun check(rules: List<PermissionRule>, request: PermissionRequest): PermissionDecision =
            PermissionDecision.Reject

        override suspend fun reply(requestId: String, decision: PermissionDecision) { /* no-op */ }
    }
}
