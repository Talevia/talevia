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
        extraClip: Clip.Video? = null,
    ): Pair<FileProjectStore, ProjectId> {
        val store = ProjectStoreTestKit.create()
        val pid = ProjectId("p")
        val baseClip = Clip.Video(
            id = ClipId("c-1"),
            timeRange = TimeRange(0.seconds, 1.seconds),
            sourceRange = TimeRange(0.seconds, 1.seconds),
            assetId = AssetId("a-1"),
            filters = filters,
        )
        val videoClips = listOfNotNull(baseClip, extraClip)
        val video = Track.Video(id = TrackId("v"), clips = videoClips)
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
            RemoveFilterTool.Input(projectId = pid.value, clipIds = listOf("c-1"), filterName = "blur"),
            ctx(parts),
        ).data

        assertEquals(1, out.results.size)
        val only = out.results.single()
        assertEquals("c-1", only.clipId)
        assertEquals(1, only.removedCount)
        assertEquals(1, only.remainingFilterCount)
        assertEquals(1, out.totalRemoved)

        val project = store.get(pid)!!
        val clip = project.timeline.tracks.filterIsInstance<Track.Video>().single().clips
            .filterIsInstance<Clip.Video>().single { it.id.value == "c-1" }
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
            RemoveFilterTool.Input(projectId = pid.value, clipIds = listOf("c-1"), filterName = "blur"),
            ctx(mutableListOf()),
        ).data

        val only = out.results.single()
        assertEquals(2, only.removedCount)
        assertEquals(1, only.remainingFilterCount)
        val clip = store.get(pid)!!.timeline.tracks.filterIsInstance<Track.Video>().single().clips
            .filterIsInstance<Clip.Video>().single { it.id.value == "c-1" }
        assertEquals(listOf("brightness"), clip.filters.map { it.name })
    }

    @Test fun removesFromMultipleClipsAtomically() = runTest {
        val extra = Clip.Video(
            id = ClipId("c-2"),
            timeRange = TimeRange(2.seconds, 1.seconds),
            sourceRange = TimeRange(0.seconds, 1.seconds),
            assetId = AssetId("a-2"),
            filters = listOf(Filter("blur", emptyMap())),
        )
        val (store, pid) = fixture(extraClip = extra)
        val tool = RemoveFilterTool(store)
        val out = tool.execute(
            RemoveFilterTool.Input(
                projectId = pid.value,
                clipIds = listOf("c-1", "c-2"),
                filterName = "blur",
            ),
            ctx(mutableListOf()),
        ).data
        assertEquals(2, out.results.size)
        assertEquals(2, out.totalRemoved)

        val clips = store.get(pid)!!.timeline.tracks.filterIsInstance<Track.Video>().single().clips
            .filterIsInstance<Clip.Video>().associateBy { it.id.value }
        assertEquals(listOf("brightness"), clips["c-1"]!!.filters.map { it.name })
        assertEquals(emptyList(), clips["c-2"]!!.filters.map { it.name })
    }

    @Test fun idempotentWhenFilterNotPresentOnSomeClip() = runTest {
        val extra = Clip.Video(
            id = ClipId("c-2"),
            timeRange = TimeRange(2.seconds, 1.seconds),
            sourceRange = TimeRange(0.seconds, 1.seconds),
            assetId = AssetId("a-2"),
            filters = emptyList(),
        )
        val (store, pid) = fixture(extraClip = extra)
        val tool = RemoveFilterTool(store)
        val out = tool.execute(
            RemoveFilterTool.Input(
                projectId = pid.value,
                clipIds = listOf("c-1", "c-2"),
                filterName = "blur",
            ),
            ctx(mutableListOf()),
        ).data
        assertEquals(1, out.totalRemoved)
        assertEquals(1, out.results.first { it.clipId == "c-1" }.removedCount)
        assertEquals(0, out.results.first { it.clipId == "c-2" }.removedCount)
    }

    @Test fun idempotentWhenFilterNotPresent() = runTest {
        val (store, pid) = fixture()
        val tool = RemoveFilterTool(store)
        val out = tool.execute(
            RemoveFilterTool.Input(projectId = pid.value, clipIds = listOf("c-1"), filterName = "vignette"),
            ctx(mutableListOf()),
        ).data

        assertEquals(0, out.totalRemoved)
        val clip = store.get(pid)!!.timeline.tracks.filterIsInstance<Track.Video>().single().clips
            .filterIsInstance<Clip.Video>().single { it.id.value == "c-1" }
        assertEquals(2, clip.filters.size)
    }

    @Test fun noFiltersOnClipIsSuccessfulNoop() = runTest {
        val (store, pid) = fixture(filters = emptyList())
        val tool = RemoveFilterTool(store)
        val out = tool.execute(
            RemoveFilterTool.Input(projectId = pid.value, clipIds = listOf("c-1"), filterName = "blur"),
            ctx(mutableListOf()),
        ).data
        assertEquals(0, out.totalRemoved)
    }

    @Test fun midBatchFailureLeavesProjectUntouched() = runTest {
        val (store, pid) = fixture()
        val before = store.get(pid)!!
        val tool = RemoveFilterTool(store)
        assertFailsWith<IllegalStateException> {
            tool.execute(
                RemoveFilterTool.Input(
                    projectId = pid.value,
                    clipIds = listOf("c-1", "ghost"),
                    filterName = "blur",
                ),
                ctx(mutableListOf()),
            )
        }
        assertEquals(before.timeline, store.get(pid)!!.timeline)
    }

    @Test fun rejectsMissingClip() = runTest {
        val (store, pid) = fixture()
        val tool = RemoveFilterTool(store)
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(
                RemoveFilterTool.Input(projectId = pid.value, clipIds = listOf("nope"), filterName = "blur"),
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
                RemoveFilterTool.Input(projectId = pid.value, clipIds = listOf("c-audio"), filterName = "blur"),
                ctx(mutableListOf()),
            )
        }
        assertTrue(ex.message!!.contains("video"))
    }

    @Test fun emitsOneSnapshotForBatch() = runTest {
        val extra = Clip.Video(
            id = ClipId("c-2"),
            timeRange = TimeRange(2.seconds, 1.seconds),
            sourceRange = TimeRange(0.seconds, 1.seconds),
            assetId = AssetId("a-2"),
            filters = listOf(Filter("brightness", emptyMap())),
        )
        val (store, pid) = fixture(extraClip = extra)
        val tool = RemoveFilterTool(store)
        val parts = mutableListOf<Part>()
        tool.execute(
            RemoveFilterTool.Input(
                projectId = pid.value,
                clipIds = listOf("c-1", "c-2"),
                filterName = "brightness",
            ),
            ctx(parts),
        )
        assertEquals(1, parts.count { it is Part.TimelineSnapshot })
    }
}
