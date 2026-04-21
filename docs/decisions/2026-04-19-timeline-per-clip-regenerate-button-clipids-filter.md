## 2026-04-19 — Timeline: per-clip regenerate button + `clipIds` filter

**Context.** Round 2 shipped the bulk "Regenerate N" button on
LockfilePanel, but a user looking at a stale badge in TimelinePanel still
had to tab-switch to Lockfile to regenerate. The common "oh wait, just
this one" case forced a context switch and re-selected every stale clip.

**Decision.**
- Extend `RegenerateStaleClipsTool.Input` with
  `clipIds: List<String> = emptyList()`. Empty (default) keeps the bulk
  behaviour; non-empty filters the stale-reports list to the requested
  ids. Ids that aren't currently stale are dropped silently (fresh
  clips don't need regeneration — a noisy error would be a footgun).
- Add `onRegenerate: () -> Unit` callback to `ClipRow`, rendered only
  when the clip is stale, next to the existing "Remove" button. Wired in
  TimelinePanel to dispatch `regenerate_stale_clips` with
  `clipIds=[clip.id.value]`.
- Unit coverage: a new test stages two stale clips, filters to one,
  and asserts that only the filtered one was regenerated (engine called
  once, unlisted clip retains its original assetId).

**Alternatives considered.**
- **Call `replace_clip` directly per row.** Rejected — the whole point
  of `regenerate_stale_clips` is that it already knows how to look up
  the originating tool + re-dispatch with `baseInputs`. Duplicating
  that path in a new tool would fork the regeneration logic into two
  places.
- **New `regenerate_clip` tool with a different name.** Rejected — the
  semantics are "the same tool, narrower scope". Same permission, same
  output shape, same error modes. A flag on the existing tool keeps the
  surface area coherent and lets callers upgrade from per-clip to bulk
  by dropping the field.
- **Select-multiple then batch regen.** Rejected for this round —
  multi-select UI is a bigger lift and per-row + bulk-header cover the
  two interesting cases. Mid-scope multi-select can land later if the
  need appears.

**Why.** Mac-first priority: the stale badge now offers an inline action
at the point of observation. The §3.2 loop is reachable from both the
Timeline row and the Lockfile panel; users don't context-switch to fix
one clip.

**How to apply.** When extending other tools with per-item variants,
reuse this pattern: optional list-of-ids filter on the Input, empty
means "all", non-empty means "these if they match the eligible set".
Don't mint a parallel tool name.

---
