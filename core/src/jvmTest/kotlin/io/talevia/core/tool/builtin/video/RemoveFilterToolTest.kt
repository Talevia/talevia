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
import io.talevia.core.domain.Filter
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.TimeRange
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

class RemoveFilterToolTest {

    private fun ctx(parts: MutableList<Part>): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { parts += it },
        messages = emptyList(),
    )

    private suspend fun fixture(
        filters: List<Filter> = listOf(
            Filter("blur", mapOf("radius" to 4f)),
            Filter("brightness", mapOf("amount" to 0.2f)),
        ),
    ): Pair<FileProjectStore, ProjectId> {
        val store = ProjectStoreTestKit.create()
        val pid = ProjectId("p")
        val video = Track.Video(
            id = TrackId("v"),
            clips = listOf(
                Clip.Video(
                    id = ClipId("c-1"),
                    timeRange = TimeRange(0.seconds, 1.seconds),
                    sourceRange = TimeRange(0.seconds, 1.seconds),
                    assetId = AssetId("a-1"),
                    filters = filters,
                ),
            ),
        )
        val audio = Track.Audio(
            id = TrackId("a"),
            clips = listOf(
                Clip.Audio(
                    id = ClipId("c-audio"),
                    timeRange = TimeRange(0.seconds, 1.seconds),
                    sourceRange = TimeRange(0.seconds, 1.seconds),
                    assetId = AssetId("a-audio"),
                ),
            ),
        )
        store.upsert(
            "demo",
            Project(id = pid, timeline = Timeline(tracks = listOf(video, audio), duration = 1.seconds)),
        )
        return store to pid
    }

    @Test fun removesSingleFilter() = runTest {
        val (store, pid) = fixture()
        val tool = RemoveFilterTool(store)
        val parts = mutableListOf<Part>()
        val out = tool.execute(
            RemoveFilterTool.Input(projectId = pid.value, clipId = "c-1", filterName = "blur"),
            ctx(parts),
        ).data

        assertEquals("c-1", out.clipId)
        assertEquals(1, out.removedCount)
        assertEquals(1, out.remainingFilterCount)

        val project = store.get(pid)!!
        val clip = project.timeline.tracks.filterIsInstance<Track.Video>().single().clips
            .filterIsInstance<Clip.Video>().single()
        assertEquals(listOf("brightness"), clip.filters.map { it.name })
        assertEquals(1, parts.count { it is Part.TimelineSnapshot })
    }

    @Test fun removesAllDuplicatesWithSameName() = runTest {
        val (store, pid) = fixture(
            filters = listOf(
                Filter("blur", mapOf("radius" to 2f)),
                Filter("brightness", mapOf("amount" to 0.2f)),
                Filter("blur", mapOf("radius" to 8f)),
            ),
        )
        val tool = RemoveFilterTool(store)
        val out = tool.execute(
            RemoveFilterTool.Input(projectId = pid.value, clipId = "c-1", filterName = "blur"),
            ctx(mutableListOf()),
        ).data

        assertEquals(2, out.removedCount)
        assertEquals(1, out.remainingFilterCount)
        val clip = store.get(pid)!!.timeline.tracks.filterIsInstance<Track.Video>().single().clips
            .filterIsInstance<Clip.Video>().single()
        assertEquals(listOf("brightness"), clip.filters.map { it.name })
    }

    @Test fun idempotentWhenFilterNotPresent() = runTest {
        val (store, pid) = fixture()
        val tool = RemoveFilterTool(store)
        val out = tool.execute(
            RemoveFilterTool.Input(projectId = pid.value, clipId = "c-1", filterName = "vignette"),
            ctx(mutableListOf()),
        ).data

        assertEquals(0, out.removedCount)
        assertEquals(2, out.remainingFilterCount)

        val clip = store.get(pid)!!.timeline.tracks.filterIsInstance<Track.Video>().single().clips
            .filterIsInstance<Clip.Video>().single()
        assertEquals(2, clip.filters.size)
    }

    @Test fun noFiltersOnClipIsSuccessfulNoop() = runTest {
        val (store, pid) = fixture(filters = emptyList())
        val tool = RemoveFilterTool(store)
        val out = tool.execute(
            RemoveFilterTool.Input(projectId = pid.value, clipId = "c-1", filterName = "blur"),
            ctx(mutableListOf()),
        ).data
        assertEquals(0, out.removedCount)
        assertEquals(0, out.remainingFilterCount)
    }

    @Test fun rejectsMissingClip() = runTest {
        val (store, pid) = fixture()
        val tool = RemoveFilterTool(store)
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(
                RemoveFilterTool.Input(projectId = pid.value, clipId = "nope", filterName = "blur"),
                ctx(mutableListOf()),
            )
        }
        assertTrue(ex.message!!.contains("not found"))
    }

    @Test fun rejectsAudioClip() = runTest {
        val (store, pid) = fixture()
        val tool = RemoveFilterTool(store)
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(
                RemoveFilterTool.Input(projectId = pid.value, clipId = "c-audio", filterName = "blur"),
                ctx(mutableListOf()),
            )
        }
        assertTrue(ex.message!!.contains("video"))
    }

    @Test fun emitsTimelineSnapshot() = runTest {
        val (store, pid) = fixture()
        val tool = RemoveFilterTool(store)
        val parts = mutableListOf<Part>()
        tool.execute(
            RemoveFilterTool.Input(projectId = pid.value, clipId = "c-1", filterName = "brightness"),
            ctx(parts),
        )
        assertEquals(1, parts.count { it is Part.TimelineSnapshot })
    }
}
