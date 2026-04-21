## 2026-04-19 — `list_clips_for_source` — forward-index of sourceBinding

**Context.** `find_stale_clips` gives the agent the *backward* view of the
DAG (edit happened → what drifted?). There was no *forward* view: "if I'm
about to edit Mei, what will that touch?" Without it, the novice path has
to make edits and then see fallout, and the expert path has to `get_project_state`
+ hand-walk the timeline. VISION §5.1 rubric explicitly asks this question.

**Decision.**
- Add `Project.clipsBoundTo(sourceNodeId)` in `ProjectStaleness.kt` —
  walks `source.stale(setOf(id))` (the existing DAG closure) and returns a
  per-clip report with `{clipId, trackId, assetId, directlyBound, boundVia}`.
- Add `ListClipsForSourceTool` under `tool/builtin/project/` wrapping it.
  Input: `{projectId, sourceNodeId}`. Read-only (`project.read` permission).
  Fails loud when the node id is absent so the agent doesn't silently
  conclude "no bindings" when it actually mistyped.
- Report each clip's `boundVia` — the subset of its `sourceBinding` that
  lay inside the queried node's transitive closure — so the UI / agent
  can show "this scene-1-bound clip reaches you via scene-1" rather than
  just "bound somehow." `directlyBound: true` when the clip lists the
  queried id itself.
- Wired into desktop + server containers next to `FindStaleClipsTool`.
- Tests cover direct + transitive bind, leaf-with-no-clips, missing-node
  failure, track/asset echoes.

**Alternatives considered.**
- **Fold this into `find_stale_clips` via a flag.** Rejected — one tool
  answers "drift happened", the other answers "drift would happen".
  Different questions, different defaults (drift includes hash snapshot
  comparison from the lockfile; forward-preview doesn't need any
  lockfile at all).
- **Return only direct binds.** Rejected — the user edits an ancestor
  when they change Mei's hair; anything reachable downstream through
  `parents` pointers is in scope. Including transitive bindings matches
  what `staleClipsFromLockfile` does on the reverse side.
- **Compute from scratch in the tool.** Rejected — the graph walk already
  exists as `source.stale(...)`; duplicating BFS logic in two places is
  exactly how the forward and reverse views would drift apart. One BFS,
  two consumers.
- **Per-track API rather than per-clip.** Rejected — the caller's next
  action is always per-clip (regenerate one, replace one, inspect one);
  per-track would force another flattening pass.

**Why.** VISION §5.1 rubric rates "改一个 source 节点…下游哪些 clip /
scene / artifact 会被标为 stale?" — this is a first-class question. The
backward answer was already there; shipping the forward answer closes
the symmetric pair and unblocks UI work (desktop SourcePanel can now
show downstream clips under each node inline, next task).

**How to apply.** If later work adds new consumers of "who depends on
this?" (scene-level binding views, brand-palette impact reports), route
them through `Project.clipsBoundTo` rather than adding parallel walks.

---
