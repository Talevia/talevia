## 2026-04-23 — `session_query(select=run_failure)` post-mortem aggregation (VISION §5.4 rubric axis)

**Context.** When `Agent.run` terminates with
`AgentRunState.Failed(cause)`, `cause` is a single string on the
terminal message. Operators investigating "why did this turn fail?"
had to cross-reference three selects (`status` for current state,
`message` for the failed message's parts, `tool_calls` for its tool
dispatches) and then manually correlate with recent retry / fallback
events that they'd have to tail from the live bus (impossible post-
process-restart). No single-dispatch way to say "give me the
post-mortem for messageId=X".

Rubric delta §5.4 (agent-loop observability — post-mortem): moves from
**部分** (raw signals all exist but manual cross-reference required)
to **有** (one-dispatch `session_query(select=run_failure)` aggregates
terminal cause + per-step error parts + tracker-derived terminal kind
into a list of `RunFailureRow`).

**Decision.** New select `SELECT_RUN_FAILURE = "run_failure"` on
`SessionQueryTool`. Handler lives at
`core/src/commonMain/.../tool/builtin/session/query/RunFailureQuery.kt`.

Contract:

- Input: `sessionId` required; optional `messageId` drills to one
  specific turn (returns 1 row or 0 with distinguishable narrative).
- Output: one `RunFailureRow` per `Message.Assistant` where
  `finish == FinishReason.ERROR`, oldest-first:
  - `messageId` — the failed turn.
  - `model` — `"providerId/modelId"`.
  - `terminalCause` — the message's `error` field (nullable).
  - `stepFinishErrors: List<StepFinishErrorEntry>` — every
    `Part.StepFinish(finish=ERROR)` on this message with its token
    spend (input / output / cacheRead / cacheWrite as `Long` matching
    `TokenUsage`).
  - `runStateTerminalKind` — coarse tag (`"failed"` / `"cancelled"` /
    other) derived from `AgentRunStateTracker`'s transition history
    whose `epochMs` falls in `[message.createdAt, nextMessage.createdAt)`.
    Null when no tracker is wired (pure-persistence test rigs stay
    functional).

`includeCompacted=true` when reading parts — compaction otherwise
hides the failure rows the post-mortem query is specifically meant to
surface.

**What's NOT in this iteration:**

- `fallbackChain` (`AgentProviderFallback` events). `BusEvent.AgentProviderFallback`
  is bus-only — not persisted as a Part. Capturing it requires a
  parallel tracker (analogous to `AgentRunStateTracker`). Filed as
  follow-up `agent-provider-fallback-chain-tracking` in BACKLOG.
- `retryTrace[reason/waitMs]`. `BusEvent.AgentRetryScheduled.reason`
  / `waitMs` are bus-only too. The cycle-41 `retryAttempt` field on
  `AgentRunStateChanged` is available on transitions the tracker
  already holds, but the tracker's `StateTransition` shape predates
  that wiring and doesn't carry it. Filed as follow-up
  `agent-retry-attempt-tracker-capture` in BACKLOG so a future cycle
  can extend `StateTransition` + back-fill `maxRetryAttemptObserved`
  into this row without schema churn on `RunFailureRow` itself.

**Axis.** n/a — net-new select.

**Alternatives considered.**

- **New tool `describe_run_failure(sessionId, messageId)`.** Rejected
  per §3a #1 — net tool count is an LLM-context cost. A new select on
  an existing tool reuses 3-4 hundred tokens of shared spec; a new
  tool would add another ~400-500. `session_query` is already the
  session-aggregation primitive; this is a natural fit.

- **Make the select persist fallback / retry trace by writing new
  `Part` kinds on every bus event.** Rejected — that's a persistence-
  layer change that doesn't belong in a read-side query decision.
  Parallel tracker for bus-only signals is the right shape (filed as
  follow-ups).

- **Return ALL failed messages regardless of compaction state.**
  Accepted (`includeCompacted=true`) — the primary use case IS
  finding out what went wrong, including in compacted turns. The
  operator who asks "why did run #42 fail two hours ago?" wants the
  answer even if compaction has since summarised the transcript.

- **Require `messageId`.** Rejected — a common investigator query is
  "did this session have any failures?" — no target messageId known
  yet. Optional messageId with list-all default matches other selects
  (e.g. `status`, `session_metadata`).

**Budget.** Adding this select pushed `tool_spec_budget` from 22_518
to 22_632 (+114, ceiling 22_600). Trimmed the select's helpText and
the schema description for `messageId` + the `select` enum; final
budget lands at **22_594 / 22_600** — headroom 6 tokens. Tight but
within ceiling; no ceiling bump required.

**Coverage.** 8 tests in `SessionQueryRunFailureTest`:

1. `emptySessionReturnsNoFailureRows` — clean session → 0 rows +
   distinctive narrative.
2. `failedTurnSurfacesTerminalCauseAndStepFinishErrors` — happy path;
   asserts model / terminalCause / stepFinishErrors contents (only
   ERROR step-finish counts, token spend preserved).
3. `multipleFailuresReturnOldestFirstInNarrative` — oldest-first
   ordering; narrative names most recent; intermediate successful
   turns filtered out.
4. `messageIdScopesToSingleFailure` — drill-down to one turn works.
5. `messageIdOnSuccessfulTurnReturnsEmptyAndAnnotates` (§3a #9) —
   scoped miss vs. clean session has distinct narrative.
6. `runFailureRequiresSessionId` — missing filter fails loud.
7. `messageIdStillRejectedOnIncompatibleSelect` — `rejectIncompatibleFilters`
   relaxation only opened messageId for message + run_failure
   selects; other selects still reject.
8. `trackerAbsenceLeavesTerminalKindNull` (§3a #9) — null-tracker
   path returns rows with `runStateTerminalKind=null`, primary
   fields stay filled.

**Registration.** No new tool — `session_query` already registered in
all 4 JVM AppContainers via `registerSessionAndMetaTools` (iOS skips
tool registration per CLAUDE.md).

**§3a arch-tax check (#12).** `session_query` select count: 15 → 16.
`debt-unified-dispatcher-select-plugin-shape` trigger at any
dispatcher ≥ 20. Still 4 selects under the threshold.
`rejectIncompatibleFilters` rule count: expanded the existing
"messageId only for SELECT_MESSAGE" to allow run_failure. No new
reject rule added; count unchanged. Neither trigger activates.

Gradle: `:core:jvmTest` + `:apps:cli:test` + `:apps:server:test` +
`:apps:desktop:assemble` + `:core:compileKotlinIosSimulatorArm64` +
`:apps:android:assembleDebug` + ktlintCheck all green.
