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
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
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
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class AddClipToolTest {

    private data class Rig(
        val store: FileProjectStore,
        val tool: AddClipTool,
        val ctx: ToolContext,
        val projectId: ProjectId,
    )

    private suspend fun newRig(project: Project): Rig {
        val store = ProjectStoreTestKit.create()
        val tool = AddClipTool(store)
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        store.upsert("test", project)
        return Rig(store, tool, ctx, project.id)
    }

    private suspend fun Rig.importAsset(path: String, durationSeconds: Double): AssetId {
        val assetId = AssetId(Uuid.random().toString())
        val asset = MediaAsset(
            id = assetId,
            source = MediaSource.File(path),
            metadata = MediaMetadata(duration = durationSeconds.seconds),
        )
        store.mutate(projectId) { it.copy(assets = it.assets + asset) }
        return assetId
    }

    private fun single(
        assetId: String,
        timelineStartSeconds: Double? = null,
        sourceStartSeconds: Double = 0.0,
        durationSeconds: Double? = null,
        trackId: String? = null,
    ) = AddClipTool.Input(
        projectId = null,
        items = listOf(
            AddClipTool.Item(
                assetId = assetId,
                timelineStartSeconds = timelineStartSeconds,
                sourceStartSeconds = sourceStartSeconds,
                durationSeconds = durationSeconds,
                trackId = trackId,
            ),
        ),
    )

    @Test fun returnsTheInsertedClipWhenAddingIntoMiddleOfTrack() = runTest {
        val existing = Clip.Video(
            id = ClipId("existing"),
            timeRange = TimeRange(5.seconds, 5.seconds),
            sourceRange = TimeRange(Duration.ZERO, 5.seconds),
            assetId = AssetId("asset-existing"),
        )
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(existing)))),
            ),
        )
        val assetId = rig.importAsset("/tmp/new.mp4", durationSeconds = 3.0)

        val result = rig.tool.execute(
            AddClipTool.Input(
                projectId = rig.projectId.value,
                items = listOf(
                    AddClipTool.Item(
                        assetId = assetId.value,
                        timelineStartSeconds = 1.0,
                        durationSeconds = 1.5,
                    ),
                ),
            ),
            rig.ctx,
        )

        val only = result.data.results.single()
        assertEquals(1.0, only.timelineStartSeconds, 0.001)
        assertEquals(2.5, only.timelineEndSeconds, 0.001)

        val refreshed = rig.store.get(rig.projectId)!!
        val added = refreshed.timeline.tracks.flatMap { it.clips }
            .filterIsInstance<Clip.Video>()
            .first { it.id.value == only.clipId }
        assertEquals(1.seconds, added.timeRange.start)
        assertEquals(1.5.seconds, added.timeRange.duration)
        assertEquals(assetId, added.assetId)
    }

    @Test fun batchAddsMultipleClipsEndToEndOnSameTrack() = runTest {
        val rig = newRig(Project(id = ProjectId("p"), timeline = Timeline()))
        val a1 = rig.importAsset("/tmp/a.mp4", 3.0)
        val a2 = rig.importAsset("/tmp/b.mp4", 4.0)
        val a3 = rig.importAsset("/tmp/c.mp4", 2.0)

        val out = rig.tool.execute(
            AddClipTool.Input(
                projectId = rig.projectId.value,
                items = listOf(
                    AddClipTool.Item(a1.value),
                    AddClipTool.Item(a2.value),
                    AddClipTool.Item(a3.value),
                ),
            ),
            rig.ctx,
        ).data
        assertEquals(3, out.results.size)
        assertEquals(0.0, out.results[0].timelineStartSeconds, 0.001)
        assertEquals(3.0, out.results[1].timelineStartSeconds, 0.001)
        assertEquals(7.0, out.results[2].timelineStartSeconds, 0.001)

        val refreshed = rig.store.get(rig.projectId)!!
        assertEquals(3, refreshed.timeline.tracks.single().clips.size)
    }

    @Test fun midBatchFailureLeavesProjectUntouched() = runTest {
        val rig = newRig(Project(id = ProjectId("p"), timeline = Timeline()))
        val a1 = rig.importAsset("/tmp/a.mp4", 3.0)
        val before = rig.store.get(rig.projectId)!!
        assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                AddClipTool.Input(
                    projectId = rig.projectId.value,
                    items = listOf(
                        AddClipTool.Item(a1.value),
                        AddClipTool.Item("missing-asset"),
                    ),
                ),
                rig.ctx,
            )
        }
        assertEquals(before.timeline, rig.store.get(rig.projectId)!!.timeline)
    }

    @Test fun explicitNonVideoTrackIsRejected() = runTest {
        val audioTrack = Track.Audio(TrackId("a1"))
        val rig = newRig(Project(id = ProjectId("p"), timeline = Timeline(tracks = listOf(audioTrack))))
        val assetId = rig.importAsset("/tmp/audio.mp4", durationSeconds = 3.0)

        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                AddClipTool.Input(
                    projectId = rig.projectId.value,
                    items = listOf(AddClipTool.Item(assetId.value, trackId = "a1")),
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("not a video track"), ex.message)
    }

    @Test fun sourceStartPastAssetDurationIsRejected() = runTest {
        val rig = newRig(Project(id = ProjectId("p"), timeline = Timeline()))
        val assetId = rig.importAsset("/tmp/short.mp4", durationSeconds = 2.0)

        val ex = assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(
                AddClipTool.Input(
                    projectId = rig.projectId.value,
                    items = listOf(AddClipTool.Item(assetId.value, sourceStartSeconds = 3.0)),
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("exceeds asset duration"), ex.message)
    }

    @Test fun preservesExistingTrackOrderWhenUpdatingVideoTrack() = runTest {
        val subtitleTrack = Track.Subtitle(TrackId("s1"))
        val videoTrack = Track.Video(TrackId("v1"))
        val audioTrack = Track.Audio(TrackId("a1"))
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(subtitleTrack, videoTrack, audioTrack)),
            ),
        )
        val assetId = rig.importAsset("/tmp/new.mp4", durationSeconds = 3.0)

        rig.tool.execute(
            AddClipTool.Input(
                projectId = rig.projectId.value,
                items = listOf(AddClipTool.Item(assetId.value, trackId = "v1")),
            ),
            rig.ctx,
        )

        val refreshed = rig.store.get(rig.projectId)!!
        assertEquals(listOf("s1", "v1", "a1"), refreshed.timeline.tracks.map { it.id.value })
    }

    @Test fun projectIdOmittedFallsBackToSessionBinding() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1")))),
            ),
        )
        val assetId = rig.importAsset("/tmp/new.mp4", durationSeconds = 3.0)
        val ctxBound = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
            currentProjectId = rig.projectId,
        )

        val result = rig.tool.execute(
            single(assetId = assetId.value),
            ctxBound,
        ).data
        assertEquals("v1", result.results.single().trackId)
        val refreshed = rig.store.get(rig.projectId)!!
        val videoTrack = refreshed.timeline.tracks.filterIsInstance<Track.Video>().single { it.id.value == "v1" }
        assertEquals(1, videoTrack.clips.size)
    }

    @Test fun unboundSessionAndOmittedProjectIdFailsLoud() = runTest {
        val rig = newRig(
            Project(id = ProjectId("p"), timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"))))),
        )
        val assetId = rig.importAsset("/tmp/new.mp4", durationSeconds = 3.0)
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(single(assetId = assetId.value), rig.ctx)
        }
        assertTrue(ex.message!!.contains("switch_project"), ex.message)
    }
}
