package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.TextStyle
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.addNode
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.session.Part
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SetClipSourceBindingToolTest {

    private fun ctx(parts: MutableList<Part>): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { parts += it },
        messages = emptyList(),
    )

    /**
     * Fixture: three clips (Video / Audio / Text), each with an initial sourceBinding
     * of `{node-a}`. Source has three nodes (`node-a`, `node-b`, `node-c`) so we can
     * exercise both "replace" and "clear".
     */
    private suspend fun fixture(): Pair<FileProjectStore, ProjectId> {
        val store = ProjectStoreTestKit.create()
        val pid = ProjectId("p")

        val videoTrack = Track.Video(
            id = TrackId("v"),
            clips = listOf(
                Clip.Video(
                    id = ClipId("c-video"),
                    timeRange = TimeRange(0.seconds, 5.seconds),
                    sourceRange = TimeRange(0.seconds, 5.seconds),
                    assetId = AssetId("asset-1"),
                    sourceBinding = setOf(SourceNodeId("node-a")),
                ),
            ),
        )
        val audioTrack = Track.Audio(
            id = TrackId("a"),
            clips = listOf(
                Clip.Audio(
                    id = ClipId("c-audio"),
                    timeRange = TimeRange(0.seconds, 4.seconds),
                    sourceRange = TimeRange(0.seconds, 4.seconds),
                    assetId = AssetId("asset-2"),
                    sourceBinding = setOf(SourceNodeId("node-a")),
                ),
            ),
        )
        val subtitleTrack = Track.Subtitle(
            id = TrackId("sub"),
            clips = listOf(
                Clip.Text(
                    id = ClipId("c-text"),
                    timeRange = TimeRange(0.seconds, 3.seconds),
                    text = "hello",
                    style = TextStyle(),
                    sourceBinding = setOf(SourceNodeId("node-a")),
                ),
            ),
        )
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(
                    tracks = listOf(videoTrack, audioTrack, subtitleTrack),
                    duration = 5.seconds,
                ),
            ),
        )
        store.mutateSource(pid) { src ->
            src.addNode(SourceNode(id = SourceNodeId("node-a"), kind = "test.kind"))
                .addNode(SourceNode(id = SourceNodeId("node-b"), kind = "test.kind"))
                .addNode(SourceNode(id = SourceNodeId("node-c"), kind = "test.kind"))
        }
        return store to pid
    }

    @Test fun replacesBindingOnVideoClip() = runTest {
        val (store, pid) = fixture()
        val out = SetClipSourceBindingTool(store).execute(
            SetClipSourceBindingTool.Input(
                projectId = pid.value,
                clipId = "c-video",
                sourceBinding = listOf("node-b", "node-c"),
            ),
            ctx(mutableListOf()),
        ).data
        assertEquals(listOf("node-a"), out.previousBinding)
        assertEquals(listOf("node-b", "node-c"), out.newBinding)
        val clip = store.get(pid)!!.timeline.tracks
            .filterIsInstance<Track.Video>().single()
            .clips.filterIsInstance<Clip.Video>().single()
        assertEquals(setOf(SourceNodeId("node-b"), SourceNodeId("node-c")), clip.sourceBinding)
        // Non-binding fields preserved.
        assertEquals(AssetId("asset-1"), clip.assetId)
        assertEquals(TimeRange(0.seconds, 5.seconds), clip.timeRange)
    }

    @Test fun replacesBindingOnAudioClip() = runTest {
        val (store, pid) = fixture()
        val out = SetClipSourceBindingTool(store).execute(
            SetClipSourceBindingTool.Input(
                projectId = pid.value,
                clipId = "c-audio",
                sourceBinding = listOf("node-c"),
            ),
            ctx(mutableListOf()),
        ).data
        assertEquals(listOf("node-c"), out.newBinding)
        val clip = store.get(pid)!!.timeline.tracks
            .filterIsInstance<Track.Audio>().single()
            .clips.filterIsInstance<Clip.Audio>().single()
        assertEquals(setOf(SourceNodeId("node-c")), clip.sourceBinding)
        assertEquals(AssetId("asset-2"), clip.assetId)
    }

    @Test fun replacesBindingOnTextClip() = runTest {
        val (store, pid) = fixture()
        SetClipSourceBindingTool(store).execute(
            SetClipSourceBindingTool.Input(
                projectId = pid.value,
                clipId = "c-text",
                sourceBinding = listOf("node-b"),
            ),
            ctx(mutableListOf()),
        )
        val clip = store.get(pid)!!.timeline.tracks
            .filterIsInstance<Track.Subtitle>().single()
            .clips.filterIsInstance<Clip.Text>().single()
        assertEquals(setOf(SourceNodeId("node-b")), clip.sourceBinding)
        // Text + style preserved.
        assertEquals("hello", clip.text)
    }

    @Test fun emptyListClearsBinding() = runTest {
        val (store, pid) = fixture()
        val out = SetClipSourceBindingTool(store).execute(
            SetClipSourceBindingTool.Input(
                projectId = pid.value,
                clipId = "c-video",
                sourceBinding = emptyList(),
            ),
            ctx(mutableListOf()),
        ).data
        assertEquals(listOf("node-a"), out.previousBinding)
        assertEquals(emptyList<String>(), out.newBinding)
        val clip = store.get(pid)!!.timeline.tracks
            .filterIsInstance<Track.Video>().single()
            .clips.filterIsInstance<Clip.Video>().single()
        assertTrue(clip.sourceBinding.isEmpty())
    }

    @Test fun rejectsUnknownSourceNodeId() = runTest {
        val (store, pid) = fixture()
        val ex = assertFailsWith<IllegalArgumentException> {
            SetClipSourceBindingTool(store).execute(
                SetClipSourceBindingTool.Input(
                    projectId = pid.value,
                    clipId = "c-video",
                    sourceBinding = listOf("node-b", "nope-1", "nope-2"),
                ),
                ctx(mutableListOf()),
            )
        }
        assertTrue(ex.message!!.contains("nope-1"), "message must name missing ids: ${ex.message}")
        assertTrue(ex.message!!.contains("nope-2"), "message must name missing ids: ${ex.message}")
        // Binding not changed on failure.
        val clip = store.get(pid)!!.timeline.tracks
            .filterIsInstance<Track.Video>().single()
            .clips.filterIsInstance<Clip.Video>().single()
        assertEquals(setOf(SourceNodeId("node-a")), clip.sourceBinding)
    }

    @Test fun rejectsMissingClip() = runTest {
        val (store, pid) = fixture()
        val ex = assertFailsWith<IllegalStateException> {
            SetClipSourceBindingTool(store).execute(
                SetClipSourceBindingTool.Input(
                    projectId = pid.value,
                    clipId = "does-not-exist",
                    sourceBinding = listOf("node-b"),
                ),
                ctx(mutableListOf()),
            )
        }
        assertTrue(ex.message!!.contains("does-not-exist"))
    }

    @Test fun emitsExactlyOneTimelineSnapshot() = runTest {
        val (store, pid) = fixture()
        val parts = mutableListOf<Part>()
        SetClipSourceBindingTool(store).execute(
            SetClipSourceBindingTool.Input(
                projectId = pid.value,
                clipId = "c-video",
                sourceBinding = listOf("node-b"),
            ),
            ctx(parts),
        )
        assertEquals(1, parts.count { it is Part.TimelineSnapshot })
    }
}
