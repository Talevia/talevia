package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
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
import io.talevia.core.domain.source.consistency.StyleBibleBody
import io.talevia.core.domain.source.consistency.addStyleBible
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class ApplyLutToolTest {

    private data class Rig(
        val store: FileProjectStore,
        val tool: ApplyLutTool,
        val ctx: ToolContext,
        val projectId: ProjectId,
    )

    private suspend fun newRig(project: Project): Rig {
        val store = ProjectStoreTestKit.create()
        val tool = ApplyLutTool(store)
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

    private suspend fun Rig.importLut(path: String): AssetId {
        val assetId = AssetId(Uuid.random().toString())
        val asset = MediaAsset(
            id = assetId,
            source = MediaSource.File(path),
            metadata = MediaMetadata(duration = 0.seconds),
        )
        store.mutate(projectId) { it.copy(assets = it.assets + asset) }
        return assetId
    }

    private fun videoClip(id: String = "c-1", assetId: String = "asset-original") = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(0.seconds, 4.seconds),
        sourceRange = TimeRange(0.seconds, 4.seconds),
        assetId = AssetId(assetId),
    )

    private fun projectWithClip(clip: Clip.Video = videoClip()): Project = Project(
        id = ProjectId("p"),
        timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(clip)))),
    )

    private fun single(clipId: String, lutAssetId: String? = null, styleBibleId: String? = null) =
        ApplyLutTool.Input(
            projectId = "p",
            clipIds = listOf(clipId),
            lutAssetId = lutAssetId,
            styleBibleId = styleBibleId,
        )

    @Test fun directLutAssetIdAppendsFilterAndLeavesBindingAlone() = runTest {
        val rig = newRig(projectWithClip())
        val lutAsset = rig.importLut("/tmp/warm.cube")

        val result = rig.tool.execute(single("c-1", lutAssetId = lutAsset.value), rig.ctx).data
        val only = result.results.single()
        assertEquals("c-1", only.clipId)
        assertEquals(lutAsset.value, result.lutAssetId)
        assertNull(result.styleBibleId)
        assertEquals(1, only.filterCount)

        val updated = rig.store.get(rig.projectId)!!
            .timeline.tracks.flatMap { it.clips }
            .filterIsInstance<Clip.Video>()
            .single()
        assertEquals(1, updated.filters.size)
        val filter = updated.filters.single()
        assertEquals("lut", filter.name)
        assertEquals(lutAsset, filter.assetId)
        assertTrue(updated.sourceBinding.isEmpty())
    }

    @Test fun broadcastAppliesSameLutToMultipleClips() = runTest {
        val c1 = videoClip("c-1", assetId = "a1")
        val c2 = videoClip("c-2", assetId = "a2")
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(c1, c2)))),
            ),
        )
        val lutAsset = rig.importLut("/tmp/warm.cube")
        val result = rig.tool.execute(
            ApplyLutTool.Input(
                projectId = rig.projectId.value,
                clipIds = listOf("c-1", "c-2"),
                lutAssetId = lutAsset.value,
            ),
            rig.ctx,
        ).data
        assertEquals(2, result.results.size)
        val clips = rig.store.get(rig.projectId)!!.timeline.tracks.flatMap { it.clips }
            .filterIsInstance<Clip.Video>().associateBy { it.id.value }
        assertEquals(1, clips["c-1"]!!.filters.size)
        assertEquals(1, clips["c-2"]!!.filters.size)
    }

    @Test fun midBatchFailureLeavesProjectUntouched() = runTest {
        val rig = newRig(projectWithClip())
        val lutAsset = rig.importLut("/tmp/warm.cube")
        val before = rig.store.get(rig.projectId)!!
        assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                ApplyLutTool.Input(
                    projectId = rig.projectId.value,
                    clipIds = listOf("c-1", "ghost"),
                    lutAssetId = lutAsset.value,
                ),
                rig.ctx,
            )
        }
        assertEquals(before.timeline, rig.store.get(rig.projectId)!!.timeline)
    }

    @Test fun styleBibleIdResolvesLutReferenceAndBindsClip() = runTest {
        val rig = newRig(projectWithClip())
        val lutAsset = rig.importLut("/tmp/cinematic.cube")
        rig.store.mutateSource(rig.projectId) { src ->
            src.addStyleBible(
                id = SourceNodeId("style-cinematic"),
                body = StyleBibleBody(
                    name = "cinematic-warm",
                    description = "golden-hour warmth",
                    lutReference = lutAsset,
                ),
            )
        }

        val result = rig.tool.execute(
            single("c-1", styleBibleId = "style-cinematic"),
            rig.ctx,
        ).data

        assertEquals(lutAsset.value, result.lutAssetId)
        assertEquals("style-cinematic", result.styleBibleId)

        val updated = rig.store.get(rig.projectId)!!
            .timeline.tracks.flatMap { it.clips }
            .filterIsInstance<Clip.Video>()
            .single()
        assertEquals(lutAsset, updated.filters.single().assetId)
        assertTrue(SourceNodeId("style-cinematic") in updated.sourceBinding)
    }

    @Test fun missingStyleBibleFailsLoudly() = runTest {
        val rig = newRig(projectWithClip())

        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(single("c-1", styleBibleId = "ghost-style"), rig.ctx)
        }
        assertTrue(ex.message!!.contains("ghost-style"), ex.message)
    }

    @Test fun styleBibleWithoutLutReferenceFailsLoudly() = runTest {
        val rig = newRig(projectWithClip())
        rig.store.mutateSource(rig.projectId) { src ->
            src.addStyleBible(
                id = SourceNodeId("no-lut"),
                body = StyleBibleBody(name = "bare", description = "no LUT set"),
            )
        }

        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(single("c-1", styleBibleId = "no-lut"), rig.ctx)
        }
        assertTrue(ex.message!!.contains("lutReference"), ex.message)
    }

    @Test fun bothIdsSuppliedIsRejected() = runTest {
        val rig = newRig(projectWithClip())
        val lutAsset = rig.importLut("/tmp/a.cube")

        assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(
                ApplyLutTool.Input(
                    projectId = rig.projectId.value,
                    clipIds = listOf("c-1"),
                    lutAssetId = lutAsset.value,
                    styleBibleId = "style-x",
                ),
                rig.ctx,
            )
        }
    }

    @Test fun neitherIdSuppliedIsRejected() = runTest {
        val rig = newRig(projectWithClip())
        assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(
                ApplyLutTool.Input(
                    projectId = rig.projectId.value,
                    clipIds = listOf("c-1"),
                ),
                rig.ctx,
            )
        }
    }

    @Test fun missingLutAssetFailsLoudly() = runTest {
        val rig = newRig(projectWithClip())

        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(single("c-1", lutAssetId = "ghost-lut"), rig.ctx)
        }
        assertTrue(ex.message!!.contains("ghost-lut"), ex.message)
    }

    @Test fun nonVideoClipIsRejected() = runTest {
        val textClip = Clip.Text(
            id = ClipId("t-1"),
            timeRange = TimeRange(0.seconds, 1.seconds),
            text = "hello",
        )
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Subtitle(TrackId("sub"), listOf(textClip)))),
            ),
        )
        val lutAsset = rig.importLut("/tmp/x.cube")

        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(single("t-1", lutAssetId = lutAsset.value), rig.ctx)
        }
        assertTrue(ex.message!!.contains("video"), ex.message)
    }
}
