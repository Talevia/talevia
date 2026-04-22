## 2026-04-21 — Agent provider-chain fallback on exhausted retries (VISION §5.2 rubric)

Commit: `719d155`

**Context.** The Agent already retries transient provider errors
(`AgentRetryTest` — 5xx, 429, overload text) on the same provider with
exponential backoff. But when a whole provider is down or a whole
account is rate-limited, retries on that same provider exhaust and
the turn terminally fails. The user has to manually switch provider
via `switch_provider` (if there even is a secondary configured).
OpenCode's `packages/opencode/src/session/llm.ts` has a two-tier
retry model: transient retry on current provider, then fallback to
the next provider in a configured chain. Talevia's backlog bullet
(`provider-auto-fallback`, P0 top) called out the same pattern.

**Decision.** Two-tier retry/fallback is wired into Agent's existing
retry loop. Surface changes:

1. `Agent.fallbackProviders: List<LlmProvider> = emptyList()` new
   optional constructor param. Empty default preserves pre-fallback
   behavior — composition roots gain the feature by passing
   `providers.all() - primary`, and 4 of 5 AppContainers (CLI /
   Desktop / Server / Android / iOS) now do that automatically so if
   the user has both `ANTHROPIC_API_KEY` and `OPENAI_API_KEY`
   configured they get fallback for free.
2. `AgentTurnExecutor` owns the chain as `providers: List<LlmProvider>`
   (primary + deduped-by-id fallbacks, primary first). `streamTurn`
   gains a `providerIndex: Int = 0` parameter.
3. `Agent.runLoop` maintains `providerIndex`. On retry exhaustion
   (attempt == maxAttempts AND still no content), advances
   `providerIndex` by one, resets `attempt`, continues. Halts when
   index ≥ chain size. Mid-stream failures (content already emitted)
   still terminate the turn — same guard as retry, same reason:
   switching providers mid-response would give the user two truncated
   halves stitched.
4. New `BusEvent.AgentProviderFallback(sessionId, fromProviderId,
   toProviderId, reason)` emitted once per chain advance. Wired into
   metrics as `agent.provider.fallback` counter and into the server's
   SSE envelope (`BusEventDto` gets `fromProviderId` / `toProviderId`).

Zero new tools. Zero LLM-side context change (fallback is invisible
to the model's prompt). Zero schema migration (no persistent state
for the chain — it's pure runtime wiring).

**Alternatives considered.**

- *New `ProviderChain` class that implements `LlmProvider` and
  internally iterates on stream failure*: rejected — `LlmProvider.stream`
  returns a `Flow<LlmEvent>`; detecting "that flow failed terminally"
  from inside another flow operator is awkward because the flow has
  already emitted its events. The retry loop in Agent is already the
  right place (classifies errors, guards on `emittedContent`, owns
  retry budget) — extending it to also advance `providerIndex` is
  the smallest valid edit.
- *Parallel fan-out to all providers and take first success*: rejected
  — burns N× cost for the 99% case where primary works. The backlog
  bullet is about outage recovery, not latency optimisation.
- *Fallback on any error kind, not just retriable*: rejected —
  permanent errors (invalid API key, malformed request, 401) don't
  benefit from trying a different provider and would confuse the
  user's debugging (all providers silently attempted for the same
  broken key). We reuse `RetryClassifier` so the fallback trigger
  condition matches the retry condition exactly — if the error
  isn't worth retrying, it isn't worth falling back either.
- *Make fallback a Tool the LLM opts into*: rejected — the whole
  point is removing the "manual switch" step. Exposing it as a tool
  just moves the burden from the user to the model, and the model
  doesn't know the current provider's outage status at decision time.
- *Mid-stream switch with output reset*: rejected — the user has
  already seen the primary's partial content; tearing it down and
  re-streaming from secondary gives a confusing UX ("did you just
  rewrite that?"). Standard incident-recovery discipline: preserve
  what the user saw, fail honestly, let them retry if they want.

Industry consensus referenced: AWS SDK's default retry + circuit
breaker pattern (stop hammering a failing endpoint, try a fallback
endpoint once); OpenCode's `session/llm.ts` two-tier recovery;
`kubernetes` deployment rollout's "max-surge + max-unavailable"
graduated approach to partial failure — all share the principle
"retry within a failure class, escalate across failure classes."

**Coverage.**

- `AgentProviderFallbackTest.exhaustedPrimaryFallsThroughToSecondary`
  — primary fails 2x (retry budget), secondary succeeds on first try;
  exactly one `AgentProviderFallback` event with correct `fromProviderId`
  / `toProviderId`.
- `AgentProviderFallbackTest.emptyFallbackListPreservesRetryOnlyBehavior`
  — regression guard: empty `fallbackProviders` behaves identically
  to pre-fallback Agent (3 primary attempts then terminal error, no
  fallback event).
- `AgentProviderFallbackTest.midStreamFailurePreservesPartialDoesNotFallback`
  — primary streams some text then errors; secondary is never
  invoked (`requests.size == 0`), no fallback event.
- `AgentProviderFallbackTest.allProvidersFailingSurfacesTerminalError`
  — primary 2/2 + secondary 2/2 all fail; terminal error; both
  providers got their full retry budget.
- `AgentProviderFallbackTest.fallbackProviderWithSameIdAsPrimaryIsDeduplicated`
  — defence-in-depth for the deduplication filter in Agent's
  executor-build code; same-id fallback doesn't produce extra attempts.

Existing `AgentRetryTest` suite stays green (no behavior change for
empty chain) — regression guard for rule that empty fallback list is
isomorphic to pre-fallback behavior.

**Registration.** Non-empty changes in five composition roots (the
point of the feature is passing a non-empty chain):
- `apps/cli/src/main/kotlin/io/talevia/cli/CliContainer.kt`
- `apps/desktop/src/main/kotlin/io/talevia/desktop/AppContainer.kt`
- `apps/server/src/main/kotlin/io/talevia/server/ServerContainer.kt`
- `apps/android/src/main/kotlin/io/talevia/android/AndroidAppContainer.kt`
- `apps/ios/Talevia/Platform/AppContainer.swift` (via a new optional
  `fallbackProviders` param on `newIosAgent`)

Plus two wire-format updates: `BusEvent.AgentProviderFallback` event
variant, `MetricsRegistry` counter name, and `BusEventDto` JSON
fields for the SSE server.

§3a checklist pass:
- #1 zero new tools. ✓
- #2 no Define/Update pair. ✓
- #3 no `Project` blob growth. ✓
- #4 not a binary flag (provider chain is an ordered list; zero-length
  = "no fallback" is a valid state). ✓
- #5 provider-chain vocabulary is genre-neutral. ✓
- #6 no session-binding surface added. ✓
- #7 new fields on `Agent` constructor + `BusEvent.AgentProviderFallback`
  all default to empty/null; old serialised BusEvents can't exist on
  disk (BusEvents are transient), but the new server DTO fields are
  optional. ✓
- #8 wired into all five AppContainers (see Registration above). ✓
- #9 five tests cover: happy fallback, regression (empty chain),
  mid-stream guard, all-fail, dedup edge. Every retry-loop branch
  that has a fallback interaction has an assertion. ✓
- #10 zero net LLM context change — the feature is invisible to the
  model's prompt. ✓
