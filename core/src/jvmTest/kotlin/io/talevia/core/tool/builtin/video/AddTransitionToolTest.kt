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
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The happy-path for AddTransitionTool is covered by M6FeaturesTest. This
 * file nails down the guards (adjacency, missing clip) and the reuse /
 * ordering invariants on the Effect track. A regression in Effect-track
 * reuse would silently duplicate Effect tracks per transition, which
 * would corrupt the UI's effect-lane display and make exports ambiguous.
 */
class AddTransitionToolTest {

    private data class Rig(
        val store: FileProjectStore,
        val tool: AddTransitionTool,
        val ctx: ToolContext,
        val projectId: ProjectId,
    )

    private fun newRig(project: Project): Rig {
        val store = ProjectStoreTestKit.create()
        val tool = AddTransitionTool(store)
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        kotlinx.coroutines.runBlocking { store.upsert("test", project) }
        return Rig(store, tool, ctx, project.id)
    }

    private fun videoClip(id: String, start: Duration, duration: Duration): Clip.Video = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(start, duration),
        sourceRange = TimeRange(Duration.ZERO, duration),
        assetId = AssetId("a-$id"),
    )

    @Test fun nonAdjacentClipsAreRejected() = runTest {
        // Clip v1 ends at 5s, v2 starts at 6s — one-second gap. Transition tool
        // should refuse instead of silently producing a degenerate overlap.
        val v1 = videoClip("v1", Duration.ZERO, 5.seconds)
        val v2 = videoClip("v2", 6.seconds, 5.seconds)
        val rig = newRig(Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = listOf(Track.Video(TrackId("t"), listOf(v1, v2)))),
        ))
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                AddTransitionTool.Input(projectId = "p", fromClipId = "v1", toClipId = "v2"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("adjacent"), "expected adjacency guard, got: ${ex.message}")
    }

    @Test fun missingFromClipIsRejected() = runTest {
        val v1 = videoClip("v1", Duration.ZERO, 5.seconds)
        val rig = newRig(Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = listOf(Track.Video(TrackId("t"), listOf(v1)))),
        ))
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                AddTransitionTool.Input(projectId = "p", fromClipId = "ghost", toClipId = "v1"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("fromClipId"), ex.message)
    }

    @Test fun missingToClipIsRejected() = runTest {
        val v1 = videoClip("v1", Duration.ZERO, 5.seconds)
        val rig = newRig(Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = listOf(Track.Video(TrackId("t"), listOf(v1)))),
        ))
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                AddTransitionTool.Input(projectId = "p", fromClipId = "v1", toClipId = "ghost"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("toClipId"), ex.message)
    }

    @Test fun clipsOnDifferentTracksAreRejected() = runTest {
        val left = videoClip("left", Duration.ZERO, 5.seconds)
        val right = videoClip("right", 5.seconds, 5.seconds)
        val rig = newRig(Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = listOf(
                Track.Video(TrackId("t1"), listOf(left)),
                Track.Video(TrackId("t2"), listOf(right)),
            )),
        ))
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                AddTransitionTool.Input(projectId = "p", fromClipId = "left", toClipId = "right"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("same track"), ex.message)
    }

    @Test fun reusesExistingEffectTrackAcrossTransitions() = runTest {
        // Two adjacent pairs: v1→v2 and v2→v3. After two add_transition calls
        // we must have exactly ONE Effect track containing both transitions
        // (sorted by timeRange.start), otherwise the UI will render duplicate
        // effect lanes and the data model no longer has a canonical effect row.
        val v1 = videoClip("v1", Duration.ZERO, 5.seconds)
        val v2 = videoClip("v2", 5.seconds, 5.seconds)
        val v3 = videoClip("v3", 10.seconds, 5.seconds)
        val rig = newRig(Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = listOf(Track.Video(TrackId("t"), listOf(v1, v2, v3)))),
        ))
        rig.tool.execute(
            AddTransitionTool.Input(projectId = "p", fromClipId = "v2", toClipId = "v3", transitionName = "dissolve", durationSeconds = 0.4),
            rig.ctx,
        )
        rig.tool.execute(
            AddTransitionTool.Input(projectId = "p", fromClipId = "v1", toClipId = "v2", transitionName = "fade", durationSeconds = 0.8),
            rig.ctx,
        )

        val refreshed = rig.store.get(rig.projectId)!!
        val effectTracks = refreshed.timeline.tracks.filterIsInstance<Track.Effect>()
        assertEquals(1, effectTracks.size, "Effect track must be reused, not duplicated per transition")
        val clips = effectTracks.single().clips.filterIsInstance<Clip.Video>()
        assertEquals(2, clips.size)
        // Sorted by start — v1→v2 transition (around 5s) must come before v2→v3 (around 10s).
        assertTrue(clips[0].timeRange.start < clips[1].timeRange.start, "transitions must be sorted by start")
        assertEquals("fade", clips[0].filters.single().name)
        assertEquals("dissolve", clips[1].filters.single().name)
    }

    @Test fun transitionCenteredOnJoinPoint() = runTest {
        // A 0.5s fade between two clips joined at 5s should span [4.75s, 5.25s).
        // Off-centering regresses the cross-fade silently during render.
        val v1 = videoClip("v1", Duration.ZERO, 5.seconds)
        val v2 = videoClip("v2", 5.seconds, 5.seconds)
        val rig = newRig(Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = listOf(Track.Video(TrackId("t"), listOf(v1, v2)))),
        ))
        rig.tool.execute(
            AddTransitionTool.Input(projectId = "p", fromClipId = "v1", toClipId = "v2", transitionName = "fade", durationSeconds = 0.5),
            rig.ctx,
        )
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
        val rig = newRig(Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = listOf(
                Track.Video(TrackId("vtrack"), listOf(v1, v2)),
                existingEffect,
                Track.Audio(TrackId("atrack")),
            )),
        ))

        rig.tool.execute(
            AddTransitionTool.Input(projectId = "p", fromClipId = "v1", toClipId = "v2"),
            rig.ctx,
        )

        val refreshed = rig.store.get(rig.projectId)!!
        assertEquals(listOf("vtrack", "fx", "atrack"), refreshed.timeline.tracks.map { it.id.value })
    }
}
