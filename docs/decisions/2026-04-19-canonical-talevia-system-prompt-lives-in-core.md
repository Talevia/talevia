## 2026-04-19 — Canonical Talevia system prompt lives in Core

**Context.** The Agent's `systemPrompt` defaulted to `null`. Tool schemas alone don't
teach the model the invariants the VISION depends on — build-system mental model,
consistency bindings, seed discipline, cache hits, dual-user distinction.

**Decision.** `core/agent/TaleviaSystemPrompt.kt` defines a terse canonical prompt +
a `taleviaSystemPrompt(extraSuffix)` composer. Both `AppContainer` (desktop) and
`ServerContainer` wire it into their Agent construction. Server appends a
headless-runtime note so the model doesn't plan around interactive ASK prompts.

**Why a single canonical prompt, not per-app.** The VISION invariants are about the
*system*, not a delivery surface. Forking prompts per app risks prompt drift. Apps
compose on top via `extraSuffix` for runtime-specific nuance (server = headless,
desktop = interactive).

**Regression guard.** `TaleviaSystemPromptTest` asserts each key-phrase (`Source`,
`Compiler`, `consistencyBindingIds`, `character_ref`, `cacheHit`, etc.) still appears.
If someone edits out a rule by accident, the test fails loudly.

**When to revise.** When a new VISION invariant lands (a new tool category, a new
consistency kind), the prompt is the second place to update after the tool itself.
Terseness matters more than exhaustiveness — every token ships on every turn.
