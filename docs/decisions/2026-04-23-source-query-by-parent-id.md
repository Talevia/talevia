## 2026-04-23 — source_query(select=descendants / ancestors) — DAG reachability primitive (VISION §5.5 propagation reasoning)

**Context.** `SourceQueryTool` had three selects — `nodes`, `dag_summary`,
`dot` — but no way to answer the two questions VISION §5.5
propagation/fold reasoning leans on most: "what depends on this
character_ref?" (downstream fan-out) and "what did this shot fold in
from upstream?" (upstream provenance chain). Callers could retrieve
the whole DAG via `select=dag_summary` or `select=dot` and walk it
externally, but both paths dump O(nodes) data the LLM then has to
traverse in-token, not compute server-side. Rubric delta §5.5:
propagation reasoning `部分 → 有` (two dedicated traversal selects
answer the canonical propagation / provenance questions in one
round-trip each).

**Decision.** Added two new selects to `SourceQueryTool`:

- **`select=descendants`** — BFS from `Input.root` via `Source
  .childIndex` (the existing reverse-parent index used by `stale()`).
  Returns every node transitively reachable downstream, including the
  root itself as row 0. Each row carries a new `depthFromRoot: Int?`
  field (null for other selects — §3a-7 forward-compat) so callers can
  read "how many hops away" without recomputing the traversal.
- **`select=ancestors`** — symmetric BFS upstream via each node's
  `parents` list. Same row shape + depth semantics.

Both take two new `Input` fields:

- `root: String` (required; unknown id fails loud with a
  `source_query(select=nodes)` hint)
- `depth: Int? = null` (null or negative = unbounded; 0 = root only;
  positive N = up to N hops)

Implementation lives in
`core/tool/builtin/source/query/RelativesQuery.kt` (150 lines). One
shared `runRelativesQuery` handler parameterised by a `neighborsOf`
lambda, so descendants and ancestors share cycle-safety + depth
bounding + row shaping — only the edge-direction differs. Standard
`limit` / `offset` paging (default 100, clamped `[1, 500]`).

Help text + JSON Schema updated to describe the new selects + two
Input fields. `rejectIncompatibleFilters` gained symmetric rules:
`root` and `depth` are rejected outside descendants/ancestors;
`includeBody` is now allowed for the relatives selects (auditing "what
propagates" often wants full bodies); paginated filters (`limit`,
`offset`) are now accepted by `nodes | descendants | ancestors`.

**Alternatives considered.**
- **One `select=relatives` with a `direction: "up" | "down"`
  discriminator.** Would trim one select id but add a mandatory
  discriminator field that doesn't apply to any other select.
  Rejected: direction is a cognitive load the LLM shouldn't have to
  carry — if the agent asks "ancestors of X" it should be able to
  name that directly, not compose "relatives of X with direction=up".
  The two-select shape matches the natural verbs.
- **Separate `Ancestors`/`Descendants` tools** (outside
  `source_query`). Rejected on §3a-1 — would net-add 2 tools to the
  top-level LLM spec while the existing `source_query` already hosts
  every other source-DAG query. Adding selects keeps the LLM's
  cognitive entry point one tool per lane.
- **Include `childIds` on the row** (so descendants walks are
  inherently self-describing — "here are the rows and here's how they
  connect"). Rejected for this cycle: `parentIds` is already on
  `NodeRow`, which is sufficient to reconstruct the edge set from a
  descendants result (every row's parents either all appear earlier
  in the result or were explicitly pruned by depth). Adding
  `childIds` would double-count every edge on the wire. Revisit if a
  consumer can't reconstruct the edges from parentIds alone — no
  concrete driver today.
- **Reuse `Source.stale(root)` directly.** The existing
  `stale(changed: Set<SourceNodeId>)` helper in `SourceDag.kt`
  computes exactly the unbounded-descendants closure. Rejected: it
  returns `Set<SourceNodeId>` (no depth info), which the new feature
  explicitly carries. Invoking it and then re-BFS'ing to recover
  depth would be two walks for the same data. The new handler does
  one walk recording depth; `stale()` stays as the minimal API for
  callers that only need the set.

**Coverage.** New tests in `SourceQueryToolTest`:
- `descendantsWalksReverseParentIndexBfs` — diamond DAG, asserts row
  order (BFS) + depth annotations + unrelated island excluded.
- `descendantsDepthZeroReturnsOnlyRoot` — depth=0 boundary.
- `descendantsDepthOneStopsAtImmediateChildren` — depth bounds
  correct-by-construction against the diamond.
- `descendantsNegativeDepthMeansUnbounded` — explicit negative-depth
  handling.
- `ancestorsWalksParentsUpwardBfs` — asserts depth dedup across
  multiple paths (a diamond means depth-2 root reachable via both a
  and b).
- `descendantsUnknownRootFailsLoud` + `descendantsRequiresRoot` +
  `rootFieldRejectedOutsideRelativesSelects` — error paths.
- `descendantsCycleSafe` — cycle-tolerance (the data layer doesn't
  forbid cycles; traversal must still terminate — same contract as
  `Source.stale`).

`:core:jvmTest`, `:core:compileKotlinIosSimulatorArm64`,
`:apps:android:assembleDebug`, `:apps:desktop:assemble`, `ktlintCheck`
all green. (`ktlintFormat` re-sorted the `OpenProjectTool` import
that cycle 8 landed into alphabetical position; pure import reorder,
no code changed.)

**Registration.** None — `SourceQueryTool` already registered in all
5 `AppContainer`s, and the `RegisteredToolsContractTest` added last
cycle confirms that. New selects ship by virtue of being dispatch
branches on the existing tool.
