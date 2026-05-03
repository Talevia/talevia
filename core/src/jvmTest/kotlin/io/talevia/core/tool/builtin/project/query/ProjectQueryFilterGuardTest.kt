package io.talevia.core.tool.builtin.project.query

import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Direct tests for [rejectIncompatibleProjectQueryFilters] —
 * cross-field validation guard that fails loud when an LLM
 * supplies a filter on the wrong `select`. Cycle 125 audit:
 * 117 LOC, **zero** transitive test references; the
 * dispatcher-side type-check that surfaces typos as errors
 * instead of silently empty results was previously unprotected.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Multi-select-valid filters (`onlyPinned`, `assetId`,
 *    `sourceNodeId`, `fromSnapshotId`/`toSnapshotId`,
 *    `engineId`) accept on EVERY listed select but reject
 *    elsewhere.** A regression narrowing acceptance to one
 *    select would silently break workflows that legitimately
 *    use these fields across multiple selects (e.g.
 *    `select=lockfile_entry` setting `assetId` to look up
 *    "what entry produced this asset?").
 *
 * 2. **Wrong-select rejection lists the valid select(s) in
 *    the error.** A regression dropping the recovery hint
 *    would force the LLM to guess where the field belongs.
 *    The test suite verifies each error message includes
 *    a `select=...only` segment naming the field's home.
 *
 * 3. **Multiple misapplied filters concatenate in one error.**
 *    `multipleMisappliedFiltersConcatenateInOneError` plants
 *    3 wrong-select fields and verifies the error names all
 *    3 — one validation pass surfaces every violation rather
 *    than failing on the first and forcing iterative LLM
 *    retries.
 */
class ProjectQueryFilterGuardTest {

    // ── compatibility (no error path) ─────────────────────────────

    @Test fun emptyInputAcceptsAnySelect() {
        // Pin: a bare `Input(select=X)` with no filter fields set
        // never errors regardless of select. Cross-validation
        // only fires when a filter is set.
        for (select in listOf(
            ProjectQueryTool.SELECT_TRACKS,
            ProjectQueryTool.SELECT_TIMELINE_CLIPS,
            ProjectQueryTool.SELECT_ASSETS,
            ProjectQueryTool.SELECT_LOCKFILE_ENTRIES,
            ProjectQueryTool.SELECT_INCREMENTAL_PLAN,
        )) {
            // Should not throw.
            rejectIncompatibleProjectQueryFilters(
                select,
                ProjectQueryTool.Input(select = select),
            )
        }
    }

    @Test fun onlyNonEmptyAcceptedOnTracksSelect() {
        rejectIncompatibleProjectQueryFilters(
            ProjectQueryTool.SELECT_TRACKS,
            ProjectQueryTool.Input(
                select = ProjectQueryTool.SELECT_TRACKS,
                onlyNonEmpty = true,
            ),
        )
    }

    @Test fun trackIdAcceptedOnTimelineClipsSelect() {
        rejectIncompatibleProjectQueryFilters(
            ProjectQueryTool.SELECT_TIMELINE_CLIPS,
            ProjectQueryTool.Input(
                select = ProjectQueryTool.SELECT_TIMELINE_CLIPS,
                trackId = "vt",
            ),
        )
    }

    @Test fun kindAcceptedOnAssetsSelect() {
        rejectIncompatibleProjectQueryFilters(
            ProjectQueryTool.SELECT_ASSETS,
            ProjectQueryTool.Input(
                select = ProjectQueryTool.SELECT_ASSETS,
                kind = "video",
            ),
        )
    }

    // ── single-select wrong-target rejection ──────────────────────

    @Test fun onlyNonEmptyOnAssetsSelectFailsWithRecoveryHint() {
        val ex = assertFailsWith<IllegalStateException> {
            rejectIncompatibleProjectQueryFilters(
                ProjectQueryTool.SELECT_ASSETS,
                ProjectQueryTool.Input(
                    select = ProjectQueryTool.SELECT_ASSETS,
                    onlyNonEmpty = true,
                ),
            )
        }
        val msg = ex.message ?: ""
        assertTrue("onlyNonEmpty" in msg, "field name in error; got: $msg")
        assertTrue("select=tracks only" in msg, "recovery hint; got: $msg")
        // Pin: error mentions the offending select too.
        assertTrue("select='assets'" in msg, "context select; got: $msg")
    }

    @Test fun trackIdOnTracksSelectFailsRecoveryNamesTimelineClips() {
        val ex = assertFailsWith<IllegalStateException> {
            rejectIncompatibleProjectQueryFilters(
                ProjectQueryTool.SELECT_TRACKS,
                ProjectQueryTool.Input(
                    select = ProjectQueryTool.SELECT_TRACKS,
                    trackId = "vt",
                ),
            )
        }
        val msg = ex.message ?: ""
        assertTrue("trackId" in msg, "got: $msg")
        assertTrue("select=timeline_clips only" in msg, "got: $msg")
    }

    @Test fun fromSecondsOnAssetsSelectFails() {
        val ex = assertFailsWith<IllegalStateException> {
            rejectIncompatibleProjectQueryFilters(
                ProjectQueryTool.SELECT_ASSETS,
                ProjectQueryTool.Input(
                    select = ProjectQueryTool.SELECT_ASSETS,
                    fromSeconds = 0.0,
                ),
            )
        }
        assertTrue("fromSeconds" in (ex.message ?: ""))
        assertTrue("select=timeline_clips only" in (ex.message ?: ""))
    }

    @Test fun kindOnLockfileEntriesSelectFails() {
        val ex = assertFailsWith<IllegalStateException> {
            rejectIncompatibleProjectQueryFilters(
                ProjectQueryTool.SELECT_LOCKFILE_ENTRIES,
                ProjectQueryTool.Input(
                    select = ProjectQueryTool.SELECT_LOCKFILE_ENTRIES,
                    kind = "video",
                ),
            )
        }
        assertTrue("kind" in (ex.message ?: ""))
        assertTrue("select=assets only" in (ex.message ?: ""))
    }

    @Test fun onlyUnusedOnTracksSelectFails() {
        val ex = assertFailsWith<IllegalStateException> {
            rejectIncompatibleProjectQueryFilters(
                ProjectQueryTool.SELECT_TRACKS,
                ProjectQueryTool.Input(
                    select = ProjectQueryTool.SELECT_TRACKS,
                    onlyUnused = true,
                ),
            )
        }
        assertTrue("onlyUnused" in (ex.message ?: ""))
        assertTrue("select=assets only" in (ex.message ?: ""))
    }

    @Test fun trackKindOnAssetsSelectFailsWithSpecificMessage() {
        // Pin: the trackKind error has a unique message form
        // ("select=tracks or timeline_clips only") rather than
        // the bare "select=X only" — because trackKind applies
        // to two selects.
        val ex = assertFailsWith<IllegalStateException> {
            rejectIncompatibleProjectQueryFilters(
                ProjectQueryTool.SELECT_ASSETS,
                ProjectQueryTool.Input(
                    select = ProjectQueryTool.SELECT_ASSETS,
                    trackKind = "video",
                ),
            )
        }
        assertTrue("trackKind" in (ex.message ?: ""))
        assertTrue("select=tracks or timeline_clips only" in (ex.message ?: ""))
    }

    @Test fun onlyOrphanedOnTracksSelectFails() {
        val ex = assertFailsWith<IllegalStateException> {
            rejectIncompatibleProjectQueryFilters(
                ProjectQueryTool.SELECT_TRACKS,
                ProjectQueryTool.Input(
                    select = ProjectQueryTool.SELECT_TRACKS,
                    onlyOrphaned = true,
                ),
            )
        }
        assertTrue("onlyOrphaned" in (ex.message ?: ""))
        assertTrue("select=transitions only" in (ex.message ?: ""))
    }

    @Test fun toolIdOnTracksSelectFails() {
        val ex = assertFailsWith<IllegalStateException> {
            rejectIncompatibleProjectQueryFilters(
                ProjectQueryTool.SELECT_TRACKS,
                ProjectQueryTool.Input(
                    select = ProjectQueryTool.SELECT_TRACKS,
                    toolId = "generate_image",
                ),
            )
        }
        assertTrue("toolId" in (ex.message ?: ""))
        assertTrue("select=lockfile_entries only" in (ex.message ?: ""))
    }

    @Test fun maxAgeDaysOnTimelineClipsSelectFails() {
        val ex = assertFailsWith<IllegalStateException> {
            rejectIncompatibleProjectQueryFilters(
                ProjectQueryTool.SELECT_TIMELINE_CLIPS,
                ProjectQueryTool.Input(
                    select = ProjectQueryTool.SELECT_TIMELINE_CLIPS,
                    maxAgeDays = 30,
                ),
            )
        }
        assertTrue("maxAgeDays" in (ex.message ?: ""))
        assertTrue("select=snapshots only" in (ex.message ?: ""))
    }

    @Test fun clipIdOnAssetsSelectFails() {
        val ex = assertFailsWith<IllegalStateException> {
            rejectIncompatibleProjectQueryFilters(
                ProjectQueryTool.SELECT_ASSETS,
                ProjectQueryTool.Input(
                    select = ProjectQueryTool.SELECT_ASSETS,
                    clipId = "c1",
                ),
            )
        }
        assertTrue("clipId" in (ex.message ?: ""))
        assertTrue("select=clip only" in (ex.message ?: ""))
    }

    @Test fun inputHashOnLockfileEntriesSelectFails() {
        // Subtle: lockfile_entries uses inputHash to filter? No —
        // inputHash is for SELECT_LOCKFILE_ENTRY (singular,
        // describe one entry). The plural enumerator uses other
        // filters.
        val ex = assertFailsWith<IllegalStateException> {
            rejectIncompatibleProjectQueryFilters(
                ProjectQueryTool.SELECT_LOCKFILE_ENTRIES,
                ProjectQueryTool.Input(
                    select = ProjectQueryTool.SELECT_LOCKFILE_ENTRIES,
                    inputHash = "h1",
                ),
            )
        }
        assertTrue("inputHash" in (ex.message ?: ""))
        assertTrue("select=lockfile_entry only" in (ex.message ?: ""))
    }

    @Test fun sourceNodeIdsOnAssetsSelectFails() {
        // sourceNodeIds (plural, list) is incremental_plan-only.
        // A regression accepting it elsewhere would silently
        // route the LLM's typo (sourceNodeId vs sourceNodeIds)
        // to a different select's empty list.
        val ex = assertFailsWith<IllegalStateException> {
            rejectIncompatibleProjectQueryFilters(
                ProjectQueryTool.SELECT_ASSETS,
                ProjectQueryTool.Input(
                    select = ProjectQueryTool.SELECT_ASSETS,
                    sourceNodeIds = listOf("n1", "n2"),
                ),
            )
        }
        assertTrue("sourceNodeIds" in (ex.message ?: ""))
        assertTrue("select=incremental_plan only" in (ex.message ?: ""))
    }

    // ── multi-select-valid filters ────────────────────────────────

    @Test fun onlyPinnedAcceptedOnBothTimelineClipsAndLockfileEntries() {
        // Marquee multi-select pin: per code, onlyPinned is
        // valid on BOTH selects. Accept calls.
        rejectIncompatibleProjectQueryFilters(
            ProjectQueryTool.SELECT_TIMELINE_CLIPS,
            ProjectQueryTool.Input(
                select = ProjectQueryTool.SELECT_TIMELINE_CLIPS,
                onlyPinned = true,
            ),
        )
        rejectIncompatibleProjectQueryFilters(
            ProjectQueryTool.SELECT_LOCKFILE_ENTRIES,
            ProjectQueryTool.Input(
                select = ProjectQueryTool.SELECT_LOCKFILE_ENTRIES,
                onlyPinned = true,
            ),
        )
    }

    @Test fun onlyPinnedOnAssetsSelectFails() {
        val ex = assertFailsWith<IllegalStateException> {
            rejectIncompatibleProjectQueryFilters(
                ProjectQueryTool.SELECT_ASSETS,
                ProjectQueryTool.Input(
                    select = ProjectQueryTool.SELECT_ASSETS,
                    onlyPinned = true,
                ),
            )
        }
        assertTrue("onlyPinned" in (ex.message ?: ""))
        assertTrue(
            "select=timeline_clips or lockfile_entries only" in (ex.message ?: ""),
            "must list both valid selects; got: ${ex.message}",
        )
    }

    @Test fun assetIdAcceptedOnBothClipsForAssetAndLockfileEntry() {
        // assetId valid on clips_for_asset (lookup clips bound
        // to an asset) AND lockfile_entry (reverse-lookup the
        // entry that produced an asset).
        rejectIncompatibleProjectQueryFilters(
            ProjectQueryTool.SELECT_CLIPS_FOR_ASSET,
            ProjectQueryTool.Input(
                select = ProjectQueryTool.SELECT_CLIPS_FOR_ASSET,
                assetId = "asset-1",
            ),
        )
        rejectIncompatibleProjectQueryFilters(
            ProjectQueryTool.SELECT_LOCKFILE_ENTRY,
            ProjectQueryTool.Input(
                select = ProjectQueryTool.SELECT_LOCKFILE_ENTRY,
                assetId = "asset-1",
            ),
        )
    }

    @Test fun assetIdOnAssetsSelectFails() {
        val ex = assertFailsWith<IllegalStateException> {
            rejectIncompatibleProjectQueryFilters(
                ProjectQueryTool.SELECT_ASSETS,
                ProjectQueryTool.Input(
                    select = ProjectQueryTool.SELECT_ASSETS,
                    assetId = "asset-1",
                ),
            )
        }
        assertTrue(
            "select=clips_for_asset or lockfile_entry only" in (ex.message ?: ""),
            "must list both valid selects; got: ${ex.message}",
        )
    }

    @Test fun sourceNodeIdAcceptedOnThreeValidSelects() {
        // sourceNodeId (singular) is valid for 3 selects:
        //   - clips_for_source (lookup clips bound to source)
        //   - consistency_propagation (which source nodes
        //     this one fold-affects)
        //   - lockfile_entries (filter entries by source binding)
        for (sel in listOf(
            ProjectQueryTool.SELECT_CLIPS_FOR_SOURCE,
            ProjectQueryTool.SELECT_CONSISTENCY_PROPAGATION,
            ProjectQueryTool.SELECT_LOCKFILE_ENTRIES,
        )) {
            rejectIncompatibleProjectQueryFilters(
                sel,
                ProjectQueryTool.Input(select = sel, sourceNodeId = "n1"),
            )
        }
    }

    @Test fun sourceNodeIdOnTracksSelectFailsListingAllThreeValid() {
        val ex = assertFailsWith<IllegalStateException> {
            rejectIncompatibleProjectQueryFilters(
                ProjectQueryTool.SELECT_TRACKS,
                ProjectQueryTool.Input(
                    select = ProjectQueryTool.SELECT_TRACKS,
                    sourceNodeId = "n1",
                ),
            )
        }
        // Pin: error names ALL THREE valid selects.
        val msg = ex.message ?: ""
        assertTrue("clips_for_source" in msg, "got: $msg")
        assertTrue("consistency_propagation" in msg, "got: $msg")
        assertTrue("lockfile_entries" in msg, "got: $msg")
    }

    @Test fun snapshotIdsAcceptedOnBothTimelineDiffAndLockfileDiff() {
        rejectIncompatibleProjectQueryFilters(
            ProjectQueryTool.SELECT_TIMELINE_DIFF,
            ProjectQueryTool.Input(
                select = ProjectQueryTool.SELECT_TIMELINE_DIFF,
                fromSnapshotId = "s1",
                toSnapshotId = "s2",
            ),
        )
        rejectIncompatibleProjectQueryFilters(
            ProjectQueryTool.SELECT_LOCKFILE_DIFF,
            ProjectQueryTool.Input(
                select = ProjectQueryTool.SELECT_LOCKFILE_DIFF,
                fromSnapshotId = "s1",
                toSnapshotId = "s2",
            ),
        )
    }

    @Test fun fromSnapshotIdOnTracksSelectFails() {
        val ex = assertFailsWith<IllegalStateException> {
            rejectIncompatibleProjectQueryFilters(
                ProjectQueryTool.SELECT_TRACKS,
                ProjectQueryTool.Input(
                    select = ProjectQueryTool.SELECT_TRACKS,
                    fromSnapshotId = "s1",
                ),
            )
        }
        assertTrue("fromSnapshotId" in (ex.message ?: ""))
        assertTrue(
            "select=timeline_diff or lockfile_diff only" in (ex.message ?: ""),
            "must list both diff selects; got: ${ex.message}",
        )
    }

    @Test fun engineIdAcceptedOnBothRenderStaleAndIncrementalPlan() {
        rejectIncompatibleProjectQueryFilters(
            ProjectQueryTool.SELECT_RENDER_STALE,
            ProjectQueryTool.Input(
                select = ProjectQueryTool.SELECT_RENDER_STALE,
                engineId = "ffmpeg-jvm",
            ),
        )
        rejectIncompatibleProjectQueryFilters(
            ProjectQueryTool.SELECT_INCREMENTAL_PLAN,
            ProjectQueryTool.Input(
                select = ProjectQueryTool.SELECT_INCREMENTAL_PLAN,
                engineId = "ffmpeg-jvm",
            ),
        )
    }

    @Test fun engineIdOnTracksSelectFails() {
        val ex = assertFailsWith<IllegalStateException> {
            rejectIncompatibleProjectQueryFilters(
                ProjectQueryTool.SELECT_TRACKS,
                ProjectQueryTool.Input(
                    select = ProjectQueryTool.SELECT_TRACKS,
                    engineId = "ffmpeg-jvm",
                ),
            )
        }
        assertTrue("engineId" in (ex.message ?: ""))
        assertTrue(
            "select=render_stale or incremental_plan only" in (ex.message ?: ""),
            "must list both engine selects; got: ${ex.message}",
        )
    }

    // ── multi-misapplied concatenation ────────────────────────────

    @Test fun multipleMisappliedFiltersConcatenateInOneError() {
        // Pin: 3 wrong-select fields → all 3 named in one error.
        // Single validation pass surfaces every violation rather
        // than failing on the first and forcing iterative LLM
        // retries.
        val ex = assertFailsWith<IllegalStateException> {
            rejectIncompatibleProjectQueryFilters(
                ProjectQueryTool.SELECT_TRACKS,
                ProjectQueryTool.Input(
                    select = ProjectQueryTool.SELECT_TRACKS,
                    kind = "video", // wrong (assets-only)
                    toolId = "generate_image", // wrong (lockfile_entries-only)
                    onlyOrphaned = true, // wrong (transitions-only)
                ),
            )
        }
        val msg = ex.message ?: ""
        assertTrue("kind" in msg, "got: $msg")
        assertTrue("toolId" in msg, "got: $msg")
        assertTrue("onlyOrphaned" in msg, "got: $msg")
        // Pin error format: "do not apply to select='tracks':
        // <field-list>".
        assertTrue("do not apply to select='tracks'" in msg, "format; got: $msg")
    }

    @Test fun mixedValidAndInvalidFiltersOnlyComplainsAboutInvalid() {
        // Pin: a query with one valid filter (kind on assets) +
        // one invalid filter (trackId on assets) only complains
        // about trackId. The valid filter doesn't get caught up.
        val ex = assertFailsWith<IllegalStateException> {
            rejectIncompatibleProjectQueryFilters(
                ProjectQueryTool.SELECT_ASSETS,
                ProjectQueryTool.Input(
                    select = ProjectQueryTool.SELECT_ASSETS,
                    kind = "video", // valid
                    trackId = "vt", // wrong (timeline_clips-only)
                ),
            )
        }
        val msg = ex.message ?: ""
        assertTrue("trackId" in msg, "trackId reported; got: $msg")
        // kind should NOT be reported as misapplied.
        assertTrue(
            !msg.contains("kind ("),
            "kind should not be in the misapplied list; got: $msg",
        )
    }

    // ── error format ──────────────────────────────────────────────

    @Test fun errorMessagePrefixIsConsistent() {
        // Pin: every error starts with "The following filter
        // fields do not apply to select='<select>': ".
        val ex = assertFailsWith<IllegalStateException> {
            rejectIncompatibleProjectQueryFilters(
                ProjectQueryTool.SELECT_TRACKS,
                ProjectQueryTool.Input(
                    select = ProjectQueryTool.SELECT_TRACKS,
                    kind = "video",
                ),
            )
        }
        val msg = ex.message ?: ""
        assertTrue(
            msg.startsWith("The following filter fields do not apply to select='tracks': "),
            "consistent prefix; got: $msg",
        )
    }
}
