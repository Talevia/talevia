## 2026-04-22 — lockfile_entries gains sourceNodeId + sinceEpochMs filters (VISION §5.4 debug)

Commit: `5fe3965`

**Context.** `project_query(select=lockfile_entries)` supported filtering
by `toolId` + `onlyPinned` — good enough to answer "which generate_image
entries are pinned" but not the two debug drills the §5.4 rubric actually
asks for:

- **"Show me this character's generation history"** — per-character_ref
  lineage across the project. The user picks a source node in the UI and
  wants every AIGC entry bound to it, newest-first, so a "teal-hair Mei
  was generated 5 times — tap to see each" timeline can render.
- **"What has this project burned since the last refresh / the mobile
  sync / Monday morning?"** — delta-queries against a timestamp so
  polling UIs don't ship full lockfile dumps on every tick.

Backlog bullet (P2): `lockfile-history-explorer`. Also this cycle the
stale `copy-source-node-across-projects` bullet is removed — the live
cross-project copy flow it asked for already ships as
`import_source_node(fromProjectId, fromNodeId, toProjectId)` per the
tool's existing KDoc + covering test `importsLeafCharacterRefIntoEmptyTarget`.
Same stale-bullet pattern as the earlier `project-export-portable-envelope`
cleanup — no code needed, the bullet had simply rotted.

Sixth skip on `per-clip-incremental-render` (multi-day refactor, stays
deferred).

**Decision.** Extend the existing `select=lockfile_entries` with two
optional filter fields. No new select, no new row shape, no new tool.

1. **`sourceNodeId: String?`** becomes valid for `lockfile_entries`
   (previously `clips_for_source` + `consistency_propagation` only).
   When set, keeps entries where `sourceBinding` contains that node id.
   Reuses the existing Input field name so the LLM doesn't have to
   learn a new one per select; the `rejectIncompatibleFilters` guard
   grows by one select mention.

2. **`sinceEpochMs: Long?`** — new Input field; entries kept when
   `provenance.createdAtEpochMs >= sinceEpochMs`. `lockfile_entries`
   only (hard-rejected elsewhere; future selects that want time-
   filtering can opt in one-by-one).

Both filters are AND-combined with the existing `toolId` and
`onlyPinned` filters. Row shape unchanged — a polling UI that already
decodes `LockfileEntryRow.serializer()` keeps working with zero shape
assumptions. `sinceEpochMs` field's range is deliberately tri-state
with the existing convention: `null` = no filter, any Long ≥ 0 = filter
from that millisecond epoch (§3a rule 4).

**Alternatives considered.**

1. **New `select=lockfile_entries_for_source`** — rejected. Row shape
   is identical; the only thing that differs is which filter field is
   required. A new select would grow the LLM-facing enum + row
   serializer register for no shape payoff. Consistent with the
   decision from the 2026-04-22 `debt-consolidate-provider-queries`
   cycle: one select per row shape, not one select per filter.

2. **Aggregation select `lockfile_history_by_source`** (groupBy =
   sourceNode, returns `(sourceNodeId, count, first/last timestamp)`) —
   rejected this round. The backlog bullet anticipated either the
   filter path (chosen) or the grouping path; the filter is the
   smaller hammer and already answers the UI's "N times" count via
   `total` in the echoed output. If a future cycle needs per-axis
   aggregation, a dedicated select is the right shape — but premature
   until a UI demands it. Queued as a possible follow-up in the
   backlog's next rubric pass.

3. **Client-side `filter { node in it.sourceBinding }`** after a
   blanket fetch — rejected for large projects. A 1000-entry lockfile
   dump costs ~200 KB JSON; pushing filtering to the server cuts the
   wire payload proportionally and means mobile polling doesn't blow
   up the SSE frame size.

4. **Cleanup-only cycle for the stale `copy-source-node-across-projects`
   bullet alone** — rejected. Combining the stale-bullet deletion
   with a real cycle is the pattern established in the earlier
   `adaptive-retry-backoff` cycle: the two unrelated deletions are
   still deletions-only and don't reorder surviving bullets, so the
   `git diff docs/BACKLOG.md` stays "only-removed-lines" per the
   skill's rule 6.

**Coverage.**

- `project.ProjectQueryLockfileFiltersTest` — 6 tests: `sinceEpochMs`
  filters older entries; `sourceNodeId` filters to bindings containing
  that node; both filters AND-combine; `sinceEpochMs` on unrelated
  select fails loud; nonexistent source yields empty rows; existing
  `toolId` filter still wins when combined.
- Existing `ProjectQueryToolTest` cases unaffected — the new fields
  default to `null` and don't perturb fixtures.
- Full JVM + Android + desktop + iOS compile + ktlintCheck all green.

**Registration.** Pure schema + query-body change; no new tools, no
AppContainer edits. `sinceEpochMs` is an `integer` field in the JSON
schema (fits `Long.MIN..Long.MAX` — the schema advertises no range,
matching the other numeric filters).

---
