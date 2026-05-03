package io.talevia.core.tool.builtin.project.query

import io.talevia.core.AssetId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.ProjectSnapshotId
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectSnapshot
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.lockfile.EagerLockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Direct tests for [runLockfileDiffQuery] —
 * `project_query(select=lockfile_diff)`. Diffs two project
 * lockfiles by `inputHash` (added / removed / unchanged-count)
 * to answer "after `regenerate_stale_clips`, which entries are
 * new?" Cycle 129 audit: 159 LOC, **zero** transitive test refs.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Diff is keyed by `inputHash`, NOT by assetId or
 *    provenance.** Per kdoc: "keyed by inputHash (the
 *    content-addressable identity of a cached generation)". A
 *    regression switching to assetId would silently miscount —
 *    same prompt regenerated → same inputHash → unchanged; but
 *    new asset → diff would think it's added. The contract
 *    matters because regenerate-stale-clips deliberately
 *    overwrites assetIds while preserving inputHash equivalence.
 *
 * 2. **Two-category diff (added + removed), no "changed".** Per
 *    kdoc: "LockfileEntry is immutable by design ... entries
 *    get appended (or pinned/unpinned) but never mutated in
 *    place." A regression introducing a "changed" bucket would
 *    silently split equality cases that should stay in
 *    unchangedCount.
 *
 * 3. **At least one of `from`/`to` snapshot must be set.** Per
 *    kdoc: "diffing current-vs-current is always identical and
 *    almost always a usage error." A regression accepting
 *    null/null would silently surface "all unchanged" results
 *    on what is actually a noop call.
 */
class LockfileDiffQueryTest {

    private fun entry(
        hash: String,
        toolId: String = "generate_image",
        assetId: String = "asset-$hash",
        providerId: String = "openai",
        modelId: String = "gpt-image-1",
        createdAtEpochMs: Long = 0L,
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
        originatingMessageId = MessageId("m"),
    )

    private fun project(
        snapshots: List<ProjectSnapshot> = emptyList(),
        currentEntries: List<LockfileEntry> = emptyList(),
    ): Project = Project(
        id = ProjectId("p"),
        timeline = Timeline(),
        lockfile = EagerLockfile(entries = currentEntries),
        snapshots = snapshots,
    )

    private fun snapshot(
        id: String,
        label: String = "snap-$id",
        entries: List<LockfileEntry>,
    ) = ProjectSnapshot(
        id = ProjectSnapshotId(id),
        label = label,
        capturedAtEpochMs = 0L,
        project = Project(
            id = ProjectId("p"),
            timeline = Timeline(),
            lockfile = EagerLockfile(entries = entries),
        ),
    )

    private fun input(
        fromSnapshotId: String? = null,
        toSnapshotId: String? = null,
    ) = ProjectQueryTool.Input(
        projectId = "p",
        select = ProjectQueryTool.SELECT_LOCKFILE_DIFF,
        fromSnapshotId = fromSnapshotId,
        toSnapshotId = toSnapshotId,
    )

    private fun decodeRow(out: ProjectQueryTool.Output): LockfileDiffRow =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(LockfileDiffRow.serializer()),
            out.rows,
        ).single()

    // ── input validation ──────────────────────────────────────────

    @Test fun bothSnapshotIdsNullErrorsLoud() {
        // Marquee pin: per kdoc, "diffing current-vs-current is
        // always identical and almost always a usage error".
        val ex = assertFailsWith<IllegalArgumentException> {
            runLockfileDiffQuery(project(), input())
        }
        val msg = ex.message ?: ""
        assertTrue("at least one of" in msg, "got: $msg")
        assertTrue("fromSnapshotId" in msg, "got: $msg")
        assertTrue("toSnapshotId" in msg, "got: $msg")
        assertTrue("current-vs-current" in msg, "explanation; got: $msg")
    }

    @Test fun unknownFromSnapshotIdErrorsWithRecoveryHint() {
        val ex = assertFailsWith<IllegalStateException> {
            runLockfileDiffQuery(project(), input(fromSnapshotId = "ghost"))
        }
        val msg = ex.message ?: ""
        assertTrue("Snapshot 'ghost' not found" in msg, "got: $msg")
        assertTrue("(from side)" in msg, "side label; got: $msg")
        assertTrue("project_query(select=snapshots)" in msg, "recovery; got: $msg")
    }

    @Test fun unknownToSnapshotIdErrorsWithRecoveryHint() {
        val ex = assertFailsWith<IllegalStateException> {
            runLockfileDiffQuery(project(), input(toSnapshotId = "ghost"))
        }
        val msg = ex.message ?: ""
        assertTrue("Snapshot 'ghost' not found" in msg, "got: $msg")
        assertTrue("(to side)" in msg, "side label distinct; got: $msg")
    }

    // ── diff direction: snapshot-vs-current ──────────────────────

    @Test fun fromSnapshotToCurrentSurfacesAddedAndRemoved() {
        // Snapshot has [h1, h2]; current has [h2, h3].
        // Diff (snap → current): added=[h3], removed=[h1],
        // unchanged=1 (h2).
        val snap = snapshot(
            "snap-1",
            entries = listOf(entry("h1"), entry("h2")),
        )
        val current = listOf(entry("h2"), entry("h3"))
        val proj = project(snapshots = listOf(snap), currentEntries = current)
        val row = decodeRow(
            runLockfileDiffQuery(proj, input(fromSnapshotId = "snap-1")).data,
        )
        assertEquals(setOf("h3"), row.added.map { it.inputHash }.toSet())
        assertEquals(setOf("h1"), row.removed.map { it.inputHash }.toSet())
        assertEquals(1, row.unchangedCount)
        assertEquals(false, row.identical)
        assertEquals(2, row.totalChanges)
    }

    @Test fun bothSnapshotsCompares() {
        val snapA = snapshot(
            "a",
            label = "before",
            entries = listOf(entry("h1"), entry("h2")),
        )
        val snapB = snapshot(
            "b",
            label = "after",
            entries = listOf(entry("h2"), entry("h3"), entry("h4")),
        )
        val proj = project(snapshots = listOf(snapA, snapB))
        val row = decodeRow(
            runLockfileDiffQuery(
                proj,
                input(fromSnapshotId = "a", toSnapshotId = "b"),
            ).data,
        )
        assertEquals(setOf("h3", "h4"), row.added.map { it.inputHash }.toSet())
        assertEquals(setOf("h1"), row.removed.map { it.inputHash }.toSet())
        assertEquals(1, row.unchangedCount)
    }

    @Test fun toSnapshotFromCurrentReversesDirection() {
        // Pin: from=current, to=snapshot is ALSO valid (no kdoc-
        // forbidden direction). Tests the symmetry.
        val snap = snapshot(
            "snap-1",
            entries = listOf(entry("h1")),
        )
        val current = listOf(entry("h2"))
        val proj = project(snapshots = listOf(snap), currentEntries = current)
        val row = decodeRow(
            runLockfileDiffQuery(proj, input(toSnapshotId = "snap-1")).data,
        )
        // Direction: current ([h2]) → snapshot ([h1]).
        // Added (only in to/snap): h1.
        // Removed (only in from/current): h2.
        assertEquals(setOf("h1"), row.added.map { it.inputHash }.toSet())
        assertEquals(setOf("h2"), row.removed.map { it.inputHash }.toSet())
    }

    // ── identical case ───────────────────────────────────────────

    @Test fun identicalLockfilesProduceIdenticalTrueAndZeroChanges() {
        val snap = snapshot("a", entries = listOf(entry("h1"), entry("h2")))
        val current = listOf(entry("h1"), entry("h2"))
        val proj = project(snapshots = listOf(snap), currentEntries = current)
        val row = decodeRow(
            runLockfileDiffQuery(proj, input(fromSnapshotId = "a")).data,
        )
        assertEquals(true, row.identical)
        assertEquals(0, row.totalChanges)
        assertEquals(emptyList(), row.added)
        assertEquals(emptyList(), row.removed)
        assertEquals(2, row.unchangedCount)
    }

    @Test fun bothEmptyLockfilesIdentical() {
        val snap = snapshot("a", entries = emptyList())
        val proj = project(snapshots = listOf(snap), currentEntries = emptyList())
        val row = decodeRow(
            runLockfileDiffQuery(proj, input(fromSnapshotId = "a")).data,
        )
        assertEquals(true, row.identical)
        assertEquals(0, row.unchangedCount)
        assertEquals(0, row.totalChanges)
    }

    // ── ordering: newest first within added/removed ──────────────

    @Test fun addedAndRemovedSortByCreatedAtEpochMsDescending() {
        // Pin per code: `sortedByDescending { it.createdAtEpochMs }`.
        // Newer changes surface first so a scrolling reader sees
        // recent activity at the top.
        val snap = snapshot(
            "a",
            entries = listOf(
                entry("removed-old", createdAtEpochMs = 100L),
                entry("removed-new", createdAtEpochMs = 300L),
            ),
        )
        val current = listOf(
            entry("added-old", createdAtEpochMs = 200L),
            entry("added-new", createdAtEpochMs = 400L),
        )
        val proj = project(snapshots = listOf(snap), currentEntries = current)
        val row = decodeRow(
            runLockfileDiffQuery(proj, input(fromSnapshotId = "a")).data,
        )
        // Pin: newest first within each bucket.
        assertEquals(listOf("added-new", "added-old"), row.added.map { it.inputHash })
        assertEquals(
            listOf("removed-new", "removed-old"),
            row.removed.map { it.inputHash },
        )
    }

    // ── inputHash keyed (NOT assetId) ─────────────────────────────

    @Test fun sameInputHashWithDifferentAssetIdIsUnchanged() {
        // Marquee semantic pin: same inputHash on both sides →
        // unchanged, regardless of differing assetId / provenance.
        // A regression keying on assetId would silently flip this
        // to "added + removed" → tells the LLM "regenerate
        // succeeded" when actually the cache hit (assetId stayed
        // stable across rerun, semantic identity preserved).
        // Note: we use different assetId values to verify the
        // diff doesn't trigger on them.
        val snap = snapshot(
            "a",
            entries = listOf(entry("h1", assetId = "asset-old")),
        )
        val current = listOf(entry("h1", assetId = "asset-new"))
        val proj = project(snapshots = listOf(snap), currentEntries = current)
        val row = decodeRow(
            runLockfileDiffQuery(proj, input(fromSnapshotId = "a")).data,
        )
        // Same hash → unchanged.
        assertEquals(true, row.identical, "same hash, different assetId → unchanged")
        assertEquals(1, row.unchangedCount)
        assertEquals(0, row.totalChanges)
    }

    // ── detail cap (50) + counts stay exact ─────────────────────

    @Test fun perSideDetailListCapsAtFiftyButCountsStayExact() {
        // Pin LOCKFILE_DIFF_MAX_DETAIL semantic: per-side details
        // cap at 50; unchangedCount and totalChanges stay exact
        // regardless. A regression dropping the cap would blow
        // up the response on wholesale lockfile rewrites.
        val snap = snapshot("a", entries = emptyList())
        val current = (1..60).map { entry("h$it", createdAtEpochMs = it.toLong()) }
        val proj = project(snapshots = listOf(snap), currentEntries = current)
        val row = decodeRow(
            runLockfileDiffQuery(proj, input(fromSnapshotId = "a")).data,
        )
        // Pin: detail list trimmed to 50.
        assertEquals(50, row.added.size, "detail capped at 50")
        // Pin: totalChanges stays exact (NOT 50).
        assertEquals(60, row.totalChanges, "totalChanges exact")
    }

    // ── DiffEntryRef field round-trip ─────────────────────────────

    @Test fun diffEntryRefFieldsRoundTripFromEntry() {
        val snap = snapshot("a", entries = emptyList())
        val current = listOf(
            entry(
                hash = "h1",
                toolId = "generate_video",
                assetId = "asset-1",
                providerId = "openai",
                modelId = "sora-2",
                createdAtEpochMs = 9000L,
            ),
        )
        val proj = project(snapshots = listOf(snap), currentEntries = current)
        val row = decodeRow(
            runLockfileDiffQuery(proj, input(fromSnapshotId = "a")).data,
        )
        val ref = row.added.single()
        assertEquals("h1", ref.inputHash)
        assertEquals("generate_video", ref.toolId)
        assertEquals("asset-1", ref.assetId)
        assertEquals("openai", ref.providerId)
        assertEquals("sora-2", ref.modelId)
        assertEquals(9000L, ref.createdAtEpochMs)
    }

    // ── label format ──────────────────────────────────────────────

    @Test fun labelsFormatAsProjectIdAtCurrentOrAtSnapshotLabel() {
        // Pin label format: "<projectId> @current" or
        // "<projectId> @<snapLabel>". UI consumers display these
        // as side titles.
        val snap = snapshot("a", label = "v1.0", entries = emptyList())
        val proj = project(snapshots = listOf(snap), currentEntries = emptyList())
        val row = decodeRow(
            runLockfileDiffQuery(proj, input(fromSnapshotId = "a")).data,
        )
        assertEquals("p @v1.0", row.fromLabel, "snapshot side uses snap label")
        assertEquals("p @current", row.toLabel, "current side uses @current")
    }

    @Test fun labelsBothSnapshotsUseTheirLabels() {
        val snap1 = snapshot("a", label = "before", entries = emptyList())
        val snap2 = snapshot("b", label = "after", entries = emptyList())
        val proj = project(snapshots = listOf(snap1, snap2))
        val row = decodeRow(
            runLockfileDiffQuery(proj, input(fromSnapshotId = "a", toSnapshotId = "b")).data,
        )
        assertEquals("p @before", row.fromLabel)
        assertEquals("p @after", row.toLabel)
    }

    // ── outputForLlm summary ──────────────────────────────────────

    @Test fun outputForLlmIdenticalCaseUsesIdenticalPhrase() {
        val snap = snapshot("a", entries = listOf(entry("h1")))
        val current = listOf(entry("h1"))
        val proj = project(snapshots = listOf(snap), currentEntries = current)
        val out = runLockfileDiffQuery(proj, input(fromSnapshotId = "a")).outputForLlm
        // Pin format: "<from> → <to>: lockfile identical
        // (<count> entries unchanged)."
        assertTrue("p @snap-a → p @current" in out, "label arrow; got: $out")
        assertTrue("lockfile identical" in out, "identical phrase; got: $out")
        assertTrue("(1 entries unchanged)" in out, "count; got: $out")
    }

    @Test fun outputForLlmNonIdenticalCaseUsesShorthandDelta() {
        val snap = snapshot("a", entries = listOf(entry("h1"), entry("h2")))
        val current = listOf(entry("h2"), entry("h3"))
        val proj = project(snapshots = listOf(snap), currentEntries = current)
        val out = runLockfileDiffQuery(proj, input(fromSnapshotId = "a")).outputForLlm
        // Pin format: "<from> → <to>: lockfile <total>Δ
        // (+<added> added / -<removed> removed / =<unchanged>
        // unchanged)."
        assertTrue("lockfile 2Δ" in out, "delta count; got: $out")
        assertTrue("+1 added" in out, "added shorthand; got: $out")
        assertTrue("-1 removed" in out, "removed shorthand; got: $out")
        assertTrue("=1 unchanged" in out, "unchanged shorthand; got: $out")
    }

    // ── output framing ────────────────────────────────────────────

    @Test fun outputCarriesProjectIdSelectAndSingleRow() {
        val snap = snapshot("a", entries = emptyList())
        val proj = project(snapshots = listOf(snap), currentEntries = emptyList())
        val result = runLockfileDiffQuery(proj, input(fromSnapshotId = "a"))
        assertEquals("p", result.data.projectId)
        assertEquals(ProjectQueryTool.SELECT_LOCKFILE_DIFF, result.data.select)
        assertEquals(1, result.data.total)
        assertEquals(1, result.data.returned)
    }

    @Test fun titleIncludesFromAndToLabels() {
        val snap = snapshot("a", label = "snap-tagged", entries = emptyList())
        val proj = project(snapshots = listOf(snap), currentEntries = emptyList())
        val result = runLockfileDiffQuery(proj, input(fromSnapshotId = "a"))
        // Pin: title format "lockfile_diff <projectId>
        // (<fromLabel> → <toLabel>)".
        assertTrue(
            "lockfile_diff p (p @snap-tagged → p @current)" in (result.title ?: ""),
            "title format; got: ${result.title}",
        )
    }
}
