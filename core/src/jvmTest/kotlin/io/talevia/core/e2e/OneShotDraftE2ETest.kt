package io.talevia.core.e2e

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.agent.Agent
import io.talevia.core.agent.RetryPolicy
import io.talevia.core.agent.RunInput
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.permission.AllowAllPermissionService
import io.talevia.core.platform.BundleBlobWriter
import io.talevia.core.platform.GeneratedVideo
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.platform.VideoGenEngine
import io.talevia.core.platform.VideoGenRequest
import io.talevia.core.platform.VideoGenResult
import io.talevia.core.provider.LlmEvent
import io.talevia.core.provider.LlmProvider
import io.talevia.core.provider.LlmRequest
import io.talevia.core.provider.ModelInfo
import io.talevia.core.session.FinishReason
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.aigc.GenerateVideoTool
import io.talevia.core.tool.builtin.source.SourceNodeActionTool
import io.talevia.core.tool.builtin.video.ClipActionTool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * VISION §4 e2e regression guard for the small-user "一句话出初稿" path.
 *
 * Talevia's 双用户张力 promise hinges on the agent autonomously walking from
 * "user said `make a 30s vlog about my weekend`" → a populated `Project`
 * with sources, AIGC products, and a timeline ready to export. Manual
 * smoke-tests work today, but no regression guard pins the *trajectory*:
 * an LLM-prompt drift that makes the agent skip `source_action` (creating
 * AIGC without consistency bindings) or skip `clip_action` (leaving the
 * timeline empty) would land green on every existing unit test while
 * silently breaking the small-user demo.
 *
 * This test scripts a 5-step LLM trajectory against the real `Agent` +
 * real tool registry (not a mocked dispatcher) and asserts the load-
 * bearing invariants of a one-shot draft:
 *
 *   1. `Project.source.nodes.size ≥ 2` — agent created at least
 *      `character_ref` + `style_bible` source nodes (the §3.3
 *      consistency primitives the AIGC tool will fold into prompts).
 *   2. `Project.lockfile.entries.size ≥ 1` — the `generate_image`
 *      call landed an entry, proving the AIGC lane fired with full
 *      provenance.
 *   3. `Project.assets.size ≥ 1` — the generated image is in
 *      `Project.assets` (not just lockfile-only).
 *   4. `Project.timeline.tracks` has a Video track with ≥ 1 clip —
 *      the agent stitched the AIGC product onto the timeline (not
 *      just generated and dropped). This is the "ready-to-export"
 *      tail of the small-user path.
 *
 * **Lazy turn provider**. Step 4's `clip_action(addItems=[{assetId=...}])`
 * needs the assetId minted by step 3's `generate_image`. Because the
 * assetId is `Uuid.random()` at AIGC dispatch time, it can't be
 * pre-scripted into [FakeProvider]'s static turn list. [LazyTurnProvider]
 * accepts per-turn builders that read the latest project state (via the
 * test's [ProjectStore] reference) at stream-time, so step 4's input is
 * built after step 3's tool dispatch has populated `Project.assets`.
 *
 * If a future agent-loop refactor changes the prompt-chaining contract
 * (e.g. tool dispatch becoming async-out-of-step), this test catches the
 * drift by failing on the lazy assetId lookup returning null.
 */
class OneShotDraftE2ETest {

    @Test fun agentTrajectoryProducesSourcesAigcAndNonEmptyTimeline() = runTest {
        val tmpDir = createTempDirectory("oneshot-draft-").toFile()
        val (sessionStore, bus) = newSessionStore()
        val sessionId = primeSession(sessionStore)

        val projectStore = ProjectStoreTestKit.create()
        val pid = ProjectId("oneshot-draft")
        projectStore.upsert(
            "oneshot-draft",
            Project(id = pid, timeline = Timeline(tracks = emptyList(), duration = Duration.ZERO)),
        )

        val videoEngine = ScriptedVideoEngine()
        val registry = ToolRegistry().apply {
            register(SourceNodeActionTool(projectStore))
            register(GenerateVideoTool(videoEngine, FakeBlobWriter(tmpDir), projectStore))
            register(ClipActionTool(projectStore))
        }

        // 5-step trajectory (one LLM step per tool call, mirroring the
        // realistic agent-loop shape). Each lazy turn closes over
        // projectStore so step 4 can read the freshly-minted assetId.
        val provider = LazyTurnProvider(
            id = "oneshot-fake",
            turns = listOf(
                // Step 1 — character_ref (the genre's main visual binding).
                { _ ->
                    val toolPart = PartId("p-source-1")
                    val callId = CallId("call-source-1")
                    val input = buildJsonObject {
                        put("projectId", pid.value)
                        put("action", "add")
                        put("nodeId", "hero")
                        put("kind", "character_ref")
                        putJsonObject("body") {
                            put("visualDescription", "a curious red panda explorer in a denim vest")
                        }
                    }
                    listOf(
                        LlmEvent.ToolCallStart(toolPart, callId, "source_node_action"),
                        LlmEvent.ToolCallReady(toolPart, callId, "source_node_action", input),
                        LlmEvent.StepFinish(FinishReason.TOOL_CALLS, TokenUsage(input = 8, output = 4)),
                    )
                },
                // Step 2 — style_bible (cross-cutting consistency primitive).
                { _ ->
                    val toolPart = PartId("p-source-2")
                    val callId = CallId("call-source-2")
                    val input = buildJsonObject {
                        put("projectId", pid.value)
                        put("action", "add")
                        put("nodeId", "warm-dusk-style")
                        put("kind", "style_bible")
                        putJsonObject("body") {
                            put("style", "warm dusk lighting, painterly highlights")
                        }
                    }
                    listOf(
                        LlmEvent.ToolCallStart(toolPart, callId, "source_node_action"),
                        LlmEvent.ToolCallReady(toolPart, callId, "source_node_action", input),
                        LlmEvent.StepFinish(FinishReason.TOOL_CALLS, TokenUsage(input = 12, output = 4)),
                    )
                },
                // Step 3 — AIGC fires; the engine mints an assetId + lockfile
                // entry. Video gen (not image gen) so the asset has non-zero
                // metadata.duration and `clip_action(add)` accepts it without
                // needing a still-to-video duration override.
                { _ ->
                    val toolPart = PartId("p-aigc")
                    val callId = CallId("call-aigc")
                    val input = buildJsonObject {
                        put("prompt", "the red panda explorer at dusk")
                        put("projectId", pid.value)
                        put("width", 256)
                        put("height", 256)
                        put("durationSeconds", 4.0)
                        put("seed", 7L)
                        put("model", "stub-video-1")
                        // Empty bindings — keeps the test focused on the
                        // trajectory shape; consistency-fold semantics are
                        // separately covered by RefactorLoopE2ETest.
                        put("consistencyBindingIds", buildJsonArray { })
                    }
                    listOf(
                        LlmEvent.ToolCallStart(toolPart, callId, "generate_video"),
                        LlmEvent.ToolCallReady(toolPart, callId, "generate_video", input),
                        LlmEvent.StepFinish(FinishReason.TOOL_CALLS, TokenUsage(input = 20, output = 6)),
                    )
                },
                // Step 4 — clip_action add. Lazy: read the latest assetId
                // from projectStore (just minted by step 3) and feed it
                // into addItems.
                { _ ->
                    val assetId = projectStore.get(pid)
                        ?.assets
                        ?.lastOrNull()
                        ?.id
                        ?.value
                        ?: error("step 4 expected an asset to exist by now (step 3's AIGC should have minted one)")
                    val toolPart = PartId("p-clip")
                    val callId = CallId("call-clip")
                    val input = buildJsonObject {
                        put("projectId", pid.value)
                        put("action", "add")
                        put(
                            "addItems",
                            buildJsonArray {
                                addJsonObject { put("assetId", assetId) }
                            },
                        )
                    }
                    listOf(
                        LlmEvent.ToolCallStart(toolPart, callId, "clip_action"),
                        LlmEvent.ToolCallReady(toolPart, callId, "clip_action", input),
                        LlmEvent.StepFinish(FinishReason.TOOL_CALLS, TokenUsage(input = 28, output = 5)),
                    )
                },
                // Step 5 — END_TURN with the agent's natural-language summary.
                { _ ->
                    val replyPart = PartId("p-summary")
                    listOf(
                        LlmEvent.TextStart(replyPart),
                        LlmEvent.TextEnd(replyPart, "draft ready: 1 character, 1 style, 1 generated clip on track."),
                        LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage(input = 35, output = 12)),
                    )
                },
            ),
        )

        val agent = Agent(
            provider = provider,
            registry = registry,
            store = sessionStore,
            permissions = AllowAllPermissionService(),
            bus = bus,
            retryPolicy = RetryPolicy(maxAttempts = 1, initialDelayMs = 0, maxDelayNoHeadersMs = 0),
        )

        val asst = agent.run(
            RunInput(sessionId, "make me a short vlog about my red panda field trip", ModelRef("fake", "test")),
        )
        assertEquals(FinishReason.END_TURN, asst.finish, "trajectory must end cleanly with summary")

        val finalProject = projectStore.get(pid)
            ?: error("project missing after trajectory")

        // Assertion 1: at least 2 source nodes (character_ref + style_bible).
        assertTrue(
            finalProject.source.nodes.size >= 2,
            "expected ≥ 2 source nodes after small-user trajectory, got ${finalProject.source.nodes.size}: " +
                finalProject.source.nodes.joinToString { "${it.id.value}:${it.kind}" },
        )

        // Assertion 2: at least 1 lockfile entry — AIGC fired with provenance.
        assertTrue(
            finalProject.lockfile.entries.isNotEmpty(),
            "expected ≥ 1 lockfile entry after generate_image step, got 0 — AIGC didn't land",
        )
        assertEquals(1, videoEngine.calls, "video engine should have been called exactly once")

        // Assertion 3: the generated asset is in Project.assets (not lockfile-only).
        assertTrue(
            finalProject.assets.isNotEmpty(),
            "generate_image must append to Project.assets, got 0 assets",
        )

        // Assertion 4: timeline has a Video track with ≥ 1 clip — clip_action
        // landed the AIGC product on the timeline. This is the "ready-to-export"
        // tail of the small-user path.
        val videoTracks = finalProject.timeline.tracks.filterIsInstance<Track.Video>()
        assertTrue(videoTracks.isNotEmpty(), "timeline must have at least one Video track after clip_action(add)")
        assertTrue(
            videoTracks.first().clips.isNotEmpty(),
            "first Video track must have ≥ 1 clip after clip_action(add) — got 0; trajectory left the timeline empty",
        )
    }

    /**
     * Records call count + returns deterministic bytes with non-zero duration
     * so [ClipActionTool]'s `add` verb accepts the resulting asset.
     * Image-engine variants exist in `ProviderFallbackE2ETest`; this one is
     * scoped to video gen because that's the lane the small-user one-shot
     * draft path lands assets on a Video track from.
     */
    private class ScriptedVideoEngine : VideoGenEngine {
        override val providerId: String = "scripted-video"
        var calls: Int = 0
            private set

        override suspend fun generate(request: VideoGenRequest): VideoGenResult {
            calls += 1
            return VideoGenResult(
                videos = listOf(
                    GeneratedVideo(
                        mp4Bytes = ByteArray(16) { 0x37 },
                        width = request.width,
                        height = request.height,
                        durationSeconds = request.durationSeconds,
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

    /**
     * [LlmProvider] whose per-turn event lists are produced by lambdas
     * evaluated at `stream()` time — letting the test read the live
     * `ProjectStore` between turns. Each lambda receives the [LlmRequest]
     * the agent built (so a lambda can also inspect tool-result message
     * history if it prefers to extract values from there). Throws if the
     * agent makes more turns than scripted.
     */
    private class LazyTurnProvider(
        override val id: String,
        turns: List<suspend (LlmRequest) -> List<LlmEvent>>,
    ) : LlmProvider {
        private val turnQueue = ArrayDeque(turns)

        override suspend fun listModels(): List<ModelInfo> = emptyList()

        override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
            val build = turnQueue.removeFirstOrNull()
                ?: error("LazyTurnProvider exhausted (agent made more turns than scripted)")
            for (e in build(request)) emit(e)
        }
    }

    private fun newSessionStore(): Pair<SqlDelightSessionStore, EventBus> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val bus = EventBus()
        return SqlDelightSessionStore(db, bus) to bus
    }

    private suspend fun primeSession(store: SqlDelightSessionStore): SessionId {
        val sid = SessionId("oneshot-draft-session")
        val now = Clock.System.now()
        store.createSession(
            Session(
                id = sid,
                projectId = ProjectId("oneshot-draft"),
                title = "oneshot-draft",
                createdAt = now,
                updatedAt = now,
            ),
        )
        return sid
    }
}
