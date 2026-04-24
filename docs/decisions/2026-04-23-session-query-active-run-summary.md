## 2026-04-23 — session_query(select=active_run_summary) composes running-turn stats into one row (VISION §5.4 rubric axis)

**Context.** Before cycle-55, to answer "what is the agent doing right now,
and how much has it done this turn?" an operator had to cross-reference
three separate `session_query` selects:

1. `select=status` → current AgentRunState phase.
2. `select=message` (with latest `messageId`) → token usage + part summaries.
3. `select=run_state_history` → transitions timeline (to infer "when did
   the current run start? how many compactions so far?").

Three round-trips, each returning a different row shape, for a single
intuitive question that comes up constantly when babysitting a long agent
run. The read-side composition was begging to be a primitive: no new
state to add, just compose existing trackers + the latest assistant
message into one row.

§5.4 delta (operator introspection surface): **部分 → 有**. The
running-turn view joins the "current phase" + "accumulated tokens" +
"tools dispatched" + "elapsed wall-clock" axes into one atomic row.

**Decision.** New select on the existing `session_query` dispatcher:
`select=active_run_summary`. Composed from three existing sources (no
new state added):

- `AgentRunStateTracker.currentState(sid)` → `state` + `cause`.
- Latest `Message.Assistant` in the session (via
  `SessionStore.listMessagesWithParts`) → `runStartedAtEpochMs` (=
  `createdAt.toEpochMilliseconds()`), `tokensIn/Out/reasoning/cacheRead/cacheWrite`
  (from `TokenUsage`), `toolCallCount` (count of `Part.Tool`),
  `latestAssistantMessageId`.
- `AgentRunStateTracker.history(sid, since=runStartedAtEpochMs)` filtered
  to `Compacting` → `compactionsInRun`.
- Injected `Clock` (defaulted to `Clock.System`) → `elapsedMs = now -
  runStartedAtEpochMs`, clamped to ≥ 0.

Row shape (`ActiveRunSummaryRow`):

```kotlin
val sessionId: String
val state: String            // idle|generating|awaiting_tool|compacting|cancelled|failed
val cause: String? = null    // non-null only on state=failed
val neverRan: Boolean = false
val runStartedAtEpochMs: Long? = null
val elapsedMs: Long? = null
val toolCallCount: Int = 0
val tokensIn: Long = 0
val tokensOut: Long = 0
val reasoningTokens: Long = 0
val cacheReadTokens: Long = 0
val cacheWriteTokens: Long = 0
val compactionsInRun: Int = 0
val latestAssistantMessageId: String? = null
```

File: `core/src/commonMain/.../tool/builtin/session/query/ActiveRunSummaryQuery.kt`.
Wiring in `SessionQueryTool`: added `SELECT_ACTIVE_RUN_SUMMARY` constant,
`ALL_SELECTS` entry, `rowSerializerFor` branch, `execute` dispatch branch,
helpText bullet, JSON schema enum entry. One-bullet helpText delta
(~20 tokens); no new filter field needed (reuses `sessionId`).

Scope cuts (filed as follow-up bullets, already in BACKLOG as
`agent-retry-attempt-tracker-capture` and `agent-provider-fallback-chain-tracking`):

- **`retriesScheduled`** count NOT populated in this cycle. The
  `AgentRunStateTracker.StateTransition` record doesn't carry the
  `retryAttempt` field added in cycle-41 to
  `BusEvent.AgentRunStateChanged`. Once that plumbing lands, the field
  opens up here with a default.
- **`providerFallbacks`** NOT populated — needs an
  `AgentProviderFallbackTracker` sibling of `AgentRunStateTracker`, also
  filed separately. Adding it here would require a second tracker wired
  into all five AppContainers.

Both omissions are additive (new fields with defaults), so consumers
that rely on today's row shape won't break when the fields land.

**Axis.** n/a — net-new select, not a split/refactor. The growth axis
for `SessionQueryTool` itself is "new select rows" (tracked separately
by `debt-unified-dispatcher-select-plugin-shape`, trigger-gated at
≥ 20 selects; current count after this cycle: 17). Does not trip the
threshold.

**Alternatives considered.**

- **New top-level tool `describe_active_run(sessionId)`.** Rejected —
  violates §3a #1 (tool count net increase). The
  `session_query`-dispatcher pattern specifically exists to avoid each
  new read shape becoming a new tool spec. This composite read IS what
  the dispatcher was designed for.

- **Wait for the retry + fallback trackers to land, then implement
  in one shot.** Rejected — the 80% of the value (state + elapsed +
  tokens + toolCalls + compactions) is already composable from existing
  sources. Blocking the whole select on the retry-attempt tracker
  would mean operators keep doing the 3-call cross-reference for weeks
  while we incrementally ratchet the trackers. Additive JSON fields
  means shipping now doesn't cost anything when the trackers land.

- **Compute elapsed from the tracker's first non-Idle transition
  instead of the assistant message's createdAt.** Rejected — the
  tracker's ring buffer caps at 256 per session. A long-running session
  could, in theory, drop the oldest non-Idle transition of the current
  run (extremely unlikely — a single run emits a handful of
  transitions, not hundreds), at which point "run start" becomes
  ambiguous. The assistant message createdAt is persistent (SQLite-
  backed) and unambiguously marks when this run's turn began writing.
  The tracker still drives `compactionsInRun` — a drop there just
  under-counts, which we document in the row's kdoc.

- **Read `TokenUsage` from ctx's running event stream instead of the
  persisted Message.** Rejected — mid-stream `TokenUsage` updates DO
  land on the assistant Message via `SessionStore.upsertMessage` as the
  provider publishes token deltas. Reading from the persisted message
  already sees the running total without adding a bus subscription.

**Coverage.** 6 new tests in `SessionQueryActiveRunSummaryTest`:

1. `neverRanSessionCollapsesToZero` — new session: neverRan=true, all
   numeric fields 0, runStartedAtEpochMs/elapsedMs/latestAssistantMessageId null.
2. `midRunReportsTokensAndToolCalls` — seeded assistant message with
   TokenUsage + 3 Part.Tool parts + Generating state → all fields
   populated; elapsedMs ≥ 0.
3. `failedStateCarriesCause` — Failed transition populates `cause`.
4. `compactionsCountedWithinRunArc` — two Compacting transitions after
   runStart → compactionsInRun = 2. (Guards the `since` filter in the
   tracker.history call.)
5. `missingSessionIdFailsLoud` — sessionId omitted → IllegalStateException.
6. `noTrackerWiredFailsLoud` — container without AgentRunStateTracker →
   IllegalArgumentException.

Gradle: `:core:jvmTest` + `:apps:cli:test` + `:apps:server:test` +
`:apps:desktop:assemble` + `:apps:android:assembleDebug` +
`:core:compileKotlinIosSimulatorArm64` + ktlintCheck all green.

**Registration.** No new AppContainer changes. `session_query` is
already wired in CLI / Desktop / Server / Android with an
`AgentRunStateTracker`. iOS doesn't register tools (no agent-side tool
dispatch on iOS today).

**§3a arch-tax check (#12).** `session_query` dispatcher select count
moves 16 → 17 with this cycle. `debt-unified-dispatcher-select-plugin-shape`
trigger is ≥ 20, so no promotion. `ToolSpecBudgetGateTest` ceiling
22,700 — new helpText line + schema enum entry add ~30 tokens, well
under the ceiling. No bump needed.
