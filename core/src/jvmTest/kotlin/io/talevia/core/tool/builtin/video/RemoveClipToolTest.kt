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
 * `remove_clip` is the missing scalpel — until it landed, the only way to drop
 * a clip was `revert_timeline` (which would also discard every later edit) or
 * to never add the clip in the first place. Tests exercise the happy path,
 * cross-track scoping, the absent-clip guard, and the post-mutation snapshot
 * emission so `revert_timeline` keeps working.
 */
class RemoveClipToolTest {

    private data class Rig(
        val store: SqlDelightProjectStore,
        val tool: RemoveClipTool,
        val ctx: ToolContext,
        val projectId: ProjectId,
        val emittedParts: MutableList<Part>,
    )

    private fun newRig(project: Project): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val tool = RemoveClipTool(store)
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

    @Test fun removesNamedClipAndKeepsSiblings() = runTest {
        val a = videoClip("c1", Duration.ZERO, 3.seconds)
        val b = videoClip("c2", 3.seconds, 3.seconds)
        val c = videoClip("c3", 6.seconds, 3.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(a, b, c)))),
            ),
        )

        val out = rig.tool.execute(RemoveClipTool.Input("p", "c2"), rig.ctx)
        assertEquals("c2", out.data.clipId)
        assertEquals("v1", out.data.trackId)
        assertEquals(2, out.data.remainingClipsOnTrack)

        val refreshed = rig.store.get(rig.projectId)!!
        val ids = refreshed.timeline.tracks.single().clips.map { it.id.value }
        assertEquals(listOf("c1", "c3"), ids)
        // Surviving clips' timeRanges are NOT shifted to fill the gap (no ripple).
        val survivors = refreshed.timeline.tracks.single().clips
        assertEquals(Duration.ZERO, survivors[0].timeRange.start)
        assertEquals(6.seconds, survivors[1].timeRange.start)
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
        rig.tool.execute(RemoveClipTool.Input("p", "vc"), rig.ctx)
        val refreshed = rig.store.get(rig.projectId)!!
        assertTrue(refreshed.timeline.tracks.filterIsInstance<Track.Video>().single().clips.isEmpty())
        assertEquals(1, refreshed.timeline.tracks.filterIsInstance<Track.Audio>().single().clips.size)
    }

    @Test fun emptyTrackIsLeftInPlace() = runTest {
        val a = videoClip("c1", Duration.ZERO, 3.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(a)))),
            ),
        )
        rig.tool.execute(RemoveClipTool.Input("p", "c1"), rig.ctx)
        val refreshed = rig.store.get(rig.projectId)!!
        // The track itself remains so subsequent add_clip calls have a target.
        assertEquals(1, refreshed.timeline.tracks.size)
        assertTrue(refreshed.timeline.tracks.single().clips.isEmpty())
    }

    @Test fun removesAudioClipFromAudioTrack() = runTest {
        val audio = Clip.Audio(
            id = ClipId("a1"),
            timeRange = TimeRange(Duration.ZERO, 5.seconds),
            sourceRange = TimeRange(Duration.ZERO, 5.seconds),
            assetId = AssetId("voice"),
            volume = 0.6f,
        )
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Audio(TrackId("a"), listOf(audio)))),
            ),
        )
        val out = rig.tool.execute(RemoveClipTool.Input("p", "a1"), rig.ctx)
        assertEquals("a", out.data.trackId)
        assertEquals(0, out.data.remainingClipsOnTrack)
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
            rig.tool.execute(RemoveClipTool.Input("p", "ghost"), rig.ctx)
        }
        assertTrue("ghost" in ex.message!!, ex.message)
        // The original clip is untouched on failure.
        val refreshed = rig.store.get(rig.projectId)!!
        assertEquals(1, refreshed.timeline.tracks.single().clips.size)
    }

    @Test fun emitsTimelineSnapshotForRevert() = runTest {
        val a = videoClip("c1", Duration.ZERO, 3.seconds)
        val b = videoClip("c2", 3.seconds, 3.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(a, b)))),
            ),
        )
        rig.tool.execute(RemoveClipTool.Input("p", "c1"), rig.ctx)
        val snap = rig.emittedParts.filterIsInstance<Part.TimelineSnapshot>().single()
        // Snapshot reflects the *post-mutation* timeline.
        val ids = snap.timeline.tracks.single().clips.map { it.id.value }
        assertEquals(listOf("c2"), ids)
    }
}
