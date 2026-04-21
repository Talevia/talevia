## 2026-04-21 — `list_source_nodes` limit + deterministic sort (VISION §5.1 Source 层 / §5.4 Agent UX)

Commit: `95fee78`

**Context.** `list_source_nodes` has been the agent's go-to orientation tool
for "what source nodes exist on this project?" since the consistency lane
landed. Two gaps show up as soon as a project grows past a toy size:

1. **No cap.** The tool returns *every* matching node. A project with 50+
   character refs plus style bibles plus brand palettes gets its entire
   Source DAG dumped into LLM context on each call — the same shape the
   recent `list_lockfile_entries` / `find_stale_clips` caps were added to
   prevent.
2. **Non-deterministic order.** The response order follows
   `project.source.nodes` — an append-ordered list. Rename, fork, or
   update operations reorder nodes (replace-at-index when in place, append
   on add). An agent that cited "the 3rd node" in one message would see
   a different node after any mutation, so it can't reliably point back.

**Decision.** Schema extension — no semantic break:

- New `limit: Int? = null` input, default 100, clamped to `[1, 500]` via
  `coerceIn`. Matches the `list_lockfile_entries` shape (smaller caps than
  that tool because source nodes' summaries include free-form body snippets
  and the natural "give me everything" use case is genuinely large).
- New `sortBy: String? = null` input, values `"id"` (default) / `"kind"` /
  `"revision-desc"`. Normalised via `.trim().lowercase()`; blank collapses
  to the default; anything else raises `IllegalArgumentException` with a
  message listing the valid values.
- Default sort is `id` ascending — stable across every mutation, since
  `SourceNodeId.value` is the one field a rename tool has to treat as
  write-once from the caller's perspective.
- `kind` sorts alphabetically by kind, ties broken by id ascending.
- `revision-desc` sorts highest revision first (the natural "show me what
  I've been editing lately" view), ties broken by id ascending so the
  answer is still deterministic when two nodes share a revision number.
- `totalCount` remains the **full pre-filter count** of
  `project.source.nodes.size` — matches the existing semantic, unchanged.
  `returnedCount` is the post-cap size (already present, preserved).
- `outputForLlm` tail mentions when a cap was applied:
  `"(showing 100 of 237 matching node(s); raise limit to see more)"`.

**Alternatives considered.**

1. *No default sort — leave insertion/append order as-is.* Rejected:
   non-deterministic across mutations. The agent can't cite a node by
   position between calls, and diff-style debug transcripts reorder on
   every edit. The whole point of `list_*` tools is stable enumeration.
2. *Fixed `sortBy = id ASC` only, drop `kind` / `revision-desc` knobs.*
   Rejected: `revision-desc` is the natural "show me the newest work"
   view — it's the second-most-common ordering the agent actually wants
   ("what has been changing?"), and implementing it here is a one-line
   `compareByDescending` with zero extra Core surface. `kind` grouping
   falls out for free from the same shape.
3. *Pagination via `offset` / cursor instead of a hard cap.* Rejected for
   v1: the cap shape matches the sibling tools (`list_lockfile_entries`,
   `find_stale_clips`), and there's no concrete driver yet for full
   pagination. When a project genuinely outgrows 500 source nodes, adding
   `offset` as an additive input doesn't break any caller.
4. *Higher max (e.g. 2000).* Rejected: the rubric is "can the agent still
   reason about the result usefully in context?" — 500 nodes of summary
   text is already near the limit where the LLM stops extracting signal.
   Pair with the `kind` / `kindPrefix` filters for narrow drill-downs.

**Coverage.** `ListSourceNodesToolTest` — 11 tests:

- `emptySourceReturnsEmpty` — no-node project returns `totalCount=0`,
  `returnedCount=0`.
- `defaultLimitKeepsAllWhenUnderDefault` — 3 seeded nodes with no `limit`
  returns all 3 (default 100 doesn't kick in).
- `limitCapsResponse` — 5 nodes, `limit=2` → `returnedCount=2`,
  `totalCount=5` preserved.
- `limitClampedToMax` — `limit=999_999` on 2 nodes → clamped silently,
  both returned, no exception.
- `defaultSortIsByIdAscending` — ids inserted `z-1`, `a-2`, `m-3` sort to
  `a-2, m-3, z-1`.
- `sortByRevisionDesc` — revisions 1/5/3 (stored as 2/6/4 after `addNode`'s
  bumpedForWrite) sort high-to-low.
- `sortByKindGroupsDeterministically` — mixed consistency kinds sort
  alphabetically by kind then by id ascending.
- `sortByInvalidFailsLoudly` — `sortBy="ghost"` raises
  `IllegalArgumentException` naming the three valid values.
- `kindFilterComposesWithLimit` — 3 character_ref + 3 style_bible,
  `kind=character_ref, limit=2` → returns 2 character_refs and preserves
  `totalCount=6` (full pre-filter count).
- `sortByBlankFallsBackToDefault` — whitespace-only `sortBy` normalises to
  default instead of rejecting.
- `includeBodyReturnsBody` — regression guard that existing `includeBody`
  behaviour still round-trips.

**Registration.** No new registration — this extends an existing tool
already registered in all 5 composition roots (CliContainer,
desktop `AppContainer`, server `ServerContainer`, Android
`AndroidAppContainer`, iOS `AppContainer.swift`). Input schema is additive
with sensible defaults, so existing callers keep working unchanged.
