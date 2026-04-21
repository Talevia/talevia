## 2026-04-20 — ripple-delete on `remove_clip` (per-track, atomic)

**Context.** `remove_clip` intentionally left the gap behind when it shipped
("preserve transitions / subtitles aligned to specific timestamps"). The
system prompt told the model "if the user asks for ripple-delete
behavior, follow up with `move_clip` on each downstream clip" — which is a
smell: O(n) tool calls per delete, N separate snapshots on the undo stack,
and the agent has to remember the follow-up. Every time we paper over a
pattern in the system prompt instead of lifting it into a primitive, the
model carries the cost turn after turn.

**Decision.** Give `remove_clip` an optional `ripple: Boolean = false`
parameter. When true, after removing the target clip, shift every clip on
the **same track** whose `timeRange.start >= removed.timeRange.end` left
by `removed.timeRange.duration`. Overlapping clips (start < end) are left
alone — they were intentionally placed to overlap (PiP / layered edits),
and shifting them would destroy the overlap. Single-track ripple only;
other tracks stay put. Emits one timeline snapshot so `revert_timeline`
rolls the whole operation back in one hop.

Output gained `rippled: Boolean`, `shiftedClipCount: Int`, and
`shiftSeconds: Double` so the caller can surface a terse confirmation
("Rippled 3 clip(s) left by 2.4s").

**Alternatives considered.**

- **Sequence-wide ripple (shift every track).** Matches FCP's default
  behaviour and solves the audio-video sync case cleanly. But it also
  drifts independent tracks (background music, reference layer,
  unrelated subtitle lanes), which silently ruins the mix for any
  multi-track edit. Picked per-track (DaVinci "ripple delete" default)
  because incorrect-on-average-but-loud is worse than
  correct-on-the-common-case-with-explicit-extension. Sync-critical
  edits can follow up with `move_clip` on paired tracks — at least
  the surface is explicit.
- **A new `ripple_delete_clip` tool parallel to `remove_clip`.** More
  discoverable in the tool list but doubles the agent's surface for
  a feature that's one boolean. The system prompt update is cheaper
  and the flag keeps the cut/stitch/filter vocabulary tight.
- **Ripple subtitle clips automatically on video deletion.** Too
  clever — subtitles carry their own timing intent, and a well-timed
  caption past the gap might be meant to stay at its absolute
  timestamp. Left to the caller.
- **Shift `timeRange` AND `sourceRange`.** No — ripple changes WHEN
  the clip plays, not WHICH source bytes it reads. `sourceRange`
  stays untouched.

**Files touched.**
`core/src/commonMain/.../tool/builtin/video/RemoveClipTool.kt`
(added ripple logic + schema field + shiftStart helper),
`core/src/commonMain/.../agent/TaleviaSystemPrompt.kt` (rewrote the
"Removing clips" section to teach the ripple flag),
`core/src/jvmTest/.../tool/builtin/video/RemoveClipToolTest.kt`
(added 4 tests: ripple shifts downstream, ignores overlapping,
single-track only, ripple=false matches old behaviour).

---
