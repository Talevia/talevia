package io.talevia.core.tool.builtin.project

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Covers the LLM-facing contract of [ListTimelineClipsTool]: deterministic
 * ordering, per-track / per-kind / per-time-window filtering, truncation
 * accounting, and per-kind payload shape (video vs audio vs text).
 */
class ListTimelineClipsToolTest {

    private data class Rig(
        val store: SqlDelightProjectStore,
        val tool: ListTimelineClipsTool,
        val ctx: ToolContext,
        val projectId: ProjectId,
    )

    private fun newRig(project: Project): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val tool = ListTimelineClipsTool(store)
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = {},
            messages = emptyList(),
        )
        kotlinx.coroutines.runBlocking { store.upsert("test", project) }
        return Rig(store, tool, ctx, project.id)
    }

    private fun videoClip(id: String, start: Duration, duration: Duration, assetId: String = "a-$id"): Clip.Video =
        Clip.Video(
            id = ClipId(id),
            timeRange = TimeRange(start, duration),
            sourceRange = TimeRange(Duration.ZERO, duration),
            assetId = AssetId(assetId),
        )

    @Test fun listsAllClipsOrderedByStart() = runTest {
        val a = videoClip("c1", 2.seconds, 3.seconds)
        val b = videoClip("c2", Duration.ZERO, 2.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(a, b)))),
            ),
        )

        val out = rig.tool.execute(ListTimelineClipsTool.Input("p"), rig.ctx)
        assertEquals(2, out.data.totalClipCount)
        assertEquals(2, out.data.returnedClipCount)
        assertFalse(out.data.truncated)
        // Ordered by timeRange.start — c2 (0s) precedes c1 (2s).
        assertEquals(listOf("c2", "c1"), out.data.clips.map { it.clipId })
    }

    @Test fun filtersByTrackKind() = runTest {
        val v = videoClip("vc", Duration.ZERO, 5.seconds)
        val a = Clip.Audio(
            id = ClipId("ac"),
            timeRange = TimeRange(Duration.ZERO, 5.seconds),
            sourceRange = TimeRange(Duration.ZERO, 5.seconds),
            assetId = AssetId("voice"),
            volume = 0.7f,
        )
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(TrackId("vt"), listOf(v)),
                        Track.Audio(TrackId("at"), listOf(a)),
                    ),
                ),
            ),
        )

        val audio = rig.tool.execute(ListTimelineClipsTool.Input("p", trackKind = "audio"), rig.ctx)
        assertEquals(1, audio.data.clips.size)
        val audioInfo = audio.data.clips.single()
        assertEquals("audio", audioInfo.clipKind)
        assertEquals("audio", audioInfo.trackKind)
        assertEquals(0.7f, audioInfo.volume)
    }

    @Test fun filtersByTimeWindow() = runTest {
        val a = videoClip("c1", Duration.ZERO, 2.seconds)
        val b = videoClip("c2", 3.seconds, 2.seconds)
        val c = videoClip("c3", 8.seconds, 2.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(a, b, c)))),
            ),
        )

        // Only clips whose [start, end] intersects [2.5, 6] — c2 (3..5) hits.
        val out = rig.tool.execute(
            ListTimelineClipsTool.Input("p", fromSeconds = 2.5, toSeconds = 6.0),
            rig.ctx,
        )
        assertEquals(listOf("c2"), out.data.clips.map { it.clipId })
    }

    @Test fun truncatesAtLimit() = runTest {
        val clips = (0 until 5).map { videoClip("c$it", (it * 2).seconds, 2.seconds) }
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), clips))),
            ),
        )
        val out = rig.tool.execute(ListTimelineClipsTool.Input("p", limit = 3), rig.ctx)
        assertEquals(5, out.data.totalClipCount)
        assertEquals(3, out.data.returnedClipCount)
        assertTrue(out.data.truncated)
    }

    @Test fun videoClipCarriesFiltersAndBindings() = runTest {
        val v = Clip.Video(
            id = ClipId("c1"),
            timeRange = TimeRange(Duration.ZERO, 3.seconds),
            sourceRange = TimeRange(Duration.ZERO, 3.seconds),
            assetId = AssetId("a"),
            filters = listOf(io.talevia.core.domain.Filter(name = "sepia")),
            sourceBinding = setOf(SourceNodeId("n1"), SourceNodeId("n2")),
        )
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("vt"), listOf(v)))),
            ),
        )
        val out = rig.tool.execute(ListTimelineClipsTool.Input("p"), rig.ctx)
        val info = out.data.clips.single()
        assertEquals(1, info.filterCount)
        assertEquals(listOf("n1", "n2"), info.sourceBindingNodeIds)
        assertEquals("a", info.assetId)
    }

    @Test fun textClipCarriesPreview() = runTest {
        val longText = "This is a long subtitle that should be truncated at eighty characters for the preview window"
        val t = Clip.Text(
            id = ClipId("t1"),
            timeRange = TimeRange(Duration.ZERO, 2.seconds),
            text = longText,
        )
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Subtitle(TrackId("st"), listOf(t)))),
            ),
        )
        val out = rig.tool.execute(ListTimelineClipsTool.Input("p"), rig.ctx)
        val info = out.data.clips.single()
        assertEquals("text", info.clipKind)
        assertEquals("subtitle", info.trackKind)
        assertEquals(longText.take(80), info.textPreview)
    }

    @Test fun unknownTrackKindFailsLoudly() = runTest {
        val v = videoClip("c1", Duration.ZERO, 2.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(v)))),
            ),
        )
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(ListTimelineClipsTool.Input("p", trackKind = "vfx"), rig.ctx)
        }
        assertTrue("vfx" in ex.message!!, ex.message)
    }

    @Test fun onlySourceBoundReturnsOnlyAigcDerivedClips() = runTest {
        val imported = videoClip("imported", Duration.ZERO, 2.seconds)
        val aigc = Clip.Video(
            id = ClipId("aigc"),
            timeRange = TimeRange(2.seconds, 3.seconds),
            sourceRange = TimeRange(Duration.ZERO, 3.seconds),
            assetId = AssetId("gen"),
            sourceBinding = setOf(SourceNodeId("n1")),
        )
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(imported, aigc)))),
            ),
        )

        val out = rig.tool.execute(
            ListTimelineClipsTool.Input("p", onlySourceBound = true),
            rig.ctx,
        )
        assertEquals(1, out.data.totalClipCount)
        assertEquals(listOf("aigc"), out.data.clips.map { it.clipId })
        assertEquals(listOf("n1"), out.data.clips.single().sourceBindingNodeIds)
    }

    @Test fun onlySourceBoundComposesWithTrackKind() = runTest {
        val videoAigc = Clip.Video(
            id = ClipId("vc-aigc"),
            timeRange = TimeRange(Duration.ZERO, 2.seconds),
            sourceRange = TimeRange(Duration.ZERO, 2.seconds),
            assetId = AssetId("gen-v"),
            sourceBinding = setOf(SourceNodeId("nv")),
        )
        val videoImported = videoClip("vc-imp", 2.seconds, 2.seconds)
        val audioAigc = Clip.Audio(
            id = ClipId("ac-aigc"),
            timeRange = TimeRange(Duration.ZERO, 3.seconds),
            sourceRange = TimeRange(Duration.ZERO, 3.seconds),
            assetId = AssetId("gen-a"),
            sourceBinding = setOf(SourceNodeId("na")),
        )
        val audioImported = Clip.Audio(
            id = ClipId("ac-imp"),
            timeRange = TimeRange(3.seconds, 2.seconds),
            sourceRange = TimeRange(Duration.ZERO, 2.seconds),
            assetId = AssetId("imp-a"),
        )
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(TrackId("vt"), listOf(videoAigc, videoImported)),
                        Track.Audio(TrackId("at"), listOf(audioAigc, audioImported)),
                    ),
                ),
            ),
        )

        val out = rig.tool.execute(
            ListTimelineClipsTool.Input("p", trackKind = "audio", onlySourceBound = true),
            rig.ctx,
        )
        // Orthogonal composition: only the AIGC audio clip survives both filters.
        assertEquals(1, out.data.totalClipCount)
        assertEquals(listOf("ac-aigc"), out.data.clips.map { it.clipId })
    }

    @Test fun onlySourceBoundFalseOrNullMatchesDefaultBehaviour() = runTest {
        val imported = videoClip("imported", Duration.ZERO, 2.seconds)
        val aigc = Clip.Video(
            id = ClipId("aigc"),
            timeRange = TimeRange(2.seconds, 3.seconds),
            sourceRange = TimeRange(Duration.ZERO, 3.seconds),
            assetId = AssetId("gen"),
            sourceBinding = setOf(SourceNodeId("n1")),
        )
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(imported, aigc)))),
            ),
        )

        val baseline = rig.tool.execute(ListTimelineClipsTool.Input("p"), rig.ctx)
        val explicitNull = rig.tool.execute(
            ListTimelineClipsTool.Input("p", onlySourceBound = null),
            rig.ctx,
        )
        val explicitFalse = rig.tool.execute(
            ListTimelineClipsTool.Input("p", onlySourceBound = false),
            rig.ctx,
        )

        val ids = listOf("imported", "aigc")
        assertEquals(ids, baseline.data.clips.map { it.clipId })
        assertEquals(ids, explicitNull.data.clips.map { it.clipId })
        assertEquals(ids, explicitFalse.data.clips.map { it.clipId })
        assertEquals(2, baseline.data.totalClipCount)
        assertEquals(2, explicitNull.data.totalClipCount)
        assertEquals(2, explicitFalse.data.totalClipCount)
    }

    @Test fun filtersByTrackId() = runTest {
        val a = videoClip("c1", Duration.ZERO, 2.seconds)
        val b = videoClip("c2", Duration.ZERO, 2.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(TrackId("v-main"), listOf(a)),
                        Track.Video(TrackId("v-alt"), listOf(b)),
                    ),
                ),
            ),
        )
        val out = rig.tool.execute(ListTimelineClipsTool.Input("p", trackId = "v-alt"), rig.ctx)
        assertEquals(listOf("c2"), out.data.clips.map { it.clipId })
    }
}
