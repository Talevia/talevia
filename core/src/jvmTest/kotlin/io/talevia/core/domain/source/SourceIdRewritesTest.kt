package io.talevia.core.domain.source

import io.talevia.core.AssetId
import io.talevia.core.MessageId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.ClipId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.lockfile.EagerLockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.domain.lockfile.ModalityHashes
import io.talevia.core.platform.GenerationProvenance
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for the three structural id-rewrite extensions in
 * [SourceIdRewrites] — `Source.rewriteNodeId`, `Timeline.rewriteSourceBinding`,
 * and `Lockfile.rewriteSourceBinding`. Cycle 95 audit: these are the
 * mechanics underneath every `rename_source_node` invocation, but their
 * only test coverage came transitively through `SourceNodeActionRenameTest`
 * (one transitive ref) — the boundary cases (touched counts, contentHash
 * cascades, the third `sourceContentHashesByModality` map) weren't pinned
 * directly. A regression dropping the modality-map key rewrite would
 * silently bypass cache lookups after a rename — every `regenerate_stale_clips`
 * after a `rename_source_node` would see the old key in the modality map
 * and treat the entry as orphaned.
 */
class SourceIdRewritesTest {

    private val nodeA = SourceNodeId("a")
    private val nodeA2 = SourceNodeId("a2")
    private val nodeB = SourceNodeId("b")
    private val nodeC = SourceNodeId("c")

    // ── Source.rewriteNodeId ──────────────────────────────────────

    @Test fun renameTargetGetsNewId() {
        val src = Source(
            nodes = listOf(
                SourceNode.create(id = nodeA, kind = "test"),
            ),
        )
        val (rewritten, _) = src.rewriteNodeId(nodeA, nodeA2)
        assertNotNull(rewritten.byId[nodeA2], "renamed id must appear in rewritten source")
        assertNull(rewritten.byId[nodeA], "old id must be gone")
    }

    @Test fun descendantParentRefIsRewritten() {
        val src = Source(
            nodes = listOf(
                SourceNode.create(id = nodeA, kind = "test"),
                SourceNode.create(id = nodeB, kind = "test", parents = listOf(SourceRef(nodeA))),
            ),
        )
        val (rewritten, parentsTouched) = src.rewriteNodeId(nodeA, nodeA2)
        val newB = rewritten.byId[nodeB]!!
        assertEquals(listOf(SourceRef(nodeA2)), newB.parents)
        assertEquals(1, parentsTouched, "B's parents list was rewritten — count should be 1")
    }

    @Test fun parentsTouchedCountExcludesRenameTarget() {
        // Pin the kdoc contract: "does not count the renamed node itself."
        // A renamed node's `id` field changes but its `parents` list does
        // not — so the target's own contribution to the count is 0.
        val src = Source(
            nodes = listOf(
                SourceNode.create(id = nodeA, kind = "test"),
                SourceNode.create(id = nodeB, kind = "test", parents = listOf(SourceRef(nodeA))),
                SourceNode.create(id = nodeC, kind = "test", parents = listOf(SourceRef(nodeA))),
            ),
        )
        val (_, parentsTouched) = src.rewriteNodeId(nodeA, nodeA2)
        assertEquals(2, parentsTouched, "B + C had old parent ref; target A doesn't count")
    }

    @Test fun nodesNotInvolvedAreUntouched() {
        val src = Source(
            nodes = listOf(
                SourceNode.create(id = nodeA, kind = "test"),
                SourceNode.create(id = nodeB, kind = "other-kind", parents = listOf(SourceRef(nodeC))),
            ),
        )
        val (rewritten, parentsTouched) = src.rewriteNodeId(nodeA, nodeA2)
        // B isn't a parent of A and doesn't have A as parent — must be byte-identical.
        val originalB = src.byId[nodeB]!!
        val newB = rewritten.byId[nodeB]!!
        assertEquals(originalB, newB, "untouched node must be `===` equal data-class-wise")
        assertEquals(0, parentsTouched, "no descendants of A — count is 0")
    }

    @Test fun targetContentHashUnchangedWhenBodyAndParentsUnchanged() {
        // The kdoc explicitly commits to this: contentHash is over
        // (kind, body, parents) — NOT id. Renaming alone must not flip the
        // hash, otherwise every rename would invalidate every cache key
        // upstream of the renamed node (a regression we'd see as a flood
        // of stale clips after `rename_source_node`).
        val src = Source(
            nodes = listOf(
                SourceNode.create(id = nodeA, kind = "test"),
            ),
        )
        val originalHash = src.byId[nodeA]!!.contentHash
        val (rewritten, _) = src.rewriteNodeId(nodeA, nodeA2)
        assertEquals(originalHash, rewritten.byId[nodeA2]!!.contentHash)
    }

    @Test fun descendantContentHashFlipsWhenParentRefChanges() {
        // Inverse of the above: a descendant's parents list serializes
        // differently after a rename, so its contentHash MUST flip.
        // This is the *correct* stale-propagation: anything downstream of
        // a renamed node should be considered logically distinct.
        val src = Source(
            nodes = listOf(
                SourceNode.create(id = nodeA, kind = "test"),
                SourceNode.create(id = nodeB, kind = "test", parents = listOf(SourceRef(nodeA))),
            ),
        )
        val originalBHash = src.byId[nodeB]!!.contentHash
        val (rewritten, _) = src.rewriteNodeId(nodeA, nodeA2)
        assertNotEquals(
            originalBHash,
            rewritten.byId[nodeB]!!.contentHash,
            "B's parents list changed → contentHash must change",
        )
    }

    @Test fun touchedNodeRevisionIncrements() {
        val src = Source(
            nodes = listOf(
                SourceNode.create(id = nodeA, kind = "test", revision = 7),
                SourceNode.create(id = nodeB, kind = "test", revision = 3, parents = listOf(SourceRef(nodeA))),
            ),
        )
        val (rewritten, _) = src.rewriteNodeId(nodeA, nodeA2)
        // Pin the +1 rule — every touched node bumps revision exactly once.
        assertEquals(8, rewritten.byId[nodeA2]!!.revision, "renamed target bumps revision")
        assertEquals(4, rewritten.byId[nodeB]!!.revision, "descendant bumps revision once")
    }

    @Test fun sourceRevisionIncrements() {
        val src = Source(
            revision = 42,
            nodes = listOf(SourceNode.create(id = nodeA, kind = "test")),
        )
        val (rewritten, _) = src.rewriteNodeId(nodeA, nodeA2)
        assertEquals(43, rewritten.revision, "Source.revision bumps by 1")
    }

    @Test fun renamingNonExistentIdIsNoOpStructurally() {
        // The function doesn't validate existence — caller is responsible.
        // But behaviorally: renaming `nodeC` (absent) when only A + B
        // exist should leave node identities untouched. Source revision
        // still bumps (the function unconditionally rewrites the data
        // class). Pin both observed behaviours so a refactor that adds
        // a "skip if absent" optimisation either preserves or
        // deliberately changes the contract.
        val src = Source(
            nodes = listOf(
                SourceNode.create(id = nodeA, kind = "test"),
                SourceNode.create(id = nodeB, kind = "test"),
            ),
        )
        val (rewritten, parentsTouched) = src.rewriteNodeId(nodeC, nodeA2)
        assertEquals(0, parentsTouched, "nothing referenced nodeC — no parents touched")
        assertNotNull(rewritten.byId[nodeA])
        assertNotNull(rewritten.byId[nodeB])
        assertNull(rewritten.byId[nodeC])
        assertNull(rewritten.byId[nodeA2])
    }

    // ── Timeline.rewriteSourceBinding ─────────────────────────────

    private val timeRange = TimeRange(start = 0.seconds, duration = 5.seconds)

    private fun videoClipBoundTo(id: String, binding: Set<SourceNodeId>) = Clip.Video(
        id = ClipId(id),
        timeRange = timeRange,
        sourceRange = timeRange,
        assetId = AssetId("asset-$id"),
        sourceBinding = binding,
    )

    @Test fun timelineRewriteFlipsBindingAcrossTracks() {
        // Pin: rewrites apply across multiple tracks + multiple track
        // types, not just the first one found.
        val tl = Timeline(
            tracks = listOf(
                Track.Video(
                    id = TrackId("v1"),
                    clips = listOf(videoClipBoundTo("c1", setOf(nodeA)), videoClipBoundTo("c2", setOf(nodeB))),
                ),
                Track.Subtitle(
                    id = TrackId("s1"),
                    clips = listOf(
                        Clip.Text(id = ClipId("c3"), timeRange = timeRange, text = "x", sourceBinding = setOf(nodeA)),
                    ),
                ),
            ),
        )
        val (rewritten, clipsTouched) = tl.rewriteSourceBinding(nodeA, nodeA2)
        // 2 clips bound to nodeA: c1 (video) and c3 (subtitle). c2 untouched.
        assertEquals(2, clipsTouched)
        val v = rewritten.tracks.filterIsInstance<Track.Video>().first().clips
        assertEquals(setOf(nodeA2), v[0].sourceBinding)
        assertEquals(setOf(nodeB), v[1].sourceBinding, "c2 was not bound to nodeA — unchanged")
        val s = rewritten.tracks.filterIsInstance<Track.Subtitle>().first().clips
        assertEquals(setOf(nodeA2), s[0].sourceBinding)
    }

    @Test fun timelineRewriteCountIsClipsNotTracks() {
        // Pin the kdoc contract: "count of *clips* touched (not tracks)."
        // Two tracks, three clips bound to oldId — count is 3, not 2.
        val tl = Timeline(
            tracks = listOf(
                Track.Video(
                    id = TrackId("v1"),
                    clips = listOf(videoClipBoundTo("c1", setOf(nodeA)), videoClipBoundTo("c2", setOf(nodeA))),
                ),
                Track.Audio(
                    id = TrackId("a1"),
                    clips = listOf(
                        Clip.Audio(
                            id = ClipId("c3"),
                            timeRange = timeRange,
                            sourceRange = timeRange,
                            assetId = AssetId("asset-c3"),
                            sourceBinding = setOf(nodeA),
                        ),
                    ),
                ),
            ),
        )
        val (_, clipsTouched) = tl.rewriteSourceBinding(nodeA, nodeA2)
        assertEquals(3, clipsTouched)
    }

    @Test fun timelineRewriteIsNoOpWhenNoClipBound() {
        val tl = Timeline(
            tracks = listOf(
                Track.Video(id = TrackId("v1"), clips = listOf(videoClipBoundTo("c1", setOf(nodeB)))),
            ),
        )
        val (rewritten, clipsTouched) = tl.rewriteSourceBinding(nodeA, nodeA2)
        assertEquals(0, clipsTouched)
        assertEquals(tl, rewritten, "byte-identical when nothing matched")
    }

    @Test fun timelineRewritePreservesOtherFieldsOnAllClipShapes() {
        // Pin: only `sourceBinding` flips — assetId, transforms, volume,
        // text, and timeRanges must round-trip untouched. A regression
        // dropping into `Clip.Video.copy(sourceBinding = ..., assetId = "")`
        // by mistake would silently zero out asset references.
        val tl = Timeline(
            tracks = listOf(
                Track.Video(
                    id = TrackId("v1"),
                    clips = listOf(videoClipBoundTo("c1", setOf(nodeA, nodeB))),
                ),
                Track.Audio(
                    id = TrackId("a1"),
                    clips = listOf(
                        Clip.Audio(
                            id = ClipId("c2"),
                            timeRange = timeRange,
                            sourceRange = timeRange,
                            assetId = AssetId("audio-asset"),
                            volume = 0.7f,
                            fadeInSeconds = 0.5f,
                            sourceBinding = setOf(nodeA),
                        ),
                    ),
                ),
                Track.Subtitle(
                    id = TrackId("s1"),
                    clips = listOf(
                        Clip.Text(
                            id = ClipId("c3"),
                            timeRange = timeRange,
                            text = "hello",
                            sourceBinding = setOf(nodeA),
                        ),
                    ),
                ),
            ),
        )
        val (rewritten, _) = tl.rewriteSourceBinding(nodeA, nodeA2)
        val v = rewritten.tracks.filterIsInstance<Track.Video>().first().clips.first() as Clip.Video
        assertEquals(AssetId("asset-c1"), v.assetId)
        // Multi-element sourceBinding: nodeA flipped, nodeB preserved.
        assertEquals(setOf(nodeA2, nodeB), v.sourceBinding)
        val a = rewritten.tracks.filterIsInstance<Track.Audio>().first().clips.first() as Clip.Audio
        assertEquals(AssetId("audio-asset"), a.assetId)
        assertEquals(0.7f, a.volume)
        assertEquals(0.5f, a.fadeInSeconds)
        val t = rewritten.tracks.filterIsInstance<Track.Subtitle>().first().clips.first() as Clip.Text
        assertEquals("hello", t.text)
    }

    // ── Lockfile.rewriteSourceBinding ─────────────────────────────

    private fun entry(
        hash: String,
        sourceBinding: Set<SourceNodeId> = emptySet(),
        sourceContentHashes: Map<SourceNodeId, String> = emptyMap(),
        modality: Map<SourceNodeId, ModalityHashes> = emptyMap(),
    ) = LockfileEntry(
        inputHash = hash,
        toolId = "test_tool",
        assetId = AssetId("asset-$hash"),
        provenance = GenerationProvenance(
            providerId = "test",
            modelId = "m1",
            modelVersion = "v1",
            seed = 0,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = 0L,
        ),
        sourceBinding = sourceBinding,
        sourceContentHashes = sourceContentHashes,
        sourceContentHashesByModality = modality,
        originatingMessageId = MessageId("m"),
    )

    @Test fun lockfileRewriteFlipsAllThreeFields() {
        // Pin: all three id-bearing fields rewrite — sourceBinding,
        // sourceContentHashes, AND sourceContentHashesByModality. The
        // last one is critical: omitting it would leave the modality
        // map keyed by a stale id, and `staleClipsFromLockfile` would
        // miss the rename entirely.
        val before = EagerLockfile(
            entries = listOf(
                entry(
                    hash = "h1",
                    sourceBinding = setOf(nodeA),
                    sourceContentHashes = mapOf(nodeA to "hashA"),
                    modality = mapOf(nodeA to ModalityHashes(visual = "v1", audio = "a1")),
                ),
            ),
        )
        val (rewritten, touched) = before.rewriteSourceBinding(nodeA, nodeA2)
        assertEquals(1, touched)
        val e = rewritten.entries.single()
        assertEquals(setOf(nodeA2), e.sourceBinding)
        assertEquals(mapOf(nodeA2 to "hashA"), e.sourceContentHashes)
        assertEquals(
            mapOf(nodeA2 to ModalityHashes(visual = "v1", audio = "a1")),
            e.sourceContentHashesByModality,
            "modality map key must rewrite — silent breakage flag",
        )
    }

    @Test fun lockfileEntryCountedOnceWhenInBindingAndHashesAndModality() {
        // Pin the kdoc contract: "an entry counts once even if both
        // [all three] fields contained the id." Counting 3 instead of 1
        // would inflate audit/log output and confuse cache-stats.
        val before = EagerLockfile(
            entries = listOf(
                entry(
                    hash = "h1",
                    sourceBinding = setOf(nodeA),
                    sourceContentHashes = mapOf(nodeA to "hashA"),
                    modality = mapOf(nodeA to ModalityHashes(visual = "v1", audio = "a1")),
                ),
            ),
        )
        val (_, touched) = before.rewriteSourceBinding(nodeA, nodeA2)
        assertEquals(1, touched, "one entry — count once even when all three fields hit")
    }

    @Test fun lockfileEntryUntouchedWhenIdAbsent() {
        val before = EagerLockfile(
            entries = listOf(
                entry(
                    hash = "h1",
                    sourceBinding = setOf(nodeB),
                    sourceContentHashes = mapOf(nodeB to "hashB"),
                ),
                entry(hash = "h2", sourceBinding = setOf(nodeC)),
            ),
        )
        val (rewritten, touched) = before.rewriteSourceBinding(nodeA, nodeA2)
        assertEquals(0, touched)
        assertEquals(before.entries, rewritten.entries, "no field had nodeA — entries untouched")
    }

    @Test fun lockfileRewriteAppliesAcrossMultipleEntries() {
        val before = EagerLockfile(
            entries = listOf(
                entry(hash = "h1", sourceBinding = setOf(nodeA)),
                entry(hash = "h2", sourceContentHashes = mapOf(nodeA to "hashA")),
                entry(hash = "h3", sourceBinding = setOf(nodeB)),
            ),
        )
        val (rewritten, touched) = before.rewriteSourceBinding(nodeA, nodeA2)
        assertEquals(2, touched, "h1 and h2 both reference nodeA; h3 does not")
        assertEquals(setOf(nodeA2), rewritten.entries[0].sourceBinding)
        assertEquals(mapOf(nodeA2 to "hashA"), rewritten.entries[1].sourceContentHashes)
        assertEquals(setOf(nodeB), rewritten.entries[2].sourceBinding, "h3 untouched")
    }

    @Test fun lockfileRewritePreservesNonIdEntryFields() {
        // Pin: hash + assetId + provenance + pinned must round-trip.
        // A regression copying only the id-bearing fields would drop
        // pinned state and provenance, silently breaking GC immunity
        // (pinned hero shots) and audit trails.
        val before = EagerLockfile(
            entries = listOf(
                entry(hash = "h1", sourceBinding = setOf(nodeA)).copy(pinned = true, costCents = 42),
            ),
        )
        val (rewritten, _) = before.rewriteSourceBinding(nodeA, nodeA2)
        val e = rewritten.entries.single()
        assertEquals("h1", e.inputHash)
        assertEquals(AssetId("asset-h1"), e.assetId)
        assertTrue(e.pinned, "pinned flag must round-trip")
        assertEquals(42, e.costCents, "costCents must round-trip")
    }
}
