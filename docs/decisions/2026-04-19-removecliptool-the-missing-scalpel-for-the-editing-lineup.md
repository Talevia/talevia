## 2026-04-19 — `RemoveClipTool` — the missing scalpel for the editing lineup

**Context.** The cut/stitch/filter/transition lineup the agent uses to *edit*
(VISION §1) had a gaping hole: the agent could `add_clip`, `replace_clip`,
`split_clip`, `apply_filter`, `apply_lut`, `add_transition`, `add_subtitle` —
but had **no way to delete a clip**. The only workaround was
`revert_timeline` to a prior snapshot, which is a bulldozer (it discards
every later edit too) where a scalpel was needed. A user saying "drop the
second take" had no clean execution path.

**Decision.** Add a new `remove_clip(projectId, clipId)` tool that finds a
clip by id across all tracks and removes it. Output reports the trackId it
came from and remaining clip count on that track.

**Why no ripple-delete.** Considered shifting downstream clips' `timeRange`s
left by the gap size. Rejected: timestamps in the timeline are addressed by
absolute time across tracks. Subtitles at 00:14, transitions between v1[02]
and v1[03], audio fades at 00:08 — all of those are positioned by absolute
timeline time. Ripple-deleting one video clip would silently invalidate
transitions / subtitles / audio cues whose authors were targeting wall-clock
positions on the timeline, not relative offsets within a track. The
NLE-style "ripple delete" trade-off is real (a casual editor *expects* the
gap to close), but the cost of silent corruption to other tracks is higher.
If the user wants ripple behavior, the agent can chain `move_clip` for each
downstream clip — explicit two-step instead of magic side effect.

**Why preserve the empty track.** When a track loses its last clip, the
track itself is left in place rather than auto-removed. Reason: subsequent
`add_clip(trackId=…)` calls need a target. The agent often deletes the
single placeholder clip on a fresh track and immediately adds a real one;
forcing it to recreate the track would add a round-trip and a chance to
pick the wrong track id.

**Why timeline.write permission.** Same scope as add_clip / split_clip /
replace_clip. Removing a clip is symmetric with adding one — a routine
mutation, not a project-level destructive op like `delete_project`.

**Snapshot for revert_timeline.** Emits `Part.TimelineSnapshot` post-mutation
via the shared `emitTimelineSnapshot` helper. So `revert_timeline` can roll
the deletion back — no data is permanently lost from the agent's POV. This
is the same pattern every other timeline-mutating tool uses; consistency
matters more than micro-optimizing snapshot frequency.

**Failure mode.** Missing clipId fails loudly with `IllegalStateException`
naming the offending id, and the project is left untouched. We don't
silently no-op because the LLM should learn from the error and find the
right id rather than silently moving on assuming the delete succeeded.

**Coverage.** `RemoveClipToolTest` (6 tests): named-clip removal with
sibling preservation and no-shift assertion, cross-track scoping
(audio track untouched when video clip removed), empty-track
preservation, audio-clip removal from audio track, missing-clip
fail-loud, post-mutation snapshot emission for revert_timeline.

**Registration.** All four composition roots (server, desktop, Android,
iOS Swift) register `RemoveClipTool` next to `SplitClipTool`. System
prompt gained a "Removing clips" section teaching when to use it (drop a
clip vs revert) and the no-ripple semantics. Key phrase `remove_clip`
added to `TaleviaSystemPromptTest`.

---
