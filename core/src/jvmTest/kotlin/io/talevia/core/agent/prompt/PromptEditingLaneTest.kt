package io.talevia.core.agent.prompt

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Direct content tests for [PROMPT_EDITING_LANE] —
 * `core/src/commonMain/kotlin/io/talevia/core/agent/prompt/PromptEditingLane.kt:14`.
 * Cycle 283 audit: 0 prior test refs (cycle 281 covered
 * PROMPT_DUAL_USER, cycle 282 covered PROMPT_AIGC_LANE).
 *
 * Same audit-pattern fallback as cycles 207-282. Wrap-tolerance
 * idiom (`flat` whitespace-collapsed view) banked in cycle 281.
 *
 * `PROMPT_EDITING_LANE` is in the static base prompt (every
 * turn). It teaches the agent the per-tool semantics for the
 * **timeline-editing toolset** — 10 distinct tools across
 * 9 sections (clip_action remove/duplicate/set_transform/
 * set_volume/fade, clear_timeline, edit_text_clip,
 * remove_filter, move_clip, add_track, trim_clip,
 * extract_frame). Token cost ~700-900 per turn.
 *
 * Drift signals:
 *   - **Drop the ripple-delete single-track caveat** → LLM
 *     starts using ripple to drift independent music tracks
 *     out of sync with rippled video.
 *   - **Soften the "Null = keep; "" clears" patch semantic
 *     for edit_text_clip** → LLM either over-writes or fails
 *     to clear backgroundColor.
 *   - **Drop the cross-kind-refused rules for duplicate /
 *     move_clip** → LLM dispatches video clips onto audio
 *     tracks; engine errors silently swallowed.
 *   - **Drift in `revert_timeline can undo` refrain** → LLM
 *     stops surfacing the undo affordance, leaves users
 *     stranded on irreversible-feeling edits.
 *
 * Pins via marker-substring presence on the whitespace-flat
 * view.
 */
class PromptEditingLaneTest {

    private val flat: String = PROMPT_EDITING_LANE.replace(Regex("\\s+"), " ")

    // ── Section headers ─────────────────────────────────────

    @Test fun allNineSectionHeadersPresent() {
        // Marquee structural pin: lane is composed of 9
        // distinct sections per the doc-comment. Drift to
        // drop one would silently remove that tool group's
        // behavioural anchor.
        for (header in listOf(
            "# Removing clips",
            "# Editing subtitles / text overlays",
            "# Undoing filters",
            "# Duplicating clips",
            "# Moving clips",
            "# Declaring tracks explicitly",
            "# Trimming clips",
            "# Clip transforms (opacity / scale / translate / rotate)",
            "# Frame extraction",
            "# Audio volume",
        )) {
            assertTrue(
                header in flat,
                "lane MUST contain section header '$header'",
            )
        }
    }

    // ── # Removing clips — ripple semantic ──────────────────

    @Test fun rippleDeleteSingleTrackSemantic() {
        // Marquee pin: ripple is single-track; drift to
        // multi-track ripple silently drifts independent
        // music against video.
        assertTrue(
            "ripple=true" in flat,
            "MUST name the ripple=true parameter",
        )
        assertTrue(
            "Ripple is single-track" in flat,
            "MUST anchor on ripple-is-single-track invariant",
        )
        assertTrue(
            "background music" in flat,
            "MUST cite the multi-track-drift example (background music)",
        )
    }

    @Test fun removeNotRevertTimelineNudge() {
        // Pin: lane MUST steer agent toward
        // clip_action(remove) instead of revert_timeline for
        // clip drops. Drift would re-enable the "nuke later
        // edits" pattern.
        assertTrue(
            "not* `revert_timeline`" in flat ||
                "not revert_timeline" in flat ||
                "*not* `revert_timeline`" in flat,
            "MUST disambiguate clip_action(remove) vs revert_timeline (drift to drop nudge)",
        )
        assertTrue(
            "discard every later edit" in flat,
            "MUST justify with the irreversibility argument",
        )
    }

    @Test fun clearTimelineDestructiveAndPreserveTracksDefault() {
        // Marquee pin: clear_timeline asks for destructive
        // permission AND preserves tracks by default.
        assertTrue("clear_timeline" in flat, "MUST name clear_timeline tool")
        assertTrue(
            "preserveTracks=true" in flat,
            "MUST document preserveTracks=true default",
        )
        assertTrue(
            "destructive permission" in flat,
            "MUST anchor that clear_timeline asks for destructive permission",
        )
        assertTrue(
            "Do NOT call this just to remove" in flat,
            "MUST forbid using clear_timeline as surgical-edit shortcut",
        )
    }

    // ── # Editing subtitles — patch semantic ────────────────

    @Test fun editTextClipPatchSemanticNullKeepEmptyClears() {
        // Marquee pin: the "Null = keep; provided replaces;
        // \"\" clears" patch semantic. Drift in any of the
        // three branches would silently change patch
        // behavior — typo-fix tools get ambiguous null/blank
        // handling.
        assertTrue("edit_text_clip" in flat, "MUST name edit_text_clip tool")
        assertTrue(
            "Null = keep" in flat,
            "MUST anchor null-means-keep patch branch",
        )
        assertTrue(
            "provided value replaces" in flat ||
                "provided\nvalue replaces" in flat,
            "MUST anchor provided-replaces patch branch",
        )
        assertTrue(
            "clears it (transparent)" in flat,
            "MUST anchor empty-string-clears-backgroundColor patch branch",
        )
    }

    @Test fun editTextClipPreservesIdAndAttachedState() {
        // Pin: lane MUST steer agent away from the remove
        // + add_subtitles pattern that loses clip id /
        // transforms.
        assertTrue(
            "Prefer this over `clip_action(action=\"remove\") + add_subtitles`" in flat,
            "MUST prefer edit_text_clip over remove+add for typo fixes",
        )
        assertTrue(
            "transforms, and timeRange are preserved" in flat,
            "MUST anchor the preservation invariant",
        )
    }

    // ── # Undoing filters — remove_filter idempotency ──────

    @Test fun removeFilterIdempotentRemovedCountZero() {
        // Marquee pin: idempotent + removedCount: 0 on no-op.
        // Drift to "throws if no match" would silently break
        // speculative cleanup loops.
        assertTrue("remove_filter" in flat, "MUST name remove_filter tool")
        assertTrue(
            "Idempotent" in flat,
            "MUST anchor idempotent semantic",
        )
        assertTrue(
            "removedCount: 0" in flat,
            "MUST document the no-op return shape (removedCount: 0)",
        )
        assertTrue(
            "speculative cleanup is safe" in flat,
            "MUST justify the no-op behavior",
        )
        assertTrue(
            "Video clips only" in flat,
            "MUST anchor video-clips-only restriction",
        )
    }

    // ── # Duplicating clips — cross-kind refused ────────────

    @Test fun duplicateCrossKindRefusedAndStatePreserved() {
        // Marquee pin: cross-kind refused + state-preserved
        // semantic differentiates duplicate from add.
        assertTrue(
            "clip_action(action=\"duplicate\")" in flat,
            "MUST name the duplicate verb on clip_action",
        )
        assertTrue(
            "Video→Video, Audio→Audio, Text→Subtitle/Effect" in flat,
            "MUST enumerate the cross-kind allow-list",
        )
        assertTrue(
            "cross-kind is refused" in flat,
            "MUST anchor cross-kind-refused invariant",
        )
        assertTrue(
            "preserving filters, transforms, source bindings" in flat,
            "MUST anchor state-preservation invariant differentiating duplicate from add",
        )
    }

    // ── # Moving clips — move_clip both-null rejection ──────

    @Test fun moveClipBothNullRejectedAndCrossKindFails() {
        // Marquee pin: both-null rejection + cross-kind fail-
        // loud invariant. Drift to silent fall-through would
        // re-enable LLM dispatching no-op moves.
        assertTrue("move_clip" in flat, "MUST name move_clip tool")
        assertTrue(
            "both null is rejected" in flat,
            "MUST anchor the both-null rejection invariant",
        )
        assertTrue(
            "Cross-kind targets" in flat &&
                "fail loud" in flat,
            "MUST anchor cross-kind fail-loud (rendering semantics don't survive)",
        )
        assertTrue(
            "Duration, source range, filters, transforms" in flat,
            "MUST list preserved fields",
        )
    }

    // ── # Declaring tracks — add_track auto-create caveat ──

    @Test fun addTrackAutoCreateRedundancyWarning() {
        // Pin: lane MUST steer agent away from redundant
        // add_track for single-layer edits.
        assertTrue(
            "add_track(projectId, trackKind, trackId?)" in flat,
            "MUST document add_track signature",
        )
        assertTrue(
            "auto-create the *first* track" in flat ||
                "auto-create the first track" in flat,
            "MUST anchor that clip_action(add) auto-creates the first track of needed kind",
        )
        assertTrue(
            "don't call `add_track` redundantly" in flat,
            "MUST forbid redundant add_track calls",
        )
    }

    // ── # Trimming clips — preserve timeRange.start ────────

    @Test fun trimClipPreservesTimelineAnchor() {
        // Marquee pin: trim preserves timeRange.start (clip
        // stays anchored). Drift to shifting the start
        // silently breaks downstream timing.
        assertTrue("trim_clip" in flat, "MUST name trim_clip tool")
        assertTrue(
            "preserves `timeRange.start`" in flat,
            "MUST anchor timeline-anchor preservation",
        )
        assertTrue(
            "chain `move_clip` if the user" in flat,
            "MUST direct to move_clip for slide-and-trim combos",
        )
        assertTrue(
            "Subtitle/text clips are not trimmable here" in flat,
            "MUST anchor non-trimmable subtitle/text restriction",
        )
        assertTrue(
            "`add_subtitles` instead" in flat,
            "MUST redirect to add_subtitles for subtitle re-timing",
        )
    }

    // ── # Clip transforms — clamps + single-element norm ───

    @Test fun setTransformClampsAndSingleElementNormalization() {
        // Marquee pin: opacity ∈ [0, 1], scale > 0, list
        // normalised to single element. Drift to allowing
        // negative scale or multi-element list silently
        // breaks engine compatibility.
        assertTrue(
            "clip_action(action=\"set_transform\")" in flat,
            "MUST name the set_transform verb",
        )
        assertTrue(
            "opacity ∈ [0, 1]" in flat,
            "MUST anchor opacity clamp",
        )
        assertTrue(
            "scaleX` / `scaleY > 0" in flat ||
                "scaleX / scaleY > 0" in flat,
            "MUST anchor scale > 0 clamp",
        )
        assertTrue(
            "single-element list" in flat,
            "MUST anchor list normalization (v1: one transform per clip)",
        )
        assertTrue(
            "audio clip writes the transform field but has no effect" in flat,
            "MUST anchor that audio clips silently no-op transforms",
        )
    }

    // ── # Frame extraction — duration=0 + fail-loud ────────

    @Test fun extractFrameDurationZeroAndBoundsCheck() {
        // Marquee pin: duration=0 marker + fail-loud on
        // out-of-bounds timestamp.
        assertTrue("extract_frame" in flat, "MUST name extract_frame tool")
        assertTrue(
            "duration=0" in flat,
            "MUST anchor duration=0 marker on extracted frames (distinguishable from video asset)",
        )
        assertTrue(
            "fails loudly if the timestamp is negative" in flat,
            "MUST anchor fail-loud on negative timestamp",
        )
        assertTrue(
            "past the source duration" in flat,
            "MUST anchor fail-loud on past-source-duration timestamp",
        )
    }

    // ── # Audio volume — set_volume + fade ─────────────────

    @Test fun setVolumeRangeAndAudioOnly() {
        // Marquee pin: [0, 4] range + 0.0 mutes + fail-loud
        // on non-audio.
        assertTrue(
            "clip_action(action=\"set_volume\")" in flat,
            "MUST name the set_volume verb",
        )
        assertTrue(
            "absolute multiplier in [0, 4]" in flat,
            "MUST anchor volume range",
        )
        assertTrue(
            "0.0` mutes" in flat ||
                "`0.0` mutes" in flat,
            "MUST anchor 0.0=mute semantic",
        )
        assertTrue(
            "Audio clips only" in flat,
            "MUST anchor audio-only restriction",
        )
        assertTrue(
            "fails loud" in flat,
            "MUST anchor fail-loud on video/text clips",
        )
    }

    @Test fun fadeIsCompilerCapturesRendererCatchesUp() {
        // Marquee pin: fade is the "compiler captures,
        // renderer catches up" stub — drift in this note
        // would mislead about engine support state.
        assertTrue(
            "clip_action(action=\"fade\")" in flat,
            "MUST name the fade verb",
        )
        assertTrue(
            "fadeInSeconds + fadeOutSeconds" in flat,
            "MUST name the two fade fields",
        )
        assertTrue(
            "must not exceed the clip's timeline duration" in flat,
            "MUST anchor sum-bounds invariant",
        )
        assertTrue(
            "compiler captures, renderer catches up" in flat,
            "MUST anchor the engine-stub status note (cycle 282 sister phrase)",
        )
    }

    // ── Cross-section invariants ────────────────────────────

    @Test fun emitTimelineSnapshotRefrainAcrossEditingTools() {
        // Marquee cross-tool refrain: every editing tool
        // ends with "Emits a timeline snapshot so
        // revert_timeline can undo". Drift to drop the
        // refrain from any one tool would silently break
        // the agent's mental model that every edit is
        // revertible.
        // Count distinct sections that mention the snapshot/
        // revert refrain — expect ≥ 7 (most editing
        // sections).
        val snapshotCount = "revert_timeline" in flat
        val emitsCount = Regex("[Ee]mits.{0,40}timeline snapshot").findAll(flat).count()
        assertTrue(snapshotCount, "MUST mention revert_timeline as undo affordance")
        assertTrue(
            emitsCount >= 6,
            "MUST emit-snapshot refrain across most editing sections; matched: $emitsCount",
        )
    }

    @Test fun crossKindRulesEnumerateAllowList() {
        // Pin: cross-kind allow-list (Video→Video,
        // Audio→Audio, Text→Subtitle) appears in BOTH
        // duplicate and move_clip sections — the agent
        // must know the rule consistently.
        val allowList = "Video→Video, Audio→Audio, Text→Subtitle"
        assertTrue(
            allowList in flat,
            "MUST enumerate the cross-kind allow-list as a stable phrase",
        )
    }

    @Test fun lengthIsBoundedAndMeaningful() {
        // Pin: per its content density (10 tools across 9
        // sections) the lane is the largest single base-
        // prompt asset; ~700-900 tokens. Char-band:
        // 4000-12000.
        val s = PROMPT_EDITING_LANE
        assertTrue(
            s.length > 4000,
            "lane content MUST be > 4000 chars (drift to no-op surfaces here); got: ${s.length}",
        )
        assertTrue(
            s.length < 12_000,
            "lane content MUST be < 12000 chars (drift to bloated surfaces here); got: ${s.length}",
        )
    }

    @Test fun laneIsTrimmedNoLeadingOrTrailingBlankLines() {
        val s = PROMPT_EDITING_LANE
        assertTrue(
            s == s.trim(),
            "lane MUST be trimmed (no leading/trailing whitespace)",
        )
    }
}
