package io.talevia.core.tool.builtin.project.query

import io.talevia.core.AssetId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.domain.Project
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.lockfile.EagerLockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Direct tests for [runSpendQuery] — `project_query(select=spend)`.
 * Single-row AIGC spend aggregate broken down by tool / session
 * with unknown-cost-entry tracking. Cycle 132 audit: 99 LOC, 1
 * transitive test ref (only via integration through the
 * dispatcher).
 *
 * Three correctness contracts pinned:
 *
 * 1. **Unknown-cost entries excluded from `totalCostCents` —
 *    NOT silently coalesced to zero.** Per kdoc: "entries with
 *    `null` cost (no pricing rule) are counted in
 *    `unknownCostEntries` and NOT rolled into `totalCostCents`
 *    — silent zero-coalescing would misrepresent spend as
 *    cheaper than it is." A regression treating null as 0
 *    would silently underreport spend, leading the LLM to
 *    advise "you've barely spent anything" when actually the
 *    spend is unknown (typically much higher than known).
 *
 * 2. **`bySession` only includes entries with stamped
 *    sessionId.** Per code: `if (sid != null) bySession[sid]
 *    += cents`. A regression accumulating null-session entries
 *    under "" or a sentinel would silently leak orphan
 *    spend into UI dashboards keyed by session.
 *
 * 3. **Map output sorted by key for diff stability.** All
 *    three breakdown maps (byTool / bySession / unknownByTool)
 *    use `sortedByKey()` to produce LinkedHashMap. UI
 *    consumers expect re-running the same query to produce
 *    byte-identical output.
 */
class SpendQueryTest {

    private fun entry(
        hash: String,
        toolId: String = "generate_image",
        costCents: Long? = null,
        sessionId: String? = null,
        assetId: String = "asset-$hash",
    ) = LockfileEntry(
        inputHash = hash,
        toolId = toolId,
        assetId = AssetId(assetId),
        provenance = GenerationProvenance(
            providerId = "openai",
            modelId = "gpt-image-1",
            modelVersion = null,
            seed = 0,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = 0L,
        ),
        costCents = costCents,
        sessionId = sessionId,
        originatingMessageId = MessageId("m"),
    )

    private fun project(entries: List<LockfileEntry>): Project = Project(
        id = ProjectId("p"),
        timeline = Timeline(),
        lockfile = EagerLockfile(entries = entries),
    )

    private fun decodeRow(out: ProjectQueryTool.Output): SpendSummaryRow =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SpendSummaryRow.serializer()),
            out.rows,
        ).single()

    // ── empty / shape ─────────────────────────────────────────────

    @Test fun emptyLockfileProducesZeroSpendAndEmptyBreakdowns() {
        val row = decodeRow(runSpendQuery(project(emptyList())).data)
        assertEquals("p", row.projectId)
        assertEquals(0L, row.totalCostCents)
        assertEquals(0, row.entryCount)
        assertEquals(0, row.knownCostEntries)
        assertEquals(0, row.unknownCostEntries)
        assertEquals(emptyMap(), row.byTool)
        assertEquals(emptyMap(), row.bySession)
        assertEquals(emptyMap(), row.unknownByTool)
    }

    // ── unknown-cost handling ─────────────────────────────────────

    @Test fun unknownCostEntriesAreCountedSeparatelyAndExcludedFromTotal() {
        // Marquee pin: 1 known @ ¢100 + 2 unknown → totalCostCents=
        // 100 (NOT 100 + 0 + 0 = 100 by coincidence), with
        // unknownCostEntries=2 + unknownByTool tracking. The
        // distinction matters because a regression coalescing
        // null to 0 would still produce totalCostCents=100 here
        // — but would pass `unknownCostEntries=0` (silently
        // hiding the unknowns).
        val priced = entry("h-known", toolId = "generate_image", costCents = 100)
        val unknown1 = entry("h-u1", toolId = "generate_image", costCents = null)
        val unknown2 = entry("h-u2", toolId = "generate_video", costCents = null)
        val row = decodeRow(runSpendQuery(project(listOf(priced, unknown1, unknown2))).data)
        assertEquals(100L, row.totalCostCents)
        assertEquals(3, row.entryCount)
        assertEquals(1, row.knownCostEntries)
        assertEquals(2, row.unknownCostEntries)
        // Pin: unknownByTool tracks per-tool unknown counts so
        // the LLM can advise "wire up pricing for these tools".
        assertEquals(1, row.unknownByTool["generate_image"])
        assertEquals(1, row.unknownByTool["generate_video"])
    }

    @Test fun unknownByToolDoesNotIncludePricedEntries() {
        // Pin: a tool that has BOTH priced and unpriced entries
        // shows up in BOTH byTool (priced sum) AND unknownByTool
        // (unpriced count). The maps are independent — neither
        // is a subset of the other.
        val priced = entry("h1", toolId = "generate_image", costCents = 50)
        val unpriced = entry("h2", toolId = "generate_image", costCents = null)
        val row = decodeRow(runSpendQuery(project(listOf(priced, unpriced))).data)
        // byTool counts only priced.
        assertEquals(50L, row.byTool["generate_image"])
        // unknownByTool counts only unpriced.
        assertEquals(1, row.unknownByTool["generate_image"])
    }

    // ── byTool / bySession breakdowns ─────────────────────────────

    @Test fun byToolGroupsSpendByToolId() {
        val image1 = entry("h1", toolId = "generate_image", costCents = 100)
        val image2 = entry("h2", toolId = "generate_image", costCents = 50)
        val video = entry("h3", toolId = "generate_video", costCents = 200)
        val row = decodeRow(runSpendQuery(project(listOf(image1, image2, video))).data)
        assertEquals(150L, row.byTool["generate_image"], "image sum")
        assertEquals(200L, row.byTool["generate_video"], "video sum")
        assertEquals(350L, row.totalCostCents, "total = sum of all")
    }

    @Test fun bySessionOnlyIncludesEntriesWithStampedSessionId() {
        // Pin marquee: null-sessionId entries are excluded from
        // bySession (they still count in totalCostCents). A
        // regression accumulating them under "" or a sentinel
        // would silently leak orphan spend into per-session
        // dashboards.
        val withSession = entry("h1", costCents = 100, sessionId = "s1")
        val noSession = entry("h2", costCents = 50, sessionId = null)
        val row = decodeRow(runSpendQuery(project(listOf(withSession, noSession))).data)
        // Total includes both (per kdoc — totalCostCents sums all
        // priced regardless of session).
        assertEquals(150L, row.totalCostCents)
        // bySession only includes the s1 entry.
        assertEquals(mapOf("s1" to 100L), row.bySession)
    }

    @Test fun bySessionGroupsSameSessionEntriesTogether() {
        val a1 = entry("h1", costCents = 100, sessionId = "s1")
        val a2 = entry("h2", costCents = 50, sessionId = "s1")
        val b = entry("h3", costCents = 200, sessionId = "s2")
        val row = decodeRow(runSpendQuery(project(listOf(a1, a2, b))).data)
        assertEquals(150L, row.bySession["s1"])
        assertEquals(200L, row.bySession["s2"])
    }

    // ── map sort by key ───────────────────────────────────────────

    @Test fun byToolMapKeysAreSortedAlphabetically() {
        // Pin: insertion order [zebra, alpha, mango] → output
        // alphabetical via sortedByKey().
        val z = entry("h1", toolId = "zebra-tool", costCents = 1)
        val a = entry("h2", toolId = "alpha-tool", costCents = 1)
        val m = entry("h3", toolId = "mango-tool", costCents = 1)
        val row = decodeRow(runSpendQuery(project(listOf(z, a, m))).data)
        // LinkedHashMap preserves insertion order; assert that.
        assertEquals(
            listOf("alpha-tool", "mango-tool", "zebra-tool"),
            row.byTool.keys.toList(),
        )
    }

    @Test fun bySessionMapKeysAreSortedAlphabetically() {
        val z = entry("h1", costCents = 1, sessionId = "z-session")
        val a = entry("h2", costCents = 1, sessionId = "a-session")
        val row = decodeRow(runSpendQuery(project(listOf(z, a))).data)
        assertEquals(listOf("a-session", "z-session"), row.bySession.keys.toList())
    }

    @Test fun unknownByToolMapKeysAreSortedAlphabetically() {
        val z = entry("h1", toolId = "zebra-tool", costCents = null)
        val a = entry("h2", toolId = "alpha-tool", costCents = null)
        val row = decodeRow(runSpendQuery(project(listOf(z, a))).data)
        assertEquals(listOf("alpha-tool", "zebra-tool"), row.unknownByTool.keys.toList())
    }

    // ── outputForLlm summary ──────────────────────────────────────

    @Test fun summaryShowsDollarAmountAndCountWithSingularPriced() {
        val e = entry("h1", costCents = 150)
        val out = runSpendQuery(project(listOf(e))).outputForLlm
        // Pin: "Project p has spent ~$1.5 across 1 priced
        // entry, top tool generate_image (150¢)."
        assertTrue("Project p has spent" in out, "header; got: $out")
        assertTrue("~\$1.5" in out, "dollar formatted; got: $out")
        assertTrue("1 priced entry" in out, "singular form; got: $out")
        // No unknown tail.
        assertTrue("unknown-cost" !in out, "no unknown tail; got: $out")
        // Top tool surfaces.
        assertTrue("top tool generate_image (150¢)" in out, "top tool; got: $out")
    }

    @Test fun summaryUsesPluralPricedEntries() {
        val e1 = entry("h1", costCents = 100)
        val e2 = entry("h2", costCents = 50)
        val out = runSpendQuery(project(listOf(e1, e2))).outputForLlm
        assertTrue("2 priced entries" in out, "plural form; got: $out")
    }

    @Test fun summaryShowsUnknownTailWithSingularPluralisation() {
        // Pin: "1 unknown-cost entry" singular vs "N unknown-cost
        // entries" plural — same safe `entr` prefix + suffix-swap
        // pattern (NOT a leafves-style trap, per the cycle-107
        // audit).
        val priced = entry("h1", costCents = 100)
        val unpriced = entry("h2", costCents = null)
        val out = runSpendQuery(project(listOf(priced, unpriced))).outputForLlm
        assertTrue(
            "1 unknown-cost entry" in out,
            "singular form; got: $out",
        )
        // Plural variant.
        val unp1 = entry("h2", costCents = null)
        val unp2 = entry("h3", costCents = null)
        val out2 = runSpendQuery(project(listOf(priced, unp1, unp2))).outputForLlm
        assertTrue(
            "2 unknown-cost entries" in out2,
            "plural form; got: $out2",
        )
    }

    @Test fun summaryDropsTopToolTailWhenAllEntriesUnpriced() {
        // Pin: when byTool is empty (zero priced entries),
        // `topTool` is null → tail dropped entirely. A
        // regression always emitting "top tool null (0¢)"
        // would clutter every all-unpriced output.
        val e = entry("h1", costCents = null)
        val out = runSpendQuery(project(listOf(e))).outputForLlm
        assertTrue("top tool" !in out, "no top tool when no priced entries; got: $out")
        // But the unknown tail still surfaces.
        assertTrue("1 unknown-cost entry" in out)
    }

    @Test fun summaryDollarAmountFormattedFromCentsViaDouble() {
        // Pin: 150¢ → "1.5", 250¢ → "2.5", 12345¢ → "123.45".
        // The .take(10) guard caps very large totals so the
        // summary stays readable.
        val e = entry("h1", costCents = 12345)
        val out = runSpendQuery(project(listOf(e))).outputForLlm
        assertTrue("~\$123.45" in out, "decimal format; got: $out")
    }

    @Test fun summaryNoUnknownTailWhenAllEntriesPriced() {
        val e = entry("h1", costCents = 100)
        val out = runSpendQuery(project(listOf(e))).outputForLlm
        assertFalse("unknown-cost" in out, "no unknown tail; got: $out")
    }

    @Test fun topToolFromHighestCostNotMostEntries() {
        // Pin: top tool is by SUM of costCents, not count.
        // 5 image entries @ ¢10 each = ¢50 vs 1 video @ ¢100 →
        // video is top.
        val images = (1..5).map { entry("hi$it", toolId = "generate_image", costCents = 10) }
        val video = entry("hv", toolId = "generate_video", costCents = 100)
        val out = runSpendQuery(project(images + video)).outputForLlm
        assertTrue("top tool generate_video (100¢)" in out, "got: $out")
    }

    // ── output framing ────────────────────────────────────────────

    @Test fun outputCarriesProjectIdSelectAndSingleRow() {
        val result = runSpendQuery(project(emptyList()))
        assertEquals("p", result.data.projectId)
        assertEquals(ProjectQueryTool.SELECT_SPEND, result.data.select)
        assertEquals(1, result.data.total)
        assertEquals(1, result.data.returned)
    }

    @Test fun titleIncludesTotalCents() {
        val e1 = entry("h1", costCents = 100)
        val e2 = entry("h2", costCents = 50)
        val result = runSpendQuery(project(listOf(e1, e2)))
        assertTrue(
            "project_query spend (150¢)" in (result.title ?: ""),
            "title; got: ${result.title}",
        )
    }

    @Test fun rowProjectIdMirrorsProjectId() {
        val result = runSpendQuery(project(emptyList()))
        val row = decodeRow(result.data)
        assertEquals("p", row.projectId, "row carries project id")
    }
}
