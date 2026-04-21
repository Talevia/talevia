## 2026-04-20 — `duplicate_clip` for clone-with-attached-state

**Context.** A very common editing intent is "put that clip again at a
new position, with the same look." Today the only way to do this is
`add_clip(asset, @newStart)` which mounts the asset but strips every
piece of attached state — filters, transforms, source bindings, audio
volume, fade envelope, text body, text style. To replicate the
original's look the agent has to follow up with 3-5 further tool calls
(`apply_filter`, `set_clip_transform`, `set_clip_volume`,
`fade_audio_clip`, …), each of which can fail or be forgotten. For
character-driven runs the source-binding loss is especially bad: a
clone of a Mei clip that loses `Set(meiRefNode)` will no longer be
flagged stale when Mei's reference is revised, so the two copies
drift.

**Decision.** Ship a dedicated `duplicate_clip` tool that byte-for-byte
clones every non-identity, non-start field of a clip:

- `id` is fresh (`ClipId(Uuid.random().toString())`).
- `timeRange.start` is the new timeline position.
- `timeRange.duration` is preserved.
- Everything else (Video: filters, transforms, sourceBinding, assetId,
  sourceTimeRange; Audio: volume, fadeIn/Out, sourceBinding, assetId,
  sourceTimeRange; Text: body, style, sourceBinding) is a structural
  copy.

Optional `trackId` lets the caller place the duplicate on a different
track **of the same kind** (Video→Video, Audio→Audio,
Text→Subtitle/Effect). Cross-kind moves are refused — the clip data
model can't survive the transition. Omit `trackId` to place on the
source clip's current track (the 95% case).

**Alternatives considered.**

- *Have the agent chain add_clip + apply_filter + … itself.* This is
  the status quo. It's brittle, verbose (3-5 tool calls for every
  duplicate), and most importantly, the source-binding set can't be
  recovered after the fact because `apply_filter` / `set_clip_volume`
  never touch bindings — the agent would have to re-derive them from
  the asset's origin, which is not always possible (AIGC clips' source
  binding comes from the tool that generated them, not the asset).

- *Extend `add_clip` with a `cloneFromClipId` parameter.* Rejected —
  overloads an already-parametric tool with a fundamentally different
  semantics ("mount an asset" vs "clone attached state"), and forces
  every future add_clip caller to wonder whether clone-mode changed
  some field they relied on. Clean split is cheaper.

- *Validate that the duplicate doesn't overlap existing clips on the
  target track.* Rejected — same stance as `MoveClipTool`. Overlap
  validation is a Timeline-level concern, not a per-tool concern; if
  the Timeline contract ever hardens against overlaps, both tools
  change together. Today they don't validate.

**Reasoning.** The "copy-paste with look intact" intent is exactly the
kind of shortcut that makes agent runs feel competent — one tool call
instead of five, no risk of drift, source-binding staleness still
tracks correctly across the duplicate. Cost is ~190 lines of
straightforward clone logic and 7 tests. The cross-kind refusal
covers the only real footgun: silently allowing a Video clip onto an
Audio track would make the project unrenderable.

---
