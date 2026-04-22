## 2026-04-22 — cross-session-spend-aggregator already shipped (no-op)

Commit: `(docs-only — no code change; see reasoning below)`

**Context.** Backlog bullet `cross-session-spend-aggregator` said:
`session_query(select=spend)` only aggregates a single session, and the
project's total spend across sessions had no query entry point. Proposed
direction: either extend `SELECT_SPEND` to accept `projectId` without
`sessionId`, OR add `project_query(select=spend)`.

**Status.** The second path is **already implemented** as of commit
`8964878` (same-day: `feat(cost): track AIGC spend per session/project
via lockfile cost stamps`). `project_query(select=spend)` emits:

```
ProjectQueryTool.SpendSummaryRow(
  projectId: String,
  totalCostCents: Long,
  entryCount: Int,
  knownCostEntries: Int,
  unknownCostEntries: Int,
  byTool: Map<String, Long>,
  bySession: Map<String, Long>,   // ← the cross-session aggregation
  unknownByTool: Map<String, Int>,
)
```

`bySession` is the exact payload the backlog bullet was asking for —
keyed by sessionId, valued in cents, summed over every lockfile entry
whose stamped sessionId matches. Test coverage:
`core/src/jvmTest/.../project/ProjectQuerySpendTest.kt:119` exercises
a multi-session fixture and asserts
`mapOf("s" to 23L, "s2" to 5L) == row.bySession`.

**Decision.** Close the backlog bullet with no code change. Both paths
the bullet offered would have been redundant with the shipped
implementation:

1. **Extending `session_query(select=spend)` to accept projectId
   without sessionId** — rejected. `session_query` is session-scoped
   by convention (see its `Input.sessionId` required-for-most-selects
   contract). Making `spend` the one select that doesn't need
   sessionId would break the mental model; the project-scoped
   variant belongs on `project_query` where all project-lane
   aggregates live (`project_metadata`, `snapshots`, now `spend`).
2. **Adding `project_query(select=spend)`** — already shipped.

**Coverage check.** For the three common cross-session questions:
- "project X total spend" → `project_query(select=spend).totalCostCents`.
- "which session burned the budget" →
  `project_query(select=spend).bySession`.
- "one session's spend" → `session_query(select=spend)` (unchanged).

All three covered, no seam.

**Why the bullet existed.** The backlog was repopulated today by the
rubric-driven `/iterate-gap` sweep, which evidently didn't see the
spend-tracking work landed earlier the same day (commit `8964878`
vs. the backlog-repopulate commit `24ade91`). The feature beat the
bullet to the tree. Recording this decision so the next sweep
doesn't re-open it.

**Impact.**
- No code change. No tests modified.
- Backlog bullet `cross-session-spend-aggregator` removed.
- Decision doc preserves the "evaluated → already done" outcome.
