## 2026-04-19 — `MoveClipTool` — closes the ripple-delete chain

**Context.** `RemoveClipTool` shipped with system-prompt guidance saying
"if you want ripple-delete behavior, follow up with `move_clip` on each
downstream clip" — but `move_clip` did not exist. Same for the help text
on `RemoveClipTool` itself. The agent was being told to call a tool that
wasn't registered, a credibility gap that would manifest as the LLM either
hallucinating a `move_clip` call that fails at dispatch or silently dropping
the ripple-delete workflow entirely.

**Decision.** Add `move_clip(projectId, clipId, newStartSeconds)` that
repositions a clip on the timeline by id. Output reports trackId, oldStart,
newStart so the LLM can chain moves without re-reading state.

**Why same-track only.** Considered allowing a `newTrackId` parameter so
the agent could move a clip across tracks (e.g. v1 → v2). Rejected for v1:
cross-track moves change the rendering pipeline (different stack ordering
for video, different filter chains, different audio routing). The
move_clip tool would either need to validate "destination track is the
same track *type*" (refuse video → audio) or silently allow nonsense, both
of which are worse than "this tool is for shifting in time, period." When
a real cross-track driver appears, a separate `move_clip_to_track` tool
keeps the semantics distinct.

**Why preserve `sourceRange`.** A move shifts when the clip plays on the
timeline, not what material it plays. `sourceRange` (start/duration into
the source asset) is untouched — the same frames render, just at a
different timeline time. Conflating "move on timeline" with "trim source
window" would make this tool double as `trim_clip`, which deserves its
own primitive when it lands.

**Why allow overlap.** Considered refusing a move that would create
overlap with siblings. Rejected: overlapping clips on a single track are
the foundation of picture-in-picture, layered effects, and transitions
(which by definition span the boundary between two adjacent clips). The
existing `add_clip` doesn't refuse overlap; `move_clip` matches that
discipline. The agent (or user inspecting the result) is expected to
catch unintended overlaps and iterate.

**Why `newStartSeconds: Double` and not `deltaSeconds`.** Absolute time
matches how every other timeline tool addresses positions
(`atTimelineSeconds` in `split_clip`, `start` in `add_clip`). A delta API
would force the agent to read the current position before computing the
target, adding a round-trip. The agent can compute `newStart = oldStart +
delta` itself when it has the clip in hand from a prior tool result.

**Validation.**
- `newStartSeconds < 0` → IllegalStateException ("must be >= 0").
  Negative start times are nonsense on a timeline anchored at zero.
- Missing clipId → IllegalStateException naming the offending id, project
  untouched. Same fail-loud discipline as `remove_clip` / `split_clip`.

**Snapshot for revert_timeline.** Emits `Part.TimelineSnapshot`
post-mutation — same pattern as every other timeline-mutating tool. The
move can be rolled back via `revert_timeline` like any other edit.

**Sibling reordering.** After updating `timeRange.start` the track's
clips are re-sorted by start time so downstream consumers don't have to
assume sorted-or-not. Matches what `SplitClipTool` does on its rebuild.

**Coverage.** `MoveClipToolTest` (7 tests): same-track move with sibling
reordering, duration + sourceRange preservation, cross-track scoping,
overlap-allowed (no refusal), missing-clip fail-loud, negative-start
rejection, post-mutation snapshot emission for revert_timeline.

**Registration.** All four composition roots register `MoveClipTool`
next to `RemoveClipTool`. System prompt gained a "Moving clips" section
teaching duration preservation, the same-track-only constraint, and the
ripple-delete chain pattern (`oldStart - removedDuration` arithmetic).
Key phrase `move_clip` added to `TaleviaSystemPromptTest`.
