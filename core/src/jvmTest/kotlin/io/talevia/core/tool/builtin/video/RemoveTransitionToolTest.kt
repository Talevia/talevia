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

    private suspend fun Rig.addOne(
        from: String,
        to: String,
        name: String = "fade",
        durationSeconds: Double = 0.5,
    ): AddTransitionTool.ItemResult = add.execute(
        AddTransitionTool.Input(
            projectId = projectId.value,
            items = listOf(AddTransitionTool.Item(from, to, name, durationSeconds)),
        ),
        ctx,
    ).data.results.single()

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

    @Test fun removesSingletonTransitionAndLeavesVideoClipsIntact() = runTest {
        val rig = newRig(projectWithTwoAdjacentClips())
        val add = rig.addOne("v1", "v2", "fade", 0.5)
        val beforeEffectClipCount = rig.store.get(rig.projectId)!!.timeline.tracks
            .first { it.id.value == add.trackId }.clips.size
        assertEquals(1, beforeEffectClipCount)

        val out = rig.remove.execute(
            RemoveTransitionTool.Input(rig.projectId.value, listOf(add.transitionClipId)),
            rig.ctx,
        ).data
        assertEquals(1, out.results.size)
        val only = out.results.single()
        assertEquals(add.transitionClipId, only.transitionClipId)
        assertEquals(add.trackId, only.trackId)
        assertEquals("fade", only.transitionName)
        assertEquals(0, out.remainingTransitionsTotal)

        val proj = rig.store.get(rig.projectId)!!
        val effectTrack = proj.timeline.tracks.first { it.id.value == add.trackId }
        assertEquals(0, effectTrack.clips.size)
        val videoTrack = proj.timeline.tracks.first { it.id.value == "vt" }
        assertEquals(listOf("v1", "v2"), videoTrack.clips.map { it.id.value })
    }

    @Test fun removesMultipleTransitionsAtomically() = runTest {
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
        val twoAdds = rig.add.execute(
            AddTransitionTool.Input(
                projectId = rig.projectId.value,
                items = listOf(
                    AddTransitionTool.Item("v1", "v2", "fade", 0.5),
                    AddTransitionTool.Item("v2", "v3", "dissolve", 0.5),
                ),
            ),
            rig.ctx,
        ).data.results
        val t1 = twoAdds[0]
        val t2 = twoAdds[1]
        assertEquals(t1.trackId, t2.trackId) // reused Effect track

        val out = rig.remove.execute(
            RemoveTransitionTool.Input(
                rig.projectId.value,
                listOf(t1.transitionClipId, t2.transitionClipId),
            ),
            rig.ctx,
        ).data
        assertEquals(2, out.results.size)
        assertEquals(0, out.remainingTransitionsTotal)
        assertEquals(
            setOf("fade", "dissolve"),
            out.results.map { it.transitionName }.toSet(),
        )

        val proj = rig.store.get(rig.projectId)!!
        val effectClips = proj.timeline.tracks.first { it.id.value == t1.trackId }.clips
        assertEquals(0, effectClips.size)
    }

    @Test fun emitsOneSnapshotPerBatch() = runTest {
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
        val twoAdds = rig.add.execute(
            AddTransitionTool.Input(
                projectId = rig.projectId.value,
                items = listOf(
                    AddTransitionTool.Item("v1", "v2", "fade", 0.5),
                    AddTransitionTool.Item("v2", "v3", "dissolve", 0.5),
                ),
            ),
            rig.ctx,
        ).data.results
        val t1 = twoAdds[0]
        val t2 = twoAdds[1]
        val before = rig.snapshots.size
        rig.remove.execute(
            RemoveTransitionTool.Input(
                rig.projectId.value,
                listOf(t1.transitionClipId, t2.transitionClipId),
            ),
            rig.ctx,
        )
        assertEquals(before + 1, rig.snapshots.size)
    }

    @Test fun batchAtomicityLeavesProjectUntouchedOnAnyFailure() = runTest {
        val rig = newRig(projectWithTwoAdjacentClips())
        val add = rig.addOne("v1", "v2", "fade", 0.5)
        val beforeProject = rig.store.get(rig.projectId)!!
        val beforeSnapshotCount = rig.snapshots.size

        // Second id is bogus — the whole batch must abort.
        val ex = assertFailsWith<IllegalStateException> {
            rig.remove.execute(
                RemoveTransitionTool.Input(
                    rig.projectId.value,
                    listOf(add.transitionClipId, "does-not-exist"),
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("transitionClipIds[1]"), ex.message)

        val afterProject = rig.store.get(rig.projectId)!!
        assertEquals(beforeProject.timeline, afterProject.timeline)
        // No snapshot emitted on failure.
        assertEquals(beforeSnapshotCount, rig.snapshots.size)
    }

    @Test fun rejectsEmptyIdList() = runTest {
        val rig = newRig(projectWithTwoAdjacentClips())
        val ex = assertFailsWith<IllegalArgumentException> {
            rig.remove.execute(
                RemoveTransitionTool.Input(rig.projectId.value, emptyList()),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("must not be empty"))
    }

    @Test fun rejectsRegularClipId() = runTest {
        val rig = newRig(projectWithTwoAdjacentClips())
        val ex = assertFailsWith<IllegalStateException> {
            rig.remove.execute(
                RemoveTransitionTool.Input(rig.projectId.value, listOf("v1")),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("video"))
        assertTrue(ex.message!!.contains("remove_clips"))
    }

    @Test fun rejectsUnknownId() = runTest {
        val rig = newRig(projectWithTwoAdjacentClips())
        val ex = assertFailsWith<IllegalStateException> {
            rig.remove.execute(
                RemoveTransitionTool.Input(rig.projectId.value, listOf("does-not-exist")),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("not found"))
    }

    @Test fun rejectsMissingProject() = runTest {
        val rig = newRig(projectWithTwoAdjacentClips())
        val add = rig.addOne("v1", "v2")
        val ex = assertFailsWith<IllegalStateException> {
            rig.remove.execute(
                RemoveTransitionTool.Input("nope", listOf(add.transitionClipId)),
                rig.ctx,
            )
        }
        assertNotNull(ex.message)
    }

    @Test fun rejectsEffectClipWithoutTransitionSentinel() = runTest {
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
                RemoveTransitionTool.Input(rig.projectId.value, listOf("fx-1")),
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
        val twoAdds = rig.add.execute(
            AddTransitionTool.Input(
                projectId = rig.projectId.value,
                items = listOf(
                    AddTransitionTool.Item("v1", "v2", "fade", 0.5),
                    AddTransitionTool.Item("v2", "v3", "dissolve", 0.5),
                ),
            ),
            rig.ctx,
        ).data.results
        val t1 = twoAdds[0]
        val t2 = twoAdds[1]
        assertEquals(t1.trackId, t2.trackId)

        val out = rig.remove.execute(
            RemoveTransitionTool.Input(rig.projectId.value, listOf(t1.transitionClipId)),
            rig.ctx,
        ).data
        assertEquals(1, out.remainingTransitionsTotal)

        val proj = rig.store.get(rig.projectId)!!
        val effectClips = proj.timeline.tracks.first { it.id.value == t1.trackId }.clips
        assertEquals(listOf(t2.transitionClipId), effectClips.map { it.id.value })
    }

    @Test fun transitionNameEchoesFirstFilter() = runTest {
        val rig = newRig(projectWithTwoAdjacentClips())
        val add = rig.addOne("v1", "v2", "dissolve", 0.4)
        val out = rig.remove.execute(
            RemoveTransitionTool.Input(rig.projectId.value, listOf(add.transitionClipId)),
            rig.ctx,
        ).data
        assertEquals("dissolve", out.results.single().transitionName)
    }

    @Test fun snapshotTimelineReflectsRemoval() = runTest {
        val rig = newRig(projectWithTwoAdjacentClips())
        val add = rig.addOne("v1", "v2")
        rig.remove.execute(
            RemoveTransitionTool.Input(rig.projectId.value, listOf(add.transitionClipId)),
            rig.ctx,
        )
        val lastSnapshot = rig.snapshots.last()
        val effectTrack = lastSnapshot.timeline.tracks.firstOrNull { it.id.value == add.trackId }
        assertNotNull(effectTrack)
        assertEquals(0, effectTrack.clips.size)
    }

    @Test fun snapshotIdIsFreshEachRun() = runTest {
        val rig = newRig(projectWithTwoAdjacentClips())
        val add = rig.addOne("v1", "v2")
        val beforeSnapshotIds = rig.snapshots.map { it.id }.toSet()
        rig.remove.execute(
            RemoveTransitionTool.Input(rig.projectId.value, listOf(add.transitionClipId)),
            rig.ctx,
        )
        val newSnapshotIds = rig.snapshots.map { it.id }.toSet() - beforeSnapshotIds
        assertEquals(1, newSnapshotIds.size)
        val id = newSnapshotIds.single()
        assertTrue(id.value.isNotBlank())
        assertNull(beforeSnapshotIds.firstOrNull { it == id })
    }
}
