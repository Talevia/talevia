## 2026-04-21 — list_provider_models HTTP complement (VISION §5.2 Compiler 层)

Commit: `71b61a1`

**Context.** `list_providers` (prior cycle) surfaced *which* providers
are wired but not *what models* each offers. The agent reasoning
about model selection ("use a cheaper model for bulk summarisation",
"this task needs a 1M-context model") has no Core tool to enumerate
the catalog. `LlmProvider` exposes `listModels(): List<ModelInfo>`
which every backend implementation (Anthropic, OpenAI, Gemini) has
populated, but there was no agent-visible path to call it.

**Decision.** `ListProviderModelsTool(providerId)` — resolves the
provider via the injected `ProviderRegistry`, invokes
`provider.listModels()`, returns a flat list of
`ModelSummary(id, name, contextWindow, supportsTools,
supportsThinking, supportsImages)` plus the aggregate
`modelCount`.

**Error surfacing** mirrors `web_fetch` / `web_search` conventions —
external-call failures (401 unauthorized, network timeout, rate
limit) are caught and reported through a structured `error: String?`
field on the output with an empty `models` list. Only caller-level
bugs (unknown providerId) throw. That gives the LLM a predictable
contract: "the tool either returns models or returns a reason it
couldn't." No exception stacks for expected transient failures.

**Permission:** reuses `provider.read`. The model-listing endpoints
are provider-side catalog lookups (Anthropic's `/v1/models`,
OpenAI's `/v1/models`, Gemini's `v1beta/models`), historically
unmetered. A silent-default matches how the operators of these
providers publish the data. Future: if one provider starts metering,
the permission can be split `provider.models.read=ASK` per-pattern.

**Registration** reuses the init-block pattern from `list_providers`
— each AppContainer appends the tool to its registry after
`providers` is initialised.

**Alternatives considered.**

1. *Throw on HTTP failure instead of the `error` field.* Rejected —
   it's a known-transient failure class (network flaps, revoked
   keys, rate limits). The agent should be able to react without a
   try/except pattern; an error field lets the LLM see "API key not
   set" and tell the user vs. "network timeout" and retry. Same
   design rationale as `web_fetch`'s structured error surface.
2. *Make the listModels call optional via a flag
   (`includeCatalog: Boolean` on `list_providers`).* Rejected —
   would conflate local introspection (list_providers, cheap) with
   external calls (list_provider_models, network round-trip). Two
   tools = two security / reliability surfaces = cleaner mental
   model for operators choosing to allow or deny the network-going
   path.
3. *Return the raw `List<ModelInfo>` from the provider as-is.*
   Rejected — `ModelInfo` is an internal type; the tool wire surface
   should be a stable DTO that can evolve independently of the
   provider interface (same reason `describe_clip` uses a DTO vs.
   raw `Clip`).
4. *Add a cross-provider variant that queries every registered
   provider in parallel.* Rejected for this cycle as scope creep —
   the agent can call `list_provider_models` per id if it needs a
   cross-provider view. Parallelism would require picking an error
   aggregation shape, which we don't have a concrete flow for.

**Coverage.** `ListProviderModelsToolTest` — five tests: happy path
with diverse model capabilities (tools / thinking / images flags);
unknown providerId fails loud with `list_providers` hint; HTTP
failure surfaces through `error` field without throwing; empty
catalog succeeds with `error = null`; non-default provider reports
`isDefault = false`.

**Registration.** `ListProviderModelsTool` registered in
`CliContainer.kt`, `apps/desktop/AppContainer.kt`,
`apps/server/ServerContainer.kt`, `apps/android/AndroidAppContainer.kt`,
`apps/ios/Talevia/Platform/AppContainer.swift` — all via the same
post-`providers` init block `list_providers` already uses. No new
permission (reuses `provider.read`).
