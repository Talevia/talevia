## 2026-04-20 — `list_assets` for media catalog introspection

**Context.** Projects accumulate assets fast — imported media,
extracted frames, generated images / videos / audio, LUTs. Today the
only way for the agent to enumerate the catalog is `get_project_state`,
which dumps *everything*: full timeline, lockfile, snapshots, render
cache, source graph. That's a wall of JSON when the actual question
was "what videos do I have?" or "is asset X still referenced by any
clip before I prune it?" Token waste on that scale shows up directly
in compaction frequency.

**Decision.** Ship `list_assets(projectId, kind?, onlyUnused?, limit,
offset)` — a paginated projection of `Project.assets` with just the
fields an agent needs:

- `assetId`, `kind` (coarse classification: video / audio / image),
  `durationSeconds`, optional `width` / `height`, `hasVideoTrack`,
  `hasAudioTrack`, `sourceKind` (file / http / platform),
  `inUseByClips` count.
- Kind classification: `videoCodec != null` → video (includes muxed
  video+audio); `audioCodec != null` only → audio; neither → image.
- `onlyUnused = true` filters to assets referenced by zero clips —
  the "what can I safely prune?" query.
- Pagination: `limit` default 50, hard cap 500; `offset` default 0.

Parallel to `list_timeline_clips` and `list_source_nodes` in
philosophy: a tight, filterable projection over what
`get_project_state` already returns in bulk.

**Alternatives considered.**

- *Make `get_project_state` accept a section filter
  (`sections: ["assets"]`).* Rejected — coarse-grained filtering
  doesn't give us pagination or kind filtering, and changes the
  contract of a tool many existing flows rely on. A new tool is
  additive and leaves the old path alone.

- *Expose raw codec strings and bitrates.* Rejected — engines care,
  agents don't, and every extra field is bytes in every turn once
  the agent starts calling this reflexively. `hasVideoTrack` /
  `hasAudioTrack` booleans convey the useful signal without the
  vocabulary cost.

- *Ship `remove_asset` in the same change.* Deferred — removing an
  asset that any clip still references would leave the project
  broken (`validate_project` would flag `dangling-asset` on every
  dependent clip). `list_assets(onlyUnused=true)` gives the agent
  the safe-to-prune list, but actually doing the prune needs a
  thoughtful safety story (refuse when in-use? require a
  `removeDependentClips=true` opt-in?) — worth its own iteration.

**Reasoning.** Cheap projections over already-computed state are the
lowest-risk, highest-value tools to ship: ~140 lines, 14 tests, no
new invariants, no engine work. The `inUseByClips` count is the
load-bearing feature here — it's the one piece agents cannot
currently compute without reading every clip in the timeline.

---
