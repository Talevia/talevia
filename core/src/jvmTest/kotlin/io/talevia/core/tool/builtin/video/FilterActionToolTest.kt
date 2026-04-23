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

/**
 * Covers both action paths of the consolidated [FilterActionTool]
 * (debt-consolidate-video-filter-lut-apply-remove, 2026-04-23 —
 * filter half landed first). Old `ApplyFilterToolTest` +
 * `RemoveFilterToolTest` test classes folded into this file;
 * case names preserved where possible so a regression that flagged
 * there still flags by the same name here.
 */
class FilterActionToolTest {

    private fun ctx(parts: MutableList<Part>): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { parts += it },
        messages = emptyList(),
    )

    private suspend fun applyFixture(): Pair<FileProjectStore, ProjectId> {
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
                ),
                Clip.Video(
                    id = ClipId("c-2"),
                    timeRange = TimeRange(1.seconds, 1.seconds),
                    sourceRange = TimeRange(0.seconds, 1.seconds),
                    assetId = AssetId("a-2"),
                ),
            ),
        )
        val audio = Track.Audio(
            id = TrackId("a"),
            clips = listOf(
                Clip.Audio(
                    id = ClipId("c-audio"),
                    timeRange = TimeRange(0.seconds, 2.seconds),
                    sourceRange = TimeRange(0.seconds, 2.seconds),
                    assetId = AssetId("a-audio"),
                ),
            ),
        )
        store.upsert(
            "demo",
            Project(id = pid, timeline = Timeline(tracks = listOf(video, audio), duration = 2.seconds)),
        )
        return store to pid
    }

    private suspend fun removeFixture(
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

    // ──────────── Apply action ────────────

    @Test fun allVideoClipsAppliesUniformly() = runTest {
        val (store, pid) = applyFixture()
        val tool = FilterActionTool(store)
        val parts = mutableListOf<Part>()
        val out = tool.execute(
            FilterActionTool.Input(
                projectId = pid.value,
                action = "apply",
                filterName = "vignette",
                params = mapOf("intensity" to 0.5f),
                allVideoClips = true,
            ),
            ctx(parts),
        ).data

        assertEquals(2, out.appliedClipIds.size)
        assertEquals(listOf("c-1", "c-2"), out.appliedClipIds)

        val project = store.get(pid)!!
        val clips = project.timeline.tracks.filterIsInstance<Track.Video>().single().clips
            .filterIsInstance<Clip.Video>()
        clips.forEach { assertEquals(listOf("vignette"), it.filters.map { f -> f.name }) }
        assertEquals(1, parts.count { it is Part.TimelineSnapshot })
    }

    @Test fun clipIdsHonorsExplicitList() = runTest {
        val (store, pid) = applyFixture()
        val tool = FilterActionTool(store)
        val out = tool.execute(
            FilterActionTool.Input(
                projectId = pid.value,
                action = "apply",
                filterName = "blur",
                params = mapOf("radius" to 4f),
                clipIds = listOf("c-2"),
            ),
            ctx(mutableListOf()),
        ).data
        assertEquals(1, out.appliedClipIds.size)
        assertEquals(listOf("c-2"), out.appliedClipIds)

        val clips = store.get(pid)!!.timeline.tracks.filterIsInstance<Track.Video>().single().clips
            .filterIsInstance<Clip.Video>()
        assertTrue(clips.single { it.id.value == "c-1" }.filters.isEmpty())
        assertEquals("blur", clips.single { it.id.value == "c-2" }.filters.single().name)
    }

    @Test fun audioClipIdListedAsSkipped() = runTest {
        val (store, pid) = applyFixture()
        val tool = FilterActionTool(store)
        val out = tool.execute(
            FilterActionTool.Input(
                projectId = pid.value,
                action = "apply",
                filterName = "brightness",
                clipIds = listOf("c-1", "c-audio", "nope"),
            ),
            ctx(mutableListOf()),
        ).data
        assertEquals(1, out.appliedClipIds.size)
        val reasons = out.skipped.associate { it.clipId to it.reason }
        assertTrue(reasons["c-audio"]!!.contains("not a video"))
        assertTrue(reasons["nope"]!!.contains("not found"))
    }

    @Test fun multipleSelectorsFailLoud() = runTest {
        val (store, pid) = applyFixture()
        val tool = FilterActionTool(store)
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(
                FilterActionTool.Input(
                    projectId = pid.value,
                    action = "apply",
                    filterName = "blur",
                    allVideoClips = true,
                    clipIds = listOf("c-1"),
                ),
                ctx(mutableListOf()),
            )
        }
        assertTrue(ex.message!!.contains("exactly one"))
    }

    @Test fun trackIdScopesToTrack() = runTest {
        val (store, pid) = applyFixture()
        val tool = FilterActionTool(store)
        val out = tool.execute(
            FilterActionTool.Input(
                projectId = pid.value,
                action = "apply",
                filterName = "saturation",
                params = mapOf("amount" to 0.3f),
                trackId = "v",
            ),
            ctx(mutableListOf()),
        ).data
        assertEquals(2, out.appliedClipIds.size, "both video clips on track v should be targeted")
    }

    @Test fun singleClipViaOneElementList() = runTest {
        val (store, pid) = applyFixture()
        val tool = FilterActionTool(store)
        val out = tool.execute(
            FilterActionTool.Input(
                projectId = pid.value,
                action = "apply",
                filterName = "blur",
                params = mapOf("radius" to 4f),
                clipIds = listOf("c-1"),
            ),
            ctx(mutableListOf()),
        ).data
        assertEquals(1, out.appliedClipIds.size)
        assertEquals(listOf("c-1"), out.appliedClipIds)
        assertTrue(out.skipped.isEmpty())
        val refreshed = store.get(pid)!!
        val c1 = refreshed.timeline.tracks
            .flatMap { it.clips }
            .first { it.id.value == "c-1" } as Clip.Video
        assertEquals(1, c1.filters.size)
        assertEquals("blur", c1.filters.single().name)
        val c2 = refreshed.timeline.tracks
            .flatMap { it.clips }
            .first { it.id.value == "c-2" } as Clip.Video
        assertTrue(c2.filters.isEmpty())
    }

    // ──────────── Remove action ────────────

    @Test fun removesSingleFilter() = runTest {
        val (store, pid) = removeFixture()
        val tool = FilterActionTool(store)
        val parts = mutableListOf<Part>()
        val out = tool.execute(
            FilterActionTool.Input(
                projectId = pid.value,
                action = "remove",
                clipIds = listOf("c-1"),
                filterName = "blur",
            ),
            ctx(parts),
        ).data

        assertEquals(1, out.removed.size)
        val only = out.removed.single()
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
        val (store, pid) = removeFixture(
            filters = listOf(
                Filter("blur", mapOf("radius" to 2f)),
                Filter("brightness", mapOf("amount" to 0.2f)),
                Filter("blur", mapOf("radius" to 8f)),
            ),
        )
        val tool = FilterActionTool(store)
        val out = tool.execute(
            FilterActionTool.Input(
                projectId = pid.value,
                action = "remove",
                clipIds = listOf("c-1"),
                filterName = "blur",
            ),
            ctx(mutableListOf()),
        ).data

        val only = out.removed.single()
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
        val (store, pid) = removeFixture(extraClip = extra)
        val tool = FilterActionTool(store)
        val out = tool.execute(
            FilterActionTool.Input(
                projectId = pid.value,
                action = "remove",
                clipIds = listOf("c-1", "c-2"),
                filterName = "blur",
            ),
            ctx(mutableListOf()),
        ).data
        assertEquals(2, out.removed.size)
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
        val (store, pid) = removeFixture(extraClip = extra)
        val tool = FilterActionTool(store)
        val out = tool.execute(
            FilterActionTool.Input(
                projectId = pid.value,
                action = "remove",
                clipIds = listOf("c-1", "c-2"),
                filterName = "blur",
            ),
            ctx(mutableListOf()),
        ).data
        assertEquals(1, out.totalRemoved)
        assertEquals(1, out.removed.first { it.clipId == "c-1" }.removedCount)
        assertEquals(0, out.removed.first { it.clipId == "c-2" }.removedCount)
    }

    @Test fun idempotentWhenFilterNotPresent() = runTest {
        val (store, pid) = removeFixture()
        val tool = FilterActionTool(store)
        val out = tool.execute(
            FilterActionTool.Input(
                projectId = pid.value,
                action = "remove",
                clipIds = listOf("c-1"),
                filterName = "vignette",
            ),
            ctx(mutableListOf()),
        ).data

        assertEquals(0, out.totalRemoved)
        val clip = store.get(pid)!!.timeline.tracks.filterIsInstance<Track.Video>().single().clips
            .filterIsInstance<Clip.Video>().single { it.id.value == "c-1" }
        assertEquals(2, clip.filters.size)
    }

    @Test fun noFiltersOnClipIsSuccessfulNoop() = runTest {
        val (store, pid) = removeFixture(filters = emptyList())
        val tool = FilterActionTool(store)
        val out = tool.execute(
            FilterActionTool.Input(
                projectId = pid.value,
                action = "remove",
                clipIds = listOf("c-1"),
                filterName = "blur",
            ),
            ctx(mutableListOf()),
        ).data
        assertEquals(0, out.totalRemoved)
    }

    @Test fun removeMidBatchFailureLeavesProjectUntouched() = runTest {
        val (store, pid) = removeFixture()
        val before = store.get(pid)!!
        val tool = FilterActionTool(store)
        assertFailsWith<IllegalStateException> {
            tool.execute(
                FilterActionTool.Input(
                    projectId = pid.value,
                    action = "remove",
                    clipIds = listOf("c-1", "ghost"),
                    filterName = "blur",
                ),
                ctx(mutableListOf()),
            )
        }
        assertEquals(before.timeline, store.get(pid)!!.timeline)
    }

    @Test fun removeRejectsMissingClip() = runTest {
        val (store, pid) = removeFixture()
        val tool = FilterActionTool(store)
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(
                FilterActionTool.Input(
                    projectId = pid.value,
                    action = "remove",
                    clipIds = listOf("nope"),
                    filterName = "blur",
                ),
                ctx(mutableListOf()),
            )
        }
        assertTrue(ex.message!!.contains("not found"))
    }

    @Test fun removeRejectsAudioClip() = runTest {
        val (store, pid) = removeFixture()
        val tool = FilterActionTool(store)
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(
                FilterActionTool.Input(
                    projectId = pid.value,
                    action = "remove",
                    clipIds = listOf("c-audio"),
                    filterName = "blur",
                ),
                ctx(mutableListOf()),
            )
        }
        assertTrue(ex.message!!.contains("video"))
    }

    @Test fun removeEmitsOneSnapshotForBatch() = runTest {
        val extra = Clip.Video(
            id = ClipId("c-2"),
            timeRange = TimeRange(2.seconds, 1.seconds),
            sourceRange = TimeRange(0.seconds, 1.seconds),
            assetId = AssetId("a-2"),
            filters = listOf(Filter("brightness", emptyMap())),
        )
        val (store, pid) = removeFixture(extraClip = extra)
        val tool = FilterActionTool(store)
        val parts = mutableListOf<Part>()
        tool.execute(
            FilterActionTool.Input(
                projectId = pid.value,
                action = "remove",
                clipIds = listOf("c-1", "c-2"),
                filterName = "brightness",
            ),
            ctx(parts),
        )
        assertEquals(1, parts.count { it is Part.TimelineSnapshot })
    }

    @Test fun rejectsUnknownAction() = runTest {
        val (store, pid) = removeFixture()
        val tool = FilterActionTool(store)
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(
                FilterActionTool.Input(
                    projectId = pid.value,
                    action = "delete",
                    clipIds = listOf("c-1"),
                    filterName = "blur",
                ),
                ctx(mutableListOf()),
            )
        }
        assertTrue(ex.message!!.contains("delete"), ex.message)
    }
}
