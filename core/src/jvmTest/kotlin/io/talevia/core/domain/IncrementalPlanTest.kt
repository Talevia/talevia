package io.talevia.core.domain

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.domain.lockfile.EagerLockfile
import io.talevia.core.domain.lockfile.Lockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.domain.render.ClipRenderCache
import io.talevia.core.domain.render.ClipRenderCacheEntry
import io.talevia.core.domain.render.clipMezzanineFingerprint
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.ConsistencyKinds
import io.talevia.core.domain.source.deepContentHashOf
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.platform.OutputSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * M5 §3.2 criterion #1 capstone: [Project.incrementalPlan] folds M1's
 * source-binding closure ([Project.clipsBoundTo]) + M2's lockfile
 * staleness ([Project.staleClipsFromLockfile]) + M5 #2's render-cache
 * staleness ([Project.renderStaleClips]) into a single 3-bucket "what
 * does the next export need to do?" report. Each bound clip reachable
 * from the changed source set lands in exactly one bucket.
 *
 * Cases:
 *   - empty changedSources → empty plan (no edits to evaluate).
 *   - changedSources targeting unreached source → empty plan.
 *   - lockfile-stale clip → reAigc bucket (asset bytes invalid, must
 *     regenerate before re-rendering).
 *   - render-stale-but-not-aigc-stale clip → onlyRender bucket.
 *     Constructed via legacy lockfile entry with empty
 *     `sourceContentHashes` (lockfile check skips, fingerprint check
 *     fires).
 *   - bound clip with matching cache entry → unchanged bucket.
 *   - bucket disjointness — invariant pinned across all positive
 *     cases.
 */
class IncrementalPlanTest {

    private val output = OutputSpec(
        targetPath = "/tmp/out.mp4",
        resolution = Resolution(1920, 1080),
        frameRate = 30,
        videoCodec = "h264",
        audioCodec = "aac",
    )
    private val engineId = "test-engine"

    private fun characterRefNode(id: String, body: CharacterRefBody): SourceNode = SourceNode.create(
        id = SourceNodeId(id),
        kind = ConsistencyKinds.CHARACTER_REF,
        body = JsonConfig.default.encodeToJsonElement(CharacterRefBody.serializer(), body),
    )

    private fun videoClipBoundTo(id: String, vararg nodeIds: String, start: Long = 0): Clip.Video = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(start.seconds, 5.seconds),
        sourceRange = TimeRange(0.seconds, 5.seconds),
        assetId = AssetId("asset-$id"),
        sourceBinding = nodeIds.map { SourceNodeId(it) }.toSet(),
    )

    /** Lockfile entry with whole-body sourceContentHashes (snapshots node deepHash). */
    private fun aigcEntry(clipAssetId: AssetId, nodeId: String, source: Source): LockfileEntry = LockfileEntry(
        inputHash = "h-${clipAssetId.value}",
        toolId = "generate_image",
        assetId = clipAssetId,
        provenance = GenerationProvenance(
            providerId = "fake",
            modelId = "m",
            modelVersion = null,
            seed = 0,
            parameters = kotlinx.serialization.json.JsonObject(emptyMap()),
            createdAtEpochMs = 0,
        ),
        sourceBinding = setOf(SourceNodeId(nodeId)),
        sourceContentHashes = mapOf(
            SourceNodeId(nodeId) to source.deepContentHashOf(SourceNodeId(nodeId)),
        ),
    )

    /**
     * "Legacy" lockfile entry with empty sourceContentHashes — modality
     * map also empty. `staleClipsFromLockfile` skips entries with no
     * snapshot to compare against, so the clip is never AIGC-stale via
     * this lane. Render-stale signal still fires through the per-clip
     * fingerprint (which always includes bound source deep hashes).
     */
    private fun legacyEntryNoSnapshot(clipAssetId: AssetId, nodeId: String): LockfileEntry = LockfileEntry(
        inputHash = "h-legacy-${clipAssetId.value}",
        toolId = "generate_image",
        assetId = clipAssetId,
        provenance = GenerationProvenance(
            providerId = "fake",
            modelId = "m",
            modelVersion = null,
            seed = 0,
            parameters = kotlinx.serialization.json.JsonObject(emptyMap()),
            createdAtEpochMs = 0,
        ),
        sourceBinding = setOf(SourceNodeId(nodeId)),
        sourceContentHashes = emptyMap(),
    )

    /** Compute current fingerprint for a clip given source state — lets the test seed the cache. */
    private fun fingerprintFor(project: Project, clip: Clip.Video): String {
        val cache = mutableMapOf<SourceNodeId, String>()
        val boundHashes = clip.sourceBinding
            .filter { it in project.source.byId }
            .associateWith { project.source.deepContentHashOf(it, cache) }
        return clipMezzanineFingerprint(
            clip = clip,
            fades = null,
            boundSourceDeepHashes = boundHashes,
            output = output,
            engineId = engineId,
        )
    }

    private fun mei(visual: String = "tall, dark hair"): CharacterRefBody =
        CharacterRefBody(name = "Mei", visualDescription = visual)

    @Test fun emptyChangedSourcesProducesEmptyPlan() {
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(Track.Video(TrackId("v"), listOf(videoClipBoundTo("c1", "mei")))),
                duration = 5.seconds,
            ),
            source = Source(nodes = listOf(characterRefNode("mei", mei()))),
        )
        val plan = project.incrementalPlan(emptySet(), output, engineId)
        assertTrue(plan.isEmpty)
        assertEquals(0, plan.workCount)
        assertTrue(plan.reAigc.isEmpty() && plan.onlyRender.isEmpty() && plan.unchanged.isEmpty())
    }

    @Test fun unrelatedSourceEditProducesEmptyPlan() {
        // changedSources contains a node id, but no clips bind it.
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(Track.Video(TrackId("v"), listOf(videoClipBoundTo("c1", "mei")))),
                duration = 5.seconds,
            ),
            source = Source(
                nodes = listOf(
                    characterRefNode("mei", mei()),
                    characterRefNode("noir", mei(visual = "noir style")),
                ),
            ),
        )
        // "noir" is in source but no clip binds it → affected set is empty.
        val plan = project.incrementalPlan(setOf(SourceNodeId("noir")), output, engineId)
        assertTrue(plan.isEmpty, "unrelated source edit (no clips bound) → empty plan; got $plan")
    }

    @Test fun aigcStaleClipFallsInReAigcBucket() {
        // Source state BEFORE edit; lockfile snapshot captures this state.
        val before = Source(nodes = listOf(characterRefNode("mei", mei(visual = "tall"))))
        val clip = videoClipBoundTo("c1", "mei")
        val entry = aigcEntry(clip.assetId, "mei", before)

        // Source state AFTER edit; deepContentHashOf("mei") differs from
        // entry.sourceContentHashes["mei"] → staleClipsFromLockfile flags c1.
        val after = Source(nodes = listOf(characterRefNode("mei", mei(visual = "short"))))
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(Track.Video(TrackId("v"), listOf(clip))),
                duration = 5.seconds,
            ),
            source = after,
            lockfile = EagerLockfile(entries = listOf(entry)),
        )

        val plan = project.incrementalPlan(setOf(SourceNodeId("mei")), output, engineId)
        assertEquals(listOf(ClipId("c1")), plan.reAigc, "AIGC-stale clip → reAigc bucket")
        assertTrue(plan.onlyRender.isEmpty(), "reAigc strictly disjoint from onlyRender")
        assertTrue(plan.unchanged.isEmpty(), "reAigc strictly disjoint from unchanged")
    }

    @Test fun renderStaleButNotAigcStaleFallsInOnlyRenderBucket() {
        // Legacy lockfile entry: empty sourceContentHashes → staleClipsFromLockfile
        // skips the entry → clip is NOT aigc-stale even though the bound
        // source's deepHash drifts. The fingerprint (which always includes
        // boundSourceDeepHashes) shifts → renderStaleClips flags c1.
        val source = Source(nodes = listOf(characterRefNode("mei", mei(visual = "current"))))
        val clip = videoClipBoundTo("c1", "mei")
        val legacyEntry = legacyEntryNoSnapshot(clip.assetId, "mei")
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(Track.Video(TrackId("v"), listOf(clip))),
                duration = 5.seconds,
            ),
            source = source,
            lockfile = EagerLockfile(entries = listOf(legacyEntry)),
            // No cache entries → fingerprint lookup misses → render-stale.
            clipRenderCache = ClipRenderCache(),
        )

        val plan = project.incrementalPlan(setOf(SourceNodeId("mei")), output, engineId)
        assertTrue(plan.reAigc.isEmpty(), "legacy lockfile entry skipped → not aigc-stale; got reAigc=${plan.reAigc}")
        assertEquals(
            listOf(ClipId("c1")),
            plan.onlyRender,
            "render-stale but not aigc-stale → onlyRender bucket",
        )
        assertTrue(plan.unchanged.isEmpty())
    }

    @Test fun cacheHitOnBoundClipFallsInUnchangedBucket() {
        // Project + matching cache entry → renderStaleClips returns empty
        // for c1. Lockfile snapshot also matches current state → not
        // aigc-stale. So c1 lands in `unchanged`.
        val source = Source(nodes = listOf(characterRefNode("mei", mei())))
        val clip = videoClipBoundTo("c1", "mei")
        val freshEntry = aigcEntry(clip.assetId, "mei", source)
        val coldProject = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(Track.Video(TrackId("v"), listOf(clip))),
                duration = 5.seconds,
            ),
            source = source,
            lockfile = EagerLockfile(entries = listOf(freshEntry)),
        )
        val fingerprint = fingerprintFor(coldProject, clip)
        val warmProject = coldProject.copy(
            clipRenderCache = ClipRenderCache(
                entries = listOf(
                    ClipRenderCacheEntry(
                        fingerprint = fingerprint,
                        mezzaninePath = "/tmp/cache/$fingerprint.mp4",
                        resolutionWidth = 1920,
                        resolutionHeight = 1080,
                        durationSeconds = 5.0,
                        createdAtEpochMs = 1L,
                    ),
                ),
            ),
        )

        val plan = warmProject.incrementalPlan(setOf(SourceNodeId("mei")), output, engineId)
        assertTrue(plan.reAigc.isEmpty(), "cache hit + lockfile match → not aigc-stale")
        assertTrue(plan.onlyRender.isEmpty(), "cache hit → not render-stale")
        assertEquals(
            listOf(ClipId("c1")),
            plan.unchanged,
            "bound clip with full cache hit → unchanged bucket",
        )
    }

    @Test fun bucketsAreDisjointAndCoverAffectedSet() {
        // 3 clips all bound to "mei": c1 aigc-stale, c2 only-render
        // (legacy entry), c3 unchanged (cache hit). One incrementalPlan
        // call should partition them into the three buckets without
        // overlap, and the union should equal the affected set.
        val source = Source(nodes = listOf(characterRefNode("mei", mei(visual = "current"))))

        // c1: lockfile snapshot recorded an OLD hash → aigc-stale.
        val c1 = videoClipBoundTo("c1", "mei")
        val staleEntry = LockfileEntry(
            inputHash = "h-c1",
            toolId = "generate_image",
            assetId = c1.assetId,
            provenance = GenerationProvenance(
                providerId = "fake",
                modelId = "m",
                modelVersion = null,
                seed = 0,
                parameters = kotlinx.serialization.json.JsonObject(emptyMap()),
                createdAtEpochMs = 0,
            ),
            sourceBinding = setOf(SourceNodeId("mei")),
            sourceContentHashes = mapOf(SourceNodeId("mei") to "stale-snapshot-hash-not-current"),
        )

        // c2: legacy lockfile entry with empty sourceContentHashes →
        // skipped by aigc check; render-stale via fingerprint.
        val c2 = videoClipBoundTo("c2", "mei", start = 5)
        val c2Entry = legacyEntryNoSnapshot(c2.assetId, "mei")

        // c3: fresh lockfile snapshot + cache hit → unchanged.
        val c3 = videoClipBoundTo("c3", "mei", start = 10)
        val c3Entry = aigcEntry(c3.assetId, "mei", source)

        val coldProject = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(Track.Video(TrackId("v"), listOf(c1, c2, c3))),
                duration = 15.seconds,
            ),
            source = source,
            lockfile = EagerLockfile(entries = listOf(staleEntry, c2Entry, c3Entry)),
        )
        val c3Fingerprint = fingerprintFor(coldProject, c3)
        val warmProject = coldProject.copy(
            clipRenderCache = ClipRenderCache(
                entries = listOf(
                    ClipRenderCacheEntry(
                        fingerprint = c3Fingerprint,
                        mezzaninePath = "/tmp/cache/$c3Fingerprint.mp4",
                        resolutionWidth = 1920,
                        resolutionHeight = 1080,
                        durationSeconds = 5.0,
                        createdAtEpochMs = 1L,
                    ),
                ),
            ),
        )

        val plan = warmProject.incrementalPlan(setOf(SourceNodeId("mei")), output, engineId)

        assertEquals(listOf(ClipId("c1")), plan.reAigc, "c1's stale lockfile snapshot → reAigc")
        assertEquals(listOf(ClipId("c2")), plan.onlyRender, "c2's legacy entry → onlyRender")
        assertEquals(listOf(ClipId("c3")), plan.unchanged, "c3's cache hit → unchanged")

        // Disjointness invariant.
        val all = (plan.reAigc + plan.onlyRender + plan.unchanged).toSet()
        assertEquals(3, all.size, "buckets must be disjoint — total unique = 3")
        assertEquals(setOf(ClipId("c1"), ClipId("c2"), ClipId("c3")), all)

        // workCount = re-AIGC + onlyRender (excluding unchanged).
        assertEquals(2, plan.workCount)
        assertTrue(!plan.isEmpty)
    }

    @Test fun unboundClipNotIncludedEvenWhenCacheCold() {
        // Clip with empty sourceBinding: regardless of changedSources,
        // it shouldn't appear in any bucket — incrementalPlan filters
        // to clips reachable from changedSources via clipsBoundTo,
        // and clipsBoundTo's contract excludes empty-binding clips.
        val source = Source(nodes = listOf(characterRefNode("mei", mei())))
        val unboundClip = Clip.Video(
            id = ClipId("imported"),
            timeRange = TimeRange(0.seconds, 3.seconds),
            sourceRange = TimeRange(0.seconds, 3.seconds),
            assetId = AssetId("imported-asset"),
            sourceBinding = emptySet(),
        )
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(Track.Video(TrackId("v"), listOf(unboundClip))),
                duration = 3.seconds,
            ),
            source = source,
        )
        val plan = project.incrementalPlan(setOf(SourceNodeId("mei")), output, engineId)
        assertTrue(
            plan.isEmpty,
            "unbound clip is out-of-scope for incremental tracking; not in any bucket",
        )
    }
}
