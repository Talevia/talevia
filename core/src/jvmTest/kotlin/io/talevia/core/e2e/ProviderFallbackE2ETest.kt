package io.talevia.core.e2e

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.agent.Agent
import io.talevia.core.agent.AgentProviderFallbackTracker
import io.talevia.core.agent.FakeProvider
import io.talevia.core.agent.RetryPolicy
import io.talevia.core.agent.RunInput
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Timeline
import io.talevia.core.permission.AllowAllPermissionService
import io.talevia.core.platform.BundleBlobWriter
import io.talevia.core.platform.GeneratedImage
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.platform.ImageGenEngine
import io.talevia.core.platform.ImageGenRequest
import io.talevia.core.platform.ImageGenResult
import io.talevia.core.provider.LlmEvent
import io.talevia.core.session.FinishReason
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.session.ToolState
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.aigc.GenerateImageTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * M2 criterion 5 — "Fallback 生产回归测试". The unit-level
 * [io.talevia.core.agent.AgentProviderFallbackTest] covers the agent-loop
 * hand-off (primary retry-exhausts → secondary takes over → `END_TURN`), and
 * [io.talevia.core.agent.AgentProviderFallbackTrackerTest] covers
 * [AgentProviderFallbackTracker]'s ring-buffer semantics. Neither proves the
 * end-to-end invariant the milestone actually cares about:
 *
 *   LLM provider A fails → Agent falls over to provider B → B's tool call
 *   dispatches against the real tool registry → an artefact lands in the
 *   project bundle + the project's lockfile is populated → the tracker
 *   snapshots the A→B hop so a late-subscribing UI / `session_query` can
 *   reconstruct what happened.
 *
 * This is the genuine e2e closure: without this test, a refactor that
 * silently re-issues the tool call on the primary after fallback (double
 * billing) or loses the asset-write during provider swap would slip through
 * `:core:jvmTest` entirely.
 *
 * Deliberate layer choice: this exercises **LLM-provider** fallback (chat
 * completions chain inside the Agent), not AIGC-engine fallback (e.g.
 * `SynthesizeSpeechTool`'s priority-ordered `engines: List<TtsEngine>`).
 * The tracker is LLM-loop only — there's no equivalent per-tool tracker on
 * the AIGC side — so "tracker records A→B chain" pins this to the LLM
 * layer. The asset-landing check proves that the fallback on the chat side
 * doesn't accidentally break the tool-dispatch pipeline downstream of it.
 *
 * Gotcha surfaced while writing this test: `Agent.runLoop` resets
 * `providerIndex = 0` at the top of each outer step, so a run that spans
 * two LLM round-trips (tool_use + reply) re-probes the primary on the
 * second step and the tracker records **one fallback hop per step** — not
 * one hop per run. The happy-path assertion below pins that behaviour so a
 * refactor that hoists the fallback index out of the step loop must be
 * explicit about it.
 */
class ProviderFallbackE2ETest {

    private val trackerJob = SupervisorJob()
    private val trackerScope = CoroutineScope(trackerJob + Dispatchers.Unconfined)

    @AfterTest fun teardown() {
        trackerJob.cancel()
    }

    /**
     * Counting [ImageGenEngine] stub. Minimal shape — no deterministic mode,
     * no captured prompts — we only care about call count + provenance.
     * Distinct from [RefactorLoopE2ETest]'s `CountingImageEngine` because
     * that one's call-count-derived bytes would mask a double-dispatch bug
     * by silently yielding two differently-numbered assets. Here we want
     * "exactly one engine call" to be a hard invariant.
     */
    private class OneShotImageEngine : ImageGenEngine {
        override val providerId: String = "fake-image"
        var calls: Int = 0
            private set

        override suspend fun generate(request: ImageGenRequest): ImageGenResult {
            calls += 1
            return ImageGenResult(
                images = listOf(
                    GeneratedImage(
                        pngBytes = ByteArray(8) { 0x42 },
                        width = request.width,
                        height = request.height,
                    ),
                ),
                provenance = GenerationProvenance(
                    providerId = providerId,
                    modelId = request.modelId,
                    modelVersion = null,
                    seed = request.seed,
                    parameters = JsonObject(emptyMap()),
                    createdAtEpochMs = 1_700_000_000_000L,
                ),
            )
        }
    }

    private class FakeBlobWriter(private val rootDir: File) : BundleBlobWriter {
        override suspend fun writeBlob(
            projectId: ProjectId,
            assetId: AssetId,
            bytes: ByteArray,
            format: String,
        ): MediaSource.BundleFile {
            val file = File(rootDir, "${assetId.value}.$format")
            file.writeBytes(bytes)
            return MediaSource.BundleFile("media/${file.name}")
        }
    }

    @Test fun primaryFailsSecondaryProducesArtifactAndTrackerRecordsChain() = runTest {
        val tmpDir = createTempDirectory("e2e-fallback-happy").toFile()
        val (sessionStore, bus) = newSessionStore()
        val sessionId = primeSession(sessionStore)

        val tracker = AgentProviderFallbackTracker(bus, trackerScope)

        val projectStore = ProjectStoreTestKit.create()
        val pid = ProjectId("e2e-fallback")
        projectStore.upsert(
            "e2e-fallback",
            Project(id = pid, timeline = Timeline(tracks = emptyList(), duration = Duration.ZERO)),
        )

        val imageEngine = OneShotImageEngine()
        val registry = ToolRegistry()
        registry.register(GenerateImageTool(imageEngine, FakeBlobWriter(tmpDir), projectStore))

        // Primary fails every attempt, across every agent step: the fallback
        // providerIndex resets at the top of each outer step in Agent.runLoop
        // (documented behaviour — see Agent.kt `while (step < maxSteps)` body),
        // so a two-step run (tool_use + reply) consults the primary twice per
        // step × 2 steps = 4 times. Script 4 failing turns so the FakeProvider
        // is not accidentally exhausted mid-run.
        val primaryFail = listOf(
            LlmEvent.Error("primary HTTP 503: overloaded", retriable = true),
            LlmEvent.StepFinish(FinishReason.ERROR, TokenUsage.ZERO),
        )
        val primary = FakeProvider(List(4) { primaryFail }, id = "llm-primary")

        // Secondary turn 1: a tool_use request dispatching generate_image.
        // Secondary turn 2: plain text end-of-turn once the tool result threads back.
        val toolPartId = PartId("tool-call")
        val callId = CallId("call-gen-1")
        val toolInput = buildJsonObject {
            put("prompt", "a quiet red apple")
            put("projectId", pid.value)
            // Explicit empty list → bypasses consistency fold entirely so the test
            // doesn't depend on source DAG state.
            put("consistencyBindingIds", buildJsonArray { })
            put("width", 256)
            put("height", 256)
            put("seed", 42L)
            put("model", "stub-image-1")
        }
        val turnToolCall = listOf(
            LlmEvent.ToolCallStart(toolPartId, callId, "generate_image"),
            LlmEvent.ToolCallReady(toolPartId, callId, "generate_image", toolInput),
            LlmEvent.StepFinish(FinishReason.TOOL_CALLS, TokenUsage(input = 6, output = 4)),
        )
        val replyPartId = PartId("text-reply")
        val turnReply = listOf(
            LlmEvent.TextStart(replyPartId),
            LlmEvent.TextEnd(replyPartId, "image generated."),
            LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage(input = 10, output = 2)),
        )
        val secondary = FakeProvider(listOf(turnToolCall, turnReply), id = "llm-secondary")

        val agent = Agent(
            provider = primary,
            registry = registry,
            store = sessionStore,
            permissions = AllowAllPermissionService(),
            bus = bus,
            retryPolicy = RetryPolicy(maxAttempts = 2, initialDelayMs = 0, maxDelayNoHeadersMs = 0),
            fallbackProviders = listOf(secondary),
        )

        val asst = agent.run(RunInput(sessionId, "draw me an apple", ModelRef("fake", "test")))

        // Agent completed cleanly via the fallback provider.
        assertEquals(FinishReason.END_TURN, asst.finish, "fallback turn must terminate cleanly")
        // Primary consumed its retry budget on *each* step (providerIndex
        // resets at the outer step boundary): 2 retries × 2 steps = 4 calls.
        // Secondary handles the actual work: 1 tool_use turn + 1 reply turn.
        assertEquals(4, primary.requests.size, "primary drains its retry budget on both steps")
        assertEquals(2, secondary.requests.size, "secondary handles tool_use + reply turns")

        // Fallback fired at the LLM level, but the AIGC engine was called
        // exactly once — the tool dispatch is not retried across the provider
        // swap. This is the "don't double-bill on fallback" invariant.
        assertEquals(1, imageEngine.calls, "image engine must fire exactly once across fallback")

        // Tracker captured a fallback hop on *each* step the primary failed
        // over. Two steps × one A→B hop each = two hops in the ring buffer,
        // both with the same fromId / toId — this is the per-step reset
        // documented in Agent.kt, and it's the right shape because each
        // step independently makes its own primary→secondary decision
        // (the primary could recover between steps in a real-world scenario).
        // yield() so the tracker's background collector observes the
        // AgentProviderFallback events before we snapshot.
        yield()
        val hops = tracker.hops(sessionId)
        assertEquals(2, hops.size, "one A→B hop per agent step (providerIndex resets)")
        assertTrue(
            hops.all { it.fromProviderId == "llm-primary" && it.toProviderId == "llm-secondary" },
            "every hop must record the correct from/to provider ids, got: $hops",
        )

        // The artefact landed. Two independent views:
        //
        //   1. The tool Part's ToolState.Completed carries the assetId and
        //      providerId — the LLM's surface view.
        //   2. The project's lockfile carries a matching LockfileEntry — the
        //      persisted, replayable view that survives restart.
        //
        // Both must agree so a `session_query(select=run_failure)` reader
        // post-mortem can walk from tool part → lockfile → asset.
        val toolPart = sessionStore.listSessionParts(sessionId).filterIsInstance<Part.Tool>().single()
        val state = toolPart.state
        assertTrue(state is ToolState.Completed, "tool dispatch succeeded, got $state")
        val toolOutput = (state as ToolState.Completed).data.jsonObject
        val outputAssetId = toolOutput["assetId"]!!.jsonPrimitive.content
        val outputProviderId = toolOutput["providerId"]!!.jsonPrimitive.content
        assertEquals(
            imageEngine.providerId, outputProviderId,
            "provenance.providerId on the tool output must match the image engine that produced the bytes",
        )

        val entries = projectStore.get(pid)!!.lockfile.entries
        assertEquals(1, entries.size, "exactly one lockfile entry minted by the fallback turn")
        val entry = entries.single()
        assertEquals(AssetId(outputAssetId), entry.assetId, "lockfile.assetId must match tool output")
        assertEquals(
            imageEngine.providerId, entry.provenance.providerId,
            "lockfile provenance must record the AIGC engine that actually produced the asset",
        )
        assertEquals("generate_image", entry.toolId, "lockfile must record the dispatching tool")
    }

    /**
     * 反直觉边界 — negative control (§3a #9). When *both* LLM providers
     * exhaust their retry budgets, no tool call ever fires, so no asset
     * lands and no lockfile entry is minted. The tracker still records the
     * A→B hop because the fallback advance itself happened — the subsequent
     * terminal failure on B is a retry-exhaustion, not a fallback event.
     * Without this assertion, a bug that quietly swallows terminal errors
     * and declares a tool-less turn "successful" could pass as a silent
     * pass in CI.
     */
    @Test fun bothProvidersFailingLeavesNoArtifactButChainIsStillRecorded() = runTest {
        val tmpDir = createTempDirectory("e2e-fallback-dead").toFile()
        val (sessionStore, bus) = newSessionStore()
        val sessionId = primeSession(sessionStore)

        val tracker = AgentProviderFallbackTracker(bus, trackerScope)

        val projectStore = ProjectStoreTestKit.create()
        val pid = ProjectId("e2e-fallback-dead")
        projectStore.upsert(
            "e2e-fallback-dead",
            Project(id = pid, timeline = Timeline(tracks = emptyList(), duration = Duration.ZERO)),
        )

        val imageEngine = OneShotImageEngine()
        val registry = ToolRegistry()
        registry.register(GenerateImageTool(imageEngine, FakeBlobWriter(tmpDir), projectStore))

        val failingTurn = listOf(
            LlmEvent.Error("HTTP 503: overloaded", retriable = true),
            LlmEvent.StepFinish(FinishReason.ERROR, TokenUsage.ZERO),
        )
        val primary = FakeProvider(List(2) { failingTurn }, id = "dead-primary")
        val secondary = FakeProvider(List(2) { failingTurn }, id = "dead-secondary")

        val agent = Agent(
            provider = primary,
            registry = registry,
            store = sessionStore,
            permissions = AllowAllPermissionService(),
            bus = bus,
            retryPolicy = RetryPolicy(maxAttempts = 2, initialDelayMs = 0, maxDelayNoHeadersMs = 0),
            fallbackProviders = listOf(secondary),
        )

        val asst = agent.run(RunInput(sessionId, "draw me something", ModelRef("fake", "test")))
        assertEquals(FinishReason.ERROR, asst.finish, "exhausted chain must surface terminal error")
        assertEquals(0, imageEngine.calls, "no tool call reaches the AIGC engine when both providers die")
        assertEquals(
            0, projectStore.get(pid)!!.lockfile.entries.size,
            "failed-chain turn must not leave a lockfile entry behind",
        )

        yield()
        val hops = tracker.hops(sessionId)
        assertEquals(1, hops.size, "the A→B advance itself happened even though B later also failed")
        assertEquals("dead-primary", hops.single().fromProviderId)
        assertEquals("dead-secondary", hops.single().toProviderId)
    }

    /**
     * 反直觉边界 — the "no fallback happened, so tracker stays empty" branch
     * (§3a #9). If provider A succeeds on the first try the Agent never
     * touches the fallback chain, and the tracker's snapshot for this
     * session is empty. Guards against a regression that eagerly pre-populates
     * hops on every run.
     */
    @Test fun singleProviderSucceedingLeavesTrackerEmpty() = runTest {
        val tmpDir = createTempDirectory("e2e-fallback-noop").toFile()
        val (sessionStore, bus) = newSessionStore()
        val sessionId = primeSession(sessionStore)

        val tracker = AgentProviderFallbackTracker(bus, trackerScope)

        val projectStore = ProjectStoreTestKit.create()
        val pid = ProjectId("e2e-fallback-noop")
        projectStore.upsert(
            "e2e-fallback-noop",
            Project(id = pid, timeline = Timeline(tracks = emptyList(), duration = Duration.ZERO)),
        )

        val imageEngine = OneShotImageEngine()
        val registry = ToolRegistry()
        registry.register(GenerateImageTool(imageEngine, FakeBlobWriter(tmpDir), projectStore))

        val toolPartId = PartId("tool-happy")
        val callId = CallId("call-happy")
        val toolInput = buildJsonObject {
            put("prompt", "a blue apple")
            put("projectId", pid.value)
            put("consistencyBindingIds", buildJsonArray { })
            put("width", 256)
            put("height", 256)
            put("seed", 7L)
            put("model", "stub-image-1")
        }
        val turnToolCall = listOf(
            LlmEvent.ToolCallStart(toolPartId, callId, "generate_image"),
            LlmEvent.ToolCallReady(toolPartId, callId, "generate_image", toolInput),
            LlmEvent.StepFinish(FinishReason.TOOL_CALLS, TokenUsage(input = 6, output = 4)),
        )
        val replyPartId = PartId("text-happy")
        val turnReply = listOf(
            LlmEvent.TextStart(replyPartId),
            LlmEvent.TextEnd(replyPartId, "done."),
            LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage(input = 10, output = 2)),
        )
        val primary = FakeProvider(listOf(turnToolCall, turnReply), id = "llm-primary-only")
        // A fallback IS wired — the invariant is that it stays untouched
        // because the primary didn't fail. `FakeProvider(emptyList())` would
        // throw if consulted.
        val secondary = FakeProvider(emptyList(), id = "unused-secondary")

        val agent = Agent(
            provider = primary,
            registry = registry,
            store = sessionStore,
            permissions = AllowAllPermissionService(),
            bus = bus,
            retryPolicy = RetryPolicy(maxAttempts = 2, initialDelayMs = 0, maxDelayNoHeadersMs = 0),
            fallbackProviders = listOf(secondary),
        )

        val asst = agent.run(RunInput(sessionId, "draw", ModelRef("fake", "test")))
        assertEquals(FinishReason.END_TURN, asst.finish)
        assertEquals(2, primary.requests.size, "primary handles both turns alone")
        assertEquals(0, secondary.requests.size, "secondary must not be consulted when primary succeeds")
        assertEquals(1, imageEngine.calls, "asset produced by primary's turn")
        assertEquals(1, projectStore.get(pid)!!.lockfile.entries.size)

        yield()
        assertTrue(
            tracker.hops(sessionId).isEmpty(),
            "no fallback happened → tracker snapshot stays empty",
        )
    }

    private fun newSessionStore(): Pair<SqlDelightSessionStore, EventBus> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val bus = EventBus()
        return SqlDelightSessionStore(db, bus) to bus
    }

    private suspend fun primeSession(store: SqlDelightSessionStore): SessionId {
        val sid = SessionId("fallback-e2e-session")
        val now = Clock.System.now()
        store.createSession(
            Session(
                id = sid,
                projectId = ProjectId("proj"),
                title = "fallback-e2e",
                createdAt = now,
                updatedAt = now,
            ),
        )
        return sid
    }
}
