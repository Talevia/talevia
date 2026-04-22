package io.talevia.core.domain

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.domain.source.Source
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Covers VISION §5.1 transitive-source-hash-propagation. The cycle's
 * load-bearing invariant: changing an **ancestor** node body (not just the
 * directly-bound one) surfaces as a stale clip in
 * `Project.staleClipsFromLockfile()`. Pre-cycle-14 the detector only looked
 * at the directly-bound `SourceNode.contentHash`, which is a function of
 * `(kind, body, parents-by-id)` — ancestor body changes were silently
 * swallowed.
 */
class TransitiveSourceHashTest {

    private fun genericNode(id: String, body: JsonObject, parents: List<String> = emptyList()): SourceNode =
        SourceNode.create(
            id = SourceNodeId(id),
            kind = "test.generic",
            body = body,
            parents = parents.map { SourceRef(SourceNodeId(it)) },
        )

    private fun bodyWith(label: String): JsonObject = buildJsonObject { put("label", label) }

    @Test fun grandparentEditChangesGrandchildDeepHash() {
        val source = Source(
            nodes = listOf(
                genericNode("grandparent", bodyWith("v1")),
                genericNode("parent", bodyWith("p1"), parents = listOf("grandparent")),
                genericNode("grandchild", bodyWith("g1"), parents = listOf("parent")),
            ),
        )
        val initialDeep = source.deepContentHashOf(SourceNodeId("grandchild"))

        // Edit only the grandparent body.
        val mutated = source.replaceNode(SourceNodeId("grandparent")) {
            genericNode("grandparent", bodyWith("v2"))
        }
        val mutatedDeep = mutated.deepContentHashOf(SourceNodeId("grandchild"))

        assertNotEquals(
            initialDeep,
            mutatedDeep,
            "grandparent edit must propagate into grandchild's deep hash (VISION §5.1)",
        )
    }

    @Test fun unchangedDagHasStableDeepHash() {
        val source = Source(
            nodes = listOf(
                genericNode("a", bodyWith("a1")),
                genericNode("b", bodyWith("b1"), parents = listOf("a")),
            ),
        )
        assertEquals(
            source.deepContentHashOf(SourceNodeId("b")),
            source.deepContentHashOf(SourceNodeId("b")),
            "deep hash must be deterministic across invocations",
        )
    }

    @Test fun danglingParentFoldsAsSentinelRatherThanThrowing() {
        // Source references a parent that isn't in byId. deepContentHashOf
        // must fold a stable "missing:<id>" sentinel instead of crashing —
        // ValidateProjectTool is the lane that surfaces dangling refs.
        val source = Source(
            nodes = listOf(
                genericNode("only-child", bodyWith("c1"), parents = listOf("ghost-parent")),
            ),
        )
        val hash = source.deepContentHashOf(SourceNodeId("only-child"))
        assertTrue(hash.isNotBlank(), "dangling-parent deep hash must be deterministic, got: $hash")
    }

    @Test fun siblingContentChangePropagatesThroughSharedParent() {
        // Two siblings share a parent. Editing one sibling's body must NOT
        // affect the other sibling's deep hash (deep hash walks ancestors,
        // not siblings). This is a negative assertion that guards against a
        // naive "include whole Source in hash" implementation.
        val source = Source(
            nodes = listOf(
                genericNode("shared-parent", bodyWith("sp")),
                genericNode("sibling-a", bodyWith("a1"), parents = listOf("shared-parent")),
                genericNode("sibling-b", bodyWith("b1"), parents = listOf("shared-parent")),
            ),
        )
        val aBefore = source.deepContentHashOf(SourceNodeId("sibling-a"))

        val mutated = source.replaceNode(SourceNodeId("sibling-b")) {
            genericNode("sibling-b", bodyWith("b2"), parents = listOf("shared-parent"))
        }
        val aAfter = mutated.deepContentHashOf(SourceNodeId("sibling-a"))

        assertEquals(aBefore, aAfter, "editing a sibling must not affect another sibling's deep hash")
    }

    @Test fun staleClipsFromLockfileFlagsClipWhenGrandparentEdited() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val pid = ProjectId("p")
        val asset = AssetId("img-1")

        val videoClip = Clip.Video(
            id = ClipId("c-1"),
            timeRange = TimeRange(0.seconds, 2.seconds),
            sourceRange = TimeRange(0.seconds, 2.seconds),
            assetId = asset,
            sourceBinding = setOf(SourceNodeId("grandchild")),
        )

        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(videoClip)))),
                assets = listOf(
                    MediaAsset(id = asset, source = MediaSource.File("/tmp/img.png"), metadata = MediaMetadata(duration = 0.seconds)),
                ),
            ),
        )
        store.mutateSource(pid) { src ->
            src.addNode(genericNode("grandparent", bodyWith("v1")))
                .addNode(genericNode("parent", bodyWith("p1"), parents = listOf("grandparent")))
                .addNode(genericNode("grandchild", bodyWith("g1"), parents = listOf("parent")))
        }

        // Snapshot the deep hash at the time of generation.
        val snapshotHash = store.get(pid)!!.source.deepContentHashOf(SourceNodeId("grandchild"))
        store.mutate(pid) { project ->
            project.copy(
                lockfile = project.lockfile.append(
                    LockfileEntry(
                        inputHash = "h1",
                        toolId = "generate_image",
                        assetId = asset,
                        provenance = GenerationProvenance(
                            providerId = "fake",
                            modelId = "m",
                            modelVersion = null,
                            seed = 0,
                            parameters = JsonObject(emptyMap()),
                            createdAtEpochMs = 0,
                        ),
                        sourceBinding = setOf(SourceNodeId("grandchild")),
                        sourceContentHashes = mapOf(SourceNodeId("grandchild") to snapshotHash),
                    ),
                ),
            )
        }

        // Sanity: before any edit, nothing is stale.
        assertEquals(0, store.get(pid)!!.staleClipsFromLockfile().size)

        // Edit only the GRANDPARENT body. Grandchild's own (kind, body) and
        // direct parents list are unchanged, so its SHALLOW contentHash
        // stays the same. Pre-cycle-14, this test would have said "0 stale".
        store.mutateSource(pid) { src ->
            src.replaceNode(SourceNodeId("grandparent")) {
                genericNode("grandparent", bodyWith("v2"))
            }
        }

        val stale = store.get(pid)!!.staleClipsFromLockfile()
        assertEquals(1, stale.size, "grandparent edit must flag the grandchild-bound clip stale")
        assertEquals(ClipId("c-1"), stale.single().clipId)
        assertEquals(setOf(SourceNodeId("grandchild")), stale.single().changedSourceIds)
    }
}
