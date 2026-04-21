## 2026-04-19 — `replace_clip` tool (VISION §3.2 — regenerate-after-stale)

**Context.** With `find_stale_clips` (this morning's commit) the agent can answer
"what needs regenerating?", and `generate_image` produces the new asset. But
there was no tool to splice the new asset back into the timeline — every clip
mutation tool (`split`, `apply_filter`, `add_subtitle`, …) leaves the asset id
fixed. The agent's only options were to `add_clip` again (creates a duplicate)
or do nothing. The DAG → query → re-render workflow stopped one step short of
a complete loop.

**Decision.** New tool `core.tool.builtin.video.ReplaceClipTool` — input
`(projectId, clipId, newAssetId)`, swaps `Clip.Video.assetId` /
`Clip.Audio.assetId` in place. Position (`timeRange`), trim (`sourceRange`),
transforms, filters, audio volume — all preserved. Permission `timeline.write`
(same bucket as the other clip mutators).

**Side effect on `Clip.sourceBinding`.** When the new asset has a lockfile
entry with non-empty `sourceBinding`, copy that binding onto the replaced
clip. Three reasons:

1. The agent regenerated this asset *because* a source changed — it would be
   nonsense to leave the clip's binding pointing at the *old* set (or worse,
   `emptySet()`).
2. Future `find_stale_clips` queries route through `Lockfile.findByAssetId`
   anyway, so this side effect is mostly informational. But `Clip.sourceBinding`
   *is* what `Project.staleClips(changed: Set<SourceNodeId>)` (the export-time
   incremental render path, acce14c) uses — keeping it correct means the two
   stale-detection lanes (lockfile-driven + binding-driven) agree.
3. It quietly closes a sub-gap: `add_clip` doesn't thread sourceBinding from
   the AIGC tool's output into the new clip. We deliberately *didn't* fix that
   in the lockfile-driven detector commit (would conflate add_clip with AIGC
   bookkeeping). But on `replace_clip` the relationship is unambiguous —
   you're swapping in *this specific* asset whose binding we already know.

**Why not refactor `add_clip` instead.** Considered: have `add_clip` look up
the asset's lockfile entry and copy its `sourceBinding`. Rejected because:

- `add_clip` is also the tool for hand-authored / imported clips that have no
  lockfile entry — every call would do a wasted lookup.
- The semantic of `add_clip` is "place this asset on the timeline at this
  spot." Adding "and also, by the way, copy its DAG bindings" muddies the
  purpose. `replace_clip` is *explicitly* about a regenerate flow, so the
  binding copy is on-theme.

If a future "always thread bindings" decision lands (e.g. the binding becomes
load-bearing for incremental render even on first-add), `add_clip` can adopt
the same `findByAssetId` lookup; the helper is on `Lockfile` already.

**Why text clips are rejected loudly.** `Clip.Text` has no `assetId` — it
carries the text inline in the model. Asking the tool to "replace its asset"
is meaningless. Erroring beats silently no-op'ing because the agent's plan
("replace clip X") would otherwise look like it succeeded with no observable
effect.

**Why preserve `sourceRange` instead of resizing.** A regenerate produces a
new asset whose duration may differ from the old one. Two options:

- **Resize the clip** to the new asset's full duration. Friendly but
  destructive — a previous `split_clip` chose those exact endpoints, and
  silently overwriting them re-asks the agent to re-split.
- **Preserve `sourceRange`** and clamp at render time if the new asset is
  shorter. Conservative but predictable.

Picked the second. If the regenerated asset *is* a different length and the
agent wants to honour that, it can call `split_clip` / a future `resize_clip`
explicitly. The principle: tool inputs should change exactly what they
declare, no more.

**Surface area.** Wired into all 4 composition roots (server, desktop,
Android, iOS). System prompt updated to teach the full workflow:
edit character → `find_stale_clips` → `generate_image` → `replace_clip`.

**Tests.** `ReplaceClipToolTest` covers six paths: video preserve-everything,
audio preserve-volume, text-clip rejection, missing clip, missing asset, and
the source-binding copy-from-lockfile case (the one that proves the
regenerate-flow side effect).

---
