package io.talevia.core.agent.prompt

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Direct content tests for [PROMPT_PROJECT] —
 * `core/src/commonMain/kotlin/io/talevia/core/agent/prompt/PromptProject.kt:11`.
 * Cycle 286 audit: 0 prior test refs.
 *
 * Closes the prompt-content family covering all 6 sections of
 * `TALEVIA_SYSTEM_PROMPT_BASE` (cycles 281-286: PROMPT_DUAL_USER,
 * PROMPT_AIGC_LANE, PROMPT_PROJECT [this cycle], PROMPT_EDITING_LANE,
 * PROMPT_EXTERNAL_LANE, PROMPT_BUILD_SYSTEM).
 *
 * Same audit-pattern fallback as cycles 207-285. Wrap-tolerance
 * idiom (`flat` whitespace-collapsed view) banked in cycle 281.
 *
 * `PROMPT_PROJECT` is in the static base prompt (every turn).
 * Teaches per-tool semantics for **project lifecycle + snapshots +
 * lockfile observability + project_query selects + asset cleanup
 * + fork/diff/import** (the VISION §3.4 三脚: 可分支 / 可对比 /
 * 可组合). Token cost ~1000-1300/turn.
 *
 * Sections covered:
 *   - # Project lifecycle (create / list / metadata / delete / rename)
 *   - # Project snapshots (VISION §3.4 versioning across chat sessions)
 *   - Lockfile observability + cleanup (lockfile_entries / prune /
 *     gc / preserveLiveAssets safety net)
 *   - project_query(select=validation) (6 lint kinds)
 *   - project_query unified read-only selects (tracks / timeline_clips
 *     / assets) with filter+sort + limit clamp
 *   - remove_asset (referenced-clips refusal + force=true escape +
 *     bytes-not-deleted-from-shared-storage)
 *   - fork_project (VISION §3.4 可分支 leg)
 *   - diff_projects + diff_source_nodes (VISION §3.4 可对比 leg)
 *   - source_node_action(import) (VISION §3.4 可组合 leg)
 *
 * Drift signals:
 *   - **Conflate snapshot with revert_timeline** → LLM doesn't
 *     reach for snapshots at meaningful checkpoints; cross-session
 *     versioning regresses.
 *   - **Conflate prune_lockfile with gc_lockfile** → LLM misroutes
 *     orphan-cleanup vs policy-cleanup intent.
 *   - **Drop "preserveLiveAssets=true safety net"** → gc_lockfile
 *     calls drop in-use cache hits.
 *   - **Drift in remove_asset "does NOT delete bytes from shared
 *     storage" clause** → LLM expects byte-level deletion; user
 *     gets confused when AssetId reappears in another project.
 *   - **Drift in `force=true` semantic** (Unix rm -f analogy) →
 *     LLM mis-uses force, leaves dangling clips silently.
 *
 * Pins via marker-substring presence on the whitespace-flat
 * view.
 */
class PromptProjectTest {

    private val flat: String = PROMPT_PROJECT.replace(Regex("\\s+"), " ")

    // ── Section headers ─────────────────────────────────────

    @Test fun majorSectionHeadersPresent() {
        // Marquee structural pin: PROMPT_PROJECT carries
        // 2 explicit `#` headers (lifecycle + snapshots);
        // remaining sections are introduced by tool-name
        // anchors (`project_query(...)` / `prune_lockfile`
        // / etc.) without dedicated `#` headers.
        for (header in listOf(
            "# Project lifecycle",
            "# Project snapshots (VISION §3.4 — versioning across chat sessions)",
        )) {
            assertTrue(
                header in flat,
                "lane MUST contain section header '$header'",
            )
        }
    }

    // ── # Project lifecycle ─────────────────────────────────

    @Test fun createProjectDefaultsAndOverrides() {
        // Marquee pin: 1080p/30 default + resolutionPreset
        // (720p/1080p/4k) + fps (24/30/60) override surface.
        assertTrue("create_project" in flat, "MUST name create_project tool")
        assertTrue(
            "Default output is 1080p/30" in flat,
            "MUST anchor 1080p/30 default output",
        )
        assertTrue(
            "resolutionPreset` (720p/1080p/4k)" in flat ||
                "resolutionPreset (720p/1080p/4k)" in flat,
            "MUST enumerate resolutionPreset options",
        )
        assertTrue(
            "fps` (24/30/60)" in flat ||
                "fps (24/30/60)" in flat,
            "MUST enumerate fps options",
        )
    }

    @Test fun renameVsForkPreference() {
        // Marquee pin: rename_project preserves projectId;
        // prefer over fork_project for label changes (forking
        // breaks identity).
        assertTrue("rename_project" in flat, "MUST name rename_project tool")
        assertTrue(
            "the `projectId` never changes" in flat,
            "MUST anchor projectId-stability invariant on rename",
        )
        assertTrue(
            "Prefer it over `fork_project`" in flat,
            "MUST anchor rename > fork preference for label-only changes",
        )
        assertTrue(
            "forking duplicates the whole project and breaks identity" in flat,
            "MUST justify with the identity-break argument",
        )
    }

    @Test fun deleteProjectDestructiveAndOrphansSessions() {
        // Pin: delete_project is destructive + orphans
        // sessions referencing the project + warn-before-
        // invoking discipline.
        assertTrue(
            "`delete_project` is destructive" in flat,
            "MUST anchor delete_project destructive flag",
        )
        assertTrue(
            "orphans any sessions referencing the project" in flat,
            "MUST anchor session-orphaning side-effect",
        )
        assertTrue(
            "warn before invoking" in flat,
            "MUST direct toward warning before delete",
        )
    }

    @Test fun projectMetadataOrientationBeforePlanning() {
        // Marquee pin: project_query(select=project_metadata)
        // is the orientation snapshot to call before
        // planning multi-step edits. Drift would re-enable
        // LLM guessing about project state.
        assertTrue(
            "project_query(select=project_metadata)" in flat,
            "MUST name project_metadata select",
        )
        assertTrue(
            "before planning multi-step edits" in flat,
            "MUST anchor before-planning orientation usage",
        )
        assertTrue(
            "so you don't guess about what already exists" in flat,
            "MUST anchor anti-guessing rationale",
        )
    }

    // ── # Project snapshots — VISION §3.4 ───────────────────

    @Test fun snapshotPersistsAcrossSessionsVsRevertTimeline() {
        // Marquee pin: snapshot persists across chat sessions
        // + app restarts vs revert_timeline (in-session only).
        // Drift to conflate would silently lose cross-session
        // versioning capability.
        assertTrue(
            "project_action(kind=\"snapshot\", args={action=\"save\", label?})" in flat,
            "MUST document snapshot save signature",
        )
        assertTrue(
            "Unlike `revert_timeline`" in flat,
            "MUST disambiguate from revert_timeline",
        )
        assertTrue(
            "persist across chat sessions and app restarts" in flat,
            "MUST anchor cross-session persistence invariant",
        )
        for (example in listOf("final cut v1", "before re-color", "approved storyboard")) {
            assertTrue(
                example in flat,
                "MUST cite '$example' as a meaningful-checkpoint example",
            )
        }
    }

    @Test fun snapshotRestoreReversibleAndDeleteIrreversible() {
        // Marquee pin: restore is destructive but reversible
        // (preserves snapshots list); delete is irreversible.
        assertTrue(
            "action=\"restore\"" in flat,
            "MUST document restore action",
        )
        assertTrue(
            "preserves the snapshots list itself" in flat,
            "MUST anchor restore-reversible-via-snapshot-list invariant",
        )
        assertTrue(
            "Suggest saving a snapshot first" in flat,
            "MUST direct save-before-restore discipline",
        )
        assertTrue(
            "action=\"delete\"" in flat,
            "MUST document delete action",
        )
        assertTrue(
            "irreversible" in flat,
            "MUST anchor delete-is-irreversible",
        )
    }

    // ── Lockfile observability + cleanup ───────────────────

    @Test fun pruneVsGcLockfileSemantic() {
        // Marquee pin: prune sweeps orphan rows (asset
        // gone); gc sweeps by policy (age / count) regardless
        // of liveness. Drift to conflate would mis-route
        // cleanup intent.
        assertTrue("prune_lockfile" in flat, "MUST name prune_lockfile tool")
        assertTrue("gc_lockfile" in flat, "MUST name gc_lockfile tool")
        assertTrue(
            "sweeps **orphan rows**" in flat,
            "MUST anchor prune as orphan-only cleanup",
        )
        assertTrue(
            "sweeps by **policy**" in flat,
            "MUST anchor gc as policy-based cleanup",
        )
        assertTrue(
            "regardless of whether the asset is still live" in flat,
            "MUST anchor gc's liveness-blind semantic",
        )
        assertTrue(
            "ANDed together when both are set" in flat,
            "MUST anchor that maxAgeDays + keepLatestPerTool combine via AND",
        )
    }

    @Test fun gcLockfilePoliciesAndDryRun() {
        // Pin: gc_lockfile policy fields (maxAgeDays /
        // keepLatestPerTool) + preserveLiveAssets=true
        // safety net + dryRun=true preview.
        assertTrue(
            "maxAgeDays=30" in flat,
            "MUST cite maxAgeDays example",
        )
        assertTrue(
            "keepLatestPerTool=20" in flat,
            "MUST cite keepLatestPerTool example",
        )
        assertTrue(
            "preserveLiveAssets=true` (default) is the safety net" in flat,
            "MUST anchor preserveLiveAssets=true safety net",
        )
        assertTrue(
            "in-use cache hits survive the sweep" in flat,
            "MUST anchor cache-hit survival invariant",
        )
        assertTrue(
            "Pass `dryRun=true`" in flat,
            "MUST document dryRun preview affordance",
        )
    }

    // ── # project_query(select=validation) ──────────────────

    @Test fun validationLintsCoverAllSixInvariants() {
        // Marquee pin: 6 lint kinds enumerated in the
        // validation select. Drift to drop one would silently
        // remove a structural-invariant check at export time.
        assertTrue(
            "project_query(select=validation)" in flat,
            "MUST name validation select",
        )
        for (lint in listOf(
            "dangling `assetId`",
            "dangling `sourceBinding`",
            "non-positive clip duration",
            "audio `volume` outside `[0, 4]`",
            "negative fade",
            "fade-in + fade-out exceeding clip duration",
        )) {
            assertTrue(
                lint in flat,
                "validation MUST enumerate '$lint' lint",
            )
        }
        assertTrue(
            "`timeline.duration` behind the latest clip end" in flat,
            "MUST enumerate the timeline-duration-behind lint (7th)",
        )
    }

    @Test fun validationRowShapeAndPassedFlag() {
        // Pin: row shape (severity / code / trackId / clipId /
        // message) + passed flag truth condition.
        assertTrue(
            "`severity` (`error`/`warn`)" in flat,
            "MUST document severity field",
        )
        assertTrue(
            "machine `code`" in flat,
            "MUST document machine code field",
        )
        assertTrue(
            "passed: Boolean` is true iff `errorCount == 0`" in flat,
            "MUST anchor passed flag's iff-error-count-zero invariant",
        )
        assertTrue(
            "warnings are informational" in flat,
            "MUST anchor warning-is-informational semantic (not blocking)",
        )
    }

    @Test fun validationDoesNotCoverStaleness() {
        // Marquee delimiter pin: validation is structural-
        // invariants; staleness is content-hash drift.
        // Different concerns, paired tools.
        assertTrue(
            "does NOT cover staleness" in flat ||
                "It does NOT cover staleness" in flat,
            "MUST anchor that validation does NOT cover staleness",
        )
        assertTrue(
            "pair with `project_query(select=stale_clips)`" in flat,
            "MUST direct toward stale_clips for content-hash drift",
        )
    }

    // ── # project_query unified — 3 selects + controls ─────

    @Test fun threeUnifiedSelectsAllListed() {
        // Marquee pin: 3 selects (tracks / timeline_clips /
        // assets) all enumerated with their distinguishing
        // fields. Drift to drop one silently makes that
        // select invisible at planning time.
        for (select in listOf(
            "project_query(select=tracks)",
            "project_query(select=timeline_clips)",
            "project_query(select=assets)",
        )) {
            assertTrue(
                select in flat,
                "MUST enumerate '$select'",
            )
        }
        // Distinguishing fields per select:
        assertTrue(
            "trackKind" in flat &&
                "clipCount" in flat,
            "tracks select MUST surface trackKind / clipCount",
        )
        assertTrue(
            "textPreview" in flat,
            "timeline_clips select MUST surface 80-char textPreview",
        )
        assertTrue(
            "inUseByClips" in flat,
            "assets select MUST surface inUseByClips count",
        )
    }

    @Test fun unifiedSelectControlsAndFailLoudOnInvalidFilter() {
        // Marquee pin: limit default 100 clamped [1, 500] +
        // offset default 0 + invalid filter fails loud.
        assertTrue(
            "limit` (default 100, clamped to `[1, 500]`)" in flat,
            "MUST document limit default + clamp",
        )
        assertTrue(
            "offset` (default 0)" in flat,
            "MUST document offset default",
        )
        assertTrue(
            "Setting a filter that doesn't apply to the chosen select fails loud" in flat,
            "MUST anchor fail-loud-on-typo discipline (not silent empty result)",
        )
        assertTrue(
            "rows` (an array whose shape matches" in flat,
            "MUST anchor rows-array-shape-matches-select invariant",
        )
    }

    // ── # remove_asset ──────────────────────────────────────

    @Test fun removeAssetReferenceSafeAndForceEscape() {
        // Marquee pin: refuses-when-referenced safety + force=true
        // Unix-rm-f analogy + does-NOT-delete-bytes from shared
        // storage clause.
        assertTrue("remove_asset" in flat, "MUST name remove_asset tool")
        assertTrue(
            "Safe by default: refuses when any clip still references the asset" in flat,
            "MUST anchor reference-safety default",
        )
        assertTrue(
            "returns the dependent clipIds in the error" in flat,
            "MUST anchor that error includes dependent clipIds",
        )
        assertTrue(
            "Pass `force=true` to remove anyway" in flat,
            "MUST document force=true escape",
        )
        assertTrue(
            "Unix `rm -f`" in flat,
            "MUST anchor Unix rm-f mental model for force",
        )
        assertTrue(
            "Does **not** delete bytes from shared media storage" in flat,
            "MUST anchor bytes-not-deleted invariant",
        )
        assertTrue(
            "may live in snapshots or other projects" in flat,
            "MUST justify with cross-project asset sharing",
        )
    }

    // ── # fork_project — VISION §3.4 可分支 ─────────────────

    @Test fun forkProjectSharesAssetBytesNotDuplicates() {
        // Marquee pin: fork shares asset bytes (not
        // duplicates) + closes VISION §3.4 可分支 leg.
        assertTrue("fork_project" in flat, "MUST name fork_project tool")
        assertTrue(
            "可分支" in flat,
            "MUST anchor VISION §3.4 可分支 leg in CJK",
        )
        assertTrue(
            "Asset bytes are shared, not duplicated" in flat,
            "MUST anchor asset-bytes-shared invariant",
        )
        assertTrue(
            "fresh id" in flat,
            "MUST anchor fresh projectId on fork",
        )
        assertTrue(
            "empty snapshots list" in flat,
            "MUST anchor empty-snapshots-on-fork (snapshots don't carry over)",
        )
        assertTrue(
            "what-if" in flat,
            "MUST cite the canonical 'what-if' use case",
        )
    }

    // ── # diff_projects + diff_source_nodes — 可对比 ────────

    @Test fun diffProjectsThreePayloadCombinations() {
        // Pin: diff_projects supports 3 payload combinations
        // (snapshot vs snapshot / snapshot vs current / fork
        // vs parent).
        assertTrue("diff_projects" in flat, "MUST name diff_projects tool")
        assertTrue(
            "snapshot vs snapshot, snapshot vs current state, or fork vs parent" in flat,
            "MUST enumerate the 3 payload combinations",
        )
        assertTrue(
            "tracks/clips added/removed/changed" in flat,
            "MUST document timeline-side change reporting",
        )
        assertTrue(
            "Detail lists are capped; counts are exact" in flat,
            "MUST anchor that detail caps don't lose count accuracy",
        )
    }

    @Test fun diffSourceNodesMissingNodeReporting() {
        // Pin: diff_source_nodes reports missing nodes via
        // leftExists / rightExists / bothExist instead of
        // failing — drift to fail-on-missing would re-enable
        // post-rename audit blocks.
        assertTrue("diff_source_nodes" in flat, "MUST name diff_source_nodes tool")
        assertTrue(
            "kind change, contentHash change" in flat,
            "MUST enumerate the change axes (kind / contentHash)",
        )
        assertTrue(
            "per-field JSON body deltas (dotted path + left/right values)" in flat,
            "MUST anchor per-field JSON delta shape",
        )
        assertTrue(
            "leftExists`" in flat &&
                "rightExists`" in flat &&
                "bothExist`" in flat,
            "MUST enumerate the 3 existence flags",
        )
        assertTrue(
            "instead of failing" in flat,
            "MUST anchor non-failing semantic on missing nodes",
        )
    }

    // ── # source_node_action(import) — 可组合 ──────────────

    @Test fun importSourceNodeTwoInputShapesAndIdempotency() {
        // Marquee pin: live cross-project (fromProjectId +
        // fromNodeId) + portable envelope shapes;
        // contentHash idempotent; cache hits transfer
        // because keys are content-addressed.
        assertTrue(
            "source_node_action(action=\"import\")" in flat,
            "MUST name the import action",
        )
        assertTrue(
            "可组合" in flat,
            "MUST anchor VISION §3.4 可组合 leg in CJK",
        )
        assertTrue(
            "fromProjectId` + `fromNodeId" in flat,
            "MUST document live cross-project input shape",
        )
        assertTrue(
            "envelope`) ingests an `export_source_node` JSON envelope" in flat,
            "MUST document portable-envelope input shape",
        )
        assertTrue(
            "Idempotent on contentHash" in flat,
            "MUST anchor contentHash-idempotency invariant",
        )
        assertTrue(
            "AIGC lockfile cache hits transfer across projects automatically" in flat,
            "MUST anchor cross-project cache-hit transfer",
        )
        assertTrue(
            "cache keys are content-addressed" in flat,
            "MUST justify with content-addressed cache key invariant",
        )
        assertTrue(
            "newNodeId` only when the original id collides" in flat,
            "MUST document newNodeId collision-resolution use",
        )
    }

    // ── Cross-section invariants ────────────────────────────

    @Test fun threeVisionThreePointFourLegsAllNamed() {
        // Marquee pin: VISION §3.4 三脚 (可分支 / 可对比 /
        // 可组合) all surface in the lane. Drift to drop one
        // would weaken the cross-project capability story.
        assertTrue(
            "可分支" in flat,
            "MUST anchor 可分支 leg (fork)",
        )
        assertTrue(
            "可组合" in flat,
            "MUST anchor 可组合 leg (import)",
        )
        // 可对比 is mentioned obliquely via diff_projects
        // semantics rather than verbatim — pin presence of
        // diff verbs as proxy.
        assertTrue(
            "diff_projects" in flat &&
                "diff_source_nodes" in flat,
            "MUST surface 可对比 leg via diff_projects + diff_source_nodes verbs",
        )
        assertTrue(
            "VISION §3.4" in flat,
            "MUST anchor VISION §3.4 reference",
        )
    }

    @Test fun lengthIsBoundedAndMeaningful() {
        // Pin: PROMPT_PROJECT is the second-largest single
        // base-prompt asset (rivals BUILD_SYSTEM /
        // EDITING_LANE for size). 1000-1300 tokens / ~5000-
        // 12000 chars.
        val s = PROMPT_PROJECT
        assertTrue(
            s.length > 5000,
            "lane content MUST be > 5000 chars; got: ${s.length}",
        )
        assertTrue(
            s.length < 12_000,
            "lane content MUST be < 12000 chars; got: ${s.length}",
        )
    }

    @Test fun laneIsTrimmedNoLeadingOrTrailingBlankLines() {
        val s = PROMPT_PROJECT
        assertTrue(
            s == s.trim(),
            "lane MUST be trimmed (no leading/trailing whitespace)",
        )
    }
}
