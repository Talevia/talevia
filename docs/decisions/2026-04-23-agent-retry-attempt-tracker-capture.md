## 2026-04-23 — retryAttempt plumbed into StateTransition + RunFailureRow.maxRetryAttemptObserved (VISION §5.4 rubric axis)

**Context.** Cycle-41 added `retryAttempt: Int? = null` to
`BusEvent.AgentRunStateChanged` so bus subscribers could correlate
retry-tagged transitions to the run they belong to. That plumbing
stopped at the bus — `AgentRunStateTracker.StateTransition` discarded
the field, and downstream reads (`session_query(select=run_failure)`
from cycle-50, `select=run_state_history` from cycle-35) had no way
to answer "how many retries did this failed turn burn before giving
up?". Operators doing post-mortem on a Failed turn had to cross-reference
the live bus stream or stitch together multiple `message` drill-downs.

§5.4 delta (retry observability in post-mortem reads): **部分 → 有**.
Retry-tagged transitions now survive the tracker's ring buffer with
their attempt count, and both `run_state_history` and `run_failure`
selects surface it.

**Decision.** Three minimal additive changes, all defaulted so nothing
regresses:

1. `AgentRunStateTracker.StateTransition` — new field
   `val retryAttempt: Int? = null` (trailing, defaulted). The
   `AgentRunStateChanged` collector populates it from
   `event.retryAttempt` directly.

2. `RunStateTransitionRow` (from `select=run_state_history`) — new
   field `val retryAttempt: Int? = null`, populated from
   `transition.retryAttempt`. Older decoders see null; callers that
   want retry annotations see them for free.

3. `RunFailureRow` (from `select=run_failure`) — new field
   `val maxRetryAttemptObserved: Int? = null`. Computed in-window
   (`[message.createdAt, nextMessage.createdAt)` — same wall-clock
   scope the query already uses for `runStateTerminalKind` and
   `fallbackChain`) as `trackerHistory.mapNotNull { it.retryAttempt }.maxOrNull()`.
   Null when no retry-tagged transitions were seen — distinguishes
   "never retried" from "retried once" (first-attempt failure stays
   null, not 0).

**Axis.** n/a — additive plumbing, not a refactor. Growth axis for
`RunFailureRow`: new post-mortem facets (retry count, fallback chain,
compaction hits, ...). Each facet comes in as an additive defaulted
field matching cycles 57 and 58's shape. Consumers that serialize old
JSON with no new field stay compatible via the defaults.

**Alternatives considered.**

- **Separate retry-attempt tracker parallel to `AgentProviderFallbackTracker`.**
  Rejected — the retry attempt count is already
  1-to-1 with `AgentRunStateChanged` events, so any new tracker would
  just subscribe to the exact same bus event `AgentRunStateTracker`
  already handles. Bolting an extra ring buffer on for the same event
  = double storage for no gain. The existing tracker's `StateTransition`
  ring buffer was the right place; it just needed the field.

- **Keep `retryAttempt` bus-only; derive max in a post-hoc scan.**
  Rejected — a post-hoc scan of `BusEvent` stream requires subscribers
  to have tailed the bus from before the failure. Late subscribers
  (the whole reason `AgentRunStateTracker` exists) would see nothing.
  The tracker's raison d'être is "what did the Agent just do without
  having streamed it live"; retry attempts clearly fall under that.

- **Extend `RunFailureRow.stepFinishErrors` with retry metadata.**
  Rejected — `StepFinishErrorEntry` is per-step persisted state
  (`Part.StepFinish`) and retries don't correspond 1:1 with
  step-finish ERROR parts (retries that ultimately succeed never
  produce an error step-finish; retries mid-tool-dispatch that
  fail do, but the retry count is attached to the state transition
  not the step-finish). Surfacing retry count at the `RunFailureRow`
  level — alongside `fallbackChain` and `runStateTerminalKind` —
  matches the semantic axis (post-mortem signals, not persisted part
  attributes).

**Coverage.** 3 new tests in
`core/src/jvmTest/.../agent/AgentRunStateTrackerRetryAttemptTest.kt`:

1. `retryAttemptPropagatesIntoStateTransition` — retry=2 event →
   transition carries retryAttempt=2.
2. `retryAttemptDefaultsNullWhenEventOmitsIt` — legacy null-default
   flow preserved; no "0 vs null" confusion.
3. `maxRetryAttemptAcrossHistoryIsRecoverableByConsumers` — multiple
   retries coexist in the ring buffer; highest wins for the
   `RunFailureRow` query's use case.

Plus 2 new tests in `SessionQueryRunFailureTest`:

- `maxRetryAttemptObservedPickedFromTrackerHistory` — end-to-end via
  bus → tracker → query: two retry events (1 then 3) + terminal
  Failed inside a failed turn's window → row reports 3.
- `maxRetryAttemptObservedNullWhenNoRetriesInWindow` — guards the
  null-not-zero contract for first-attempt failures.

Gradle: `:core:jvmTest` + `:apps:cli:test` + `:apps:server:test` +
`:apps:desktop:assemble` + `:apps:android:assembleDebug` +
`:core:compileKotlinIosSimulatorArm64` + ktlintCheck all green.

**Registration.** No new tool, no new select, no container changes.
Three additive fields on existing data classes; all containers
already wire the trackers that surface them.

**§3a arch-tax check (#12).** No new tool. No new select. No new
reject rule. `ToolSpecBudgetGateTest` unchanged (additive JSON field
on existing row, no schema / helpText delta). Nothing triggers.
