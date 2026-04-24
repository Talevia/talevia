## 2026-04-23 — Agent compaction threshold becomes a per-model resolver (VISION §5.4 rubric axis)

**Context.** Before cycle-56, `Agent.compactionTokenThreshold: Int = 120_000`
was a single constant every session's auto-compaction triggered against —
a 200k-context Claude Haiku session and a 64k-context hypothetical model
hit compaction at the same raw token count. OpenCode's
`session/compaction.ts` uses ~85% of the model's `contextWindow` as the
trigger, which keeps effective headroom constant across models. On a
200k-ctx session the 120k default fires at 60% utilisation — compaction
fires ~40% too early, dropping older tool outputs the agent still
wanted and forcing an extra summary round-trip the session didn't need.

§5.4 delta (auto-compaction cadence quality): **部分 → 有**. Compaction
trigger now follows the model being used, not a single process-wide
constant.

**Decision.** New abstraction `core/src/commonMain/.../compaction/CompactionThreshold.kt`:

```kotlin
const val DEFAULT_COMPACTION_TOKEN_THRESHOLD: Int = 120_000
const val DEFAULT_COMPACTION_THRESHOLD_RATIO: Double = 0.85

class PerModelCompactionThreshold(
    contextWindowByRef: Map<Pair<String, String>, Int>,
    ratio: Double = DEFAULT_COMPACTION_THRESHOLD_RATIO,
    fallback: Int = DEFAULT_COMPACTION_TOKEN_THRESHOLD,
) : (ModelRef) -> Int {
    override operator fun invoke(ref: ModelRef): Int =
        contextWindowByRef[ref.providerId to ref.modelId]
            ?.let { (it * ratio).toInt() } ?: fallback

    companion object {
        suspend fun fromRegistry(registry: ProviderRegistry, ...): PerModelCompactionThreshold
    }
}
```

Agent constructor swap:

- Removed: `compactionTokenThreshold: Int = 120_000`.
- Added: `compactionThreshold: (ModelRef) -> Int = { DEFAULT_COMPACTION_TOKEN_THRESHOLD }`.
- Inside `run()`: `val perModelThreshold = compactionThreshold(input.model)` replaces the direct Int read; `SessionCompactionAuto.thresholdTokens` reports the per-turn value.

Composition roots wire the resolver:

- `CliContainer`, `AppContainer` (desktop), `ServerContainer`, `AndroidAppContainer`
  all build `PerModelCompactionThreshold.fromRegistry(providers)` via
  `runBlocking { ... }` at container init. Current providers
  (Anthropic / OpenAI / Gemini) return hardcoded `listModels()` lists,
  so `fromRegistry` completes synchronously — the suspend is a formality.
- iOS bridge (`newIosAgent` in `IosBridges.kt`) keeps the default
  resolver. Scope cut: iOS takes a single `provider: LlmProvider`
  (not a registry), and wiring per-model thresholds on iOS would
  require extending the factory signature. Filed as opportunistic
  follow-up; iOS currently has no compaction regression because the
  default threshold is unchanged from pre-cycle-56 behavior.

Shared constant `DEFAULT_COMPACTION_TOKEN_THRESHOLD` moved from
`StatusQuery.kt` (where it was a local mirror) into
`core/compaction/CompactionThreshold.kt`. `StatusQuery` and
`ContextPressureQuery` now import the canonical version; the fallback
semantics for both UIs stay identical.

**Axis.** n/a — function-shape extraction, not a split of an existing
type. The growth axis for `PerModelCompactionThreshold` itself is "new
strategies beyond fixed-ratio-of-context-window" (e.g. per-task budgets,
user-configured overrides). When that need arrives, replace the
`(ModelRef) -> Int` lambda shape with an interface — the function-type
alias we picked makes that upgrade trivial.

**Alternatives considered.**

- **Just bump the constant to 170_000 (85% of 200k Claude).** Rejected —
  trades one wrong number for a slightly-less-wrong number. 170k would
  fire near-ceiling on 128k-context GPT-4 models (compaction too late;
  session overflows before it triggers) and stays ~15% too early on
  1M-context Gemini. The model IS the variable; the right fix
  parameterises on it.

- **Change `LlmProvider.listModels()` from suspend to non-suspend.**
  Rejected — the three in-tree providers return hardcoded lists, so
  today suspend is a formality, but a future provider that fetches
  models from a network catalog (enterprise SKU listings,
  dynamically-allowed fine-tunes) would need the suspend back and
  re-breaking the interface is bad discipline. Instead each
  container pays the suspend cost once at init via `runBlocking`;
  hot-path `invoke(ref)` is non-suspend.

- **Pass `ProviderRegistry` directly into Agent instead of a resolver
  lambda.** Rejected — Agent doesn't need the registry for anything
  else (it resolves providers by name via fallback-list + primary
  params already). Coupling Agent to the whole registry would create
  a cyclic dependency between Agent and provider wiring that
  currently stays linear. The function-shape lambda is strictly
  narrower than "whole registry".

- **Keep `compactionTokenThreshold: Int` and add a parallel
  `compactionThresholdByModel: Map<ModelRef, Int>`.** Rejected — two
  ways to express the same knob, and consumers would have to decide
  which one "wins" for unknown models. The resolver-lambda shape
  encodes the fallback logic in one place; callers can't get it
  wrong.

- **Truncate vs round.** We truncate (`(contextWindow * 0.85).toInt()`).
  Off-by-one in the safe direction (compact slightly earlier than
  exact 85%) is fine; rounding up could drift past the provider's
  real window on models that advertise a conservative window. Test
  `ratioTruncatesToInt` pins this.

**Coverage.** 6 new tests in
`core/src/jvmTest/.../compaction/PerModelCompactionThresholdTest.kt`:

1. `knownModelScalesByRatio` — 200k → 170k, 64k → 54_400 at ratio 0.85.
2. `unknownModelFallsThroughToDefault` — registered provider but
   model id not in its `listModels()` → fallback constant.
3. `wrongProviderIdAlsoFallsThrough` — two providers sharing a
   model id; resolver doesn't cross-wire them, wrong-providerId
   queries fall through.
4. `emptyRegistryAlwaysReturnsFallback` — no providers at all.
5. `customFallbackOverridesDefault` — caller-supplied fallback
   replaces library default.
6. `ratioTruncatesToInt` — 100_000 × 0.333 = 33_300 exactly, no
   rounding-up drift.

Existing `AgentCompactionTest` updated: two occurrences of
`compactionTokenThreshold = 100` → `compactionThreshold = { 100 }`.
The test's compaction-firing invariants unchanged.

Gradle: `:core:jvmTest` + `:apps:cli:test` + `:apps:server:test` +
`:apps:desktop:assemble` + `:apps:android:assembleDebug` +
`:core:compileKotlinIosSimulatorArm64` + ktlintCheck all green.

**Registration.** No new tool, no new select, no new AppContainer
slot — 4 existing AppContainers (CLI / Desktop / Server / Android) gain
one line computing the resolver from their existing `providers`
registry. iOS bridge unchanged (factory signature doesn't expose a
registry).

**§3a arch-tax check (#12).** No new tool. No new select. No new
reject rule. `ToolSpecBudgetGateTest` unchanged (no LLM-facing
surface added). Nothing triggers.
