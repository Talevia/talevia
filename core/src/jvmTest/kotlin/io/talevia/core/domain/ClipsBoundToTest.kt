package io.talevia.core.domain

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.addNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [Project.clipsBoundTo] —
 * `core/src/commonMain/kotlin/io/talevia/core/domain/
 * ProjectStalenessCommon.kt:152`. Cycle 300 audit: 0 prior
 * direct test files (verified via cycle 289-banked
 * duplicate-check idiom).
 *
 * Same audit-pattern fallback as cycles 207-299. Sister of
 * cycle 299's [FreshClipsTest] — `clipsBoundTo` is the
 * VISION §5.1 forward "改一个 source 节点 → 下游哪些 clip
 * 受影响" answer; `staleClips`/`freshClips` are the backward
 * "we just drifted, what's stale?" pair.
 *
 * Drift surface protected:
 *   - Drift in `directlyBound` semantic (true iff
 *     sourceNodeId is in clip.sourceBinding directly, NOT
 *     just via DAG descendants) silently flips the impact
 *     preview's "is this a direct dependency?" signal.
 *   - Drift in `boundVia` (clip.sourceBinding ∩ closure) →
 *     UI surfaces wrong descendant chain in the preview.
 *   - Drift to consider only Track.Video silently misses
 *     audio / text clips bound to the changed source.
 *   - Drift in unknown-node short-circuit silently throws
 *     instead of returning emptyList (the "guard absent" path).
 *   - Drift in assetId mapping for Text clips (null) → UI
 *     would treat Text clips as having an asset.
 */
class ClipsBoundToTest {

    private fun videoClip(id: String, vararg nodeIds: String, start: Long = 0): Clip.Video =
        Clip.Video(
            id = ClipId(id),
            timeRange = TimeRange(start.seconds, 5.seconds),
            sourceRange = TimeRange(0.seconds, 5.seconds),
            assetId = AssetId("asset-$id"),
            sourceBinding = nodeIds.map { SourceNodeId(it) }.toSet(),
        )

    private fun audioClip(id: String, vararg nodeIds: String, start: Long = 0): Clip.Audio =
        Clip.Audio(
            id = ClipId(id),
            timeRange = TimeRange(start.seconds, 5.seconds),
            sourceRange = TimeRange(0.seconds, 5.seconds),
            assetId = AssetId("asset-$id"),
            sourceBinding = nodeIds.map { SourceNodeId(it) }.toSet(),
        )

    private fun textClip(id: String, vararg nodeIds: String, start: Long = 0): Clip.Text =
        Clip.Text(
            id = ClipId(id),
            timeRange = TimeRange(start.seconds, 2.seconds),
            text = "hello",
            style = TextStyle(),
            sourceBinding = nodeIds.map { SourceNodeId(it) }.toSet(),
        )

    private fun node(id: String, vararg parents: String): SourceNode = SourceNode.create(
        id = SourceNodeId(id),
        kind = "test.node",
        parents = parents.map { SourceRef(SourceNodeId(it)) },
    )

    private fun project(timeline: Timeline, source: Source = Source.EMPTY): Project = Project(
        id = ProjectId("p"),
        timeline = timeline,
        source = source,
    )

    // ── Empty / no-op cases ─────────────────────────────────

    @Test fun unknownSourceNodeReturnsEmptyList() {
        // Marquee guard pin: sourceNodeId not in
        // source.byId → emptyList. Drift to throw or include
        // descendants of a missing root would surface here.
        val proj = project(Timeline())
        assertEquals(
            emptyList(),
            proj.clipsBoundTo(SourceNodeId("does-not-exist")),
            "unknown sourceNodeId MUST return emptyList (NOT throw)",
        )
    }

    @Test fun emptyTimelineReturnsEmptyList() {
        // Edge: node exists but no clips → emptyList.
        val proj = project(
            timeline = Timeline(),
            source = Source.EMPTY.addNode(node("n1")),
        )
        assertEquals(emptyList(), proj.clipsBoundTo(SourceNodeId("n1")))
    }

    @Test fun noClipBindsTheNodeReturnsEmptyList() {
        // Pin: node exists in source but no clip's
        // sourceBinding intersects its closure → emptyList.
        val proj = project(
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        clips = listOf(videoClip("c1", "n2")),
                    ),
                ),
            ),
            source = Source.EMPTY.addNode(node("n1")).addNode(node("n2")),
        )
        // n1 has no descendants reaching c1.
        assertEquals(emptyList(), proj.clipsBoundTo(SourceNodeId("n1")))
    }

    @Test fun unboundClipsAreSkipped() {
        // Pin: clips with empty sourceBinding never appear
        // in clipsBoundTo. Drift to include them would
        // surface unbound clips as false dependents.
        val proj = project(
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        clips = listOf(
                            videoClip("c-bound", "n1"),
                            videoClip("c-unbound" /* empty binding */),
                        ),
                    ),
                ),
            ),
            source = Source.EMPTY.addNode(node("n1")),
        )
        val report = proj.clipsBoundTo(SourceNodeId("n1"))
        assertEquals(1, report.size)
        assertEquals(ClipId("c-bound"), report.single().clipId)
    }

    // ── Direct vs transitive binding ───────────────────────

    @Test fun directlyBoundIsTrueWhenSourceIdInBindingDirectly() {
        // Marquee directlyBound pin: clip's binding contains
        // the queried node id verbatim → directlyBound=true.
        val proj = project(
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        clips = listOf(videoClip("c-direct", "n1")),
                    ),
                ),
            ),
            source = Source.EMPTY.addNode(node("n1")),
        )
        val report = proj.clipsBoundTo(SourceNodeId("n1")).single()
        assertTrue(
            report.directlyBound,
            "clip binding contains 'n1' verbatim → directlyBound MUST be true",
        )
        assertEquals(setOf(SourceNodeId("n1")), report.boundVia)
    }

    @Test fun directlyBoundIsFalseWhenOnlyTransitivelyBound() {
        // Marquee transitive pin: clip binds n2; query n1
        // (parent of n2). n2 IS in n1's downstream closure,
        // so the clip surfaces — but directlyBound=false
        // (n1 is NOT in clip.sourceBinding directly).
        // Drift to mark transitive as direct silently
        // changes the UI's "you'll directly affect this
        // clip" signal.
        val proj = project(
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        clips = listOf(videoClip("c-via-n2", "n2")),
                    ),
                ),
            ),
            source = Source.EMPTY
                .addNode(node("n1"))
                .addNode(node("n2", "n1")), // n2 derives from n1
        )
        val report = proj.clipsBoundTo(SourceNodeId("n1")).single()
        assertEquals(
            false,
            report.directlyBound,
            "clip binding contains 'n2' (descendant), NOT 'n1' verbatim → directlyBound MUST be false",
        )
        // boundVia is the intersection of clip.sourceBinding
        // with the closure — for c-via-n2, that's {n2} (the
        // transitive descendant the clip actually binds).
        assertEquals(setOf(SourceNodeId("n2")), report.boundVia)
    }

    @Test fun multiBindingClipDirectAndTransitiveCorrectlyClassified() {
        // Pin: clip binds {n1, n3}. Query n1 (directly
        // bound, descendants {n1, n2}). Both bindings sit in
        // the closure — boundVia covers {n1}; directlyBound
        // = true because n1 IS in clip.sourceBinding.
        val proj = project(
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        clips = listOf(videoClip("c-multi", "n1", "n3")),
                    ),
                ),
            ),
            source = Source.EMPTY
                .addNode(node("n1"))
                .addNode(node("n2", "n1"))
                .addNode(node("n3")), // unrelated to n1
        )
        val report = proj.clipsBoundTo(SourceNodeId("n1")).single()
        assertTrue(report.directlyBound, "n1 IS in clip's binding → direct")
        assertEquals(
            setOf(SourceNodeId("n1")),
            report.boundVia,
            "boundVia = clip.binding ∩ closure(n1) = {n1, n3} ∩ {n1, n2} = {n1}",
        )
    }

    // ── boundVia spans transitive descendants ──────────────

    @Test fun boundViaIncludesAllDescendantsTheClipBindsTo() {
        // Pin: deep DAG. n1 → n2 → n3. Clip binds {n2, n3}.
        // Query n1 → closure {n1, n2, n3}. boundVia =
        // clip.binding ∩ closure = {n2, n3}.
        val proj = project(
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        clips = listOf(videoClip("c-deep", "n2", "n3")),
                    ),
                ),
            ),
            source = Source.EMPTY
                .addNode(node("n1"))
                .addNode(node("n2", "n1"))
                .addNode(node("n3", "n2")),
        )
        val report = proj.clipsBoundTo(SourceNodeId("n1")).single()
        assertEquals(
            setOf(SourceNodeId("n2"), SourceNodeId("n3")),
            report.boundVia,
            "boundVia MUST include ALL clip-binding nodes that sit in the closure",
        )
        assertEquals(
            false,
            report.directlyBound,
            "n1 NOT in clip.sourceBinding {n2, n3} → directlyBound=false",
        )
    }

    // ── assetId mapping per Clip subtype ───────────────────

    @Test fun videoClipAssetIdSurfacedInReport() {
        val proj = project(
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        clips = listOf(videoClip("c-v", "n1")),
                    ),
                ),
            ),
            source = Source.EMPTY.addNode(node("n1")),
        )
        val report = proj.clipsBoundTo(SourceNodeId("n1")).single()
        assertEquals(
            AssetId("asset-c-v"),
            report.assetId,
            "video clip MUST surface its assetId in the report",
        )
    }

    @Test fun audioClipAssetIdSurfacedInReport() {
        val proj = project(
            timeline = Timeline(
                tracks = listOf(
                    Track.Audio(
                        TrackId("a1"),
                        clips = listOf(audioClip("c-a", "n1")),
                    ),
                ),
            ),
            source = Source.EMPTY.addNode(node("n1")),
        )
        val report = proj.clipsBoundTo(SourceNodeId("n1")).single()
        assertEquals(
            AssetId("asset-c-a"),
            report.assetId,
            "audio clip MUST surface its assetId in the report",
        )
    }

    @Test fun textClipAssetIdIsNullInReport() {
        // Marquee Text-clip pin: per source line 165,
        // Text clips have no assetId → report.assetId is
        // null. Drift to fabricate an id would surface here.
        val proj = project(
            timeline = Timeline(
                tracks = listOf(
                    Track.Subtitle(
                        TrackId("s1"),
                        clips = listOf(textClip("c-t", "n1")),
                    ),
                ),
            ),
            source = Source.EMPTY.addNode(node("n1")),
        )
        val report = proj.clipsBoundTo(SourceNodeId("n1")).single()
        assertNull(
            report.assetId,
            "Text clip MUST surface assetId=null in the report",
        )
    }

    // ── Multi-track coverage ───────────────────────────────

    @Test fun reportsClipsAcrossVideoAudioAndSubtitleTracks() {
        // Pin: walks ALL tracks (Video + Audio + Subtitle +
        // Effect). Drift to filter by Track.Video would
        // silently miss audio/text dependents.
        val proj = project(
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        clips = listOf(videoClip("c-v", "n1")),
                    ),
                    Track.Audio(
                        TrackId("a1"),
                        clips = listOf(audioClip("c-a", "n1")),
                    ),
                    Track.Subtitle(
                        TrackId("s1"),
                        clips = listOf(textClip("c-t", "n1")),
                    ),
                ),
            ),
            source = Source.EMPTY.addNode(node("n1")),
        )
        val report = proj.clipsBoundTo(SourceNodeId("n1"))
        assertEquals(
            3,
            report.size,
            "MUST include clips from Video + Audio + Subtitle tracks",
        )
        val clipIds = report.map { it.clipId }.toSet()
        assertEquals(setOf(ClipId("c-v"), ClipId("c-a"), ClipId("c-t")), clipIds)
    }

    @Test fun trackIdEchoedPerReport() {
        // Pin: every report row carries the originating
        // track's id. Drift to drop / mis-route trackId
        // would break UI cross-references.
        val proj = project(
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v-named"),
                        clips = listOf(videoClip("c-v", "n1")),
                    ),
                    Track.Audio(
                        TrackId("a-named"),
                        clips = listOf(audioClip("c-a", "n1")),
                    ),
                ),
            ),
            source = Source.EMPTY.addNode(node("n1")),
        )
        val report = proj.clipsBoundTo(SourceNodeId("n1"))
        assertEquals(2, report.size)
        val trackByClip = report.associate { it.clipId to it.trackId }
        assertEquals(TrackId("v-named"), trackByClip[ClipId("c-v")])
        assertEquals(TrackId("a-named"), trackByClip[ClipId("c-a")])
    }

    // ── Determinism + idempotency ──────────────────────────

    @Test fun reportPreservesInsertionOrderAcrossTracks() {
        // Pin: order matches declaration order. Drift to
        // unordered Set would surface here.
        val proj = project(
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        clips = listOf(
                            videoClip("c1", "n1", start = 0),
                            videoClip("c2", "n1", start = 5),
                            videoClip("c3", "n1", start = 10),
                        ),
                    ),
                ),
            ),
            source = Source.EMPTY.addNode(node("n1")),
        )
        val report = proj.clipsBoundTo(SourceNodeId("n1"))
        assertEquals(
            listOf(ClipId("c1"), ClipId("c2"), ClipId("c3")),
            report.map { it.clipId },
            "report order MUST match declaration order",
        )
    }

    @Test fun deterministicAcrossInvocations() {
        val proj = project(
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(
                        TrackId("v1"),
                        clips = listOf(
                            videoClip("c1", "n1"),
                            videoClip("c2", "n2", start = 5),
                        ),
                    ),
                ),
            ),
            source = Source.EMPTY
                .addNode(node("n1"))
                .addNode(node("n2", "n1")),
        )
        val a = proj.clipsBoundTo(SourceNodeId("n1"))
        val b = proj.clipsBoundTo(SourceNodeId("n1"))
        assertEquals(a, b)
    }
}
