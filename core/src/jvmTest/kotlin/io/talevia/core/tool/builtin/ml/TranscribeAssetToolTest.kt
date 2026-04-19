package io.talevia.core.tool.builtin.ml

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.AsrEngine
import io.talevia.core.platform.AsrRequest
import io.talevia.core.platform.AsrResult
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.platform.MediaPathResolver
import io.talevia.core.platform.TranscriptSegment
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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

    private val resolver = MediaPathResolver { id -> "/tmp/${id.value}.wav" }
    private val ctx = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    @Test fun resolvesAssetPathAndReturnsSegments() = runTest {
        val engine = RecordingAsrEngine()
        val tool = TranscribeAssetTool(engine, resolver)
        val out = tool.execute(
            TranscribeAssetTool.Input(assetId = "vlog-1"),
            ctx,
        )
        assertEquals("/tmp/vlog-1.wav", engine.lastRequest?.audioPath)
        assertEquals("whisper-1", engine.lastRequest?.modelId)
        assertEquals(2, out.data.segments.size)
        assertEquals("hello world", out.data.text)
        assertEquals("en", out.data.detectedLanguage)
        assertEquals("fake", out.data.providerId)
    }

    @Test fun forwardsLanguageHintAndCustomModel() = runTest {
        val engine = RecordingAsrEngine()
        val tool = TranscribeAssetTool(engine, resolver)
        tool.execute(
            TranscribeAssetTool.Input(assetId = AssetId("clip").value, model = "whisper-large-v3", language = "zh"),
            ctx,
        )
        assertEquals("whisper-large-v3", engine.lastRequest?.modelId)
        assertEquals("zh", engine.lastRequest?.languageHint)
    }

    @Test fun blankLanguageIsTreatedAsAutoDetect() = runTest {
        val engine = RecordingAsrEngine()
        val tool = TranscribeAssetTool(engine, resolver)
        tool.execute(
            TranscribeAssetTool.Input(assetId = "x", language = "  "),
            ctx,
        )
        assertEquals(null, engine.lastRequest?.languageHint)
    }

    @Test fun outputForLlmIncludesPreviewAndCounts() = runTest {
        val text = "a".repeat(200)
        val engine = RecordingAsrEngine(text = text)
        val tool = TranscribeAssetTool(engine, resolver)
        val out = tool.execute(TranscribeAssetTool.Input(assetId = "x"), ctx)
        assertNotNull(out.outputForLlm)
        assertTrue("2 segment(s)" in out.outputForLlm, "should report segment count")
        assertTrue(out.outputForLlm.contains("…"), "long text should be truncated with ellipsis")
    }
}
