## 2026-04-23 — Ratchet gate for registered-tool spec budget (VISION §5.7 / §3a-10)

**Context.** `session_query(select=tool_spec_budget)` has existed for
multiple cycles and can compute "how many tokens am I spending
describing my tools every turn" in milliseconds. Nothing was guarding
against regression — a new tool could add 500+ tokens of spec (helpText
+ JSON schema) with zero visibility into the per-turn LLM cost until a
user noticed bills going up.

Measured baseline this cycle (from `ServerContainer(rawEnv = emptyMap())`):
**25_253 tokens across 97 tools**. Server is the thinnest AppContainer
— CLI / Desktop / Android register the same core tools plus
engine-gated AIGC extras, so the actual prod per-turn cost is a bit
higher than this number across platforms. Still well above the R.6
§5.7 20k P0 threshold; `debt-shrink-tool-spec-surface` tracks the
full reduction, skipped this cycle as too-large-for-one-iteration.

Rubric delta §5.7: per-turn tool-spec cost went from
"silently unbounded" to "gated at 27k with ratchet-down convention".

**Decision.** Add `apps/server/src/test/kotlin/io/talevia/server/
ToolSpecBudgetGateTest.kt`:

- Builds `ServerContainer(rawEnv = emptyMap())` — the canonical
  registry every production server boot gets, zero secrets / network
  dependency.
- Dispatches `session_query(select=tool_spec_budget)` through the
  registered tool surface (`container.tools["session_query"]!!.dispatch(...)`)
  — public API path, resilient to the internal query handler moving
  between packages.
- Decodes the `ToolSpecBudgetRow` and asserts `estimatedTokens <=
  CEILING_TOKENS`.
- On failure, prints the current estimate plus the `topByTokens`
  offenders so the next author sees which tool's spec blew the budget.

`CEILING_TOKENS = 27_000` — today's measured baseline (25_253) plus
~7% buffer. Ratchet plan documented inline:

- Current cycle: 27k (don't-regress baseline).
- After first `debt-consolidate-*` cycles: 20k (hits R.6 P0 threshold).
- Post `debt-shrink-tool-spec-surface`: 15k.
- Steady state: 10k.

The comment explicitly says tightening is the only legal direction: a
future cycle that would push the budget over ceiling is a backlog
bullet, not a raised ceiling. If a load-bearing growth truly
justifies raising CEILING_TOKENS, the author must add a matching
decision file explaining why.

A second test (`budgetIsNonTrivial`) is the positive control: asserts
`toolCount > 50` and `estimatedTokens > 5_000`. Catches a future
refactor that accidentally makes the query return an empty registry or
zero tokens (without this, the ceiling check would silently pass on a
zero value — false green is worse than failing gate).

**Axis.** "New tools added to the registry" — every new `register(...)`
call in any AppContainer pushes this number up. The gate binds the
upward pressure; individual tool-consolidation cycles ratchet the
ceiling down.

**Alternatives considered.**

- **Implement `debt-shrink-tool-spec-surface` first, then land the
  gate at 15k per the bullet's literal wording.** The bullet ordered
  shrink → gate. Rejected for this cycle: shrink is a multi-cycle
  effort (106 tools → <40, decomposable by area). Landing a gate today
  at the current baseline buys regression protection while shrink
  happens over several cycles — strictly better than no gate for
  weeks. Noted this deviation in the backlog skip-tag for
  `debt-shrink-tool-spec-surface`.

- **Source-walk approach.** Walk `*Tool.kt` files, grep for `helpText`
  string literals, estimate. Rejected: schemas are built with
  `buildJsonObject { … }` at runtime; source-walking that cleanly is
  brittle. Using the live runtime query is the canonical measure and
  it's cheap (one `ToolRegistry` construction + one dispatch — both
  already fast in the existing `ServerContainerSmokeTest` path).

- **Gate in `:core:jvmTest` instead of `:apps:server:test`.** Core
  tests don't know which tools the production runtime registers;
  that's an AppContainer concern. Putting the gate in core would
  require building a phantom registry that drifts from any real
  container's tool set. Rejected in favor of using the existing
  `ServerContainer` as the canonical registry.

- **Separate gate per AppContainer.** Would catch per-platform drift
  (e.g. Desktop-only tools bloating Desktop's spec past its own
  threshold). Rejected as scope creep for a first gate; the server
  measurement proxies across platforms well since the dominant cost
  is shared-core tools. If per-platform drift becomes a concern,
  clone the test into each platform's test module with its own
  `CEILING_TOKENS`.

**Coverage.** `:apps:server:test` (new `ToolSpecBudgetGateTest` —
2 cases: ceiling + positive-control non-triviality). Also leaves
`:core:jvmTest`, `:apps:cli:test`, `:apps:desktop:test`, `ktlintCheck`
unaffected.

**Registration.** No tool / no AppContainer change. Test-only; reads
through the registry via `session_query(select=tool_spec_budget)`
(unchanged). The build.gradle.kts `testImplementation(kotlin("test"))`
+ `useJUnitPlatform()` Server already had cover the test wiring.
