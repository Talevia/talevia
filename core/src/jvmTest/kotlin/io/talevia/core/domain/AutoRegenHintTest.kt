package io.talevia.core.domain

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.addNode
import io.talevia.core.domain.source.deepContentHashOf
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.domain.source.replaceNode
import io.talevia.core.platform.GenerationProvenance
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * Semantic-surface tests for [autoRegenHint]. Covers the three arms:
 * - No stale clips → null hint
 * - Stale clips exist → non-null hint with accurate count + suggested tool
 * - Project with no lockfile entries → null hint (nothing to compare against)
 */
class AutoRegenHintTest {

    private fun genericNode(id: String, label: String, parents: List<String> = emptyList()): SourceNode =
        SourceNode.create(
            id = SourceNodeId(id),
            kind = "test.generic",
            body = buildJsonObject { put("label", label) },
            parents = parents.map { SourceRef(SourceNodeId(it)) },
        )

    @Test fun returnsNullHintWhenProjectIsEmpty() {
        val project = Project(id = ProjectId("p"), timeline = Timeline())
        assertNull(project.autoRegenHint())
    }

    @Test fun returnsNullHintWhenLockfileEmpty() {
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = listOf(Track.Video(TrackId("v"), emptyList()))),
        )
        assertNull(project.autoRegenHint())
    }

    @Test fun returnsNonNullHintWithCountAfterSourceEdit() = runTest {
        val store = ProjectStoreTestKit.create()
        val pid = ProjectId("p-hint")
        val asset = AssetId("img-1")

        val videoClip = Clip.Video(
            id = ClipId("c-1"),
            timeRange = TimeRange(0.seconds, 2.seconds),
            sourceRange = TimeRange(0.seconds, 2.seconds),
            assetId = asset,
            sourceBinding = setOf(SourceNodeId("char")),
        )
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(videoClip)))),
                assets = listOf(
                    MediaAsset(id = asset, source = MediaSource.File("/tmp/a.png"), metadata = MediaMetadata(duration = 0.seconds)),
                ),
            ),
        )
        store.mutateSource(pid) { src ->
            src.addNode(genericNode("char", "v1"))
        }

        val seedHash = store.get(pid)!!.source.deepContentHashOf(SourceNodeId("char"))
        store.mutate(pid) { project ->
            project.copy(
                lockfile = project.lockfile.append(
                    LockfileEntry(
                        inputHash = "h1",
                        toolId = "generate_image",
                        assetId = asset,
                        provenance = GenerationProvenance(
                            providerId = "fake", modelId = "m", modelVersion = null,
                            seed = 0, parameters = JsonObject(emptyMap()), createdAtEpochMs = 0,
                        ),
                        sourceBinding = setOf(SourceNodeId("char")),
                        sourceContentHashes = mapOf(SourceNodeId("char") to seedHash),
                    ),
                ),
            )
        }

        // Before any edit: no stale.
        assertNull(store.get(pid)!!.autoRegenHint())

        // Edit node body → deep hash diverges from snapshot.
        store.mutateSource(pid) { src ->
            src.replaceNode(SourceNodeId("char")) { genericNode("char", "v2") }
        }

        val hint = store.get(pid)!!.autoRegenHint()
        assertNotNull(hint, "source edit must produce a non-null auto-regen hint")
        assertEquals(1, hint.staleClipCount)
        assertEquals("regenerate_stale_clips", hint.suggestedTool)
    }

    @Test fun hintCountMatchesStaleDetectorCount() = runTest {
        // Two clips bound to different nodes; edit one node — only that
        // clip is stale — hint count should be 1, not 2.
        val store = ProjectStoreTestKit.create()
        val pid = ProjectId("p-multi")
        val a = AssetId("img-a")
        val b = AssetId("img-b")
        val clipA = Clip.Video(
            ClipId("c-a"), TimeRange(0.seconds, 2.seconds), TimeRange(0.seconds, 2.seconds),
            assetId = a, sourceBinding = setOf(SourceNodeId("node-a")),
        )
        val clipB = Clip.Video(
            ClipId("c-b"), TimeRange(2.seconds, 4.seconds), TimeRange(0.seconds, 2.seconds),
            assetId = b, sourceBinding = setOf(SourceNodeId("node-b")),
        )
        store.upsert(
            "multi",
            Project(
                id = pid,
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(clipA, clipB)))),
                assets = listOf(
                    MediaAsset(a, MediaSource.File("/a.png"), MediaMetadata(0.seconds)),
                    MediaAsset(b, MediaSource.File("/b.png"), MediaMetadata(0.seconds)),
                ),
            ),
        )
        store.mutateSource(pid) { src ->
            src.addNode(genericNode("node-a", "v1"))
                .addNode(genericNode("node-b", "v1"))
        }
        val hashA = store.get(pid)!!.source.deepContentHashOf(SourceNodeId("node-a"))
        val hashB = store.get(pid)!!.source.deepContentHashOf(SourceNodeId("node-b"))
        store.mutate(pid) { project ->
            project.copy(
                lockfile = project.lockfile
                    .append(
                        LockfileEntry(
                            inputHash = "ha", toolId = "generate_image", assetId = a,
                            provenance = GenerationProvenance("fake", "m", null, 0, JsonObject(emptyMap()), 0),
                            sourceBinding = setOf(SourceNodeId("node-a")),
                            sourceContentHashes = mapOf(SourceNodeId("node-a") to hashA),
                        ),
                    )
                    .append(
                        LockfileEntry(
                            inputHash = "hb", toolId = "generate_image", assetId = b,
                            provenance = GenerationProvenance("fake", "m", null, 0, JsonObject(emptyMap()), 0),
                            sourceBinding = setOf(SourceNodeId("node-b")),
                            sourceContentHashes = mapOf(SourceNodeId("node-b") to hashB),
                        ),
                    ),
            )
        }
        // Edit only node-a.
        store.mutateSource(pid) { src ->
            src.replaceNode(SourceNodeId("node-a")) { genericNode("node-a", "v2") }
        }
        val hint = store.get(pid)!!.autoRegenHint()
        assertNotNull(hint)
        assertEquals(1, hint.staleClipCount, "only clipA should be stale; hint must not double-count")
    }
}
