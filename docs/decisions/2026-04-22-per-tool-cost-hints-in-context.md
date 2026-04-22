## 2026-04-22 — list_tools surfaces per-tool avg cents from MetricsRegistry (VISION §5.2 rubric)

Commit: `18a6f14`

**Context.** The `EventBusMetricsSink` already increments
`aigc.cost.cents` (global) and `aigc.cost.<toolId>.cents` (per-tool)
every time a `BusEvent.AigcCostRecorded` fires — data is collected.
What was missing: the agent has no programmatic way to read those
counters when it's deciding between two AIGC tools. Right answer to
"should I use `generate_image` with the default model or
`generate_image` with a premium model?" lives in recent history
("last N calls averaged X¢"), but the agent only sees cumulative
lockfile entries via `project_query` — no rollup by tool.

Two axes of cost information are useful:
  1. **Average cents per call** — the LLM needs this to rank
     tool-plus-model choices by unit cost. "`generate_image` averages
     15¢, `synthesize_speech` averages 3¢" is the signal.
  2. **Sample size** — an average over 1 call is noise; over 50 is
     trend. The agent should weight averages accordingly.

**Decision.** Wire both into `list_tools` Output — the tool the agent
already calls for self-introspection — and extend the metrics sink
with the missing divider.

1. **`aigc.cost.<toolId>.count` counter** added to
   `EventBusMetricsSink.attach()`. Every
   `BusEvent.AigcCostRecorded` with a non-null `costCents` now
   increments:
   - `aigc.cost.cents` (global, existed)
   - `aigc.cost.<toolId>.cents` (per-tool, existed)
   - `aigc.cost.<toolId>.count` (per-tool call count, NEW)

   The sink's old `aigc.cost.recorded` counter is kept (total count
   across tools); the new per-tool counter sits alongside.

2. **`ListToolsTool` gains an optional `metrics: MetricsRegistry? = null`
   constructor argument.** When non-null, each `Summary` in the
   Output gets two new nullable fields:
   - `avgCostCents: Long?` — computed as
     `aigc.cost.<toolId>.cents / aigc.cost.<toolId>.count`.
   - `costedCalls: Long?` — the divider itself. Lets the agent
     weight averages by sample size.

   Both are `null` when the tool has no priced calls in the runtime
   (non-AIGC tools, free-tier, or container without a
   `MetricsRegistry` wired). The `readCostHint()` private helper
   silently drops the hint when `count == 0` — guards against a
   divide-by-zero if someone ever increments cents without count
   (caught by a dedicated test).

3. **`outputForLlm` appends a one-line cost tail** when any tool has a
   hint — `"avg cost: generate_image=15¢, synthesize_speech=3¢, …"` —
   capped at 5 entries so the summary stays scannable. The agent
   reads the same signal both via typed Output and via the prose.

4. **Wired only in `ServerContainer`** for this cycle. The server
   already has `MetricsRegistry` + `EventBusMetricsSink` wired + a
   scope that attaches the sink; re-registering `ListToolsTool` with
   metrics is a one-line `init {}` block after the metrics property
   initialises. CLI / Desktop / Android / iOS containers stay with
   the null-metrics default for now — wiring a MetricsRegistry +
   sink into those four containers is a separate cycle (needs a
   coroutine scope for the sink attach, no existing infra to reuse).

The registration shape uses `ToolRegistry.register()`'s replace-by-id
semantic (same id registered twice, second wins), so the `tools`
block's original `register(ListToolsTool(this))` at the top of the
registry body stays, and the init-block re-register with `metrics`
replaces it after metrics has initialised. No property-order refactor
needed.

**Alternatives considered.**

1. **Inject cost into every tool's `helpText` (so the LLM sees it
   continuously in the tool-spec bundle)** — considered, rejected
   for this cycle. The tool-spec bundle is sent on **every turn**;
   adding ~20 tokens × ~10 AIGC tools = ~200 tokens per turn that
   most turns don't need. Putting cost data behind `list_tools`
   means the agent pays the cost only when it's actually doing a
   cost-tradeoff check — ~1% of turns. Can switch to helpText
   injection later if the "ask list_tools first" pattern proves
   too indirect; the infrastructure (counters + MetricsRegistry
   plumbing) is the same either way.

2. **Separate `tool_cost_stats(toolId)` tool** — rejected on §3a
   rule 1 (no net tool growth without compensating removal). The
   data fits naturally in `list_tools`'s existing row — adding
   two fields to an Output is strictly cheaper than adding a new
   tool + its JSON Schema + its helpText. "List tools with cost
   info" is the same operation as "list tools"; discoverability
   doesn't need a separate name.

3. **Compute average from `LockfileEntry.costCents` on every
   `list_tools` call (skip the counter)** — rejected. Would require
   loading every project's lockfile and grouping by `toolId` per
   call. That's O(entries) work for a signal we can maintain at
   O(1) via a counter. Also: lockfile-derived averages depend on
   which project is bound to the session, but cost trends are
   runtime-wide — the counter is the right granularity.

4. **Wire MetricsRegistry into every AppContainer in this cycle** —
   deferred. CLI / Desktop / Android / iOS each need a coroutine
   scope for `EventBusMetricsSink.attach`; each container already
   has one (the agent runtime), but threading the sink through
   startup flows is a distinct scope with its own tests. Server is
   the deployment where cost visibility matters most (long-running,
   no-ui, likely to loop). Agent running on CLI still lands cost
   counters on the wire-bus but has no sink reading them yet.

**Coverage.** Extended `ListToolsToolTest` with 5 new cases (plus the
existing 5 kept):

- `costHintsAreNullWhenMetricsNotWired` — null default preserves the
  pre-metrics Output shape.
- `costHintsAreNullForToolsWithNoPricedCalls` — metrics wired but
  empty: hints stay null.
- `costHintsSurfaceAverageFromMetrics` — sanity check that wired
  metrics don't leak into non-AIGC tools.
- `costHintsMatchPerToolCounters` — 250¢ / 5 calls → avg 50¢, count
  5; non-AIGC tools' hints stay null in the same run.
- `costHintDropsOnZeroCountCounter` — divide-by-zero guard: cents
  counter incremented but count stayed at zero → hint dropped.

EventBusMetricsSink's new counter is exercised via the existing
`MetricsEndpointTest` path (the AigcCostRecorded event now increments
one more counter; test assertions don't change because it never
asserted counter absence for that prefix). All existing tests pass
unchanged.

**Registration.** Server's `ServerContainer` re-registers
`ListToolsTool(tools, metrics)` in a new `init { … }` block after
`metrics` initialises — replaces the metrics-less instance from the
main `tools.apply` block via ToolRegistry's replace-by-id. Other
containers unchanged; their `ListToolsTool(tools)` call site is
still valid (the second constructor arg defaults to null).

Tool count delta: 0. LLM context cost (§3a rule 10): ~40 tokens added
to `list_tools` Output schema (two nullable fields) + up to ~80 tokens
in the prose tail when multiple tools have hints. Well below the
500-token threshold. The prose tail is capped at 5 tool hints to keep
even a large project's summary scannable.

---
