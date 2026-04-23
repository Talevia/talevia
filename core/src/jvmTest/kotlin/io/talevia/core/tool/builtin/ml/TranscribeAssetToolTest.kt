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
import io.talevia.core.platform.AsrEngine
import io.talevia.core.platform.AsrRequest
import io.talevia.core.platform.AsrResult
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.platform.TranscriptSegment
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class TranscribeAssetToolTest {

    private class RecordingAsrEngine(
        private val text: String = "hello world",
        private val language: String? = "en",
        private val segments: List<TranscriptSegment> = listOf(
            TranscriptSegment(0L, 1500L, "hello"),
            TranscriptSegment(1500L, 3000L, "world"),
        ),
    ) : AsrEngine {
        override val providerId: String = "fake"
        var lastRequest: AsrRequest? = null

        override suspend fun transcribe(request: AsrRequest): AsrResult {
            lastRequest = request
            return AsrResult(
                text = text,
                language = language,
                segments = segments,
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
        bundleRelative: String = "media/$assetId.wav",
    ): Pair<FileProjectStore, ProjectId> {
        val store = ProjectStoreTestKit.create()
        val pid = ProjectId("p-transcribe")
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

    @Test fun resolvesAssetPathAndReturnsSegments() = runTest {
        val (store, pid) = setupStoreWithAsset("vlog-1")
        val engine = RecordingAsrEngine()
        val tool = TranscribeAssetTool(engine, store)
        val out = tool.execute(
            TranscribeAssetTool.Input(assetId = "vlog-1"),
            ctx(pid),
        )
        assertTrue(engine.lastRequest?.audioPath?.endsWith("media/vlog-1.wav") == true)
        assertEquals("whisper-1", engine.lastRequest?.modelId)
        assertEquals(2, out.data.segments.size)
        assertEquals("hello world", out.data.text)
        assertEquals("en", out.data.detectedLanguage)
        assertEquals("fake", out.data.providerId)
    }

    @Test fun forwardsLanguageHintAndCustomModel() = runTest {
        val (store, pid) = setupStoreWithAsset("clip")
        val engine = RecordingAsrEngine()
        val tool = TranscribeAssetTool(engine, store)
        tool.execute(
            TranscribeAssetTool.Input(assetId = "clip", model = "whisper-large-v3", language = "zh"),
            ctx(pid),
        )
        assertEquals("whisper-large-v3", engine.lastRequest?.modelId)
        assertEquals("zh", engine.lastRequest?.languageHint)
    }

    @Test fun blankLanguageIsTreatedAsAutoDetect() = runTest {
        val (store, pid) = setupStoreWithAsset("x")
        val engine = RecordingAsrEngine()
        val tool = TranscribeAssetTool(engine, store)
        tool.execute(
            TranscribeAssetTool.Input(assetId = "x", language = "  "),
            ctx(pid),
        )
        assertEquals(null, engine.lastRequest?.languageHint)
    }

    @Test fun outputForLlmIncludesPreviewAndCounts() = runTest {
        val (store, pid) = setupStoreWithAsset("x")
        val text = "a".repeat(200)
        val engine = RecordingAsrEngine(text = text)
        val tool = TranscribeAssetTool(engine, store)
        val out = tool.execute(TranscribeAssetTool.Input(assetId = "x"), ctx(pid))
        assertNotNull(out.outputForLlm)
        assertTrue("2 segment(s)" in out.outputForLlm, "should report segment count")
        assertTrue(out.outputForLlm.contains("…"), "long text should be truncated with ellipsis")
    }
}
