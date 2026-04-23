package io.talevia.core.tool.builtin.ml

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Timeline
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.platform.VisionEngine
import io.talevia.core.platform.VisionRequest
import io.talevia.core.platform.VisionResult
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class DescribeAssetToolTest {

    private class RecordingVisionEngine(
        private val text: String = "a cat on a sunlit windowsill",
    ) : VisionEngine {
        override val providerId: String = "fake"
        var lastRequest: VisionRequest? = null

        override suspend fun describe(request: VisionRequest): VisionResult {
            lastRequest = request
            return VisionResult(
                text = text,
                provenance = GenerationProvenance(
                    providerId = providerId,
                    modelId = request.modelId,
                    modelVersion = null,
                    seed = 0L,
                    parameters = JsonObject(emptyMap()),
                    createdAtEpochMs = 0L,
                ),
            )
        }
    }

    private suspend fun setupStoreWithAsset(
        assetId: String,
        bundleRelative: String = "media/$assetId.png",
    ): Pair<FileProjectStore, ProjectId> {
        val store = ProjectStoreTestKit.create()
        val pid = ProjectId("p-describe")
        val asset = MediaAsset(
            id = AssetId(assetId),
            source = MediaSource.BundleFile(bundleRelative),
            metadata = MediaMetadata(duration = Duration.ZERO),
        )
        store.upsert(
            "demo",
            Project(id = pid, assets = listOf(asset), timeline = Timeline()),
        )
        return store to pid
    }

    private fun ctx(pid: ProjectId) = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
        currentProjectId = pid,
    )

    @Test fun resolvesAssetPathAndReturnsDescription() = runTest {
        val (store, pid) = setupStoreWithAsset("photo-1")
        val engine = RecordingVisionEngine()
        val tool = DescribeAssetTool(engine, store)
        val out = tool.execute(DescribeAssetTool.Input(assetId = "photo-1"), ctx(pid))
        assertTrue(engine.lastRequest?.imagePath?.endsWith("media/photo-1.png") == true)
        assertEquals("gpt-4o-mini", engine.lastRequest?.modelId)
        assertNull(engine.lastRequest?.prompt)
        assertEquals("a cat on a sunlit windowsill", out.data.text)
        assertEquals("fake", out.data.providerId)
    }

    @Test fun forwardsCustomPromptAndModel() = runTest {
        val (store, pid) = setupStoreWithAsset("frame")
        val engine = RecordingVisionEngine()
        val tool = DescribeAssetTool(engine, store)
        tool.execute(
            DescribeAssetTool.Input(
                assetId = "frame",
                prompt = "What brand is on the mug?",
                model = "gpt-4o",
            ),
            ctx(pid),
        )
        assertEquals("gpt-4o", engine.lastRequest?.modelId)
        assertEquals("What brand is on the mug?", engine.lastRequest?.prompt)
    }

    @Test fun blankPromptIsTreatedAsOmitted() = runTest {
        val (store, pid) = setupStoreWithAsset("x")
        val engine = RecordingVisionEngine()
        val tool = DescribeAssetTool(engine, store)
        val out = tool.execute(
            DescribeAssetTool.Input(assetId = "x", prompt = "   "),
            ctx(pid),
        )
        assertNull(engine.lastRequest?.prompt)
        assertNull(out.data.prompt)
    }

    @Test fun outputForLlmIncludesPreviewAndEllipsizesLongText() = runTest {
        val (store, pid) = setupStoreWithAsset("x")
        val text = "a".repeat(300)
        val engine = RecordingVisionEngine(text = text)
        val tool = DescribeAssetTool(engine, store)
        val out = tool.execute(DescribeAssetTool.Input(assetId = "x"), ctx(pid))
        assertNotNull(out.outputForLlm)
        assertTrue("fake/gpt-4o-mini" in out.outputForLlm)
        assertTrue(out.outputForLlm.contains("…"), "long text should be truncated with ellipsis")
    }

    @Test fun shortTextHasNoEllipsis() = runTest {
        val (store, pid) = setupStoreWithAsset("x")
        val engine = RecordingVisionEngine(text = "short description")
        val tool = DescribeAssetTool(engine, store)
        val out = tool.execute(DescribeAssetTool.Input(assetId = "x"), ctx(pid))
        assertTrue("short description" in out.outputForLlm)
        assertTrue("…" !in out.outputForLlm, "short text should not be ellipsized")
    }
}
