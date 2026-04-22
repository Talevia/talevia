package io.talevia.core.tool.builtin.video

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.session.Part
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AddSubtitlesToolTest {

    private fun freshStore(): FileProjectStore {
        return ProjectStoreTestKit.create()
    }

    private fun ctxCollecting(into: MutableList<Part>): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { into += it },
        messages = emptyList(),
    )

    @Test fun dropsAllSegmentsInOneMutationAndOneSnapshot() = runTest {
        val store = freshStore()
        val tool = AddSubtitlesTool(store)
        val parts = mutableListOf<Part>()
        val projectId = ProjectId("p")
        store.upsert(
            "demo",
            Project(
                id = projectId,
                timeline = Timeline(tracks = listOf(Track.Subtitle(TrackId("sub1")))),
            ),
        )

        val out = tool.execute(
            AddSubtitlesTool.Input(
                projectId = projectId.value,
                subtitles = listOf(
                    AddSubtitlesTool.SubtitleSpec("hello", 0.0, 1.5),
                    AddSubtitlesTool.SubtitleSpec("world", 1.5, 1.0),
                    AddSubtitlesTool.SubtitleSpec("!", 2.5, 0.4),
                ),
            ),
            ctxCollecting(parts),
        )

        assertEquals("sub1", out.data.trackId)
        assertEquals(3, out.data.clipIds.size)
        val project = store.get(projectId)!!
        val subtitleTrack = project.timeline.tracks.filterIsInstance<Track.Subtitle>().single()
        assertEquals(listOf("hello", "world", "!"), subtitleTrack.clips.map { (it as Clip.Text).text })
        assertEquals(1, parts.count { it is Part.TimelineSnapshot })
    }

    @Test fun sortsOutOfOrderSegmentsByStart() = runTest {
        val store = freshStore()
        val tool = AddSubtitlesTool(store)
        val parts = mutableListOf<Part>()
        val projectId = ProjectId("p")
        store.upsert(
            "demo",
            Project(
                id = projectId,
                timeline = Timeline(tracks = listOf(Track.Subtitle(TrackId("sub1")))),
            ),
        )

        tool.execute(
            AddSubtitlesTool.Input(
                projectId = projectId.value,
                subtitles = listOf(
                    AddSubtitlesTool.SubtitleSpec("later", 5.0, 1.0),
                    AddSubtitlesTool.SubtitleSpec("earlier", 1.0, 1.0),
                    AddSubtitlesTool.SubtitleSpec("middle", 3.0, 1.0),
                ),
            ),
            ctxCollecting(parts),
        )

        val track = store.get(projectId)!!.timeline.tracks
            .filterIsInstance<Track.Subtitle>().single()
        assertEquals(listOf("earlier", "middle", "later"), track.clips.map { (it as Clip.Text).text })
    }

    @Test fun createsSubtitleTrackWhenProjectHasNone() = runTest {
        val store = freshStore()
        val tool = AddSubtitlesTool(store)
        val parts = mutableListOf<Part>()
        val projectId = ProjectId("p")
        store.upsert(
            "demo",
            Project(
                id = projectId,
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1")))),
            ),
        )

        tool.execute(
            AddSubtitlesTool.Input(
                projectId = projectId.value,
                subtitles = listOf(AddSubtitlesTool.SubtitleSpec("hi", 0.0, 1.0)),
            ),
            ctxCollecting(parts),
        )

        val tracks = store.get(projectId)!!.timeline.tracks
        assertTrue(tracks.any { it is Track.Subtitle })
    }

    @Test fun extendsTimelineDurationToCoverTailSegment() = runTest {
        val store = freshStore()
        val tool = AddSubtitlesTool(store)
        val parts = mutableListOf<Part>()
        val projectId = ProjectId("p")
        store.upsert(
            "demo",
            Project(
                id = projectId,
                timeline = Timeline(
                    tracks = listOf(Track.Subtitle(TrackId("sub1"))),
                    duration = 2.seconds,
                ),
            ),
        )

        tool.execute(
            AddSubtitlesTool.Input(
                projectId = projectId.value,
                subtitles = listOf(
                    AddSubtitlesTool.SubtitleSpec("a", 0.0, 0.5),
                    AddSubtitlesTool.SubtitleSpec("b", 8.0, 2.5),
                ),
            ),
            ctxCollecting(parts),
        )

        val duration = store.get(projectId)!!.timeline.duration
        assertEquals(10.5.seconds, duration)
    }

    @Test fun rejectsEmptySegments() = runTest {
        val store = freshStore()
        val tool = AddSubtitlesTool(store)
        val parts = mutableListOf<Part>()
        val projectId = ProjectId("p")
        store.upsert(
            "demo",
            Project(
                id = projectId,
                timeline = Timeline(tracks = listOf(Track.Subtitle(TrackId("sub1")))),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            tool.execute(
                AddSubtitlesTool.Input(projectId = projectId.value, subtitles = emptyList()),
                ctxCollecting(parts),
            )
        }
    }

    @Test fun singleSubtitleViaOneElementList() = runTest {
        // Primary merge validation: single-subtitle callers pass a 1-element list,
        // closing the old add_subtitle + add_subtitles split.
        val store = freshStore()
        val tool = AddSubtitlesTool(store)
        val parts = mutableListOf<Part>()
        val projectId = ProjectId("p")
        store.upsert(
            "demo",
            Project(
                id = projectId,
                timeline = Timeline(tracks = listOf(Track.Subtitle(TrackId("sub1")))),
            ),
        )

        val out = tool.execute(
            AddSubtitlesTool.Input(
                projectId = projectId.value,
                subtitles = listOf(AddSubtitlesTool.SubtitleSpec("hello", 1.0, 2.5)),
                fontSize = 36f,
                color = "#FFFF00",
            ),
            ctxCollecting(parts),
        )

        assertEquals(1, out.data.clipIds.size)
        val clip = store.get(projectId)!!.timeline.tracks
            .filterIsInstance<Track.Subtitle>().single().clips.single() as Clip.Text
        assertEquals("hello", clip.text)
        assertEquals(1.0, clip.timeRange.start.inWholeMilliseconds / 1000.0, 0.001)
        assertEquals(2.5, clip.timeRange.duration.inWholeMilliseconds / 1000.0, 0.001)
        assertEquals(36f, clip.style.fontSize)
        assertEquals("#FFFF00", clip.style.color)
    }

    @Test fun preservesExistingTrackOrder() = runTest {
        val store = freshStore()
        val tool = AddSubtitlesTool(store)
        val parts = mutableListOf<Part>()
        val projectId = ProjectId("p")
        store.upsert(
            "demo",
            Project(
                id = projectId,
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(TrackId("v1")),
                        Track.Subtitle(TrackId("sub1")),
                        Track.Audio(TrackId("a1")),
                    ),
                ),
            ),
        )

        tool.execute(
            AddSubtitlesTool.Input(
                projectId = projectId.value,
                subtitles = listOf(AddSubtitlesTool.SubtitleSpec("hi", 0.0, 1.0)),
            ),
            ctxCollecting(parts),
        )

        assertEquals(
            listOf("v1", "sub1", "a1"),
            store.get(projectId)!!.timeline.tracks.map { it.id.value },
        )
    }
}
