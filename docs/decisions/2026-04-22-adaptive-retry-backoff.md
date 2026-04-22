## 2026-04-22 — Retry backoff gets jitter + rate-limit floor + kind classification (VISION §5.2 rubric)

Commit: `d345a93`

**Context.** `RetryPolicy.delayFor(attempt, retryAfterMs?)` already did
exponential backoff (2s → 4s → 8s → 16s → 30s cap) and honoured provider
`retry-after` headers. Two production signals were still missing:

- **No jitter.** A deployment that restarts mid-outage comes back
  all-at-once; every client retries at t+2s, t+4s, t+8s in lockstep and
  hammers the provider synchronously. Classic thundering-herd.
- **No kind-aware shaping.** Retrying a 429 in 2 seconds is ~guaranteed to
  fail again (quota windows reset on minute-scale); retrying a 5xx in 2s
  often succeeds. One curve for both wastes calls on the rate-limit path.

Backlog bullet (`docs/BACKLOG.md` P1, after `per-clip-incremental-render`
skipped again and `project-export-portable-envelope` found stale):
`adaptive-retry-backoff`. Also this cycle removes the already-shipped
stale `project-export-portable-envelope` entry (feature landed in the
2026-04-21 decision of the same name).

**Decision.**

1. **`RetryPolicy` grows two optional knobs:**
   - `jitterFactor: Double = 0.2` — randomly multiplies the computed
     exponential delay by `[1 - jitter, 1 + jitter]`. Default 20% — enough
     to desynchronise clients without meaningfully extending worst-case
     wait. Zero disables for deterministic tests; `RetryPolicy.Deterministic`
     is a pre-set convenience.
   - `rateLimitMinDelayMs: Long? = 15_000` — when the classified
     [BackoffKind] is `RATE_LIMIT` and no `retry-after` header is provided,
     the exponential delay is floored at this value. 15s is a heuristic
     consistent with OpenAI / Anthropic quota-reset windows; providers
     that supply explicit headers still win. Null disables the floor for
     rigs that don't want quota-specific shaping.

2. **New `BackoffKind` enum** with four values: `RATE_LIMIT`, `SERVER`,
   `NETWORK`, `OTHER`. Deliberately shallow — we don't want per-enum
   policy tuning. OTHER is the "I can't classify, use base curve" escape
   hatch so unclassified messages never fail closed. §3a rule 4 passes —
   it's a 4-state enum, not a binary flag.

3. **`RetryClassifier.kind(message, retriable)`** mirrors the existing
   `reason(message, retriable)` call site. Classification priority:
   HTTP status (transport signal) beats semantic text (which may be
   embedded in a message body). `HTTP 503: rate_limit_internal` classifies
   as SERVER, not RATE_LIMIT — matching the existing
   `EventBusMetricsSink.retryReasonSlug` heuristic. Existing `reason()` API
   unchanged.

4. **`Agent.runLoop`** now passes the classified kind into
   `retryPolicy.delayFor(attempt, retryAfterMs, kind)`. One-line change in
   the existing retry branch — no restructuring. Provider fallback
   behaviour preserved (chain advances when `attempt >= maxAttempts`
   regardless of kind).

5. **`delayFor` signature** additions are non-breaking: all new params
   default. Existing `delayFor(attempt, retryAfterMs)` callers compile
   and execute unchanged. The `random: Random` param is exposed for
   tests to inject seeded randomness — production uses `Random.Default`.

**Alternatives considered.**

- **`Map<BackoffKind, RetryPolicy>`** — one policy per kind. Rejected as
  over-engineered. Policies are nearly identical across kinds; varying the
  floor is the only meaningful dimension for 90% of cases and can be
  captured with a single optional field. We'd be trading a fresh nested
  API for configuration that nobody today wants to set.
- **Full `BackoffStrategy` sealed class** (`FlatDelay`, `Exponential`,
  `ExponentialJitter`, `Custom`) — the idiomatic "industry shape" (akin to
  AWS SDK's `RetryStrategy`). Rejected as premature abstraction. One
  implementation satisfies every current caller; a sealed hierarchy adds
  refactor cost to the existing callers and test fixtures without
  delivering proportionate value. When a second strategy becomes real
  (e.g. capped-at-N-retries for tool-dispatch errors), split then.
- **Put jitter on every call unconditionally** — rejected. Tests that
  pin exact delays (like `AgentRetryTest.delayHonorsRetryAfterHeader`)
  would break in a non-deterministic way; they now explicitly opt-in via
  `jitterFactor = 0.0`. Production gets jitter by default; test rigs
  stay predictable.
- **Move classification into the provider layer** — rejected. Providers
  already emit `LlmEvent.Error.retriable`, which is what the classifier
  consumes. Keeping the taxonomy / mapping in one place (`RetryClassifier`)
  means adding a new provider doesn't require teaching it about our
  internal kind taxonomy.

**Coverage.**

- `agent.RetryPolicyTest` — 16 tests: exponential grows across attempts;
  retry-after header wins; max-delay cap applied; zero jitter is
  deterministic; jitter stays within ±band over 100 seeds; rate-limit
  floor lifts short delays; floor doesn't apply to other kinds; null
  floor disables feature; retry-after beats floor; constructor validation
  rejects bad inputs; `kind()` classifies HTTP 429 as RATE_LIMIT; HTTP
  5xx as SERVER; semantic rate-limit/quota text as RATE_LIMIT;
  overloaded/unavailable as SERVER; network/timeout text as NETWORK;
  unknown/null as OTHER; HTTP status beats embedded semantic text.
- `agent.AgentRetryTest.delayHonorsRetryAfterHeader` — updated to opt-in
  to `jitterFactor = 0.0` so the existing exact-value assertions still
  hold. All other AgentRetryTest / AgentProviderFallbackTest cases
  continue to pass with default jitter (they look at bus events +
  emitted-content invariants, not exact delay values).
- `:core:jvmTest` + `:apps:server:test` + `:apps:android:assembleDebug` +
  `:apps:desktop:assemble` + `:core:compileKotlinIosSimulatorArm64` +
  `ktlintCheck` all green.

**Registration.** Pure core change — no new tools, no AppContainer
updates. `Agent.kt` gets a one-line classification call on the existing
retry branch.

---
