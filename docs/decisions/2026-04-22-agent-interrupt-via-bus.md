## 2026-04-22 — Bus-driven Agent cancel path (`BusEvent.SessionCancelRequested` + 500ms latency guardrail)

**Context.** Backlog bullet `agent-interrupt-via-bus` (P2,
rubric §5.4). Prior state: `Agent.cancel(sessionId)` worked
correctly, but the only way to invoke it was to hold a direct
`Agent` reference. Every transport layer (CLI Ctrl+C handler,
server HTTP endpoint, future IDE abort button) needed to thread
an `Agent` handle through its own wiring. OpenCode solves the
same problem via its bus (`packages/opencode/src/session/prompt.ts`
abort-controller flow): any subscriber publishes an abort event,
the agent drives the cancel. Without a platform-uniform cancel
path, each new platform risks duplicating the plumbing or missing
the cancel entirely. There was also no test pinning the
user-observable latency from "Ctrl+C pressed" to "run actually
cancelled" — a regression where the cancel subscription landed on
a stalled dispatcher would go unnoticed.

**Decision.** Four surgical edits, no public API breakage on the
existing `Agent.cancel` path.

1. **`BusEvent.SessionCancelRequested(sessionId)` sealed-interface
   member** (`core/bus/BusEvent.kt`). Request-shape in,
   result-shape out: it pairs with the existing
   `SessionCancelled` which fires *after* the in-flight run
   finalises. Subscribers watching for completion key off
   `SessionCancelled` + `FinishReason.CANCELLED` on the assistant
   message — not the request event.

2. **`EventBus.events` widened from `Flow<BusEvent>` to
   `SharedFlow<BusEvent>`** (`core/bus/EventBus.kt`). Required so
   the Agent's cancel subscriber can use `onSubscription { … }`
   for a ready-handshake (see point 4 below). The field is already
   backed by a `MutableSharedFlow`; this widening exposes the
   extra operators without changing runtime behaviour.

3. **`Agent` init block subscribes on construction**
   (`core/agent/Agent.kt`). Launches on `backgroundScope`,
   filters for `SessionCancelRequested`, calls `cancel(ev.sessionId)`
   per event. Throwables wrapped in `runCatching` — a cancel during
   shutdown must never propagate out of the scope and kill the
   supervisor. Chain ordering matters: `onSubscription` only exists
   on `SharedFlow`, so it **precedes** `filterIsInstance` (which
   downgrades the chain to a plain `Flow`).

4. **`Agent.awaitCancelSubscriptionReady()` test hook**
   (`core/agent/Agent.kt`). `CompletableDeferred<Unit>` completed
   by `onSubscription`. Tests must await it before the first
   `bus.publish` — `MutableSharedFlow` has no replay, so an event
   emitted before the collector is active would be dropped.
   Production is unaffected: Agent is constructed once at app
   startup and the first user cancel is much later, so the handshake
   has always completed by then.

5. **E2E test `AgentCancelViaBusTest`**
   (`core/src/jvmTest/.../agent/AgentCancelViaBusTest.kt`). Two
   cases:
   - `cancelRequestedEventCancelsInFlightRunViaBus`: hanging
     provider, publish `SessionCancelRequested`, expect
     `CancellationException` on the run job AND
     `FinishReason.CANCELLED` on the stored assistant message AND
     `elapsedNow() < 500ms` from publish → rejection.
   - `cancelRequestedForIdleSessionIsSilentNoOp`: publishing for a
     session with no in-flight run must not throw (matches
     `Agent.cancel()` returning false on idle).

   Wallclock measurement uses `Dispatchers.Default` +
   `TimeSource.Monotonic` inside `withContext`, not `runTest`'s
   virtual clock — the latency budget is a user-visible
   responsiveness contract, not a synthetic scheduler tick. 5-second
   `withTimeout` around `runJob.await()` guards against the
   dispatcher deadlocking without the test hanging CI.

**Why 500ms.** Tight enough to catch regressions where the cancel
subscription accidentally runs on a stalled dispatcher, adds an
artificial delay, or triggers an extra round-trip. Loose enough
to survive a slow CI worker under contention. OpenCode's abort
flow has no equivalent pinned budget — this is a stricter
contract than upstream.

**Alternatives considered.**

- *Keep `Agent.cancel` as the only path, wire each transport
  directly.* Rejected — every platform repeats the plumbing, and
  server / CLI / desktop end up with three subtly different
  cancel shapes. Bus already carries every other cross-layer
  signal (compaction, permission, retry); cancel is the odd one
  out.

- *Emit `SessionCancelRequested` on the bus from
  `Agent.cancel()` itself so in-tree callers still use the direct
  API.* Rejected — muddies the request/result distinction.
  Callers with an Agent handle keep using `Agent.cancel(sid)`
  directly; callers without one use the bus. Both funnel into
  the same `cancel()` body.

- *Replay the last event on subscription.* Rejected — the bus is
  shared across all sessions, a replay buffer would mean *every*
  new subscriber sees the last cancel regardless of session. The
  ready-handshake is smaller surface.

- *Poll `isRunning(sid)` from the publisher and back-off.*
  Rejected — violates §5.4 by making cancellation eventually
  consistent rather than event-driven, and adds per-transport
  polling loops.

**What didn't change.** Existing `Agent.cancel(sessionId)` stays
— the bus path is additive. All prior tests
(`AgentCancellationTest`, retry, state-machine) still pass
unmodified. `AgentMetricsSink` gets one new branch
(`session.cancel.requested`) so the counter registry covers the
new event without dropping to the "unknown event" default.
`ServerModule` gets two parallel branches (eventName +
`BusEventDto.from`) so SSE subscribers receive the request event
alongside the existing `session.cancelled`.

**Follow-ups.** None blocking. Possible future work:

- Wire a CLI `^C` handler that publishes `SessionCancelRequested`
  instead of calling `Agent.cancel` directly, so the CLI stops
  needing an Agent reference in its signal path.
- Add a per-session debounce if a user double-taps Ctrl+C —
  currently each publish triggers an additional `cancel(sid)`
  call, but the second returns `false` against an already-cancelled
  run, so there's no visible misbehaviour. Not worth fixing until
  we see it.
