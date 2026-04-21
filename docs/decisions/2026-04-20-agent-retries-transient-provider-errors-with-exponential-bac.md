## 2026-04-20 — Agent retries transient provider errors with exponential backoff

**Context.** Anthropic / OpenAI / Gemini all emit 5xx / 429 /
"overloaded" / "rate_limit" responses under load. Before this change
those turned into a dead assistant turn — `finish = ERROR`, one ugly
row in the transcript, and the user had to retype their prompt. Even
OpenCode — our reference spec — catches these at `session/retry.ts`
and transparently replays.

**Decision.**

Add retry at the Agent loop level, not at the `LlmProvider.stream`
level, for one reason: partial persisted state. `streamTurn` writes
parts (text / reasoning / tool calls / `StepFinish`) into the store
as they arrive. A retry must therefore delete the failed assistant
row — an operation the provider can't perform on its own and that we
already have via `SessionStore.deleteMessage` (courtesy of the
revert work).

Contract:

1. **New `RetryPolicy` data class** (core/agent). `maxAttempts = 4`,
   exponential backoff `2s, 4s, 8s, …` capped at 30s when no
   `Retry-After` header is available, globally capped at 10 min.
   `RetryPolicy.None` disables retry entirely for tests / batch
   runs that want failure signals without interference.

2. **New `RetryClassifier`** pure function. Input: the
   `LlmEvent.Error.message` + the provider's `retriable` hint.
   Output: a human-readable reason (logged + published on the bus)
   or `null` to bail. Recognises: HTTP 5xx / 429, "overloaded",
   "rate limit", "too many requests", "exhausted", "unavailable".
   Explicitly refuses to retry context-window-exceeded errors,
   even if the provider marked them retriable (they will never
   succeed on replay — they need compaction, not retry).

3. **`LlmEvent.Error` grows `retryAfterMs: Long?`** — parsed by each
   provider from the `retry-after-ms` / `retry-after` response
   headers via a shared `parseRetryAfterMs(...)` helper. When
   present the policy honours the server's hint; otherwise it
   falls back to exponential backoff.

4. **Providers tag their HTTP error events with `retriable = true`**
   when status is 5xx / 429 / 408. Both Anthropic and OpenAI (and
   Gemini as a bonus) get the treatment. Keeps the classifier's
   job straightforward — 99 % of the time the provider has already
   given us the right answer and we only fall through to string
   matching for non-HTTP cases (stream-level error events).

5. **`Agent.runLoop` wraps `streamTurn` in a retry loop.** On
   `FinishReason.ERROR`:
   - if the error is retryable AND no content was streamed yet AND
     attempts remain → delete the assistant message (cascades
     parts), publish `BusEvent.AgentRetryScheduled`, delay, loop
   - else propagate the error to the transcript as today.

   "No content streamed yet" is tracked inside `streamTurn` as a
   boolean flipped on the first `TextStart` / `ReasoningStart` /
   `ToolCallStart`. Mid-stream errors (rare — Anthropic emits them
   via an SSE `error` frame after some content) are NOT retried:
   the user has already seen partial output and silently replacing
   it would be worse than the stale turn.

6. **New bus event `BusEvent.AgentRetryScheduled(attempt, waitMs,
   reason)`** — consumed by the CLI renderer (`Retrying in 4s —
   Provider is overloaded`) and the server's SSE stream. UI
   surfaces can show "retrying..." instead of leaving the turn
   looking hung.

**Alternatives considered.**

- **Retry inside the provider's `stream(...)` Flow** — cleaner
  separation but requires each provider to know about part
  persistence semantics (or to stash and replay emitted events),
  which is exactly the coupling the `LlmEvent` abstraction was
  meant to avoid. Rejected.

- **OpenCode's Effect.js `Schedule` + `fromStepWithMetadata`** —
  direct port would fight Kotlin idioms. The equivalent here is
  boring imperative code and it's shorter.

- **Retry with a single global policy baked into Agent** — we kept
  the `retryPolicy` as a constructor param so tests can inject
  `RetryPolicy.None` and server deployments (where retries compete
  with human-facing SSE timeouts) can tune the caps without
  rebuilding core.

- **Don't delete the failed assistant row, just append another** —
  rejected: leaving an ERROR row per retry clutters the transcript
  and breaks the "one user turn → one assistant turn" mental model
  users rely on in history / revert flows.

**Consequences.**

- Transient outages are invisible to the user — they see a
  "retrying…" notice and then the real reply.
- Retry counter is exposed via the metrics sink as
  `agent.retry.scheduled`, so we can see how hot any given
  provider is running.
- Max delay is 30 s per attempt by default; four attempts means
  the user waits at most ~60 s cumulative before seeing a real
  failure. Longer than ideal but matches OpenCode's heuristics
  and well under Ktor's default socket timeouts.
- No breaking changes to `LlmProvider`: providers that don't set
  `retriable` still benefit from the classifier's fallback string
  match.

---
