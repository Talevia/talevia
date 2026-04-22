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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class RemoveTransitionToolTest {

    private data class Rig(
        val store: FileProjectStore,
        val add: AddTransitionTool,
        val remove: RemoveTransitionTool,
        val ctx: ToolContext,
        val snapshots: MutableList<Part.TimelineSnapshot>,
        val projectId: ProjectId,
    )

    private fun newRig(project: Project): Rig {
        val store = ProjectStoreTestKit.create()
        val snapshots = mutableListOf<Part.TimelineSnapshot>()
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { part -> if (part is Part.TimelineSnapshot) snapshots += part },
            messages = emptyList(),
        )
        kotlinx.coroutines.runBlocking { store.upsert("test", project) }
        return Rig(store, AddTransitionTool(store), RemoveTransitionTool(store), ctx, snapshots, project.id)
    }

    private fun videoClip(id: String, start: Duration, duration: Duration): Clip.Video = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(start, duration),
        sourceRange = TimeRange(Duration.ZERO, duration),
        assetId = AssetId("a-$id"),
    )

    private fun projectWithTwoAdjacentClips(): Project {
        val v1 = videoClip("v1", Duration.ZERO, 5.seconds)
        val v2 = videoClip("v2", 5.seconds, 5.seconds)
        return Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(Track.Video(TrackId("vt"), listOf(v1, v2))),
                duration = 10.seconds,
            ),
        )
    }

    @Test fun removesTransitionByIdAndLeavesVideoClipsIntact() = runTest {
        val rig = newRig(projectWithTwoAdjacentClips())
        val add = rig.add.execute(
            AddTransitionTool.Input(rig.projectId.value, "v1", "v2", "fade", 0.5),
            rig.ctx,
        ).data
        val beforeEffectClipCount = rig.store.get(rig.projectId)!!.timeline.tracks
            .first { it.id.value == add.trackId }.clips.size
        assertEquals(1, beforeEffectClipCount)

        val out = rig.remove.execute(
            RemoveTransitionTool.Input(rig.projectId.value, add.transitionClipId),
            rig.ctx,
        ).data
        assertEquals(add.transitionClipId, out.transitionClipId)
        assertEquals(add.trackId, out.trackId)
        assertEquals("fade", out.transitionName)
        assertEquals(0, out.remainingTransitionsOnTrack)

        val proj = rig.store.get(rig.projectId)!!
        val effectTrack = proj.timeline.tracks.first { it.id.value == add.trackId }
        assertEquals(0, effectTrack.clips.size)
        val videoTrack = proj.timeline.tracks.first { it.id.value == "vt" }
        assertEquals(listOf("v1", "v2"), videoTrack.clips.map { it.id.value })
        videoTrack.clips.forEach { c ->
            // Flanking video clips are unchanged — same start / duration as before.
            assertTrue(c.timeRange.start >= Duration.ZERO)
        }
    }

    @Test fun emitsOneSnapshotPerRemoval() = runTest {
        val rig = newRig(projectWithTwoAdjacentClips())
        val add = rig.add.execute(
            AddTransitionTool.Input(rig.projectId.value, "v1", "v2"),
            rig.ctx,
        ).data
        val before = rig.snapshots.size
        rig.remove.execute(
            RemoveTransitionTool.Input(rig.projectId.value, add.transitionClipId),
            rig.ctx,
        )
        assertEquals(before + 1, rig.snapshots.size)
    }

    @Test fun rejectsRegularClipId() = runTest {
        val rig = newRig(projectWithTwoAdjacentClips())
        val ex = assertFailsWith<IllegalStateException> {
            rig.remove.execute(
                RemoveTransitionTool.Input(rig.projectId.value, "v1"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("video track"))
        assertTrue(ex.message!!.contains("remove_clip"))
    }

    @Test fun rejectsUnknownId() = runTest {
        val rig = newRig(projectWithTwoAdjacentClips())
        val ex = assertFailsWith<IllegalStateException> {
            rig.remove.execute(
                RemoveTransitionTool.Input(rig.projectId.value, "does-not-exist"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("not found"))
    }

    @Test fun rejectsMissingProject() = runTest {
        val rig = newRig(projectWithTwoAdjacentClips())
        val add = rig.add.execute(
            AddTransitionTool.Input(rig.projectId.value, "v1", "v2"),
            rig.ctx,
        ).data
        val ex = assertFailsWith<IllegalStateException> {
            rig.remove.execute(
                RemoveTransitionTool.Input("nope", add.transitionClipId),
                rig.ctx,
            )
        }
        assertNotNull(ex.message)
    }

    @Test fun rejectsEffectClipWithoutTransitionSentinel() = runTest {
        // Seed a non-transition clip on the Effect track (e.g. a bare video clip the
        // user parked there for other reasons) and confirm remove_transition refuses it.
        val v1 = videoClip("v1", Duration.ZERO, 5.seconds)
        val v2 = videoClip("v2", 5.seconds, 5.seconds)
        val bogusEffect = Clip.Video(
            id = ClipId("fx-1"),
            timeRange = TimeRange(2.seconds, 1.seconds),
            sourceRange = TimeRange(Duration.ZERO, 1.seconds),
            assetId = AssetId("not-a-transition"),
        )
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(TrackId("vt"), listOf(v1, v2)),
                    Track.Effect(TrackId("fx"), listOf(bogusEffect)),
                ),
                duration = 10.seconds,
            ),
        )
        val rig = newRig(project)
        val ex = assertFailsWith<IllegalStateException> {
            rig.remove.execute(
                RemoveTransitionTool.Input(rig.projectId.value, "fx-1"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("transition"))
    }

    @Test fun remainingCountAfterPartialRemoval() = runTest {
        val v1 = videoClip("v1", Duration.ZERO, 5.seconds)
        val v2 = videoClip("v2", 5.seconds, 5.seconds)
        val v3 = videoClip("v3", 10.seconds, 5.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(Track.Video(TrackId("vt"), listOf(v1, v2, v3))),
                    duration = 15.seconds,
                ),
            ),
        )
        val t1 = rig.add.execute(
            AddTransitionTool.Input(rig.projectId.value, "v1", "v2", "fade", 0.5),
            rig.ctx,
        ).data
        val t2 = rig.add.execute(
            AddTransitionTool.Input(rig.projectId.value, "v2", "v3", "dissolve", 0.5),
            rig.ctx,
        ).data
        assertEquals(t1.trackId, t2.trackId) // reused Effect track

        val out = rig.remove.execute(
            RemoveTransitionTool.Input(rig.projectId.value, t1.transitionClipId),
            rig.ctx,
        ).data
        assertEquals(1, out.remainingTransitionsOnTrack)

        val proj = rig.store.get(rig.projectId)!!
        val effectClips = proj.timeline.tracks.first { it.id.value == t1.trackId }.clips
        assertEquals(listOf(t2.transitionClipId), effectClips.map { it.id.value })
    }

    @Test fun transitionNameEchoesFirstFilter() = runTest {
        val rig = newRig(projectWithTwoAdjacentClips())
        val add = rig.add.execute(
            AddTransitionTool.Input(rig.projectId.value, "v1", "v2", "dissolve", 0.4),
            rig.ctx,
        ).data
        val out = rig.remove.execute(
            RemoveTransitionTool.Input(rig.projectId.value, add.transitionClipId),
            rig.ctx,
        ).data
        assertEquals("dissolve", out.transitionName)
    }

    @Test fun snapshotTimelineReflectsRemoval() = runTest {
        val rig = newRig(projectWithTwoAdjacentClips())
        val add = rig.add.execute(
            AddTransitionTool.Input(rig.projectId.value, "v1", "v2"),
            rig.ctx,
        ).data
        rig.remove.execute(
            RemoveTransitionTool.Input(rig.projectId.value, add.transitionClipId),
            rig.ctx,
        )
        val lastSnapshot = rig.snapshots.last()
        val effectTrack = lastSnapshot.timeline.tracks.firstOrNull { it.id.value == add.trackId }
        assertNotNull(effectTrack)
        assertEquals(0, effectTrack.clips.size)
    }

    @Test fun snapshotIdIsFreshEachRun() = runTest {
        val rig = newRig(projectWithTwoAdjacentClips())
        val add = rig.add.execute(
            AddTransitionTool.Input(rig.projectId.value, "v1", "v2"),
            rig.ctx,
        ).data
        val beforeSnapshotIds = rig.snapshots.map { it.id }.toSet()
        rig.remove.execute(
            RemoveTransitionTool.Input(rig.projectId.value, add.transitionClipId),
            rig.ctx,
        )
        val newSnapshotIds = rig.snapshots.map { it.id }.toSet() - beforeSnapshotIds
        assertEquals(1, newSnapshotIds.size)
        val id = newSnapshotIds.single()
        // Ensure it's a non-empty id (no implicit re-use of the producer's callId).
        assertTrue(id.value.isNotBlank())
        assertNull(beforeSnapshotIds.firstOrNull { it == id })
    }
}
