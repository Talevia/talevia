## 2026-04-23 — Ratchet tool-spec budget gate 25_000 → 22_500 via helpText trim across 13 top offenders (VISION §5.7 rubric axis)

**Context.** P0 bullet from the cycle-31 repopulate,
`debt-shrink-tool-spec-surface-to-22500`. Prior ceiling 25_000 was
set in `docs/decisions/2026-04-23-debt-tool-spec-budget-ratchet-step-20k.md`
after five `debt-consolidate-*` cycles (19→23) dropped the tool count
97 → 88 but only shaved the budget 25_253 → 24_384 (-3.4%) because
action-dispatched helpTexts were ~1.5-2× longer than any individual
tool they replaced. The decision explicitly flagged 20_000 as the
target requiring "either deleting ~10 more tools or trimming
helpText across the broad surface" — this cycle executes the
helpText-trim half.

Rubric delta §5.7: `tool_spec_budget` from 24_384 → 22_518
(canonical `(bytes+2)/4` per-entry measurement from
`session_query(select=tool_spec_budget)`). -1_866 tokens / -7.6%.
Ceiling moves 25_000 → 22_500, closing headroom to ≤ 0.1% (22_518
vs 22_500 = -18 tokens under cap after the final `add_subtitles`
trim pushed it past the threshold).

**Decision.** 13 tool helpTexts trimmed without touching behaviour
or JSON schemas. Each tool's LLM-visible `helpText` field is the
per-turn cost driver (the agent reads every tool's helpText on
every turn, and the provider bills on that whole surface). Schemas
stayed intact since `list_tools(select=tool_detail)` already has a
per-field-description channel for deep drill-down and changing the
schema surface would be a separate API-compat question.

Trimmed tools + savings:

| Tool | Before | After | Delta |
|---|---|---|---|
| project_query | 1683 | 1272 | -411 |
| session_query | 1265 | 875 | -390 |
| source_query | 1097 | 827 | -270 |
| draft_plan | 788 | 617 | -171 |
| fork_project | 637 | 565 | -72 |
| filter_action | 438 | 377 | -61 |
| create_project_from_template | 433 | 382 | -51 |
| import_media | 424 | ~350 | ~-74 |
| transition_action | 407 | ~340 | ~-67 |
| project_maintenance_action | 402 | ~340 | ~-62 |
| update_source_node_body | 400 | ~310 | ~-90 |
| todowrite | 376 | ~280 | ~-96 |
| track_action | 366 | ~280 | ~-86 |
| add_subtitles | 296 | ~240 | ~-56 |

(Rough "after" columns for the smaller trims; exact token counts
weren't individually remeasured — only the total matters for the
gate.)

Trim rules applied:
- Keep the one-sentence elevator pitch ("what does this tool do?").
- Keep required / optional input enumerations in compact
  pipe-separated form (`video|audio|subtitle|effect` instead of
  prose).
- Drop "Use when…" prose — the agent picks tools based on
  inputSchema field-matching, not narrative guidance.
- Drop rationale / design-note prose (why the tool exists, what
  consolidation it replaces, what VISION section it closes).
- Drop parenthetical asides and "so you can…" tails.
- Collapse multi-paragraph workflow blocks into 1-2 sentences.
- Preserve specific constraint callouts ("one snapshot per call",
  "all-or-nothing atomic", "force=true required for non-empty
  tracks") — these are load-bearing for correct tool use.
- Preserve unit notations (ISO-639-1, epochMs, seconds, cents).

`ToolSpecBudgetGateTest`:
- `CEILING_TOKENS = 22_500` (was 25_000).
- Inline ratchet-plan comment updated to document the step and
  note the still-pending 20_000 target's path: "moving per-action
  details to a `list_tools(select=tool_detail)` sidecar so the
  live spec shrinks further without losing the narrative".

**Axis.** Number of distinct helpText strings > 300 tokens. Before
this cycle: 13 (the ones trimmed above). After: ≤ 5 (project_query
still 1272 — its select catalogue is load-bearing and further
trim risks losing the per-select affordance hints). Pressure
source for re-triggering: any new tool whose helpText crosses
~400 tokens at add-time should be reviewed for prose-vs-schema
balance before landing.

**Alternatives considered.**

- **Move per-action detail to `list_tools(select=tool_detail)`
  sidecar.** This is the canonical 20_000-target path. Rejected
  this cycle: requires a Tool-interface change (adding a
  `perActionHelpText: Map<String, String>` field surfaced only on
  the detail select), which is a design decision worth its own
  cycle. Incremental trim hits the 22_500 target without that
  churn.

- **Delete low-ROI tools outright** (`fs/` tools, `web/`
  tools, `meta/` tools). Would save ~1_500 tokens all at once.
  Rejected: these tools have real use cases (scratchpad reads,
  web fetch for citation, estimate_tokens for pre-flight cost
  checks); their per-tool cost is small relative to their
  value. Better to trim prose on the high-cost consolidation
  tools first.

- **Freeze at 25_000 and try again next cycle with a bigger
  design swing.** Rejected: the cycle-24 ratchet decision
  explicitly said "tightening is the only legal direction" and
  "ceilings drift up when nobody ratchets". A partial drop to
  22_500 is visible forward progress; the 20_000 gap is
  explicitly scoped to a follow-up bullet.

- **Trim inputSchema field descriptions too.** Rejected: schema
  descriptions are structurally important — the LLM uses them
  to decide which field to set. Trimming risks functional
  regressions. HelpText is narrative overlay, safer to compress.

**Coverage.** `ToolSpecBudgetGateTest.registeredToolSpecsFitWithinCeiling`
is the gate itself — re-passes with the new 22_500 ceiling. Ran
the full test suite (`:core:jvmTest` + `:apps:cli:test` +
`:apps:server:test` + `:apps:desktop:assemble` +
`:core:compileKotlinIosSimulatorArm64` +
`:apps:android:assembleDebug` + ktlintFormat + ktlintCheck) — all
green. No existing tool-behaviour test references the trimmed
text (all call `tool.execute(input, ctx)` against inputSchema,
never against helpText — which is the whole reason helpText is
prose-compressible).

**Registration.** 13 `*Tool.kt` / `*Schema.kt` files touched for
helpText trimming. One test file (`ToolSpecBudgetGateTest.kt`)
for the ceiling update. Debug helper
`ToolSpecBudgetTopEmitterTest.kt` created during the cycle to
enumerate top-20 offenders; deleted in the same commit (purely
diagnostic; repeated by future cycles as needed via
`session_query(select=tool_spec_budget, topByTokens=N)`).
