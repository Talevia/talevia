## 2026-04-23 — `source_query(select=nodes)` gains `hasParent` DAG-position filter (VISION §5.1 rubric axis)

**Context.** `source_query(select=nodes)` had filters for `kind`,
`kindPrefix`, `contentSubstring`, `id`, but no "only roots" or "only
leaves" filter. The only way to iterate roots was
`source_query(select=dag_summary)` which returns a single-row snapshot
with `rootNodeIds: List<String>` — useful for a one-shot summary but
requires a second `source_query(select=nodes)` dispatch to fetch each
root's full `NodeRow` (kind, revision, contentHash, parentIds,
summary, body, etc.). For common operator workflows like "show me
every root in this project" or "list leaves I can delete safely", the
double-dispatch was the only path.

Rubric delta §5.1 (source-layer structural observability): moves from
**部分** (DAG structure surfaced only via `dag_summary`'s one-row
aggregate — roots / leaves are `List<String>` fields, not first-class
`NodeRow`s) to **有** (DAG position is a first-class filter on the
`nodes` select, roots / children iterate through the same row shape
as every other node).

**Decision.** New optional field `hasParent: Boolean? = null` on
`SourceQueryTool.Input`:

- `null` (default) — return all nodes (pre-cycle-51 behavior).
- `true` — return only nodes with ≥1 parent (children-of-something).
- `false` — return only roots (parents list empty).

Threaded into both `runNodesQuery` (per-project scope) and
`runNodesAllProjectsQuery` (cross-project scope). `rejectIncompatibleFilters`
extended with `hasParent (select=nodes only)` so typing it on
dag_summary / dot / descendants / ancestors / history fails loud.
Schema + helpText document the filter succinctly.

**Axis.** n/a — new field on an existing select, not a
split / extract / dedup / refactor.

**Alternatives considered.**

- **New select `roots`** (e.g. `source_query(select=roots)`).
  Rejected per §3a arch-tax: would bump the source_query select
  count 6 → 7 and require a new row type (or same NodeRow, just
  duplicating `select=nodes` dispatch through a filter lens). Adding
  a filter field reuses the existing `nodes` dispatch and
  `NodeRow` shape — one-field surface growth vs. whole-new-select
  surface growth. Same operator outcome.

- **Three-way enum `dagPosition: "roots" | "children" | "all"`**.
  Rejected for this iteration: a third bucket (e.g. `isLeaf`) may
  someday matter — but leaves require a DAG walk (scan every node's
  `parents` for references) which is O(N²) naive and not a cheap
  filter. Add a separate `isLeaf: Boolean?` field later if the use
  case crystallises; don't prejudge the taxonomy now.
  `hasParent: Boolean?` with true/false/null is already a
  three-state matching §3a #4 (not binary, explicit null is a
  legitimate third answer).

- **Carry the filter only in `nodes`, not in `nodes` + all_projects.**
  Rejected — the all-projects scope is specifically cross-project
  search; one of the most natural queries is "show me every root
  across all projects". Applying the same filter to both variants
  is one extra line per handler with consistent semantics.

**Budget impact.** Adding the `hasParent` field + schema description
pushed `tool_spec_budget` from 22_518 to 22_623 (+105 tokens, ceiling
22_600 was 23 over). Trimmed schema description + helpText enum
listing; final budget 22_623 — still over the prior ceiling. **Bumped
ceiling 22_600 → 22_700** per the ratchet protocol (load-bearing
feature addition, documented here; see
`apps/server/src/test/kotlin/io/talevia/server/ToolSpecBudgetGateTest.kt`
docstring). The DAG-position filter is genuinely operator-facing
surface that no consolidation pass could shrink further — smaller
than adding a new `roots` select would have been.

**Coverage.** 4 new tests in `SourceQueryToolTest`:

1. `nodesHasParentFalseReturnsRootsOnly` — 2 roots + 1 child, filter
   returns exactly the 2 roots.
2. `nodesHasParentTrueReturnsChildrenOnly` — 1 root + 2 children,
   filter returns exactly the 2 children.
3. `nodesHasParentNullDefaultReturnsAll` (§3a #9) — default null
   preserves pre-cycle-51 behaviour (backwards-compatible).
4. `nodesHasParentRejectedOnIncompatibleSelect` (§3a #9) — applying
   the filter to `dag_summary` fails loud; reject-matrix still
   enforces per-select gating.

All existing `SourceQueryToolTest` + `SourceQueryAllProjectsTest`
cases continue green (the filter is opt-in).

**Registration.** No registration changes — `source_query` already
registered in all 4 JVM AppContainers via `registerSourceNodeTools`
(iOS skips tool registration).

**§3a arch-tax check (#12).** `source_query` select count unchanged
(6; filter-not-select). `rejectIncompatibleFilters` rule count: +1
(now covers hasParent). Still well under the dispatcher-plugin-
shape upgrade trigger (any dispatcher ≥ 20 selects OR ≥ 30 reject
rules).

Gradle: `:core:jvmTest` + `:apps:cli:test` + `:apps:server:test` +
`:apps:desktop:assemble` + `:core:compileKotlinIosSimulatorArm64` +
`:apps:android:assembleDebug` + ktlintCheck all green.
