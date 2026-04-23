package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.AsrEngine
import io.talevia.core.platform.AsrRequest
import io.talevia.core.platform.AsrResult
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.platform.TranscriptSegment
import io.talevia.core.session.Part
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AutoSubtitleClipToolTest {

    private class FakeAsr(
        private val segments: List<TranscriptSegment>,
        private val detectedLanguage: String? = "en",
    ) : AsrEngine {
        override val providerId: String = "fake-whisper"
        var calls: Int = 0
            private set
        var lastRequest: AsrRequest? = null
            private set

        override suspend fun transcribe(request: AsrRequest): AsrResult {
            calls += 1
            lastRequest = request
            return AsrResult(
                text = segments.joinToString(" ") { it.text },
                language = detectedLanguage,
                segments = segments,
                provenance = GenerationProvenance(
                    providerId = providerId,
                    modelId = request.modelId,
                    modelVersion = null,
                    seed = 0L,
                    parameters = JsonObject(emptyMap()),
                    createdAtEpochMs = 1_700_000_000_000L,
                ),
            )
        }
    }

    private fun ctx(captured: MutableList<Part>): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { captured += it },
        messages = emptyList(),
    )

    private suspend fun newFixture(
        clipStart: Duration = 2.seconds,
        clipDuration: Duration = 10.seconds,
    ): Triple<FileProjectStore, ProjectId, ClipId> {
        val store = ProjectStoreTestKit.create()
        val pid = ProjectId("p-auto")
        val clipId = ClipId("c-1")
        val assetId = AssetId("a-1")
        val asset = MediaAsset(
            id = assetId,
            source = MediaSource.File("/tmp/a.mp4"),
            metadata = MediaMetadata(duration = clipDuration),
        )
        val clip = Clip.Video(
            id = clipId,
            timeRange = TimeRange(clipStart, clipDuration),
            sourceRange = TimeRange(Duration.ZERO, clipDuration),
            assetId = assetId,
        )
        store.upsert(
            "demo",
            Project(
                id = pid,
                assets = listOf(asset),
                timeline = Timeline(
                    tracks = listOf(Track.Video(id = TrackId("v"), clips = listOf(clip))),
                    duration = clipStart + clipDuration,
                ),
            ),
        )
        return Triple(store, pid, clipId)
    }

    @Test fun placesSegmentsOffsetByClipStart() = runTest {
        val (store, pid, clipId) = newFixture(clipStart = 2.seconds, clipDuration = 10.seconds)
        val segs = listOf(
            TranscriptSegment(startMs = 0, endMs = 1500, text = "hello"),
            TranscriptSegment(startMs = 1500, endMs = 3000, text = "world"),
        )
        val tool = AutoSubtitleClipTool(FakeAsr(segs), store)
        val parts = mutableListOf<Part>()
        val result = tool.execute(
            AutoSubtitleClipTool.Input(projectId = pid.value, clipId = clipId.value),
            ctx(parts),
        )

        assertEquals(2, result.data.segmentCount)
        assertEquals(0, result.data.droppedSegmentCount)
        assertEquals("en", result.data.detectedLanguage)

        val project = store.get(pid)!!
        val subtitleTrack = project.timeline.tracks.filterIsInstance<Track.Subtitle>().single()
        val texts = subtitleTrack.clips.filterIsInstance<Clip.Text>()
        assertEquals(2, texts.size)
        // First segment starts at clipStart (2s) with 1500ms duration.
        assertEquals(2.seconds, texts[0].timeRange.start)
        assertEquals(1500.milliseconds, texts[0].timeRange.duration)
        assertEquals("hello", texts[0].text)
        // Second starts at clipStart + 1500ms and ends at clipStart + 3000ms.
        assertEquals(2.seconds + 1500.milliseconds, texts[1].timeRange.start)
        assertEquals(1500.milliseconds, texts[1].timeRange.duration)

        // Exactly one timeline snapshot, not two — atomic edit.
        assertEquals(1, parts.count { it is Part.TimelineSnapshot })
    }

    @Test fun clampsSegmentsExtendingPastClipEnd() = runTest {
        val (store, pid, clipId) = newFixture(clipStart = 0.seconds, clipDuration = 5.seconds)
        val segs = listOf(
            TranscriptSegment(startMs = 4500, endMs = 7000, text = "trails off"),
        )
        val tool = AutoSubtitleClipTool(FakeAsr(segs), store)
        val parts = mutableListOf<Part>()
        val result = tool.execute(
            AutoSubtitleClipTool.Input(projectId = pid.value, clipId = clipId.value),
            ctx(parts),
        )

        assertEquals(1, result.data.segmentCount)
        assertEquals(0, result.data.droppedSegmentCount)
        val text = store.get(pid)!!.timeline.tracks
            .filterIsInstance<Track.Subtitle>()
            .single()
            .clips
            .filterIsInstance<Clip.Text>()
            .single()
        // Segment clamped from 4500..7000 to 4500..5000 (clip ends at 5s).
        assertEquals(4500.milliseconds, text.timeRange.start)
        assertEquals(500.milliseconds, text.timeRange.duration)
    }

    @Test fun dropsSegmentsThatStartPastClipEnd() = runTest {
        val (store, pid, clipId) = newFixture(clipStart = 0.seconds, clipDuration = 5.seconds)
        val segs = listOf(
            TranscriptSegment(startMs = 500, endMs = 2000, text = "in"),
            TranscriptSegment(startMs = 6000, endMs = 8000, text = "out"),
        )
        val tool = AutoSubtitleClipTool(FakeAsr(segs), store)
        val result = tool.execute(
            AutoSubtitleClipTool.Input(projectId = pid.value, clipId = clipId.value),
            ctx(mutableListOf()),
        )
        assertEquals(1, result.data.segmentCount)
        assertEquals(1, result.data.droppedSegmentCount)
    }

    @Test fun errorsWhenClipHasNoAsset() = runTest {
        val store = ProjectStoreTestKit.create()
        val pid = ProjectId("p-text")
        val clipId = ClipId("c-text")
        val textClip = Clip.Text(
            id = clipId,
            timeRange = TimeRange(0.seconds, 1.seconds),
            text = "already a caption",
        )
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(
                    tracks = listOf(Track.Subtitle(id = TrackId("s"), clips = listOf(textClip))),
                    duration = 1.seconds,
                ),
            ),
        )
        val tool = AutoSubtitleClipTool(FakeAsr(emptyList()), store)
        val ex = runCatching {
            tool.execute(
                AutoSubtitleClipTool.Input(projectId = pid.value, clipId = clipId.value),
                ctx(mutableListOf()),
            )
        }.exceptionOrNull()
        assertTrue(ex is IllegalStateException)
        assertTrue(ex!!.message!!.contains("not found or has no"), ex.message)
    }

    @Test fun forwardsLanguageHintToEngine() = runTest {
        val (store, pid, clipId) = newFixture()
        val engine = FakeAsr(listOf(TranscriptSegment(0, 500, "hi")))
        val tool = AutoSubtitleClipTool(engine, store)
        tool.execute(
            AutoSubtitleClipTool.Input(projectId = pid.value, clipId = clipId.value, language = "zh"),
            ctx(mutableListOf()),
        )
        assertEquals("zh", engine.lastRequest?.languageHint)
    }
}
