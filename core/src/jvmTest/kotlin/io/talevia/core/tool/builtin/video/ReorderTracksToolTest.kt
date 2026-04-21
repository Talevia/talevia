package io.talevia.core.tool.builtin.video

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.TrackId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.domain.SqlDelightProjectStore
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ReorderTracksToolTest {

    private data class Rig(
        val store: SqlDelightProjectStore,
        val tool: ReorderTracksTool,
        val ctx: ToolContext,
        val snapshots: MutableList<Part.TimelineSnapshot>,
        val projectId: ProjectId,
    )

    private fun newRig(): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val snapshots = mutableListOf<Part.TimelineSnapshot>()
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { part -> if (part is Part.TimelineSnapshot) snapshots += part },
            messages = emptyList(),
        )
        val pid = ProjectId("p")
        val v1 = Track.Video(
            id = TrackId("bg"),
            clips = listOf(
                Clip.Video(
                    id = ClipId("c-bg"),
                    timeRange = TimeRange(0.seconds, 5.seconds),
                    sourceRange = TimeRange(0.seconds, 5.seconds),
                    assetId = AssetId("a-bg"),
                ),
            ),
        )
        val v2 = Track.Video(id = TrackId("fg"), clips = emptyList())
        val audio = Track.Audio(id = TrackId("aud"), clips = emptyList())
        val subs = Track.Subtitle(id = TrackId("sub"), clips = emptyList())
        val fx = Track.Effect(id = TrackId("fx"), clips = emptyList())
        val project = Project(
            id = pid,
            timeline = Timeline(tracks = listOf(v1, v2, audio, subs, fx), duration = 5.seconds),
        )
        kotlinx.coroutines.runBlocking { store.upsert("demo", project) }
        return Rig(store, ReorderTracksTool(store), ctx, snapshots, pid)
    }

    private fun trackOrder(rig: Rig): List<String> =
        kotlinx.coroutines.runBlocking { rig.store.get(rig.projectId)!! }.timeline.tracks.map { it.id.value }

    @Test fun movesListedTracksToFront() = runTest {
        val rig = newRig()
        rig.tool.execute(
            ReorderTracksTool.Input(rig.projectId.value, listOf("fg")),
            rig.ctx,
        )
        // fg is now first (bottom); rest keep relative order.
        assertEquals(listOf("fg", "bg", "aud", "sub", "fx"), trackOrder(rig))
    }

    @Test fun fullReorder() = runTest {
        val rig = newRig()
        rig.tool.execute(
            ReorderTracksTool.Input(
                rig.projectId.value,
                listOf("fx", "sub", "aud", "fg", "bg"),
            ),
            rig.ctx,
        )
        assertEquals(listOf("fx", "sub", "aud", "fg", "bg"), trackOrder(rig))
    }

    @Test fun preservesTailRelativeOrder() = runTest {
        val rig = newRig()
        // Only pin aud; bg/fg/sub/fx keep their order at the tail.
        rig.tool.execute(
            ReorderTracksTool.Input(rig.projectId.value, listOf("aud")),
            rig.ctx,
        )
        assertEquals(listOf("aud", "bg", "fg", "sub", "fx"), trackOrder(rig))
    }

    @Test fun clipContentsUnchanged() = runTest {
        val rig = newRig()
        val before = rig.store.get(rig.projectId)!!
            .timeline.tracks.flatMap { it.clips.map { c -> c.id.value } }.toSet()
        rig.tool.execute(
            ReorderTracksTool.Input(rig.projectId.value, listOf("fg", "bg")),
            rig.ctx,
        )
        val after = rig.store.get(rig.projectId)!!
            .timeline.tracks.flatMap { it.clips.map { c -> c.id.value } }.toSet()
        assertEquals(before, after)
    }

    @Test fun emitsOneSnapshot() = runTest {
        val rig = newRig()
        val before = rig.snapshots.size
        rig.tool.execute(
            ReorderTracksTool.Input(rig.projectId.value, listOf("fg", "bg")),
            rig.ctx,
        )
        assertEquals(before + 1, rig.snapshots.size)
        val snap = rig.snapshots.last()
        assertEquals(
            listOf("fg", "bg", "aud", "sub", "fx"),
            snap.timeline.tracks.map { it.id.value },
        )
    }

    @Test fun rejectsEmptyList() = runTest {
        val rig = newRig()
        val ex = assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(
                ReorderTracksTool.Input(rig.projectId.value, emptyList()),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("empty"))
    }

    @Test fun rejectsDuplicates() = runTest {
        val rig = newRig()
        val ex = assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(
                ReorderTracksTool.Input(rig.projectId.value, listOf("fg", "fg")),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("duplicates"))
    }

    @Test fun rejectsUnknownTrackId() = runTest {
        val rig = newRig()
        val ex = assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(
                ReorderTracksTool.Input(rig.projectId.value, listOf("nope")),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("Unknown"))
    }

    @Test fun rejectsMissingProject() = runTest {
        val rig = newRig()
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                ReorderTracksTool.Input("nope", listOf("fg")),
                rig.ctx,
            )
        }
        assertNotNull(ex.message)
    }

    @Test fun outputEchoesFinalOrder() = runTest {
        val rig = newRig()
        val out = rig.tool.execute(
            ReorderTracksTool.Input(rig.projectId.value, listOf("fg")),
            rig.ctx,
        ).data
        assertEquals(listOf("fg", "bg", "aud", "sub", "fx"), out.newOrder)
    }
}
