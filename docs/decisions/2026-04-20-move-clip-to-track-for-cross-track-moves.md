## 2026-04-20 — `move_clip_to_track` for cross-track moves

**Context.** [MoveClipTool] was documented as same-track only, with
its kdoc explicitly saying: "cross-track moves change the rendering
semantics (different track stack ordering, different filter
pipeline) and deserve their own tool when a real driver appears."
Real drivers have appeared — VISION §3 expects layered editing
(PiP, separate dialogue/music tracks, subtitle priority). Users
hitting these asked the agent and got stuck because there was no
tool. Only escape: delete and re-add, which loses filters /
transforms / consistencyBindings attached to the clip id.

**Decision.** Ship `move_clip_to_track(projectId, clipId,
targetTrackId, newStartSeconds?)` that:

1. Finds the clip and its current track.
2. Validates the target track exists and is **kind-compatible**:
   - `Clip.Video` → `Track.Video`
   - `Clip.Audio` → `Track.Audio`
   - `Clip.Text`  → `Track.Subtitle`
3. Refuses if the target is the source track — points to
   `move_clip`. Silent success here would hide that the agent
   probably had a different intent.
4. Moves the clip over, optionally shifting its `timeRange.start`
   via `newStartSeconds`; omitted = keep current start. Duration,
   sourceRange, filters, transforms, binding — all preserved.
5. Recomputes timeline duration from the new track layout (since
   moving a clip can change the max end time on its old track).
6. Emits a timeline snapshot so `revert_timeline` can roll the
   move back.

Permission `timeline.write` — same tier as `move_clip`. Not a
destructive operation (nothing is lost; revert_timeline covers
undo).

`Track.Effect` is not a valid target in either direction. The
Effect clip type isn't pinned yet — when someone needs to move
clips onto effect lanes, they'll flesh out the type first. Refusing
early keeps the contract honest.

**Alternatives considered.**

- *Fold into `move_clip` as an optional `targetTrackId`.*
  Tempting for surface minimalism, but conflates two distinct
  operations — same-track move is idiomatic and frequent; cross-
  track move is rarer and deserves its own error shape
  (kind-mismatch, target-not-found, same-track-use-move_clip). A
  single tool blurring the two loses both sets of actionable
  errors. Also, the agent's mental model benefits from the split:
  "which tool?" answers the "same or different track?" question
  immediately.
- *Auto-create the target track if it doesn't exist.* No — would
  let the agent create a track explosion without realizing it.
  Track creation remains implicit inside `add_clip` where the
  intent ("place a clip here") explicitly triggers track creation;
  a pure move should not.
- *Silently succeed when target == source.* Rejected: almost
  certainly indicates the agent meant something else. Failing loud
  points them at `move_clip`.
- *Accept kind-mismatch and coerce.* E.g. extract audio from a
  video clip when moving onto an audio track. Two operations
  entangled; out of scope. User who wants that writes an explicit
  "extract audio" tool call (not yet built, but that's the shape).

**Reasoning.** Closes the exact gap `MoveClipTool.kdoc` flagged,
keeps the error surface actionable (three distinct refusals help
the agent pick the next move), and doesn't overload an existing
tool's contract. Same-kind constraint matches the Track sealed
hierarchy's purpose — each track kind has its own render
semantics, and mixing kinds would require rewriting the clip to a
different sealed variant (a different operation).

**Coverage.** `MoveClipToTrackToolTest` — ten cases: move video
between video tracks, move with shift (audio), move text between
subtitle tracks, refuse same-track move (points at `move_clip`),
refuse video→audio kind mismatch (clip stays in place), refuse
audio→subtitle kind mismatch, reject missing target track, reject
missing clip, reject negative newStartSeconds, preserve sourceRange
and assetId after move.

**Registration.** All five composition roots register
`MoveClipToTrackTool(projects)` directly after `MoveClipTool`.
Prompt gained a paragraph under `# Moving clips` teaching the
same-track vs. cross-track split and the kind-compatibility
matrix.

---
