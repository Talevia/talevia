## 2026-04-23 — SynthesizeSpeechTool fallback chain over ordered TtsEngine list (VISION §5.2 provider-neutrality)

**Context.** `SynthesizeSpeechTool` held a single `TtsEngine` — if the
configured provider returned 5xx / network-errored / hit a rate limit it
couldn't recover from, the tool surfaced a hard failure even though a
second provider was wired elsewhere in the container (e.g. a fallback
ElevenLabs once its `TtsEngine` lands, or a self-hosted Coqui engine
for offline builds). The bullet asked for a priority-ordered engine
list with on-failure failover and a lockfile record of which provider
actually produced the audio. Rubric delta §5.2 provider fallback
`无 → 部分` (the shape is in place; cache-hit short-circuits the
chain; we still only ship one production `TtsEngine` today, so the
fallback is only exercised synthetically by tests).

**Decision.** Changed `SynthesizeSpeechTool`'s primary ctor to take
`engines: List<TtsEngine>` with `require(engines.isNotEmpty())` at
init. Added a secondary-ctor convenience `SynthesizeSpeechTool(engine,
bundleBlobWriter, projectStore)` that forwards `listOf(engine)` —
the 3 live AppContainers (CLI / Desktop / Server) all wire a single
`OpenAiTtsEngine`, so the secondary ctor kept their registration call
sites unchanged.

Core change in `execute()`: replaced the direct `engine.synthesize(...)`
with a new `synthesizeWithFallback(engines, request)` helper. The
helper iterates the engine list, returns the first successful
`TtsResult`, and on total failure throws with a message that enumerates
every attempted provider + its failure reason — so a misconfigured
chain surfaces "openai: rate-limited; elevenlabs: network-refused",
not just the tail error. `CancellationException` is re-thrown
immediately so the progress watcher's cancel path fires.

The lockfile already records `result.provenance.providerId` —
whichever engine actually produced the audio gets stamped, so mixed-
provider history is auditable after the fact without a new field.
Cache hits short-circuit the whole chain (same `AigcPipeline.findCached`
path as before); they don't call any engine.

**Alternatives considered.**
- **Ordered map `Map<String, TtsEngine>` instead of `List`.** Would
  let callers pass `mapOf("primary" to engineA, "fallback" to engineB)`
  and expose names in the error message. Rejected: adds a keying
  convention every test + container needs to follow, when `providerId`
  on each engine already carries the label. The list shape is the
  minimum viable — names come from `engine.providerId`, ordering from
  list position. Map would be noise without a real need.
- **Per-attempt metric event via `BusEvent`.** Would let dashboards
  graph "primary failed, secondary succeeded" rates. Rejected for
  this cycle: no consumer today; adding the event without a sink is
  dead code. `AigcCacheProbe` already publishes; a
  `TtsEngineFallback(providerId, attempt, outcome)` can follow when
  a dashboard actually wants it.
- **Retry the same engine before falling through.** Some 5xx / 429
  are transient and don't indicate provider-wide outage. Rejected:
  in-engine retry (with backoff) is `TtsEngine`-level concern —
  `OpenAiTtsEngine` / `ReplicateMusicGenEngine` etc. already handle
  that shape via `RetryAfter` headers. The tool-level chain is
  specifically for "this engine gave up; try someone else". Mixing
  would make the chain confusing.
- **Single-ctor that just takes `List`** (drop the secondary
  convenience ctor). Rejected: every existing container would need a
  `listOf(openAiTts)` wrap, and the single-engine case is the
  overwhelming majority today. The secondary ctor keeps call sites
  unchanged while the new list ctor is available for containers that
  wire multiple engines. Dead-code follow-up if the list ctor ever
  becomes dominant.

**Coverage.** New tests in `SynthesizeSpeechToolTest`:
- `fallbackChainUsesSecondEngineWhenFirstThrows` — primary
  deterministically throws, secondary succeeds; asserts both engines
  were attempted and `Output.providerId` records the one that
  produced the audio.
- `fallbackChainExhaustedPropagatesLastFailure` — every engine
  throws; error message names every attempted provider.
- `fallbackChainShortCircuitsOnCacheHit` — second call with identical
  inputs cache-hits via the lockfile; neither engine is probed (the
  fallback never even evaluates).
- `emptyEngineListFailsLoudAtConstruction` — `require` fires at ctor
  time, not at execute time.

`:core:jvmTest` green (all 12 tests in the file — 8 existing + 4 new —
pass). `:core:compileKotlinIosSimulatorArm64`, `:apps:android:assembleDebug`,
`:apps:desktop:assemble`, `ktlintCheck` all green. The secondary ctor
means no `AppContainer` needed editing; every production wiring
continues to register `SynthesizeSpeechTool(openAiTts, ...)`
unchanged.

**Registration.** No container changes. `RegisteredToolsContractTest`
still passes — class name hasn't moved, just the ctor signature
widened.
