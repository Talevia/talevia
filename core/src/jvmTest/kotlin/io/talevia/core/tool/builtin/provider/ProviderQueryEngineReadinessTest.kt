package io.talevia.core.tool.builtin.provider

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.buildEngineReadinessSnapshot
import io.talevia.core.provider.ProviderRegistry
import io.talevia.core.provider.ProviderWarmupStats
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.provider.query.EngineReadinessRow
import io.talevia.core.tool.query.decodeRowsAs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for `provider_query(select=engine_readiness)` — the
 * per-engine wiring snapshot built at container bootstrap.
 */
class ProviderQueryEngineReadinessTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun emptyRegistry(): ProviderRegistry =
        ProviderRegistry(byId = emptyMap(), default = null)

    private fun toolWith(engineReadiness: List<EngineReadinessRow>?): ProviderQueryTool =
        ProviderQueryTool(
            emptyRegistry(),
            ProviderWarmupStats.withSupervisor(EventBus()),
            ProjectStoreTestKit.create(),
            engineReadiness = engineReadiness,
        )

    @Test fun unwiredSnapshotReportsZeroRowsWithNote() = runTest {
        val tool = ProviderQueryTool(
            emptyRegistry(),
            ProviderWarmupStats.withSupervisor(EventBus()),
            ProjectStoreTestKit.create(),
            // engineReadiness deliberately omitted (defaults to null).
        )
        val result = tool.execute(
            ProviderQueryTool.Input(select = "engine_readiness"),
            ctx(),
        )
        assertEquals(0, result.data.total)
        assertTrue("expected 'not wired' note: ${result.outputForLlm}") {
            result.outputForLlm.contains("not wired")
        }
    }

    @Test fun emptySnapshotReportsZeroRowsWithEmptyNote() = runTest {
        val tool = toolWith(emptyList())
        val result = tool.execute(
            ProviderQueryTool.Input(select = "engine_readiness"),
            ctx(),
        )
        assertEquals(0, result.data.total)
        // Empty snapshot is distinct from a missing snapshot — note
        // shape differs.
        assertTrue("expected 'no AIGC engines' note: ${result.outputForLlm}") {
            result.outputForLlm.contains("no AIGC engines")
        }
    }

    @Test fun fullyUnwiredAndroidLikeSnapshotReportsAllFalseRows() = runTest {
        // No engines passed = all-null = the Android / iOS shape.
        val tool = toolWith(buildEngineReadinessSnapshot())
        val out = tool.execute(
            ProviderQueryTool.Input(select = "engine_readiness"),
            ctx(),
        ).data

        assertEquals(8, out.total, "8 known engine kinds")
        val rows = out.rows.decodeRowsAs(EngineReadinessRow.serializer())
        assertEquals(8, rows.size)
        assertTrue(rows.all { !it.wired }, "all engines should report wired=false")
        // missingEnvVar must be populated for every unwired engine — the
        // agent's recovery action.
        assertTrue(rows.all { it.missingEnvVar != null }, "all rows should name missing env var")
        // Sorted alphabetically for stable diffing.
        assertEquals(rows.map { it.engineKind }.sorted(), rows.map { it.engineKind })
        // Check a couple of representative mappings.
        val byKind = rows.associateBy { it.engineKind }
        assertEquals("openai", byKind["image_gen"]?.providerId)
        assertEquals("OPENAI_API_KEY", byKind["image_gen"]?.missingEnvVar)
        assertEquals("replicate", byKind["music_gen"]?.providerId)
        assertEquals("REPLICATE_API_TOKEN", byKind["music_gen"]?.missingEnvVar)
        assertEquals("tavily", byKind["search"]?.providerId)
        assertEquals("TAVILY_API_KEY", byKind["search"]?.missingEnvVar)
    }

    @Test fun partiallyWiredSnapshotMixesFlags() = runTest {
        // Server-like rig with only OPENAI_API_KEY set: image_gen / video_gen /
        // tts / asr / vision wired, music_gen / upscale / search not.
        val tool = toolWith(
            buildEngineReadinessSnapshot(
                imageGen = NoOpImageGen,
                videoGen = NoOpVideoGen,
                tts = NoOpTts,
                asr = NoOpAsr,
                vision = NoOpVision,
            ),
        )
        val out = tool.execute(
            ProviderQueryTool.Input(select = "engine_readiness"),
            ctx(),
        ).data

        val rows = out.rows.decodeRowsAs(EngineReadinessRow.serializer())
        val byKind = rows.associateBy { it.engineKind }
        // OpenAI engines: wired, no missingEnvVar.
        listOf("image_gen", "video_gen", "tts", "asr", "vision").forEach { kind ->
            val row = byKind[kind]
            assertNotNull(row, "row for $kind")
            assertTrue(row.wired, "$kind should be wired")
            assertNull(row.missingEnvVar, "$kind should have no missingEnvVar")
        }
        // Replicate engines: not wired.
        listOf("music_gen", "upscale").forEach { kind ->
            val row = byKind[kind]
            assertNotNull(row)
            assertTrue(!row.wired, "$kind should NOT be wired")
            assertEquals("REPLICATE_API_TOKEN", row.missingEnvVar)
        }
        // Tavily search: not wired.
        val search = byKind["search"]
        assertNotNull(search)
        assertTrue(!search.wired)
        assertEquals("TAVILY_API_KEY", search.missingEnvVar)
    }
}

// Stub engines — only their non-null reference is observed by the
// snapshot builder, so empty implementations suffice.
private object NoOpImageGen : io.talevia.core.platform.ImageGenEngine {
    override val providerId: String = "noop"
    override suspend fun generate(
        request: io.talevia.core.platform.ImageGenRequest,
    ): io.talevia.core.platform.ImageGenResult = error("not used")
}

private object NoOpVideoGen : io.talevia.core.platform.VideoGenEngine {
    override val providerId: String = "noop"
    override suspend fun generate(
        request: io.talevia.core.platform.VideoGenRequest,
    ): io.talevia.core.platform.VideoGenResult = error("not used")
}

private object NoOpTts : io.talevia.core.platform.TtsEngine {
    override val providerId: String = "noop"
    override suspend fun synthesize(
        request: io.talevia.core.platform.TtsRequest,
    ): io.talevia.core.platform.TtsResult = error("not used")
}

private object NoOpAsr : io.talevia.core.platform.AsrEngine {
    override val providerId: String = "noop"
    override suspend fun transcribe(
        request: io.talevia.core.platform.AsrRequest,
    ): io.talevia.core.platform.AsrResult = error("not used")
}

private object NoOpVision : io.talevia.core.platform.VisionEngine {
    override val providerId: String = "noop"
    override suspend fun describe(
        request: io.talevia.core.platform.VisionRequest,
    ): io.talevia.core.platform.VisionResult = error("not used")
}
