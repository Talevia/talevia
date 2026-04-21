## 2026-04-21 — estimate_session_tokens for pre-compaction planning (VISION §5.4 Agent/UX)

Commit: `5fb250b`

**Context.** VISION §5.4 asks for agent self-awareness around the
conversation it's running: "how heavy is this turn about to be?" and, after
the fact, "why did compaction trigger?". The text-only `estimate_tokens`
(commit `34f9f83`) sizes a *candidate* string — useful for pre-flighting a
big paste — but doesn't answer the session-weight question. Today the agent
has no way to ask "is session X close to the compaction threshold?" without
reading every message by hand and eyeballing, which is both slow and
inaccurate (the heuristic for the compactor's trigger lives in
`TokenEstimator.forHistory`, and the agent has no tool that wraps it).

**Decision.** `EstimateSessionTokensTool` — a thin session-lane wrapper over
`TokenEstimator.forHistory(listMessagesWithParts(sessionId))`. Terse by
default: `{ messageCount, totalTokens, largestMessageTokens }`. Opt-in
`includeBreakdown=true` adds a per-message `{ id, role, tokens }` list
(most-recent first, matching `list_messages`). The `largestMessageTokens`
field is surfaced *even in terse mode* because it answers a distinct
question from "total": a session with one 20k-token tool result reads very
differently from one with 50 evenly-sized messages, and the compact/keep
call hinges on where the weight is, not just how much of it there is.
Permission reuses `session.read` (already ALLOW in
`DefaultPermissionRuleset`) — no new keyword.

**Alternatives considered.**

1. *Put it in `meta/` next to `EstimateTokensTool`.* Rejected — meta/
   tools are registry-scoped introspection (what tools exist, what
   providers exist, sizing a piece of candidate text). This tool's input
   is a `sessionId` and it consumes `SessionStore`, so it belongs with the
   rest of the session-lane tools (`list_messages`, `describe_session`,
   `list_parts` …). Meta/ stays reserved for tools whose subject is the
   registry or agent state, not a session row.
2. *Always include the per-message breakdown.* Rejected — a 1000-message
   session would produce a multi-kilobyte tool-result payload just to
   answer "how heavy is this?", and the agent almost always wants the
   totals alone. Default terse matches the `list_messages` / `list_parts`
   house style where the detail tool (`describe_message` /
   `read_part`) is a deliberate follow-up, not forced on every call.
3. *Re-derive totals from `tokens_input` / `tokens_output` on assistant
   messages instead of running the heuristic.* Rejected for this tool's
   purpose: provider token counts only exist on *completed* assistant
   turns, so a session mid-turn (or with any user message added but no
   response yet) would be unmeasurable. The heuristic is deliberately
   chosen to match what the compactor's trigger uses — the whole point is
   "will the *next* turn trip compaction?", and compaction runs against
   the heuristic, not against provider telemetry. Help text + doc
   comments point callers at `list_messages` / `describe_session` for the
   real per-turn numbers when they want ground truth, not planning.
4. *Return only the `totalTokens` scalar.* Rejected — `largestMessageTokens`
   costs us one `maxOf` over the same loop we already run for
   `forHistory`, and it's the single most actionable pre-flight datum
   after the total (if the largest message is already >N% of the budget,
   compaction won't help without dropping/compacting *that* row
   specifically). Free information we'd otherwise force the agent to
   re-derive by asking for the breakdown.

**Coverage.** `EstimateSessionTokensToolTest` — six tests:
`emptySessionReturnsZero`, `wellPopulatedSessionSumsTokens` (cross-checks
against `TokenEstimator.forHistory` computed directly),
`includeBreakdownExposesPerMessageTokens` (verifies per-row ids + tokens +
most-recent-first ordering), `largestMessageTokensTracksMax` (three
messages of distinct sizes, assert the max),
`missingSessionFailsLoudly` (`IllegalStateException` containing both the
bad id and the `list_sessions` hint), `defaultOmitsBreakdown` (both the
typed field and the serialized JSON payload drop `messages` in terse mode).

**Registration.** `EstimateSessionTokensTool` registered alongside
`ListMessagesTool` in `CliContainer.kt`, `apps/desktop/AppContainer.kt`,
`apps/server/ServerContainer.kt`, `apps/android/AndroidAppContainer.kt`,
and `apps/ios/Talevia/Platform/AppContainer.swift`.
