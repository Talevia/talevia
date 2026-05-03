package io.talevia.core.tool.builtin.project.query

import io.talevia.core.AssetId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
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

/**
 * Direct tests for [runLockfileEntriesQuery] —
 * `project_query(select=lockfile_entries)`. The "what AIGC has this
 * project produced?" enumeration. Cycle 119 audit: 89 LOC, **zero**
 * transitive test references.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Most-recent first ordering.** The lockfile is append-only
 *    on disk (oldest-first); the query reverses to surface the
 *    newest entries first. UI consumers and the LLM both expect
 *    this — "what did I just generate?" is the canonical query
 *    shape. A regression dropping the `asReversed()` call would
 *    silently invert pagination (offset 0 = oldest entry) and
 *    confuse every "recent" query.
 *
 * 2. **Filter composition: toolId × onlyPinned × sourceNodeId ×
 *    sinceEpochMs.** Each filter narrows the set independently;
 *    they compose via successive filter operations. A regression
 *    in any filter's predicate would either over-include
 *    (showing irrelevant entries) or under-include (silently
 *    dropping matches).
 *
 * 3. **`sourceBindingIds` sorted alphabetically per row.**
 *    Multiple source bindings on a single entry must serialize
 *    in stable order so re-running the query produces
 *    byte-identical output. A regression dropping the sort would
 *    shuffle the list nondeterministically.
 */
class LockfileEntriesQueryTest {

    private fun entry(
        hash: String,
        toolId: String = "generate_image",
        providerId: String = "openai",
        modelId: String = "gpt-image-1",
        seed: Long = 0,
        createdAtEpochMs: Long = 0,
        sourceBinding: Set<SourceNodeId> = emptySet(),
        pinned: Boolean = false,
        assetId: String = "asset-$hash",
    ) = LockfileEntry(
        inputHash = hash,
        toolId = toolId,
        assetId = AssetId(assetId),
        provenance = GenerationProvenance(
            providerId = providerId,
            modelId = modelId,
            modelVersion = null,
            seed = seed,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = createdAtEpochMs,
        ),
        sourceBinding = sourceBinding,
        pinned = pinned,
        originatingMessageId = MessageId("m"),
    )

    private fun project(entries: List<LockfileEntry>): Project = Project(
        id = ProjectId("p"),
        timeline = Timeline(),
        lockfile = EagerLockfile(entries = entries),
    )

    private fun input(
        toolId: String? = null,
        onlyPinned: Boolean? = null,
        sourceNodeId: String? = null,
        sinceEpochMs: Long? = null,
    ) = ProjectQueryTool.Input(
        projectId = "p",
        select = ProjectQueryTool.SELECT_LOCKFILE_ENTRIES,
        toolId = toolId,
        onlyPinned = onlyPinned,
        sourceNodeId = sourceNodeId,
        sinceEpochMs = sinceEpochMs,
    )

    private fun decodeRows(out: ProjectQueryTool.Output): List<LockfileEntryRow> =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(LockfileEntryRow.serializer()),
            out.rows,
        )

    // ── empty / shape ─────────────────────────────────────────────

    @Test fun emptyLockfileReturnsNoRows() {
        val result = runLockfileEntriesQuery(project(emptyList()), input(), 100, 0)
        assertEquals(0, result.data.total)
        assertEquals(0, result.data.returned)
        assertEquals(emptyList(), decodeRows(result.data))
        // Pin: empty result message names the project.
        assertTrue(
            "No lockfile entries on project p" in result.outputForLlm,
            "got: ${result.outputForLlm}",
        )
    }

    @Test fun singleEntryRowHasAllFieldsPopulated() {
        val e = entry(
            hash = "h1",
            toolId = "generate_image",
            providerId = "openai",
            modelId = "gpt-image-1",
            seed = 42,
            createdAtEpochMs = 1000,
            sourceBinding = setOf(SourceNodeId("n1"), SourceNodeId("n2")),
            pinned = true,
            assetId = "asset-1",
        )
        val rows = decodeRows(runLockfileEntriesQuery(project(listOf(e)), input(), 100, 0).data)
        val row = rows.single()
        assertEquals("h1", row.inputHash)
        assertEquals("generate_image", row.toolId)
        assertEquals("asset-1", row.assetId)
        assertEquals("openai", row.providerId)
        assertEquals("gpt-image-1", row.modelId)
        assertEquals(42L, row.seed)
        assertEquals(1000L, row.createdAtEpochMs)
        assertEquals(true, row.pinned)
        // sourceBindingIds sorted alphabetically — pin even with 2 ids.
        assertEquals(listOf("n1", "n2"), row.sourceBindingIds)
    }

    // ── most-recent first ordering ────────────────────────────────

    @Test fun rowsAreReversedToMostRecentFirst() {
        // Lockfile is append-only on disk (oldest-first); query
        // reverses to newest-first. Pin: e3 (last appended) →
        // rows[0]; e1 (first appended) → rows.last().
        val e1 = entry("h1", createdAtEpochMs = 100)
        val e2 = entry("h2", createdAtEpochMs = 200)
        val e3 = entry("h3", createdAtEpochMs = 300)
        val rows = decodeRows(runLockfileEntriesQuery(project(listOf(e1, e2, e3)), input(), 100, 0).data)
        assertEquals(listOf("h3", "h2", "h1"), rows.map { it.inputHash })
    }

    // ── filters ───────────────────────────────────────────────────

    @Test fun toolIdFilterRestrictsToMatchingEntries() {
        val image = entry("h1", toolId = "generate_image")
        val video = entry("h2", toolId = "generate_video")
        val rows = decodeRows(
            runLockfileEntriesQuery(project(listOf(image, video)), input(toolId = "generate_image"), 100, 0).data,
        )
        assertEquals(listOf("h1"), rows.map { it.inputHash })
    }

    @Test fun blankToolIdIsIgnored() {
        // Per code: `if (input.toolId.isNullOrBlank()) all else
        // all.filter { ... }`. Blank string falls through to "no
        // filter".
        val image = entry("h1", toolId = "generate_image")
        val video = entry("h2", toolId = "generate_video")
        val rows = decodeRows(
            runLockfileEntriesQuery(project(listOf(image, video)), input(toolId = "  "), 100, 0).data,
        )
        assertEquals(2, rows.size, "blank toolId = no filter")
    }

    @Test fun onlyPinnedRestrictsToPinnedEntries() {
        val pinned = entry("h1", pinned = true)
        val unpinned = entry("h2", pinned = false)
        val rows = decodeRows(
            runLockfileEntriesQuery(project(listOf(pinned, unpinned)), input(onlyPinned = true), 100, 0).data,
        )
        assertEquals(listOf("h1"), rows.map { it.inputHash })
    }

    @Test fun onlyPinnedFalseDoesNotFilter() {
        // Pin: only `onlyPinned == true` filters; null AND false
        // both pass-through. Otherwise the dispatcher's default
        // would silently drop unpinned entries.
        val pinned = entry("h1", pinned = true)
        val unpinned = entry("h2", pinned = false)
        val rows = decodeRows(
            runLockfileEntriesQuery(project(listOf(pinned, unpinned)), input(onlyPinned = false), 100, 0).data,
        )
        assertEquals(2, rows.size, "onlyPinned=false → both entries")
    }

    @Test fun sourceNodeIdFilterRestrictsToBoundEntries() {
        val a = entry("h1", sourceBinding = setOf(SourceNodeId("char")))
        val b = entry("h2", sourceBinding = setOf(SourceNodeId("style")))
        val c = entry("h3", sourceBinding = emptySet())
        val rows = decodeRows(
            runLockfileEntriesQuery(
                project(listOf(a, b, c)),
                input(sourceNodeId = "char"),
                100,
                0,
            ).data,
        )
        assertEquals(listOf("h1"), rows.map { it.inputHash })
    }

    @Test fun blankSourceNodeIdIsIgnored() {
        // Per code: `input.sourceNodeId?.takeIf { it.isNotBlank() }`.
        // Whitespace-only falls through to "no filter".
        val a = entry("h1", sourceBinding = setOf(SourceNodeId("char")))
        val b = entry("h2", sourceBinding = emptySet())
        val rows = decodeRows(
            runLockfileEntriesQuery(project(listOf(a, b)), input(sourceNodeId = "   "), 100, 0).data,
        )
        assertEquals(2, rows.size, "blank sourceNodeId = no filter")
    }

    @Test fun sinceEpochMsFilterIncludesEntriesAtOrAfterCutoff() {
        // Pin: `>= since` (inclusive at the cutoff).
        val old = entry("h1", createdAtEpochMs = 100)
        val cutoff = entry("h2", createdAtEpochMs = 200)
        val newer = entry("h3", createdAtEpochMs = 300)
        val rows = decodeRows(
            runLockfileEntriesQuery(project(listOf(old, cutoff, newer)), input(sinceEpochMs = 200), 100, 0).data,
        )
        assertEquals(setOf("h2", "h3"), rows.map { it.inputHash }.toSet())
    }

    @Test fun multipleFiltersComposeAndIntersect() {
        // toolId=generate_image AND onlyPinned=true AND sinceEpochMs=200.
        val a = entry("h1", toolId = "generate_image", pinned = true, createdAtEpochMs = 300) // matches
        val b = entry("h2", toolId = "generate_image", pinned = false, createdAtEpochMs = 300) // unpinned
        val c = entry("h3", toolId = "generate_video", pinned = true, createdAtEpochMs = 300) // wrong tool
        val d = entry("h4", toolId = "generate_image", pinned = true, createdAtEpochMs = 100) // too old
        val rows = decodeRows(
            runLockfileEntriesQuery(
                project(listOf(a, b, c, d)),
                input(toolId = "generate_image", onlyPinned = true, sinceEpochMs = 200),
                100,
                0,
            ).data,
        )
        assertEquals(listOf("h1"), rows.map { it.inputHash })
    }

    // ── sourceBindingIds sort ─────────────────────────────────────

    @Test fun rowSourceBindingIdsAreSortedAlphabetically() {
        // Multi-binding entry → sourceBindingIds sorted.
        // Insertion order z, a, m → output [a, m, z].
        val e = entry(
            "h1",
            sourceBinding = setOf(SourceNodeId("z"), SourceNodeId("a"), SourceNodeId("m")),
        )
        val rows = decodeRows(runLockfileEntriesQuery(project(listOf(e)), input(), 100, 0).data)
        assertEquals(listOf("a", "m", "z"), rows.single().sourceBindingIds)
    }

    // ── pagination ────────────────────────────────────────────────

    @Test fun limitTrimsRowsButTotalReflectsAllFiltered() {
        val entries = (1..5).map { entry("h$it", createdAtEpochMs = it.toLong()) }
        val result = runLockfileEntriesQuery(project(entries), input(), 2, 0)
        assertEquals(2, decodeRows(result.data).size, "page = limit")
        assertEquals(5, result.data.total, "total = all filtered")
        assertEquals(2, result.data.returned)
    }

    @Test fun offsetSkipsFirstNRowsOfReversed() {
        // 5 entries created at t=1..5; reversed to most-recent
        // first → [h5, h4, h3, h2, h1]. offset=2 → start at h3.
        val entries = (1..5).map { entry("h$it", createdAtEpochMs = it.toLong()) }
        val result = runLockfileEntriesQuery(project(entries), input(), 100, 2)
        val rows = decodeRows(result.data)
        assertEquals(3, rows.size)
        assertEquals("h3", rows[0].inputHash)
        assertEquals("h2", rows[1].inputHash)
        assertEquals("h1", rows[2].inputHash)
    }

    // ── outputForLlm summary ──────────────────────────────────────

    @Test fun nonEmptyOutputShowsCountAndScopeAndHeadList() {
        val a = entry("h1", toolId = "generate_image", modelId = "gpt-image-1", assetId = "asset-1")
        val b = entry("h2", toolId = "generate_image", modelId = "gpt-image-1", assetId = "asset-2")
        val out = runLockfileEntriesQuery(
            project(listOf(a, b)),
            input(toolId = "generate_image"),
            100,
            0,
        ).outputForLlm
        // Pin: "<rows> of <filtered> entries <scope> most-recent first: <head>".
        assertTrue("2 of 2 entries" in out, "count; got: $out")
        assertTrue("toolId=generate_image" in out, "scope label; got: $out")
        assertTrue("most-recent first" in out, "ordering hint; got: $out")
        // Head format: "<toolId>/<assetId> (model=<modelId>)".
        assertTrue("generate_image/asset-2 (model=gpt-image-1)" in out, "head format; got: $out")
    }

    @Test fun outputShowsEllipsisWhenMoreThanFiveEntries() {
        val entries = (1..7).map { entry("h$it", assetId = "asset-$it", createdAtEpochMs = it.toLong()) }
        val out = runLockfileEntriesQuery(project(entries), input(), 100, 0).outputForLlm
        // Pin: "; …" suffix when rows > 5.
        assertTrue(out.endsWith("; …"), "ellipsis when > 5; got: $out")
    }

    @Test fun outputNoEllipsisWhenAtMostFive() {
        val entries = (1..3).map { entry("h$it", assetId = "asset-$it", createdAtEpochMs = it.toLong()) }
        val out = runLockfileEntriesQuery(project(entries), input(), 100, 0).outputForLlm
        assertTrue("…" !in out, "no ellipsis when ≤ 5; got: $out")
    }

    @Test fun emptyResultWithFiltersSurfacesScope() {
        // Pin: "No lockfile entries on project p (toolId=...,
        // pinned, ...)". Scope label appears in parens after the
        // project id.
        val a = entry("h1", toolId = "generate_video")
        val out = runLockfileEntriesQuery(project(listOf(a)), input(toolId = "generate_image"), 100, 0).outputForLlm
        assertTrue("(toolId=generate_image)" in out, "scope in empty msg; got: $out")
    }

    @Test fun scopeLabelsStackForMultipleFilters() {
        // Pin: when multiple filters set, scope label is comma-
        // separated.
        val a = entry("h1", pinned = true)
        val out = runLockfileEntriesQuery(
            project(listOf(a)),
            input(toolId = "generate_image", onlyPinned = true, sinceEpochMs = 100),
            100,
            0,
        ).outputForLlm
        // Empty result → "No lockfile entries on project p (<scope>)".
        assertTrue("toolId=generate_image" in out, "got: $out")
        assertTrue("pinned" in out, "got: $out")
        assertTrue("sinceEpochMs=100" in out, "got: $out")
    }

    // ── output framing ────────────────────────────────────────────

    @Test fun outputCarriesProjectIdAndSelect() {
        val result = runLockfileEntriesQuery(project(emptyList()), input(), 100, 0)
        assertEquals("p", result.data.projectId)
        assertEquals(ProjectQueryTool.SELECT_LOCKFILE_ENTRIES, result.data.select)
    }

    @Test fun titleIncludesReturnedSlashTotal() {
        val entries = (1..5).map { entry("h$it", createdAtEpochMs = it.toLong()) }
        val result = runLockfileEntriesQuery(project(entries), input(), 2, 0)
        assertTrue(
            "(2/5)" in (result.title ?: ""),
            "title format; got: ${result.title}",
        )
    }
}
