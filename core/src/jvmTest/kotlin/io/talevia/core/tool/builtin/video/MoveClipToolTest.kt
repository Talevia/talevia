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
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * `move_clip` reposition primitive — the system prompt promises it for ripple-delete
 * chaining. Tests exercise: same-track reposition with sibling reordering, duration
 * preservation, sourceRange preservation (the clip plays the same material), the
 * absent-clip guard, the negative-time guard, no-overlap-validation (we permit
 * overlapping clips because PiP / transitions need them), and post-mutation snapshot
 * emission for revert_timeline.
 */
class MoveClipToolTest {

    private data class Rig(
        val store: SqlDelightProjectStore,
        val tool: MoveClipTool,
        val ctx: ToolContext,
        val projectId: ProjectId,
        val emittedParts: MutableList<Part>,
    )

    private fun newRig(project: Project): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val tool = MoveClipTool(store)
        val parts = mutableListOf<Part>()
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { parts += it },
            messages = emptyList(),
        )
        kotlinx.coroutines.runBlocking { store.upsert("test", project) }
        return Rig(store, tool, ctx, project.id, parts)
    }

    private fun videoClip(id: String, start: Duration, duration: Duration): Clip.Video = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(start, duration),
        sourceRange = TimeRange(Duration.ZERO, duration),
        assetId = AssetId("a-$id"),
    )

    @Test fun movesClipAndReordersSiblings() = runTest {
        val a = videoClip("c1", Duration.ZERO, 3.seconds)
        val b = videoClip("c2", 3.seconds, 3.seconds)
        val c = videoClip("c3", 6.seconds, 3.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(a, b, c)))),
            ),
        )

        // Move c1 to start at 9s — should land after c3 in track order.
        val out = rig.tool.execute(MoveClipTool.Input("p", "c1", 9.0), rig.ctx)
        assertEquals("c1", out.data.clipId)
        assertEquals("v1", out.data.fromTrackId)
        assertEquals("v1", out.data.toTrackId)
        assertEquals(false, out.data.changedTrack)
        assertEquals(0.0, out.data.oldStartSeconds)
        assertEquals(9.0, out.data.newStartSeconds)

        val refreshed = rig.store.get(rig.projectId)!!
        val ids = refreshed.timeline.tracks.single().clips.map { it.id.value }
        // c2, c3, c1 — sorted by timeRange.start after the move.
        assertEquals(listOf("c2", "c3", "c1"), ids)
    }

    @Test fun preservesDurationAndSourceRange() = runTest {
        val clip = Clip.Video(
            id = ClipId("c1"),
            timeRange = TimeRange(2.seconds, 5.seconds),
            sourceRange = TimeRange(10.seconds, 5.seconds),
            assetId = AssetId("a1"),
        )
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(clip)))),
            ),
        )
        rig.tool.execute(MoveClipTool.Input("p", "c1", 20.0), rig.ctx)
        val moved = rig.store.get(rig.projectId)!!
            .timeline.tracks.single().clips.single() as Clip.Video
        assertEquals(20.seconds, moved.timeRange.start)
        // Duration unchanged.
        assertEquals(5.seconds, moved.timeRange.duration)
        // sourceRange untouched — the clip plays the same material, just at a different time.
        assertEquals(10.seconds, moved.sourceRange.start)
        assertEquals(5.seconds, moved.sourceRange.duration)
    }

    @Test fun otherTracksUnaffected() = runTest {
        val v = videoClip("vc", Duration.ZERO, 5.seconds)
        val a = Clip.Audio(
            id = ClipId("ac"),
            timeRange = TimeRange(Duration.ZERO, 5.seconds),
            sourceRange = TimeRange(Duration.ZERO, 5.seconds),
            assetId = AssetId("a"),
        )
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(TrackId("v1"), listOf(v)),
                        Track.Audio(TrackId("a1"), listOf(a)),
                    ),
                ),
            ),
        )
        rig.tool.execute(MoveClipTool.Input("p", "vc", 10.0), rig.ctx)
        val refreshed = rig.store.get(rig.projectId)!!
        // Audio clip on a separate track must be untouched.
        val audio = refreshed.timeline.tracks.filterIsInstance<Track.Audio>().single().clips.single()
        assertEquals(Duration.ZERO, audio.timeRange.start)
    }

    @Test fun overlappingMoveIsAllowed() = runTest {
        // Picture-in-picture / layered effects need overlapping clips on the same
        // track. The tool must NOT refuse a move that creates overlap.
        val a = videoClip("c1", Duration.ZERO, 5.seconds)
        val b = videoClip("c2", 5.seconds, 5.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(a, b)))),
            ),
        )
        // Move c2 to start at 2s — overlaps c1 (which runs 0..5s).
        rig.tool.execute(MoveClipTool.Input("p", "c2", 2.0), rig.ctx)
        val refreshed = rig.store.get(rig.projectId)!!
        val moved = refreshed.timeline.tracks.single().clips.first { it.id.value == "c2" }
        assertEquals(2.seconds, moved.timeRange.start)
    }

    @Test fun missingClipFailsLoudly() = runTest {
        val a = videoClip("c1", Duration.ZERO, 3.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(a)))),
            ),
        )
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(MoveClipTool.Input("p", "ghost", 5.0), rig.ctx)
        }
        assertTrue("ghost" in ex.message!!, ex.message)
        // Original clip untouched on failure.
        val refreshed = rig.store.get(rig.projectId)!!
        assertEquals(Duration.ZERO, refreshed.timeline.tracks.single().clips.single().timeRange.start)
    }

    @Test fun negativeStartIsRejected() = runTest {
        val a = videoClip("c1", 3.seconds, 3.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(a)))),
            ),
        )
        assertFailsWith<IllegalStateException> {
            rig.tool.execute(MoveClipTool.Input("p", "c1", -1.0), rig.ctx)
        }
        // Original position untouched.
        val refreshed = rig.store.get(rig.projectId)!!
        assertEquals(3.seconds, refreshed.timeline.tracks.single().clips.single().timeRange.start)
    }

    @Test fun crossTrackMovePreservesTime() = runTest {
        val v = videoClip("c1", 2.seconds, 5.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(TrackId("v1"), listOf(v)),
                        Track.Video(TrackId("v2")),
                    ),
                ),
            ),
        )
        val out = rig.tool.execute(
            MoveClipTool.Input(projectId = "p", clipId = "c1", toTrackId = "v2"),
            rig.ctx,
        )
        assertEquals("v1", out.data.fromTrackId)
        assertEquals("v2", out.data.toTrackId)
        assertEquals(true, out.data.changedTrack)
        assertEquals(2.0, out.data.oldStartSeconds)
        assertEquals(2.0, out.data.newStartSeconds)
        val refreshed = rig.store.get(rig.projectId)!!
        val src = refreshed.timeline.tracks.first { it.id.value == "v1" }.clips
        val dst = refreshed.timeline.tracks.first { it.id.value == "v2" }.clips
        assertTrue(src.isEmpty(), "source track emptied")
        assertEquals(1, dst.size)
        assertEquals(2.seconds, dst.single().timeRange.start)
    }

    @Test fun crossTrackMoveWithRepositionShiftsTime() = runTest {
        val v = videoClip("c1", 1.seconds, 5.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(TrackId("v1"), listOf(v)),
                        Track.Video(TrackId("v2")),
                    ),
                ),
            ),
        )
        rig.tool.execute(
            MoveClipTool.Input(projectId = "p", clipId = "c1", timelineStartSeconds = 10.0, toTrackId = "v2"),
            rig.ctx,
        )
        val dst = rig.store.get(rig.projectId)!!.timeline.tracks.first { it.id.value == "v2" }.clips
        assertEquals(10.seconds, dst.single().timeRange.start)
    }

    @Test fun crossTrackKindMismatchFailsLoud() = runTest {
        val v = videoClip("c1", Duration.ZERO, 3.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(TrackId("v1"), listOf(v)),
                        Track.Audio(TrackId("a1")),
                    ),
                ),
            ),
        )
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                MoveClipTool.Input(projectId = "p", clipId = "c1", toTrackId = "a1"),
                rig.ctx,
            )
        }
        assertTrue("video clip onto audio track" in ex.message!!, ex.message)
        // Clip untouched on failure.
        val refreshed = rig.store.get(rig.projectId)!!
        val src = refreshed.timeline.tracks.first { it.id.value == "v1" }.clips
        assertEquals(1, src.size)
    }

    @Test fun unknownTargetTrackFailsLoud() = runTest {
        val v = videoClip("c1", Duration.ZERO, 3.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(v)))),
            ),
        )
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                MoveClipTool.Input(projectId = "p", clipId = "c1", toTrackId = "nope"),
                rig.ctx,
            )
        }
        assertTrue("nope" in ex.message!!, ex.message)
    }

    @Test fun toTrackIdEqualToCurrentIsSameTrackPath() = runTest {
        // toTrackId == current track should NOT fail (unified tool is lenient)
        // and should exercise the same-track path.
        val v = videoClip("c1", 2.seconds, 3.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(v)))),
            ),
        )
        val out = rig.tool.execute(
            MoveClipTool.Input(projectId = "p", clipId = "c1", timelineStartSeconds = 5.0, toTrackId = "v1"),
            rig.ctx,
        )
        assertEquals("v1", out.data.fromTrackId)
        assertEquals("v1", out.data.toTrackId)
        assertEquals(false, out.data.changedTrack)
        assertEquals(5.0, out.data.newStartSeconds)
    }

    @Test fun emptyInputFailsLoud() = runTest {
        val v = videoClip("c1", Duration.ZERO, 3.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(v)))),
            ),
        )
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(MoveClipTool.Input(projectId = "p", clipId = "c1"), rig.ctx)
        }
        assertTrue("at least one of" in ex.message!!, ex.message)
    }

    @Test fun emitsTimelineSnapshotForRevert() = runTest {
        val a = videoClip("c1", Duration.ZERO, 3.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(a)))),
            ),
        )
        rig.tool.execute(MoveClipTool.Input("p", "c1", 7.0), rig.ctx)
        val snap = rig.emittedParts.filterIsInstance<Part.TimelineSnapshot>().single()
        // Snapshot reflects the *post-mutation* timeline.
        val moved = snap.timeline.tracks.single().clips.single()
        assertEquals(7.seconds, moved.timeRange.start)
    }
}
