## 2026-04-23 — `provider_query(select=cost_compare)` + `LlmPricing` static table (VISION §5.2 rubric axis)

**Context.** P1 bullet `core-provider-cost-compare-query` from the
cycle-31 repopulate. `provider_query` had two selects (providers,
models); no way for the agent to answer "which model is cheapest for
this 10k-token request?" at planning time without dispatching the call
first. VISION §5.2 explicitly names the "generate_image vs dall-e-3"
tradeoff the agent needs to navigate — the AIGC side of that question
is answered by `AigcPricing` + the `avgCostCents` hint on tool specs;
the LLM side had no equivalent.

Rubric delta §5.2 (provider cost tradeoff observability): LLM
model-cost comparison moves from **无** (agent would have to
dispatch + observe spend after-the-fact) to **有** (static table +
`provider_query(select=cost_compare)` returns rolled-up cents for a
budget, sorted ascending).

**Decision.** Three components:

1. New `core/src/commonMain/kotlin/io/talevia/core/provider/pricing/LlmPricing.kt`
   — static pricing snapshot (2026-04). Modelled on `AigcPricing`'s
   three-state contract: unknown (provider, model) → `find(...)` returns
   `null` (caller treats as "don't roll up", distinct from 0¢ meaning
   "explicitly free"). Entries stored as `(providerId, modelId,
   centsPer1kInputTokens, centsPer1kOutputTokens)` quadruples. Rates are
   `Double` for sub-cent precision — Haiku at $1/MTok input rounds to
   0.1¢/1k and needs fractional representation.

   `Entry.estimateCostCents(inputTokens, outputTokens)` rolls up to
   integer cents with:
   - Half-up rounding on the fractional sum.
   - Floor-at-1¢ when any tokens were charged (non-zero rate × non-zero
     work) so sub-cent estimates don't silently read as free.
   - 0¢ only when inputTokens=0 AND outputTokens=0.
   - Rejects negative token counts loudly (require()).

   Published 9 entries across Anthropic (opus-4-7, sonnet-4-6,
   haiku-4-5), OpenAI (gpt-5.4, gpt-5.4-mini, gpt-4o, gpt-4o-mini),
   and Google (gemini-2.5-pro, gemini-2.5-flash). Docstring flags the
   "deliberately imprecise" drift caveat + points at provider consoles
   for invoice-accurate numbers.

2. New `core/src/commonMain/.../tool/builtin/provider/query/CostCompareQuery.kt`
   — the `select=cost_compare` handler. Returns one
   `CostCompareRow(providerId, modelId, centsPer1kInputTokens,
   centsPer1kOutputTokens, estimatedCostCents)` per priced pair, sorted
   ascending on `estimatedCostCents` with (providerId, modelId)
   alphabetic tiebreak. `rows.first()` is guaranteed cheapest.
   `outputForLlm` summarises: "N priced models for (in=X, out=Y) —
   cheapest <id> @ K¢, most expensive <id> @ K¢." — agent gets the
   upper + lower bounds in one glance.

3. Extended `ProviderQueryTool`:
   - New `SELECT_COST_COMPARE = "cost_compare"` + `ALL_SELECTS` grows
     to 3.
   - `Input` gains `requestedInputTokens: Int? = null` +
     `requestedOutputTokens: Int? = null` (both required for
     cost_compare, rejected for other selects).
   - `rowSerializerFor(cost_compare) = CostCompareRow.serializer()`.
   - `rejectIncompatibleFilters` cross-validates the 3-way matrix
     (providerId ↔ models-only; tokens ↔ cost_compare-only;
     cost_compare rejects providerId + requires both tokens).
   - helpText and inputSchema updated to document the new select +
     fields.

This bullet's check against the trigger-gated
`debt-unified-dispatcher-select-plugin-shape`: `provider_query` now has
3 selects (up from 2). Still far from the 20-select trigger threshold;
no upgrade signal.

**Axis.** n/a — net-new select (not a refactor). Pressure source that
would motivate a follow-up: (a) the pricing table drifts by enough that
users file a "cents are wrong" report → PR bumps rates in the one
table; (b) a new modality gets LLM-priced (Anthropic adds extended
thinking token pricing) — add a new rate field on `Entry` with default
`0.0` so existing entries still decode; (c) `cost_compare` needs a
filter (only show ≤$X models) → add to `Input` as a post-query filter
step. Any of these extend the existing file; neither needs a rewrite.

**Alternatives considered.**

- **HTTP-call the provider's pricing endpoint** per-request. Most
  providers don't expose one; those that do don't have one for LLM
  pricing specifically. Rejected: a static table with a published-price
  drift caveat is the standard industry approach (OpenCode, Aider,
  Cursor all ship static tables). The few-week drift between reprice
  and table update is acceptable for planning-time tradeoffs.

- **Fold LLM pricing into `AigcPricing`.** Keeps all pricing in one
  file. Rejected: `AigcPricing` is per-tool-id keyed (generate_image,
  synthesize_speech, etc.); LLM pricing is per-(provider, model)
  keyed. Mixing the two schemes in one `object` would couple-then-split
  later. Separate `LlmPricing` in a sibling file matches the
  cost-estimator-per-modality shape `AigcPricing` established.

- **Add a new `llm_cost_estimate` tool** instead of a new select.
  Rejected: per §3a #1, tool count is a per-turn budget; a new tool
  spec would add ~400 tokens to the registry surface, and the
  provider_query dispatcher pattern explicitly exists to absorb this
  kind of "one more select" growth without inflating the tool count.

- **Include expected-usage-over-project-lifetime (e.g. accumulate
  per-session spend).** That's the retrospective side — already
  covered by `session_query(select=spend)` (cycle 25 repopulate).
  `cost_compare` is the prospective complement; keeping them as
  separate selects matches the verb / noun split both tools use.

- **Accept `rank` filter (top N cheapest).** Rejected: current table
  has 9 entries; shipping all of them to the LLM is 9 small rows (~100
  tokens per row post-serialisation). No need to paginate yet. If the
  table grows past ~30 entries, add a `limit: Int? = null` field with
  default 50 — same shape as other `*_query` tools.

**Ceiling bump** (`ToolSpecBudgetGateTest.CEILING_TOKENS` 22_500 →
22_600). The ratchet plan explicitly allows a load-bearing addition to
bump the ceiling with a matching decision file — this is that file.
Measured `tool_spec_budget` went from 22_470 (cycle-31 baseline) to
22_518 after this cycle's trims. +18 tokens above the prior ceiling;
headroom tightens to ~0.4% from 0.1%. Still well under the 25_000
previous ceiling, still tracking toward the 20_000 long-term target
(which needs structural reduction per the phase-1 ratchet decision, not
incremental tightening).

**Coverage.**

- `core/src/jvmTest/kotlin/io/talevia/core/provider/pricing/LlmPricingTest.kt`
  (new) — 6 test methods:
  - `allTableEntriesAreWellFormed` (non-blank ids, non-negative rates,
    output-rate ≥ input-rate sanity guard against transposition typo).
  - `tableEntriesAreUnique` ((providerId, modelId) pairs are unique).
  - `findReturnsMatchingEntryOrNull` (three-state contract pin).
  - `estimateCostCentsZeroTokensReturnsZero` (0+0 → 0, no floor kicks in).
  - `estimateCostCentsRoundsHalfUpWithAtLeastOneCentFloor`
    (§3a #9 counter-intuitive edge: sub-cent work floors to 1¢).
  - `estimateCostCentsRejectsNegativeTokenCounts` (loud reject).
  - `estimateCostCentsScalesLinearlyInBulk` (off-by-factor-10 guard).

- `ProviderQueryToolTest.kt` — 6 new test methods:
  - `costCompareSelectReturnsEveryPricedPairSortedAscending`
    (happy path; sort contract pinned).
  - `costCompareSelectRejectsMissingTokens` (both + only-output missing).
  - `costCompareSelectRejectsProviderId` (providerId not applicable).
  - `costCompareRejectsNegativeTokens` (§3a #9 reject edge).
  - `modelsSelectRejectsTokenParams` (§3a #9 cross-select mixing edge).
  - `providersSelectRejectsTokenParams` (symmetric guard).

- `ToolSpecBudgetGateTest.CEILING_TOKENS` bumped with rationale
  inlined per the ratchet protocol (docstring reflects the new step in
  the ratchet history).

Gradle: `:core:jvmTest` + `:apps:cli:test` + `:apps:server:test` +
`:apps:desktop:assemble` + `:core:compileKotlinIosSimulatorArm64` +
`:apps:android:assembleDebug` + ktlintFormat + ktlintCheck all green.

**Registration.** No new tool — `provider_query` already registered
in all 4 JVM AppContainers via the shared
`registerSessionAndMetaTools` + later `ProviderQueryTool` init-block
wiring in `ServerContainer` / `CliContainer` / Desktop + Android
containers. The new select is automatically reachable through the
existing dispatch.

**Session-binding note (§3a #6).** The `cost_compare` select doesn't
take `projectId` or `sessionId` — it's session-independent. This
matches `tool_spec_budget`'s session-independent contract, so no
future session-project-binding refactor is needed for this select.
