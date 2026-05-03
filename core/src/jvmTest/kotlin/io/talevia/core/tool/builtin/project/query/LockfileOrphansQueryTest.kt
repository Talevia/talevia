package io.talevia.core.tool.builtin.project.query

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.lockfile.EagerLockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [runLockfileOrphansQuery] —
 * `project_query(select=lockfile_orphans)`. The "which cached
 * AIGC products are unreferenced?" query that drives gc_lockfile
 * / manual prune decisions. Cycle 123 audit: 117 LOC, **zero**
 * transitive test references; sixth project-query selector.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Reference set built from CLIP refs only (Video.assetId +
 *    Audio.assetId; Text clips have no asset).** A regression
 *    including LUT-filter or other non-clip asset references
 *    would silently mark in-use cached LUTs as orphans (vs
 *    cycle 120's broader `onlyReferenced` semantic in
 *    `select=assets` which DOES span 3 sources). The two
 *    queries deliberately differ — orphans is narrower (clip-
 *    only) for the prune-actionable set.
 *
 * 2. **Sort: unpinned first, then newest-first within bucket.**
 *    Per kdoc: "the most-recent unpinned entries — the ripest
 *    drop candidates — are at the top". A regression flipping
 *    either order would buffer pinned-but-orphan entries above
 *    actionable drops, distorting the gc-priority queue UX.
 *
 * 3. **Cost aggregation in the LLM summary.** Pinned for the
 *    "how much wasted spend?" signal that drives "should I gc
 *    now?" advisory output. A regression dropping the cost
 *    sum (or summing nulls) would silently hide / inflate the
 *    cost figure.
 */
class LockfileOrphansQueryTest {

    private val timeRange = TimeRange(start = 0.seconds, duration = 5.seconds)

    private fun videoClip(id: String, assetId: String) = Clip.Video(
        id = ClipId(id),
        timeRange = timeRange,
        sourceRange = timeRange,
        assetId = AssetId(assetId),
    )

    private fun textClip(id: String) = Clip.Text(
        id = ClipId(id),
        timeRange = timeRange,
        text = "subtitle",
    )

    private fun entry(
        assetId: String,
        hash: String = "h-$assetId",
        toolId: String = "generate_image",
        providerId: String = "openai",
        modelId: String = "gpt-image-1",
        costCents: Long? = null,
        createdAtEpochMs: Long = 0,
        pinned: Boolean = false,
    ) = LockfileEntry(
        inputHash = hash,
        toolId = toolId,
        assetId = AssetId(assetId),
        provenance = GenerationProvenance(
            providerId = providerId,
            modelId = modelId,
            modelVersion = null,
            seed = 0,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = createdAtEpochMs,
        ),
        costCents = costCents,
        pinned = pinned,
        originatingMessageId = MessageId("m"),
    )

    private fun project(
        clips: List<Clip> = emptyList(),
        entries: List<LockfileEntry> = emptyList(),
    ): Project {
        val tracks = if (clips.isEmpty()) emptyList() else listOf(Track.Video(TrackId("vt"), clips))
        return Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = tracks),
            lockfile = EagerLockfile(entries = entries),
        )
    }

    private fun decodeRows(out: ProjectQueryTool.Output): List<LockfileOrphanRow> =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(LockfileOrphanRow.serializer()),
            out.rows,
        )

    // ── empty / no orphans paths ──────────────────────────────────

    @Test fun emptyLockfileReturnsZeroOrphansWithDedicatedMarker() {
        val result = runLockfileOrphansQuery(project(), 100, 0)
        assertEquals(0, result.data.total)
        assertEquals(emptyList(), decodeRows(result.data))
        // Pin: empty case has dedicated message — distinct from
        // "all referenced" (both result in 0 orphans but different
        // user-facing context).
        assertTrue(
            "No orphan lockfile entries" in result.outputForLlm,
            "marker; got: ${result.outputForLlm}",
        )
        assertTrue(
            "every cached AIGC product is still referenced by a clip" in result.outputForLlm,
            "explanatory hint; got: ${result.outputForLlm}",
        )
    }

    @Test fun allReferencedEntriesProduceZeroOrphans() {
        // Pin: when every lockfile entry's assetId IS referenced by
        // some clip, total = 0. Same marker shape as empty
        // lockfile (which is the kdoc-acceptable behavior).
        val clip = videoClip("c1", "asset-1")
        val e = entry("asset-1")
        val result = runLockfileOrphansQuery(project(listOf(clip), listOf(e)), 100, 0)
        assertEquals(0, result.data.total)
    }

    // ── reference set: clip-only (NOT LUT filters / lockfile
    //    provenance) ────────────────────────────────────────────────

    @Test fun textClipDoesNotReferenceAnyAssetSoTheLockfileEntryIsOrphan() {
        // Pin: Text clips have no assetId in the domain model, so
        // they don't contribute to the referenced set. A lockfile
        // entry for an asset only "referenced" by a text clip on
        // a subtitle track (hypothetical — text clips don't bind
        // to assets) surfaces as orphan. Constructed via mixing
        // a text clip + a lockfile entry for an unrelated asset
        // id.
        val text = textClip("t1")
        val orphan = entry("ghost")
        val tracks = listOf(Track.Subtitle(TrackId("st"), listOf(text)))
        val proj = Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = tracks),
            lockfile = EagerLockfile(entries = listOf(orphan)),
        )
        val rows = decodeRows(runLockfileOrphansQuery(proj, 100, 0).data)
        assertEquals(listOf("ghost"), rows.map { it.assetId })
    }

    @Test fun videoClipReferenceExcludesEntryFromOrphans() {
        val clip = videoClip("c1", "shared")
        val e = entry("shared")
        val result = runLockfileOrphansQuery(project(listOf(clip), listOf(e)), 100, 0)
        assertEquals(0, result.data.total, "video clip ref excludes entry")
    }

    // ── sort: unpinned first, newest first within bucket ──────────

    @Test fun sortPutsUnpinnedFirstThenNewestFirstWithinBuckets() {
        // Pin marquee sort: unpinned-first → most-recent-first.
        // Plant 4 entries: 2 unpinned (one old, one new) and 2
        // pinned (one old, one new). Verify order:
        //   1. unpinned-newest
        //   2. unpinned-oldest
        //   3. pinned-newest
        //   4. pinned-oldest
        val entries = listOf(
            entry("up-old", createdAtEpochMs = 100, pinned = false),
            entry("p-old", createdAtEpochMs = 200, pinned = true),
            entry("up-new", createdAtEpochMs = 300, pinned = false),
            entry("p-new", createdAtEpochMs = 400, pinned = true),
        )
        val rows = decodeRows(runLockfileOrphansQuery(project(entries = entries), 100, 0).data)
        // Pin exact ordering: unpinned bucket first (newest-first
        // internally), then pinned bucket (newest-first internally).
        assertEquals(listOf("up-new", "up-old", "p-new", "p-old"), rows.map { it.assetId })
    }

    @Test fun pinnedFlagRoundTripsOnRow() {
        // Pin: each row carries its pinned flag — operator can
        // audit "what's protected by my pins" via this field.
        val pinned = entry("pin-1", pinned = true)
        val unpinned = entry("up-1", pinned = false)
        val rows = decodeRows(
            runLockfileOrphansQuery(project(entries = listOf(pinned, unpinned)), 100, 0).data,
        )
        val byAsset = rows.associateBy { it.assetId }
        assertEquals(true, byAsset.getValue("pin-1").pinned)
        assertEquals(false, byAsset.getValue("up-1").pinned)
    }

    // ── pagination ────────────────────────────────────────────────

    @Test fun limitTrimsResultButTotalReflectsAllOrphans() {
        val entries = (1..5).map { entry("a$it", createdAtEpochMs = it.toLong()) }
        val result = runLockfileOrphansQuery(project(entries = entries), 2, 0)
        assertEquals(2, decodeRows(result.data).size, "page = limit")
        assertEquals(5, result.data.total)
    }

    @Test fun offsetSkipsFirstNRows() {
        val entries = (1..5).map { entry("a$it", createdAtEpochMs = it.toLong()) }
        val result = runLockfileOrphansQuery(project(entries = entries), 100, 2)
        val rows = decodeRows(result.data)
        // All unpinned, so sort is newest-first → [a5, a4, a3, a2, a1].
        // offset=2 skips a5, a4 → start at a3.
        assertEquals(3, rows.size)
        assertEquals("a3", rows[0].assetId)
    }

    // ── outputForLlm summary ──────────────────────────────────────

    @Test fun nonEmptyOutputShowsCountWithUnpinnedAndPinnedSplit() {
        val unpinned = entry("up", pinned = false)
        val pinned = entry("p", pinned = true)
        val out = runLockfileOrphansQuery(project(entries = listOf(unpinned, pinned)), 100, 0).outputForLlm
        // Pin format: "<count> orphan lockfile entries in project p
        // (<unpinnedCount> unpinned, <pinnedCount> pinned)".
        assertTrue("2 orphan lockfile entries" in out, "count + plural; got: $out")
        assertTrue("(1 unpinned, 1 pinned)" in out, "split; got: $out")
        assertTrue("project p" in out, "project id; got: $out")
    }

    @Test fun singleOrphanUsesEntrySingularForm() {
        // Pin pluralisation: 1 → "entry" (singular). Per code:
        // `"entr${if (total == 1) "y" else "ies"}"` produces
        // "entry" or "entries" — the safe `entr` prefix +
        // suffix-swap pattern. NOT a "leafves"-style trap.
        val e = entry("a")
        val out = runLockfileOrphansQuery(project(entries = listOf(e)), 100, 0).outputForLlm
        assertTrue("1 orphan lockfile entry" in out, "singular; got: $out")
        // Make sure plural form NOT in output.
        assertTrue("entries" !in out, "no plural; got: $out")
    }

    @Test fun multipleOrphansUsePluralEntries() {
        val a = entry("a")
        val b = entry("b")
        val out = runLockfileOrphansQuery(project(entries = listOf(a, b)), 100, 0).outputForLlm
        assertTrue("2 orphan lockfile entries" in out, "plural; got: $out")
    }

    @Test fun costAggregationAppearsWhenSomeEntriesHaveCost() {
        // Pin cost sum in summary. "; roughly ¢<sum> of cached AIGC
        // cost is unreferenced." A regression dropping the sum
        // would hide spend-recovery signal.
        val e1 = entry("a", costCents = 100)
        val e2 = entry("b", costCents = 50)
        val e3 = entry("c", costCents = null) // unknown cost — excluded from sum
        val out = runLockfileOrphansQuery(project(entries = listOf(e1, e2, e3)), 100, 0).outputForLlm
        assertTrue("¢150" in out, "cost sum (100 + 50); got: $out")
        assertTrue("cached AIGC cost is unreferenced" in out, "cost narrative; got: $out")
    }

    @Test fun noCostAggregationWhenAllUnknownOrZero() {
        // Pin: when no priced entries exist, the cost narrative is
        // dropped entirely. The summary ends with "." after the
        // pinned/unpinned split, NOT with "; roughly ¢0 ...".
        val e = entry("a", costCents = null)
        val out = runLockfileOrphansQuery(project(entries = listOf(e)), 100, 0).outputForLlm
        // Line ends with "(1 unpinned, 0 pinned)." — no "roughly".
        assertTrue("roughly" !in out, "no cost narrative when all unpriced; got: $out")
    }

    // ── output framing ────────────────────────────────────────────

    @Test fun outputCarriesProjectIdAndSelect() {
        val result = runLockfileOrphansQuery(project(), 100, 0)
        assertEquals("p", result.data.projectId)
        assertEquals(ProjectQueryTool.SELECT_LOCKFILE_ORPHANS, result.data.select)
    }

    @Test fun titleIncludesReturnedSlashTotal() {
        val entries = (1..5).map { entry("a$it", createdAtEpochMs = it.toLong()) }
        val result = runLockfileOrphansQuery(project(entries = entries), 2, 0)
        assertTrue(
            "(2/5)" in (result.title ?: ""),
            "title format; got: ${result.title}",
        )
    }

    @Test fun rowCarriesAllProvenanceFields() {
        // Pin all 8 LockfileOrphanRow fields populate from the
        // entry's provenance + entry-level fields.
        val e = entry(
            assetId = "asset-1",
            hash = "hash-1",
            toolId = "generate_image",
            providerId = "openai",
            modelId = "gpt-image-1",
            costCents = 250,
            createdAtEpochMs = 9000,
            pinned = true,
        )
        val rows = decodeRows(runLockfileOrphansQuery(project(entries = listOf(e)), 100, 0).data)
        val row = rows.single()
        assertEquals("asset-1", row.assetId)
        assertEquals("hash-1", row.inputHash)
        assertEquals("generate_image", row.toolId)
        assertEquals("openai", row.providerId)
        assertEquals("gpt-image-1", row.modelId)
        assertEquals(250L, row.costCents)
        assertEquals(9000L, row.createdAtEpochMs)
        assertEquals(true, row.pinned)
    }
}
