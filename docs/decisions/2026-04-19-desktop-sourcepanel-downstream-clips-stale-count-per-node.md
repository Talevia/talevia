## 2026-04-19 — Desktop SourcePanel: downstream clips + stale count per node

**Context.** `list_clips_for_source` landed, but a Mac user in SourcePanel
had no visible answer to "if I edit this character, what will break?" —
the question the tool now answers. Making the answer agent-only would
duplicate the stale-clips situation pre-LockfilePanel button.

**Decision.**
- Precompute two overlays in `SourcePanel` once per project reload:
  `staleClipIds: Set<ClipId>` from `staleClipsFromLockfile()`, and
  `reportsByNode: Map<String, List<ClipsForSourceReport>>` by calling
  `project.clipsBoundTo(SourceNodeId(node.id.value))` for every node.
  Memoized via `remember(project.timeline, project.source)` so they
  recompute only when the relevant slices change.
- Extend `SourceNodeRow` to show, in the collapsed header, a
  `"N clips · M stale"` chip next to the contentHash (amber when stale > 0,
  grey otherwise). No chip when zero clips bind.
- In the expanded body, render a `downstream clips (N):` list with
  `clipId[:8] on trackId[:6]  [stale]  via <descendant>` — amber per
  stale line; `via …` only when the bind is transitive.
- Reuses `ClipsForSourceReport` directly from `core.domain` — no
  parallel data type. Compose reads the domain type via an extra import.

**Alternatives considered.**
- **Call `ListClipsForSourceTool.dispatch` from the panel per expansion.**
  Rejected — we already hold the `Project` instance; going through the
  registry would marshal JSON for no reason. The tool is still needed
  for the agent; panels can use the domain helper directly (same pattern
  `LockfilePanel` uses for stale detection).
- **Lazy-compute only for the expanded node.** Considered; the
  per-node cost is tiny (a single BFS through a graph that rarely has
  more than dozens of nodes) and upfront computation lets the collapsed
  header also show the chip, which is where users decide whether to
  expand.
- **Add a top-level "click to see impact graph" button.** Rejected — too
  abstract. Inline chips per node answer the question at the point of
  editing.

**Why.** VISION §4 expert path: "用户能不能直接编辑 source 的每个字段…"
Editing blindly is not "editing" — experts want cost visibility before
they touch a field. This is the point-of-use UI surface for the
`list_clips_for_source` signal.

**How to apply.** When new source-related overlays become relevant
(e.g. "which AIGC providers generated clips bound to this node" or
"total estimated regeneration cost"), follow the same pattern: compute
once per project-state change with `remember(…)`, pass into the row
composable, render both a collapsed chip and an expanded detail list.
Don't add a chat round-trip to render read-only data the domain layer
already knows.

---
