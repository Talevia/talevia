package io.talevia.core.tool.builtin.project

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
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.video.AddTransitionTool
import io.talevia.core.tool.builtin.video.RemoveClipTool
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ListTransitionsToolTest {

    private data class Rig(
        val store: SqlDelightProjectStore,
        val addTransition: AddTransitionTool,
        val removeClip: RemoveClipTool,
        val list: ListTransitionsTool,
        val ctx: ToolContext,
        val projectId: ProjectId,
    )

    private fun newRig(project: Project): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        kotlinx.coroutines.runBlocking { store.upsert("test", project) }
        return Rig(
            store = store,
            addTransition = AddTransitionTool(store),
            removeClip = RemoveClipTool(store),
            list = ListTransitionsTool(store),
            ctx = ctx,
            projectId = project.id,
        )
    }

    private fun videoClip(id: String, start: Duration, duration: Duration): Clip.Video = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(start, duration),
        sourceRange = TimeRange(Duration.ZERO, duration),
        assetId = AssetId("a-$id"),
    )

    private fun projectWithThreeAdjacentClips(): Project {
        val v1 = videoClip("v1", Duration.ZERO, 5.seconds)
        val v2 = videoClip("v2", 5.seconds, 5.seconds)
        val v3 = videoClip("v3", 10.seconds, 5.seconds)
        return Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(Track.Video(TrackId("vt"), listOf(v1, v2, v3))),
                duration = 15.seconds,
            ),
        )
    }

    @Test fun emptyTimelineReturnsZero() = runTest {
        val rig = newRig(
            Project(id = ProjectId("p"), timeline = Timeline()),
        )
        val out = rig.list.execute(ListTransitionsTool.Input(rig.projectId.value), rig.ctx).data
        assertEquals(0, out.totalTransitionCount)
        assertTrue(out.transitions.isEmpty())
    }

    @Test fun listsSingleTransitionWithFlankingIds() = runTest {
        val rig = newRig(projectWithThreeAdjacentClips())
        rig.addTransition.execute(
            AddTransitionTool.Input(rig.projectId.value, "v1", "v2", "fade", 0.5),
            rig.ctx,
        )
        val out = rig.list.execute(ListTransitionsTool.Input(rig.projectId.value), rig.ctx).data
        assertEquals(1, out.totalTransitionCount)
        val row = out.transitions.single()
        assertEquals("fade", row.transitionName)
        assertEquals("v1", row.fromClipId)
        assertEquals("v2", row.toClipId)
        assertFalse(row.orphaned)
    }

    @Test fun listsMultipleTransitionsInStartOrder() = runTest {
        val rig = newRig(projectWithThreeAdjacentClips())
        rig.addTransition.execute(
            AddTransitionTool.Input(rig.projectId.value, "v2", "v3", "dissolve", 0.5),
            rig.ctx,
        )
        rig.addTransition.execute(
            AddTransitionTool.Input(rig.projectId.value, "v1", "v2", "fade", 0.5),
            rig.ctx,
        )
        val out = rig.list.execute(ListTransitionsTool.Input(rig.projectId.value), rig.ctx).data
        assertEquals(2, out.totalTransitionCount)
        // Ordered by startSeconds, regardless of insertion order.
        assertEquals("fade", out.transitions[0].transitionName)
        assertEquals("dissolve", out.transitions[1].transitionName)
        assertTrue(out.transitions[0].startSeconds < out.transitions[1].startSeconds)
    }

    @Test fun reportsOrphanedWhenBothFlankingClipsRemoved() = runTest {
        val rig = newRig(projectWithThreeAdjacentClips())
        val add = rig.addTransition.execute(
            AddTransitionTool.Input(rig.projectId.value, "v1", "v2", "fade", 0.5),
            rig.ctx,
        ).data
        rig.removeClip.execute(RemoveClipTool.Input(rig.projectId.value, "v1"), rig.ctx)
        rig.removeClip.execute(RemoveClipTool.Input(rig.projectId.value, "v2"), rig.ctx)

        val out = rig.list.execute(ListTransitionsTool.Input(rig.projectId.value), rig.ctx).data
        assertEquals(1, out.totalTransitionCount)
        assertEquals(1, out.orphanedCount)
        val row = out.transitions.single()
        assertEquals(add.transitionClipId, row.transitionClipId)
        assertTrue(row.orphaned)
        assertNull(row.fromClipId)
        assertNull(row.toClipId)
    }

    @Test fun reportsPartiallyResolvedPair() = runTest {
        // Remove only v1 → transition has toClipId but no fromClipId. NOT orphaned —
        // orphaned means BOTH missing.
        val rig = newRig(projectWithThreeAdjacentClips())
        rig.addTransition.execute(
            AddTransitionTool.Input(rig.projectId.value, "v1", "v2", "fade", 0.5),
            rig.ctx,
        )
        rig.removeClip.execute(RemoveClipTool.Input(rig.projectId.value, "v1"), rig.ctx)

        val out = rig.list.execute(ListTransitionsTool.Input(rig.projectId.value), rig.ctx).data
        val row = out.transitions.single()
        assertNull(row.fromClipId)
        assertEquals("v2", row.toClipId)
        assertFalse(row.orphaned)
        assertEquals(0, out.orphanedCount)
    }

    @Test fun transitionNameFromFilterName() = runTest {
        val rig = newRig(projectWithThreeAdjacentClips())
        rig.addTransition.execute(
            AddTransitionTool.Input(rig.projectId.value, "v1", "v2", "wipe", 0.5),
            rig.ctx,
        )
        val out = rig.list.execute(ListTransitionsTool.Input(rig.projectId.value), rig.ctx).data
        assertEquals("wipe", out.transitions.single().transitionName)
    }

    @Test fun ignoresNonTransitionEffectClips() = runTest {
        // Seed an Effect track with a bare clip that isn't a transition (assetId without the sentinel).
        val v1 = videoClip("v1", Duration.ZERO, 5.seconds)
        val fxClip = Clip.Video(
            id = ClipId("fx-1"),
            timeRange = TimeRange(2.seconds, 1.seconds),
            sourceRange = TimeRange(Duration.ZERO, 1.seconds),
            assetId = AssetId("not-a-transition"),
        )
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(TrackId("vt"), listOf(v1)),
                    Track.Effect(TrackId("fx"), listOf(fxClip)),
                ),
                duration = 5.seconds,
            ),
        )
        val rig = newRig(project)
        val out = rig.list.execute(ListTransitionsTool.Input(rig.projectId.value), rig.ctx).data
        assertEquals(0, out.totalTransitionCount)
    }

    @Test fun rejectsMissingProject() = runTest {
        val rig = newRig(projectWithThreeAdjacentClips())
        val ex = assertFailsWith<IllegalStateException> {
            rig.list.execute(ListTransitionsTool.Input("nope"), rig.ctx)
        }
        assertTrue(ex.message!!.contains("not found"))
    }

    @Test fun reportsDurationSeconds() = runTest {
        val rig = newRig(projectWithThreeAdjacentClips())
        rig.addTransition.execute(
            AddTransitionTool.Input(rig.projectId.value, "v1", "v2", "fade", 0.5),
            rig.ctx,
        )
        val out = rig.list.execute(ListTransitionsTool.Input(rig.projectId.value), rig.ctx).data
        assertEquals(0.5, out.transitions.single().durationSeconds)
    }

    @Test fun transitionClipIdMatchesAddTransitionOutput() = runTest {
        val rig = newRig(projectWithThreeAdjacentClips())
        val add = rig.addTransition.execute(
            AddTransitionTool.Input(rig.projectId.value, "v1", "v2", "fade", 0.5),
            rig.ctx,
        ).data
        val out = rig.list.execute(ListTransitionsTool.Input(rig.projectId.value), rig.ctx).data
        assertEquals(add.transitionClipId, out.transitions.single().transitionClipId)
        assertEquals(add.trackId, out.transitions.single().trackId)
    }
}
