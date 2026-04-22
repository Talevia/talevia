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
import io.talevia.core.domain.Filter
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.Transform
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.platform.InMemoryMediaStorage
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ReplaceClipToolTest {

    private data class Rig(
        val store: FileProjectStore,
        val media: InMemoryMediaStorage,
        val tool: ReplaceClipTool,
        val ctx: ToolContext,
        val projectId: ProjectId,
    )

    private suspend fun newRig(project: Project): Rig {
        val store = ProjectStoreTestKit.create()
        val media = InMemoryMediaStorage()
        val tool = ReplaceClipTool(store, media)
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

    private suspend fun Rig.importAsset(path: String, durationSeconds: Double = 3.0): AssetId =
        media.import(MediaSource.File(path)) { MediaMetadata(duration = durationSeconds.seconds) }.id

    private fun fakeProvenance(): GenerationProvenance = GenerationProvenance(
        providerId = "fake",
        modelId = "fake-model",
        modelVersion = null,
        seed = 1L,
        parameters = JsonObject(emptyMap()),
        createdAtEpochMs = 1_700_000_000_000L,
    )

    @Test fun replacesAssetIdAndPreservesPositionAndFilters() = runTest {
        val originalAsset = AssetId("asset-original")
        val clip = Clip.Video(
            id = ClipId("c-1"),
            timeRange = TimeRange(2.seconds, 4.seconds),
            sourceRange = TimeRange(1.seconds, 4.seconds),
            transforms = listOf(Transform(scaleX = 1.5f)),
            assetId = originalAsset,
            filters = listOf(Filter("blur", mapOf("radius" to 3f))),
        )
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(clip)))),
            ),
        )
        val replacement = rig.importAsset("/tmp/regen.mp4")

        val result = rig.tool.execute(
            ReplaceClipTool.Input(
                projectId = rig.projectId.value,
                clipId = "c-1",
                newAssetId = replacement.value,
            ),
            rig.ctx,
        )

        assertEquals("c-1", result.data.clipId)
        assertEquals(originalAsset.value, result.data.previousAssetId)
        assertEquals(replacement.value, result.data.newAssetId)
        assertTrue(result.data.sourceBindingIds.isEmpty(), "no lockfile entry → no binding to copy")

        val refreshed = rig.store.get(rig.projectId)!!
        val updated = refreshed.timeline.tracks.flatMap { it.clips }
            .filterIsInstance<Clip.Video>()
            .single { it.id.value == "c-1" }
        assertEquals(replacement, updated.assetId)
        // Position / source range / transforms / filters all preserved.
        assertEquals(TimeRange(2.seconds, 4.seconds), updated.timeRange)
        assertEquals(TimeRange(1.seconds, 4.seconds), updated.sourceRange)
        assertEquals(listOf(Transform(scaleX = 1.5f)), updated.transforms)
        assertEquals(listOf(Filter("blur", mapOf("radius" to 3f))), updated.filters)
    }

    @Test fun copiesSourceBindingFromNewAssetsLockfileEntry() = runTest {
        val originalAsset = AssetId("asset-original")
        val clip = Clip.Video(
            id = ClipId("c-1"),
            timeRange = TimeRange(0.seconds, 4.seconds),
            sourceRange = TimeRange(0.seconds, 4.seconds),
            assetId = originalAsset,
        )
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(clip)))),
            ),
        )
        val replacement = rig.importAsset("/tmp/regen-bound.mp4")
        // Pretend a regenerate call had already produced this asset bound to a character ref.
        val boundIds = setOf(SourceNodeId("mei"), SourceNodeId("noir"))
        rig.store.mutate(rig.projectId) {
            it.copy(
                lockfile = it.lockfile.append(
                    LockfileEntry(
                        inputHash = "h-regen",
                        toolId = "generate_image",
                        assetId = replacement,
                        provenance = fakeProvenance(),
                        sourceBinding = boundIds,
                        sourceContentHashes = boundIds.associateWith { _ -> "snapshot" },
                    ),
                ),
            )
        }

        rig.tool.execute(
            ReplaceClipTool.Input(
                projectId = rig.projectId.value,
                clipId = "c-1",
                newAssetId = replacement.value,
            ),
            rig.ctx,
        )

        val refreshed = rig.store.get(rig.projectId)!!
        val updated = refreshed.timeline.tracks.flatMap { it.clips }
            .filterIsInstance<Clip.Video>()
            .single { it.id.value == "c-1" }
        assertEquals(boundIds, updated.sourceBinding)
    }

    @Test fun audioClipIsAlsoSupported() = runTest {
        val originalAsset = AssetId("audio-original")
        val clip = Clip.Audio(
            id = ClipId("a-1"),
            timeRange = TimeRange(0.seconds, 2.seconds),
            sourceRange = TimeRange(0.seconds, 2.seconds),
            assetId = originalAsset,
            volume = 0.7f,
        )
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Audio(TrackId("a-track"), listOf(clip)))),
            ),
        )
        val replacement = rig.importAsset("/tmp/audio-regen.mp3")

        rig.tool.execute(
            ReplaceClipTool.Input(
                projectId = rig.projectId.value,
                clipId = "a-1",
                newAssetId = replacement.value,
            ),
            rig.ctx,
        )

        val refreshed = rig.store.get(rig.projectId)!!
        val updated = refreshed.timeline.tracks.flatMap { it.clips }
            .filterIsInstance<Clip.Audio>()
            .single { it.id.value == "a-1" }
        assertEquals(replacement, updated.assetId)
        assertEquals(0.7f, updated.volume, "volume must survive the asset swap")
    }

    @Test fun textClipIsRejected() = runTest {
        val clip = Clip.Text(
            id = ClipId("t-1"),
            timeRange = TimeRange(0.seconds, 1.seconds),
            text = "hello",
        )
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Subtitle(TrackId("s1"), listOf(clip)))),
            ),
        )
        val replacement = rig.importAsset("/tmp/something.mp4")

        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                ReplaceClipTool.Input(
                    projectId = rig.projectId.value,
                    clipId = "t-1",
                    newAssetId = replacement.value,
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("text clips"), ex.message)
    }

    @Test fun missingClipFailsLoudly() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1")))),
            ),
        )
        val replacement = rig.importAsset("/tmp/x.mp4")

        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                ReplaceClipTool.Input(
                    projectId = rig.projectId.value,
                    clipId = "ghost",
                    newAssetId = replacement.value,
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
    }

    @Test fun missingAssetFailsLoudly() = runTest {
        val clip = Clip.Video(
            id = ClipId("c-1"),
            timeRange = TimeRange(0.seconds, 1.seconds),
            sourceRange = TimeRange(0.seconds, 1.seconds),
            assetId = AssetId("a"),
        )
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(clip)))),
            ),
        )

        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                ReplaceClipTool.Input(
                    projectId = rig.projectId.value,
                    clipId = "c-1",
                    newAssetId = "missing-asset",
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("missing-asset"), ex.message)
    }
}
