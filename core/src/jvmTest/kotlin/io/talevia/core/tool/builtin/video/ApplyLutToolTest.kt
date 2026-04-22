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
import io.talevia.core.platform.InMemoryMediaStorage
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ApplyLutToolTest {

    private data class Rig(
        val store: FileProjectStore,
        val media: InMemoryMediaStorage,
        val tool: ApplyLutTool,
        val ctx: ToolContext,
        val projectId: ProjectId,
    )

    private suspend fun newRig(project: Project): Rig {
        val store = ProjectStoreTestKit.create()
        val media = InMemoryMediaStorage()
        val tool = ApplyLutTool(store, media)
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        store.upsert("test", project)
        return Rig(store, media, tool, ctx, project.id)
    }

    private suspend fun Rig.importLut(path: String): AssetId =
        media.import(MediaSource.File(path)) { MediaMetadata(duration = 0.seconds) }.id

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

    @Test fun directLutAssetIdAppendsFilterAndLeavesBindingAlone() = runTest {
        val rig = newRig(projectWithClip())
        val lutAsset = rig.importLut("/tmp/warm.cube")

        val result = rig.tool.execute(
            ApplyLutTool.Input(
                projectId = rig.projectId.value,
                clipId = "c-1",
                lutAssetId = lutAsset.value,
            ),
            rig.ctx,
        )

        assertEquals("c-1", result.data.clipId)
        assertEquals(lutAsset.value, result.data.lutAssetId)
        assertNull(result.data.styleBibleId)
        assertEquals(1, result.data.filterCount)

        val updated = rig.store.get(rig.projectId)!!
            .timeline.tracks.flatMap { it.clips }
            .filterIsInstance<Clip.Video>()
            .single()
        assertEquals(1, updated.filters.size)
        val filter = updated.filters.single()
        assertEquals("lut", filter.name)
        assertEquals(lutAsset, filter.assetId)
        // Direct-assetId path must not mutate sourceBinding.
        assertTrue(updated.sourceBinding.isEmpty())
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
            ApplyLutTool.Input(
                projectId = rig.projectId.value,
                clipId = "c-1",
                styleBibleId = "style-cinematic",
            ),
            rig.ctx,
        )

        assertEquals(lutAsset.value, result.data.lutAssetId)
        assertEquals("style-cinematic", result.data.styleBibleId)

        val updated = rig.store.get(rig.projectId)!!
            .timeline.tracks.flatMap { it.clips }
            .filterIsInstance<Clip.Video>()
            .single()
        assertEquals(lutAsset, updated.filters.single().assetId)
        // The style_bible id is bound to the clip so future stale-clip queries
        // can cascade edits through the DAG (VISION §3.2).
        assertTrue(SourceNodeId("style-cinematic") in updated.sourceBinding)
    }

    @Test fun missingStyleBibleFailsLoudly() = runTest {
        val rig = newRig(projectWithClip())

        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                ApplyLutTool.Input(
                    projectId = rig.projectId.value,
                    clipId = "c-1",
                    styleBibleId = "ghost-style",
                ),
                rig.ctx,
            )
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
            rig.tool.execute(
                ApplyLutTool.Input(
                    projectId = rig.projectId.value,
                    clipId = "c-1",
                    styleBibleId = "no-lut",
                ),
                rig.ctx,
            )
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
                    clipId = "c-1",
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
                    clipId = "c-1",
                ),
                rig.ctx,
            )
        }
    }

    @Test fun missingLutAssetFailsLoudly() = runTest {
        val rig = newRig(projectWithClip())

        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                ApplyLutTool.Input(
                    projectId = rig.projectId.value,
                    clipId = "c-1",
                    lutAssetId = "ghost-lut",
                ),
                rig.ctx,
            )
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
            rig.tool.execute(
                ApplyLutTool.Input(
                    projectId = rig.projectId.value,
                    clipId = "t-1",
                    lutAssetId = lutAsset.value,
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("video clips"), ex.message)
    }
}
