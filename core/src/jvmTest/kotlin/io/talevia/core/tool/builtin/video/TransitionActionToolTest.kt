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

/**
 * Covers both action paths of the consolidated [TransitionActionTool]
 * (debt-consolidate-video-add-remove-verbs, 2026-04-23 — landed for the
 * transition half first). The old `AddTransitionToolTest` +
 * `RemoveTransitionToolTest` test classes folded into this file; case
 * names preserved so a regression that flagged there still flags
 * by the same name here.
 */
class TransitionActionToolTest {

    private data class Rig(
        val store: FileProjectStore,
        val tool: TransitionActionTool,
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
        return Rig(store, TransitionActionTool(store), ctx, snapshots, project.id)
    }

    private fun videoClip(id: String, start: Duration, duration: Duration): Clip.Video = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(start, duration),
        sourceRange = TimeRange(Duration.ZERO, duration),
        assetId = AssetId("a-$id"),
    )

    private fun addOne(
        from: String,
        to: String,
        name: String = "fade",
        durationSeconds: Double = 0.5,
    ): TransitionActionTool.Input = TransitionActionTool.Input(
        projectId = "p",
        action = "add",
        items = listOf(TransitionActionTool.AddItem(from, to, name, durationSeconds)),
    )

    private suspend fun Rig.addOne(
        from: String,
        to: String,
        name: String = "fade",
        durationSeconds: Double = 0.5,
    ): TransitionActionTool.AddResult = tool.execute(
        TransitionActionTool.Input(
            projectId = projectId.value,
            action = "add",
            items = listOf(TransitionActionTool.AddItem(from, to, name, durationSeconds)),
        ),
        ctx,
    ).data.added.single()

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

    // ──────────── Add action ────────────

    @Test fun nonAdjacentClipsAreRejected() = runTest {
        val v1 = videoClip("v1", Duration.ZERO, 5.seconds)
        val v2 = videoClip("v2", 6.seconds, 5.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("t"), listOf(v1, v2)))),
            ),
        )
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(addOne("v1", "v2"), rig.ctx)
        }
        assertTrue(ex.message!!.contains("adjacent"), "expected adjacency guard, got: ${ex.message}")
    }

    @Test fun missingFromClipIsRejected() = runTest {
        val v1 = videoClip("v1", Duration.ZERO, 5.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("t"), listOf(v1)))),
            ),
        )
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(addOne("ghost", "v1"), rig.ctx)
        }
        assertTrue(ex.message!!.contains("fromClipId"), ex.message)
    }

    @Test fun missingToClipIsRejected() = runTest {
        val v1 = videoClip("v1", Duration.ZERO, 5.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("t"), listOf(v1)))),
            ),
        )
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(addOne("v1", "ghost"), rig.ctx)
        }
        assertTrue(ex.message!!.contains("toClipId"), ex.message)
    }

    @Test fun clipsOnDifferentTracksAreRejected() = runTest {
        val left = videoClip("left", Duration.ZERO, 5.seconds)
        val right = videoClip("right", 5.seconds, 5.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(TrackId("t1"), listOf(left)),
                        Track.Video(TrackId("t2"), listOf(right)),
                    ),
                ),
            ),
        )
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(addOne("left", "right"), rig.ctx)
        }
        assertTrue(ex.message!!.contains("same track"), ex.message)
    }

    @Test fun reusesExistingEffectTrackAcrossTransitions() = runTest {
        val v1 = videoClip("v1", Duration.ZERO, 5.seconds)
        val v2 = videoClip("v2", 5.seconds, 5.seconds)
        val v3 = videoClip("v3", 10.seconds, 5.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("t"), listOf(v1, v2, v3)))),
            ),
        )
        rig.tool.execute(addOne("v2", "v3", "dissolve", 0.4), rig.ctx)
        rig.tool.execute(addOne("v1", "v2", "fade", 0.8), rig.ctx)

        val refreshed = rig.store.get(rig.projectId)!!
        val effectTracks = refreshed.timeline.tracks.filterIsInstance<Track.Effect>()
        assertEquals(1, effectTracks.size, "Effect track must be reused, not duplicated per transition")
        val clips = effectTracks.single().clips.filterIsInstance<Clip.Video>()
        assertEquals(2, clips.size)
        assertTrue(clips[0].timeRange.start < clips[1].timeRange.start, "transitions must be sorted by start")
        assertEquals("fade", clips[0].filters.single().name)
        assertEquals("dissolve", clips[1].filters.single().name)
    }

    @Test fun transitionCenteredOnJoinPoint() = runTest {
        val v1 = videoClip("v1", Duration.ZERO, 5.seconds)
        val v2 = videoClip("v2", 5.seconds, 5.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("t"), listOf(v1, v2)))),
            ),
        )
        rig.tool.execute(addOne("v1", "v2", "fade", 0.5), rig.ctx)
        val refreshed = rig.store.get(rig.projectId)!!
        val transition = refreshed.timeline.tracks.filterIsInstance<Track.Effect>()
            .single().clips.single() as Clip.Video
        assertEquals(4750, transition.timeRange.start.inWholeMilliseconds)
        assertEquals(500, transition.timeRange.duration.inWholeMilliseconds)
        assertEquals("transition:fade", transition.assetId.value)
        assertNotNull(transition.filters.singleOrNull())
        assertEquals(0.5f, transition.filters.single().params["durationSeconds"])
    }

    @Test fun preservesExistingEffectTrackPosition() = runTest {
        val v1 = videoClip("v1", Duration.ZERO, 5.seconds)
        val v2 = videoClip("v2", 5.seconds, 5.seconds)
        val existingEffect = Track.Effect(TrackId("fx"))
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(TrackId("vtrack"), listOf(v1, v2)),
                        existingEffect,
                        Track.Audio(TrackId("atrack")),
                    ),
                ),
            ),
        )

        rig.tool.execute(addOne("v1", "v2"), rig.ctx)

        val refreshed = rig.store.get(rig.projectId)!!
        assertEquals(listOf("vtrack", "fx", "atrack"), refreshed.timeline.tracks.map { it.id.value })
    }

    @Test fun addBatchInsertsMultipleTransitionsAtomically() = runTest {
        val v1 = videoClip("v1", Duration.ZERO, 5.seconds)
        val v2 = videoClip("v2", 5.seconds, 5.seconds)
        val v3 = videoClip("v3", 10.seconds, 5.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("t"), listOf(v1, v2, v3)))),
            ),
        )
        val out = rig.tool.execute(
            TransitionActionTool.Input(
                projectId = "p",
                action = "add",
                items = listOf(
                    TransitionActionTool.AddItem("v1", "v2", "fade", 0.5),
                    TransitionActionTool.AddItem("v2", "v3", "dissolve", 0.4),
                ),
            ),
            rig.ctx,
        ).data
        assertEquals(2, out.added.size)
        val refreshed = rig.store.get(rig.projectId)!!
        val effectClips = refreshed.timeline.tracks.filterIsInstance<Track.Effect>()
            .single().clips.filterIsInstance<Clip.Video>()
        assertEquals(2, effectClips.size)
    }

    @Test fun addEmitsOneSnapshotPerBatch() = runTest {
        val v1 = videoClip("v1", Duration.ZERO, 5.seconds)
        val v2 = videoClip("v2", 5.seconds, 5.seconds)
        val v3 = videoClip("v3", 10.seconds, 5.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("t"), listOf(v1, v2, v3)))),
            ),
        )
        val before = rig.snapshots.size
        rig.tool.execute(
            TransitionActionTool.Input(
                projectId = "p",
                action = "add",
                items = listOf(
                    TransitionActionTool.AddItem("v1", "v2"),
                    TransitionActionTool.AddItem("v2", "v3"),
                ),
            ),
            rig.ctx,
        )
        assertEquals(before + 1, rig.snapshots.size)
    }

    @Test fun addBatchFailureLeavesProjectUntouched() = runTest {
        val v1 = videoClip("v1", Duration.ZERO, 5.seconds)
        val v2 = videoClip("v2", 5.seconds, 5.seconds)
        val v3 = videoClip("v3", 11.seconds, 5.seconds) // gap → v2→v3 not adjacent
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("t"), listOf(v1, v2, v3)))),
            ),
        )
        val beforeProject = rig.store.get(rig.projectId)!!
        val beforeSnapshots = rig.snapshots.size

        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                TransitionActionTool.Input(
                    projectId = "p",
                    action = "add",
                    items = listOf(
                        TransitionActionTool.AddItem("v1", "v2"), // ok
                        TransitionActionTool.AddItem("v2", "v3"), // not adjacent → fail
                    ),
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("items[1]"), ex.message)

        val afterProject = rig.store.get(rig.projectId)!!
        assertEquals(beforeProject.timeline, afterProject.timeline)
        assertEquals(beforeSnapshots, rig.snapshots.size)
    }

    @Test fun addRejectsEmptyItemsList() = runTest {
        val rig = newRig(Project(id = ProjectId("p"), timeline = Timeline()))
        val ex = assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(
                TransitionActionTool.Input("p", action = "add", items = emptyList()),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("must not be empty"))
    }

    @Test fun addRequiresItemsField() = runTest {
        val rig = newRig(Project(id = ProjectId("p"), timeline = Timeline()))
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(TransitionActionTool.Input("p", action = "add"), rig.ctx)
        }
        assertTrue(ex.message!!.contains("items"), ex.message)
    }

    // ──────────── Remove action ────────────

    @Test fun removesSingletonTransitionAndLeavesVideoClipsIntact() = runTest {
        val rig = newRig(projectWithTwoAdjacentClips())
        val add = rig.addOne("v1", "v2", "fade", 0.5)
        val beforeEffectClipCount = rig.store.get(rig.projectId)!!.timeline.tracks
            .first { it.id.value == add.trackId }.clips.size
        assertEquals(1, beforeEffectClipCount)

        val out = rig.tool.execute(
            TransitionActionTool.Input(
                projectId = rig.projectId.value,
                action = "remove",
                transitionClipIds = listOf(add.transitionClipId),
            ),
            rig.ctx,
        ).data
        assertEquals(1, out.removed.size)
        val only = out.removed.single()
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
        val twoAdds = rig.tool.execute(
            TransitionActionTool.Input(
                projectId = rig.projectId.value,
                action = "add",
                items = listOf(
                    TransitionActionTool.AddItem("v1", "v2", "fade", 0.5),
                    TransitionActionTool.AddItem("v2", "v3", "dissolve", 0.5),
                ),
            ),
            rig.ctx,
        ).data.added
        val t1 = twoAdds[0]
        val t2 = twoAdds[1]
        assertEquals(t1.trackId, t2.trackId) // reused Effect track

        val out = rig.tool.execute(
            TransitionActionTool.Input(
                projectId = rig.projectId.value,
                action = "remove",
                transitionClipIds = listOf(t1.transitionClipId, t2.transitionClipId),
            ),
            rig.ctx,
        ).data
        assertEquals(2, out.removed.size)
        assertEquals(0, out.remainingTransitionsTotal)
        assertEquals(
            setOf("fade", "dissolve"),
            out.removed.map { it.transitionName }.toSet(),
        )

        val proj = rig.store.get(rig.projectId)!!
        val effectClips = proj.timeline.tracks.first { it.id.value == t1.trackId }.clips
        assertEquals(0, effectClips.size)
    }

    @Test fun removeEmitsOneSnapshotPerBatch() = runTest {
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
        val twoAdds = rig.tool.execute(
            TransitionActionTool.Input(
                projectId = rig.projectId.value,
                action = "add",
                items = listOf(
                    TransitionActionTool.AddItem("v1", "v2", "fade", 0.5),
                    TransitionActionTool.AddItem("v2", "v3", "dissolve", 0.5),
                ),
            ),
            rig.ctx,
        ).data.added
        val before = rig.snapshots.size
        rig.tool.execute(
            TransitionActionTool.Input(
                projectId = rig.projectId.value,
                action = "remove",
                transitionClipIds = listOf(twoAdds[0].transitionClipId, twoAdds[1].transitionClipId),
            ),
            rig.ctx,
        )
        assertEquals(before + 1, rig.snapshots.size)
    }

    @Test fun removeBatchAtomicityLeavesProjectUntouchedOnAnyFailure() = runTest {
        val rig = newRig(projectWithTwoAdjacentClips())
        val add = rig.addOne("v1", "v2", "fade", 0.5)
        val beforeProject = rig.store.get(rig.projectId)!!
        val beforeSnapshotCount = rig.snapshots.size

        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                TransitionActionTool.Input(
                    projectId = rig.projectId.value,
                    action = "remove",
                    transitionClipIds = listOf(add.transitionClipId, "does-not-exist"),
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("transitionClipIds[1]"), ex.message)

        val afterProject = rig.store.get(rig.projectId)!!
        assertEquals(beforeProject.timeline, afterProject.timeline)
        assertEquals(beforeSnapshotCount, rig.snapshots.size)
    }

    @Test fun removeRejectsEmptyIdList() = runTest {
        val rig = newRig(projectWithTwoAdjacentClips())
        val ex = assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(
                TransitionActionTool.Input(
                    projectId = rig.projectId.value,
                    action = "remove",
                    transitionClipIds = emptyList(),
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("must not be empty"))
    }

    @Test fun removeRejectsRegularClipId() = runTest {
        val rig = newRig(projectWithTwoAdjacentClips())
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                TransitionActionTool.Input(
                    projectId = rig.projectId.value,
                    action = "remove",
                    transitionClipIds = listOf("v1"),
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("video"))
        assertTrue(ex.message!!.contains("remove_clips"))
    }

    @Test fun removeRejectsUnknownId() = runTest {
        val rig = newRig(projectWithTwoAdjacentClips())
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                TransitionActionTool.Input(
                    projectId = rig.projectId.value,
                    action = "remove",
                    transitionClipIds = listOf("does-not-exist"),
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("not found"))
    }

    @Test fun removeRejectsEffectClipWithoutTransitionSentinel() = runTest {
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
            rig.tool.execute(
                TransitionActionTool.Input(
                    projectId = rig.projectId.value,
                    action = "remove",
                    transitionClipIds = listOf("fx-1"),
                ),
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
        val twoAdds = rig.tool.execute(
            TransitionActionTool.Input(
                projectId = rig.projectId.value,
                action = "add",
                items = listOf(
                    TransitionActionTool.AddItem("v1", "v2", "fade", 0.5),
                    TransitionActionTool.AddItem("v2", "v3", "dissolve", 0.5),
                ),
            ),
            rig.ctx,
        ).data.added
        val t1 = twoAdds[0]
        val t2 = twoAdds[1]
        assertEquals(t1.trackId, t2.trackId)

        val out = rig.tool.execute(
            TransitionActionTool.Input(
                projectId = rig.projectId.value,
                action = "remove",
                transitionClipIds = listOf(t1.transitionClipId),
            ),
            rig.ctx,
        ).data
        assertEquals(1, out.remainingTransitionsTotal)

        val proj = rig.store.get(rig.projectId)!!
        val effectClips = proj.timeline.tracks.first { it.id.value == t1.trackId }.clips
        assertEquals(listOf(t2.transitionClipId), effectClips.map { it.id.value })
    }

    @Test fun removeTransitionNameEchoesFirstFilter() = runTest {
        val rig = newRig(projectWithTwoAdjacentClips())
        val add = rig.addOne("v1", "v2", "dissolve", 0.4)
        val out = rig.tool.execute(
            TransitionActionTool.Input(
                projectId = rig.projectId.value,
                action = "remove",
                transitionClipIds = listOf(add.transitionClipId),
            ),
            rig.ctx,
        ).data
        assertEquals("dissolve", out.removed.single().transitionName)
    }

    @Test fun removeSnapshotTimelineReflectsRemoval() = runTest {
        val rig = newRig(projectWithTwoAdjacentClips())
        val add = rig.addOne("v1", "v2")
        rig.tool.execute(
            TransitionActionTool.Input(
                projectId = rig.projectId.value,
                action = "remove",
                transitionClipIds = listOf(add.transitionClipId),
            ),
            rig.ctx,
        )
        val lastSnapshot = rig.snapshots.last()
        val effectTrack = lastSnapshot.timeline.tracks.firstOrNull { it.id.value == add.trackId }
        assertNotNull(effectTrack)
        assertEquals(0, effectTrack.clips.size)
    }

    @Test fun removeSnapshotIdIsFreshEachRun() = runTest {
        val rig = newRig(projectWithTwoAdjacentClips())
        val add = rig.addOne("v1", "v2")
        val beforeSnapshotIds = rig.snapshots.map { it.id }.toSet()
        rig.tool.execute(
            TransitionActionTool.Input(
                projectId = rig.projectId.value,
                action = "remove",
                transitionClipIds = listOf(add.transitionClipId),
            ),
            rig.ctx,
        )
        val newSnapshotIds = rig.snapshots.map { it.id }.toSet() - beforeSnapshotIds
        assertEquals(1, newSnapshotIds.size)
        val id = newSnapshotIds.single()
        assertTrue(id.value.isNotBlank())
        assertNull(beforeSnapshotIds.firstOrNull { it == id })
    }

    @Test fun rejectsUnknownAction() = runTest {
        val rig = newRig(Project(id = ProjectId("p"), timeline = Timeline()))
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                TransitionActionTool.Input("p", action = "delete"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("delete"), ex.message)
    }
}
