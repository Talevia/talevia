## 2026-04-23 — gemini-provider-stub: bullet is stale; provider already shipped (VISION §5.2 provider-neutrality)

**Context.** Backlog bullet `gemini-provider-stub` asked for a skeleton
Gemini `LlmProvider` under `core/provider/gemini/`, gated by
`GOOGLE_GENAI_API_KEY`, covering at least text in one round-trip
with tool-use deferred to a follow-up. Walking the tree today:

1. `core/src/commonMain/kotlin/io/talevia/core/provider/gemini
   /GeminiProvider.kt` exists at ~600 lines — full streaming implementation
   against `models/{model}:streamGenerateContent?alt=sse`, normalised into
   the shared `LlmEvent` stream. Not a stub: covers text streaming +
   tool-use (function-call emission + functionResponse replay),
   multi-model `listModels()`, thinking-model gating, image-inputs, and
   the Gemini-specific SSE quirks the bullet flagged (no `event:` header,
   synthetic call-ids for function correlation, user-role tool-result
   replay).
2. `ProviderRegistry.addEnv` wires it from either `GEMINI_API_KEY` or
   `GOOGLE_API_KEY` (simpler than the bullet's proposed
   `GOOGLE_GENAI_API_KEY` — matches what Google's own SDK docs use).
   `ProviderRegistry.addSecretStore` covers the persisted-secret path
   via `SecretKeys.GEMINI` / `SecretKeys.GOOGLE` — both env and
   UI-entered keys land it.
3. Tests: `GeminiProviderStreamTest` exercises the streaming translation
   against canned SSE fixtures; `ProviderRegistryBuilderTest
   .googleAliasRoutesToGeminiProvider` proves registry wiring works.
   Both green on this cycle's run.
4. CLI-side `SecretBootstrap.kt` prompts the user with
   `[anthropic/openai/gemini]` and routes `gemini`/`g`/`google` to the
   correct secret key. First-run UX accepts Gemini on equal footing.

Nothing to implement. The bullet's underlying VISION §5.2 goal —
"provider abstraction 没被第三个实现压过测试" — is already satisfied by
the working `GeminiProvider` + its passing tests. The abstraction held
up: translating Gemini's `GenerateContentResponse` chunks into the
shared `LlmEvent` stream landed without leaking Gemini-specific types
into `Agent` / `Compactor` / tool code (the red line stated in CLAUDE.md).

**Decision.** No source change. Close the bullet with this decision so
future cycles don't re-verify. This cycle also appends a pain-point
observation: 4 stale bullets closed in 11 cycles (~36%) is high enough
that future repopulates should start with a live-liness sweep over the
existing backlog before producing new bullets — the skill text already
proposes this; it's not enforced yet.

**Alternatives considered.**
- **Verify Gemini abstraction depth with a second test** that exercises
  a tool-call+tool-response round trip against the live provider (not
  just SSE replay). Rejected: the provider-neutrality invariant is a
  static compile-time claim ("no `GenerateContentResponse`-typed values
  in Agent code"), not a per-message runtime behaviour. Verifying it
  live requires a real Gemini endpoint + API key, which is provider-
  specific test infrastructure, not a bullet closure. If a user wants
  that coverage they can run the existing CLI `/agent` flow against a
  keyed Gemini instance.
- **Add missing OpenCode-tier features** — streaming prompt caching,
  Gemini-specific structured-output mode, batch requests. Rejected:
  out of scope; none of those are bullet deliverables. The current
  impl covers the bullet's explicit "text one turn + defer tool-use"
  baseline and then some.

**Coverage.** Re-ran `./gradlew :core:jvmTest --tests '*.GeminiProvider*'
--tests '*.ProviderRegistryBuilderTest'` on clean main — green. No
source change means no new test; the decision file + backlog delete are
the only artifacts.

**Registration.** Already complete: `ProviderRegistry.addEnv` /
`.addSecretStore` both land the provider, `SecretBootstrap` exposes it
in the CLI first-run flow, and `SecretKeys.GEMINI` / `.GOOGLE` cover
persisted storage.
