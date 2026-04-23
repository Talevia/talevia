## 2026-04-23 — Tool-spec budget ratchet step: 27_000 → 25_000 (VISION §5.7 rubric axis)

**Context.** The P2 backlog bullet `debt-tool-spec-budget-ratchet-step-20k`
(from the 2026-04-23 repopulate) asked for tightening
`ToolSpecBudgetGateTest.CEILING_TOKENS` from 27_000 to 20_000 after
the five queued `debt-consolidate-*` bullets landed. Cycle 19
(transitions) → 20 (filter) → 21 (snapshot) → 22 (maintenance) →
23 (session) completed the queue. Trigger satisfied; this cycle
actually measures the budget and tightens the ratchet.

Rubric delta §5.7: `tool_spec_budget` ratchet exercised for the
first time since it was established in cycle 6. Ceiling dropped
from 27_000 to 25_000; regression signal now 2_000 tokens tighter
than before.

**Measurement (this cycle).**

- Tool count: **88** (down from 97 at cycle-6 baseline).
- `tool_spec_budget`: **24_384 tokens** (down from 25_253).
- Drop: -9 tools (-9.3% count) but only -869 tokens (-3.4% budget).

The disparity is the key finding: action-dispatched consolidated
tools (cycle 19 `transition_action`, cycle 20 `filter_action`,
cycle 21 `project_snapshot_action`, cycle 22
`project_maintenance_action`, cycle 23 `session_action`) have
helpTexts roughly **1.5–2× longer** than any individual tool they
replaced, because they document 3–4 action branches with per-
action caveats + permission-tier notes. Combined `Input` schemas
also widen with union fields (`action` enum + per-action optional
fields). Net savings per consolidation: ~150-200 tokens instead
of the ~500-800 tokens that raw tool-count reduction alone would
suggest.

**Decision.**

- `CEILING_TOKENS` tightened from **27_000** to **25_000**.
  Headroom drops from 10.8% (27k ceiling over 24.4k budget) to
  2.5% (25k over 24.4k). Enough to absorb a small helpText typo
  fix or a single new optional field; any serious growth trips
  the gate.
- `20_000` target from the backlog bullet is NOT hit this cycle.
  Rationale: the remaining 4_384-token gap can't be closed by
  further `debt-consolidate-*` cycles alone (the five that just
  landed already removed the low-hanging pairs). Getting to 20k
  needs either:
  1. Deleting 10+ more tools (likely removing or folding some of
     the 10 remaining builtin "fs" / "web" / "meta" tools, which
     would be a substantial surface-area design change), or
  2. Trimming helpText across the broad surface (the
     action-dispatched tools document 3-4 actions each; some of
     that narrative could move into per-action `helpText` blocks
     surfaced only on `list_tools(select=tool_detail)`, keeping
     the LLM-visible `tools.json` spec terse).
  Either is a separate cycle with its own design decision.
  `debt-shrink-tool-spec-surface` (already on backlog from prior
  repopulates) is the natural home.
- The gate's inline docstring's "ratchet plan" rewritten to
  reflect reality — 27k → **25k** (this cycle) → 20k (requires
  deeper shrink) → 15k → 10k steady state. Anyone bumping the
  ceiling upward must add a matching decision file explaining
  what justifies the new number; that rule predates this cycle
  and stays in force.

**Axis.** Gate-ceiling freshness. Before: ceiling set once at
cycle 6 then never ratcheted; drift risk = budget can grow ~7%
unchallenged before the gate notices. After: ceiling reflects
current-state + 2.5% buffer; any drift > 2.5% trips the gate and
forces a cycle. Next pressure-source for re-triggering this
bullet: budget drops ≥ 1_000 tokens via follow-up consolidations,
at which point re-ratchet to 23_500 or similar.

**Alternatives considered.**

- **Tighten to 24_500 (0.5% headroom).** Maximum tightness —
  catches even small helpText edits. Rejected: false positives
  are expensive (every cycle that merges a 100-token tool-doc
  improvement trips a full reconsideration). 2.5% is pragmatic
  middle: catches genuine bloat, tolerates minor edits.

- **Leave ceiling at 27_000.** Wait for `debt-shrink-tool-spec-surface`
  to make a real dent before ratcheting. Rejected: leaving stale
  ceilings IS the drift pattern we're trying to prevent. The
  ratchet's value is "tightening is mandatory after any
  consolidation cycle, even if the tightening is modest". A
  27k ceiling after 5 consolidations have landed is a false
  all-clear signal.

- **Skip-tag the bullet** ("can't reach 20k, wait for bigger
  shrink"). Rejected: the bullet explicitly allows partial
  progress ("失败则在 decision 里解释 budget 没降到 20k 的原因").
  Moving from 27k → 25k with a documented "next step is 20k via
  shrink-tool-spec-surface" is exactly the shape the bullet
  asked for. Skip-tagging would leave a stale 27k ceiling.

**Coverage.** `:apps:server:test --tests '*ToolSpecBudgetGateTest*'`
green with the new 25_000 ceiling; budget 24_384 across 88 tools.
ktlintFormat + ktlintCheck green on `:apps:server`.

**Registration.** Single file change —
`apps/server/src/test/kotlin/io/talevia/server/ToolSpecBudgetGateTest.kt`.
No code path changes, no AppContainer registration changes,
purely a regression-guard tightening. The bullet's follow-up
(reach 20k) lives under `debt-shrink-tool-spec-surface`, already
on the backlog from prior repopulates.
