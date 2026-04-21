## 2026-04-19 — `trim_clip` — re-trim existing clips without losing bound state

**Context.** The agent could add, split, remove, move, and replace a clip,
but had no way to *re-trim* one after creation. To shorten or re-anchor a
clip's in-point into the source media the agent had to `remove_clip` +
`add_clip` — which regenerates a new `ClipId`, breaking any
`consistencyBinding` pins, filter attachments, or downstream refs that
keyed off the original id. A video editor without trim isn't a video
editor.

**Decision.** Integrate the pre-existing `TrimClipTool` in
`core.tool.builtin.video` (tool id `trim_clip`, permission
`timeline.write`) and wire it into all four composition roots. The tool
adjusts `sourceRange` and/or duration in place while preserving the
clip's `ClipId` and its `timeRange.start`. Either input field may be
omitted to keep current; at least one must be set (rejects the no-op
call explicitly).

**Vocabulary choice: absolute values, not deltas.** Matches `add_clip` /
`split_clip` / `move_clip`. Absolute means the agent doesn't need to
`get_project_state` first just to compute `current + delta`. Fewer round
trips, fewer off-by-one bugs, same final edit.

**Timeline anchor preserved.** `timeRange.start` is explicitly NOT
modified — trim adjusts the source window, not the timeline position.
If the user wants to both retrim and reposition, they chain `move_clip`.
Coupling the two would make "shrink this clip" ambiguous ("do I want it
to stay put, or do I want everything after it to shift?").

**Duration applies to both ranges.** A v1 clip plays at 1× speed; no
speed-ramp model yet. So `newDurationSeconds` becomes both
`timeRange.duration` and `sourceRange.duration`. When speed ramps land
we can split this into two fields or add `speedRatio`.

**Text clip rejection.** `Clip.Text` has no `sourceRange` — its text is
embedded. Rather than silently ignore the trim or hallucinate a
subtitle-reset, the tool fails loudly with "use add_subtitle to reset".
Clear error > footgun.

**Asset-bound guard.** Trims that would extend past the bound asset's
duration are rejected before mutation. The media lookup happens inside
`ProjectStore.mutate` so it's atomic with the trim write — no race where
the asset could be removed between the check and the commit.

**Coverage.** `TrimClipToolTest` (11 tests): tail shrink preserving
`timeRange.start`, head trim preserving timeline anchor, simultaneous
head+tail, audio-clip parity (and preservation of `Clip.Audio.volume`),
text-clip rejection, both-fields-omitted rejection, asset-duration
guard, negative `newSourceStartSeconds` rejection, zero-duration
rejection, missing-clip failure (project state untouched on failure),
and post-mutation snapshot emission for `revert_timeline`.

**Registration.** Desktop, server, Android, iOS containers all register
`TrimClipTool(projects, media)` — media resolver is required for the
asset-duration guard. System prompt gained a "# Trimming clips" section
teaching the absolute-values vocabulary, the `timeRange.start`
preservation invariant, the `move_clip` chain pattern for reposition,
the text-clip rejection, and the asset-bounds guard. Key phrase
`trim_clip` added to `TaleviaSystemPromptTest` so removal regresses
loudly.
