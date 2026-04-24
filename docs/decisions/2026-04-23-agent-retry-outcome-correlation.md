## 2026-04-23 — `AgentRunStateChanged.retryAttempt` correlation field (VISION §5.4 rubric axis)

**Context.** Before this cycle, `BusEvent.AgentRetryScheduled(attempt,
waitMs, reason)` fired when the Agent scheduled a retry, but the
follow-up `AgentRunStateChanged(Generating)` /
`AgentRunStateChanged(Idle|Failed)` transitions carried no correlation
to that retry. Operators watching SSE / CLI event streams could see
"retry #2 was scheduled" followed by "agent transitioned to Idle",
but could only answer "did retry #2 succeed?" by log-joining wall-
clock timestamps across the two event types. For a server running
many concurrent sessions, that join requires a log pipeline — not
ideal when the whole point of the bus is live observability from a
CLI or HTTP subscriber.

Rubric delta §5.4 (agent-loop observability): retry outcome
correlation moves from **无** (ops must log-join by timestamp + session
id) to **有** (every post-retry state transition, including the
terminal one, carries the attempt number that triggered the current
streaming slice).

**Decision.** Added `retryAttempt: Int? = null` as a trailing
defaulted field on `BusEvent.AgentRunStateChanged`. Semantics:

- Null for any transition that fires before any retry has been
  scheduled during the current `Agent.run` (including the initial
  `Generating` at run start and any `AgentRunStateChanged` from a
  clean turn).
- After the first retry schedule, stamped with the **most recent**
  `AgentRetryScheduled.attempt` and persists through every subsequent
  transition for the rest of the run — the terminal `Idle` / `Failed`
  / `Cancelled` carries the answer to "did retry #N succeed?" all on
  its own.
- Monotonically non-decreasing within one run. Provider fallbacks
  (the companion `AgentProviderFallback` event) do NOT reset it —
  that event already carries the `fromProviderId` / `toProviderId`
  boundary info subscribers need; clearing `retryAttempt` on fallback
  would hide "retry #2 failed so we fell back" from the terminal emit.

Wiring:

- `Agent.RunHandle` gained `@Volatile var lastRetryAttempt: Int? =
  null` (already exists for per-session in-flight state; the retry
  counter rides the same handle).
- `Agent.runLoop` writes `handle.lastRetryAttempt = attempt`
  immediately after `bus.publish(AgentRetryScheduled)` and passes
  `handle.lastRetryAttempt` to every downstream state-change emit and
  into `executor.streamTurn(..., retryAttempt = ...)`.
- `Agent.run`'s terminal block reads `handle.lastRetryAttempt` on
  Idle / Failed / Cancelled emits (plus the throwable-catch branch).
  Initial `Generating` emit at run start explicitly passes null —
  first attempt is not a retry.
- `AgentTurnExecutor.streamTurn` accepts `retryAttempt: Int? = null`
  and threads it to the 3 in-turn `AgentRunStateChanged` emits
  (AwaitingTool at tool-dispatch entry, the unknown-tool Generating
  exit, the post-dispatch Generating). `dispatchTool` takes the same
  parameter (defaulted).
- Compaction transitions (`Compacting` ↔ `Generating`) in
  `Agent.runLoop` also propagate `handle.lastRetryAttempt` — an auto-
  compaction triggered mid-step after a retry should inherit the
  current retry number so subscribers don't see a brief "retryAttempt
  went back to null during compaction" glitch.

Server-side exposure: `BusEventDto.runStateRetryAttempt: Int? = null`
wires the field into the JSON stream so SSE clients see it as
`runStateRetryAttempt` (distinct from the `attempt` field that
`AgentRetryScheduled` already uses — avoids semantic overload on one
DTO slot).

**Axis.** n/a — net-new correlation field.

**Alternatives considered.**

- **Store a `(sessionId → attempt)` map on a sidecar observer object**
  that subscribers read after receiving `AgentRunStateChanged`.
  Rejected: the mapping is already in-flight as `RunHandle`
  state; exposing it via a new side-channel API duplicates the
  single-writer model and forces every subscriber to know about both
  the event and the map. Inlining the field on the event keeps the
  correlation in the same message.

- **Add a new `BusEvent.AgentRetryOutcome(attempt, state)` event**
  emitted right after the retry's terminal transition. Rejected:
  that's a third event in the retry sequence; subscribers already
  watch `AgentRunStateChanged` for coarse phase info and
  `AgentRetryScheduled` for the intent — carving out yet another
  event for "this particular run-state-change was the retry outcome"
  is redundant. Inlining the correlation on the existing
  `AgentRunStateChanged` matches how `SessionCompactionAuto` +
  `SessionCompacted` pair without a third "compaction outcome" event.

- **Reset `retryAttempt` on provider fallback or at each new step.**
  Rejected: provider fallback has its own `AgentProviderFallback`
  event with the transition metadata, and a step boundary that
  happens *after* a retry-driven success should still let the
  terminal Idle say "most recent retry was #2". Reset semantics would
  turn the field into "current step's retry counter" which is less
  useful — subscribers wanting step-local retries can derive that by
  also watching `AgentRetryScheduled` + `StepStart` parts. §3a #4
  "avoid binary / over-reset state" principle applies: a
  monotonically-growing counter is more informative than one that
  periodically resets to null for reasons the subscriber has to
  reconstruct.

- **Put the new field on `BusEventDto.attempt` (reuse the existing
  slot that `AgentRetryScheduled` already writes to).** Rejected:
  the two semantics are distinct — `AgentRetryScheduled.attempt` is
  "this retry's number", `AgentRunStateChanged.retryAttempt` is
  "most recent retry that fired this run". Reusing one field would
  make it impossible for a DTO-only consumer to tell which event
  shape they're looking at from the attempt alone. Separate field
  (`runStateRetryAttempt`) keeps the JSON self-documenting.

**Coverage.**

- `AgentRetryTest.retryAttemptPropagatesToTerminalRunStateChanged`:
  retry-then-success scenario; asserts terminal `Idle` carries
  `retryAttempt = 1`.
- `AgentRetryTest.retryAttemptRemainsNullWhenNoRetryFired`: control —
  a clean run with no retry leaves every captured
  `AgentRunStateChanged.retryAttempt == null` (default-null pin).
- `AgentRetryTest.retryAttemptBumpsMonotonicallyAcrossMultipleRetries`:
  two retries + success; asserts terminal `Idle` carries
  `retryAttempt = 2`, pinning the monotonic-non-decreasing contract.

§3a #9 bounded-edge coverage: the first test pins the happy path;
the second is the null-default pin (catches accidental "always
stamp 0"); the third pins monotonic bumping (catches accidental "keep
only first retry attempt" or "reset between retries"). Timing note:
tests assert on `stateEvents.last()` rather than `.first()` because
the initial `Generating` event fires within microseconds of Agent.run
entry and can race `Dispatchers.Default` collector installation — a
documented `runTest + bus` gotcha already surfaced by existing retry
tests.

**Registration.** No tool changes — pure bus shape + DTO extension.
No `AppContainer` edits needed; `AgentRunStateChanged` is already
published by `Agent` and subscribed where relevant.

Gradle: `:core:jvmTest` + `:apps:cli:test` + `:apps:server:test` +
`:apps:desktop:assemble` + `:core:compileKotlinIosSimulatorArm64` +
`:apps:android:assembleDebug` + ktlintCheck all green.
