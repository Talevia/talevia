# Architectural pain points

Running log of architectural friction observed while doing `/iterate-gap`
work. Each entry grounded in the cycle that surfaced it — so a future
contributor can correlate "this felt wrong" with the code + commit that
made it feel wrong.

**Shape of entries.** Decisions (`docs/decisions/`) record *what we chose
and why*. This file records *what the act of implementing told us is
wrong today* — observations, not prescriptions. An entry can later
become a backlog bullet if the fix is actionable, or just sit here as
accumulated evidence that a deeper refactor is warranted.

**Conventions.** Append-only. Newest section at the bottom. Never edit
or re-order past entries — pain points are snapshots of what hurt then,
even if we've since fixed it.

---

## 2026-04-22 — debt-resplit-project-query-tool (`b9d0da3`)

### Row data classes leak to call sites via `Owner.Type.serializer()` coupling
23 call sites across 4 test files + `SnapshotPanel.kt` had to be touched
to relocate `ProjectQueryTool.TrackRow` etc. to the `query.` package.
The churn happened because the tool exposes its output as a raw
`JsonArray` and every caller has to know the per-select row serializer
by name. The tool "unifies" 13 selects into one dispatcher on the LLM
side but fans out 13 different typed decoding contracts on the Kotlin
side. A typed facade (`ProjectQueryTool.decodeRows<T>(output, select)`
or a select-indexed `KSerializer` table) would make the row type an
internal detail — next time we re-organise rows, zero call sites
change. This is the missing abstraction.

### Incremental splits drift back at the same rate when they only attack symptoms
`ProjectQueryTool.kt`: 638 → 540 (cycle `6e7bd8f`, 2026-04-21) → 547
one month later → 233 (this cycle). The first split extracted
`run<Select>` handlers but deliberately left row data classes nested,
citing "API stability". Every new select (consistency_propagation,
spend, snapshots, the 3 `describe_*` rollups) brought its own ~20-line
row class back into the main file. The long-file signal was a
symptom; the structural cause was "every new select adds lines to the
dispatcher file". The first split cut the wrong axis. Rule of thumb:
before a split, identify *what grows with new <feature-unit>* and cut
along that axis — not just along the current hotspot.

### "Stay nested for API stability" was net-negative because the file was at a structural limit
The prior cycle deferred call-site churn by keeping rows nested. This
cycle had to do that churn anyway (23 sites) because the file grew
back over the long-file threshold. So the stability argument *delayed*
the churn without preventing it — and the delay cost was a whole
repeated split cycle + a re-bumped entry on the backlog. Takeaway:
don't defer call-site churn for API-stability arguments when the
containing file is already at a structural limit. Take the hit once,
at the natural extraction point, not twice across two cycles.

---

## 2026-04-23 — debt-resplit-session-query-tool (`<this commit>`)

### "Unified query dispatcher" is a recurring shape, not a one-off
Back-to-back cycles had to apply the exact same refactor recipe — extract
nested row data classes to sibling files — to `ProjectQueryTool` then
`SessionQueryTool`. The convention ("rows nested on the dispatcher so callers
use `Owner.Row.serializer()`") was applied uniformly at design time across
both tools, and broke down at the same structural limit in both. A third
unified-query tool (e.g. a future `AgentQueryTool`) would hit the same wall
by default. Takeaway: when a structural pattern — dispatcher + N
per-discriminator handlers + per-discriminator rows — ships twice, the
convention of "rows live on the dispatcher" should be dropped project-wide
before the third instance lands. Better yet, introduce a `QueryDispatcher<I,
O>` base abstraction (maybe inside `core.tool.query`) that owns the
select-to-handler routing and forces rows to be top-level from day 1. Today
that abstraction is implicit and each dispatcher re-invents it.

### The "row decoding" contract duplicates across 11+ test helpers
Each session-query test file has a private `rows(out): List<FooRow>` helper
that unwraps `out.rows: JsonArray` via
`JsonConfig.default.decodeFromJsonElement(ListSerializer(FooRow.serializer()),
out.rows)`. That 3-line helper now appears in
`SessionQueryToolTest`, `SessionQueryCacheStatsTest`,
`SessionQueryContextPressureTest`, `SessionQueryRunStateHistoryTest`,
`SessionQuerySpendTest`, `SessionQueryStatusTest`,
`SessionQueryToolSpecBudgetTest`, plus the analogous versions in every
project-query test (see 2026-04-22 entry). That's 10+ copies of the same
"decode the untyped JsonArray into typed rows" operation. A 5-line
test-kit helper like `Output.decodeRows(ser: KSerializer<T>): List<T>`
collapses all of them. Evidence that we need a typed-output facade
(same missing abstraction as the 2026-04-22 first entry).
