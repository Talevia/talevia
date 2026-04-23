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
import io.talevia.core.domain.TextStyle
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

class SetClipVolumeToolTest {

    private data class Rig(
        val store: FileProjectStore,
        val tool: SetClipVolumeTool,
        val ctx: ToolContext,
        val projectId: ProjectId,
        val emittedParts: MutableList<Part>,
    )

    private suspend fun newRig(project: Project): Rig {
        val store = ProjectStoreTestKit.create()
        val tool = SetClipVolumeTool(store)
        val parts = mutableListOf<Part>()
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { parts += it },
            messages = emptyList(),
        )
        store.upsert("test", project)
        return Rig(store, tool, ctx, project.id, parts)
    }

    private fun audioClip(id: String, volume: Float = 1.0f): Clip.Audio = Clip.Audio(
        id = ClipId(id),
        timeRange = TimeRange(0.seconds, 5.seconds),
        sourceRange = TimeRange(0.seconds, 5.seconds),
        assetId = AssetId("a-$id"),
        volume = volume,
    )

    private fun single(clipId: String, volume: Float) = SetClipVolumeTool.Input(
        projectId = "p",
        items = listOf(SetClipVolumeTool.Item(clipId, volume)),
    )

    @Test fun setsAudioClipVolumeAndPreservesOtherFields() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Audio(TrackId("a1"), listOf(audioClip("c1", 1.0f))))),
            ),
        )
        val out = rig.tool.execute(single("c1", 0.3f), rig.ctx).data
        val only = out.results.single()
        assertEquals("c1", only.clipId)
        assertEquals("a1", only.trackId)
        assertEquals(1.0f, only.oldVolume)
        assertEquals(0.3f, only.newVolume)

        val refreshed = rig.store.get(rig.projectId)!!
        val updated = refreshed.timeline.tracks.single().clips.single() as Clip.Audio
        assertEquals(0.3f, updated.volume)
        assertEquals(0.seconds, updated.timeRange.start)
        assertEquals(5.seconds, updated.sourceRange.duration)
        assertEquals(AssetId("a-c1"), updated.assetId)
    }

    @Test fun zeroVolumeMutesWithoutRemoving() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Audio(TrackId("a1"), listOf(audioClip("c1", 0.5f))))),
            ),
        )
        rig.tool.execute(single("c1", 0.0f), rig.ctx)
        val refreshed = rig.store.get(rig.projectId)!!
        val muted = refreshed.timeline.tracks.single().clips.single() as Clip.Audio
        assertEquals(0.0f, muted.volume)
        assertEquals(1, refreshed.timeline.tracks.single().clips.size)
    }

    @Test fun amplificationAboveOneIsAllowed() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Audio(TrackId("a1"), listOf(audioClip("c1"))))),
            ),
        )
        rig.tool.execute(single("c1", 2.5f), rig.ctx)
        val refreshed = rig.store.get(rig.projectId)!!
        val amped = refreshed.timeline.tracks.single().clips.single() as Clip.Audio
        assertEquals(2.5f, amped.volume)
    }

    @Test fun batchSetsDifferentVolumesAtomically() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(
                    Track.Audio(
                        TrackId("a1"),
                        listOf(audioClip("c1", 1.0f), audioClip("c2", 1.0f), audioClip("c3", 1.0f)),
                    ),
                )),
            ),
        )
        val out = rig.tool.execute(
            SetClipVolumeTool.Input(
                projectId = rig.projectId.value,
                items = listOf(
                    SetClipVolumeTool.Item("c1", 0.2f),
                    SetClipVolumeTool.Item("c2", 0.5f),
                    SetClipVolumeTool.Item("c3", 2.0f),
                ),
            ),
            rig.ctx,
        ).data
        assertEquals(3, out.results.size)

        val refreshed = rig.store.get(rig.projectId)!!
        val clips = refreshed.timeline.tracks.single().clips.filterIsInstance<Clip.Audio>().associateBy { it.id.value }
        assertEquals(0.2f, clips["c1"]!!.volume)
        assertEquals(0.5f, clips["c2"]!!.volume)
        assertEquals(2.0f, clips["c3"]!!.volume)
    }

    @Test fun midBatchFailureLeavesProjectUntouched() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(
                    Track.Audio(TrackId("a1"), listOf(audioClip("c1", 1.0f), audioClip("c2", 1.0f))),
                )),
            ),
        )
        val before = rig.store.get(rig.projectId)!!
        assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                SetClipVolumeTool.Input(
                    projectId = rig.projectId.value,
                    items = listOf(
                        SetClipVolumeTool.Item("c1", 0.2f),
                        SetClipVolumeTool.Item("ghost", 0.5f), // fails
                    ),
                ),
                rig.ctx,
            )
        }
        val after = rig.store.get(rig.projectId)!!
        assertEquals(before.timeline, after.timeline)
    }

    @Test fun rejectsVideoClip() = runTest {
        val video = Clip.Video(
            id = ClipId("v1"),
            timeRange = TimeRange(0.seconds, 5.seconds),
            sourceRange = TimeRange(0.seconds, 5.seconds),
            assetId = AssetId("av"),
        )
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(video)))),
            ),
        )
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(single("v1", 0.5f), rig.ctx)
        }
        assertTrue("audio" in ex.message!!, ex.message)
    }

    @Test fun rejectsTextClip() = runTest {
        val text = Clip.Text(
            id = ClipId("t1"),
            timeRange = TimeRange(0.seconds, 5.seconds),
            text = "hi",
            style = TextStyle(),
        )
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Subtitle(TrackId("s1"), listOf(text)))),
            ),
        )
        assertFailsWith<IllegalStateException> {
            rig.tool.execute(single("t1", 0.5f), rig.ctx)
        }
    }

    @Test fun rejectsNegativeVolume() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Audio(TrackId("a1"), listOf(audioClip("c1"))))),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(single("c1", -0.5f), rig.ctx)
        }
        val refreshed = rig.store.get(rig.projectId)!!
        val unchanged = refreshed.timeline.tracks.single().clips.single() as Clip.Audio
        assertEquals(1.0f, unchanged.volume)
    }

    @Test fun rejectsVolumeAboveCap() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Audio(TrackId("a1"), listOf(audioClip("c1"))))),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(single("c1", 5.0f), rig.ctx)
        }
    }

    @Test fun missingClipFailsLoudly() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Audio(TrackId("a1"), listOf(audioClip("c1"))))),
            ),
        )
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(single("ghost", 0.5f), rig.ctx)
        }
        assertTrue("ghost" in ex.message!!, ex.message)
    }

    @Test fun emitsOneSnapshotPerBatch() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(
                    Track.Audio(TrackId("a1"), listOf(audioClip("c1"), audioClip("c2"))),
                )),
            ),
        )
        rig.tool.execute(
            SetClipVolumeTool.Input(
                projectId = rig.projectId.value,
                items = listOf(
                    SetClipVolumeTool.Item("c1", 0.4f),
                    SetClipVolumeTool.Item("c2", 0.6f),
                ),
            ),
            rig.ctx,
        )
        val snapshots = rig.emittedParts.filterIsInstance<Part.TimelineSnapshot>()
        assertEquals(1, snapshots.size)
        val clips = snapshots.single().timeline.tracks.single().clips.filterIsInstance<Clip.Audio>().associateBy { it.id.value }
        assertEquals(0.4f, clips["c1"]!!.volume)
        assertEquals(0.6f, clips["c2"]!!.volume)
    }

    @Test fun rejectsEmptyItems() = runTest {
        val rig = newRig(
            Project(id = ProjectId("p"), timeline = Timeline()),
        )
        assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(SetClipVolumeTool.Input("p", items = emptyList()), rig.ctx)
        }
    }
}
