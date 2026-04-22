## 2026-04-22 — session_query(select=run_state_history) surfaces the tracker's ring buffer (VISION §5.4 debug)

Commit: `aafea47`

**Context.** `BusEvent.AgentRunStateChanged` publishes every agent
transition (Idle / Generating / AwaitingTool / Compacting / Cancelled /
Failed). `AgentRunStateTracker` listens and caches the *current* state
per session. `session_query(select=status)` surfaces that current
state — but any "how many times did this session enter Compacting in
the last 5 minutes?" question required grepping bus logs. VISION §5.4
debug lane calls out that timeline-answering capability as a gap.

**Decision.**

1. **Tracker gains a per-session ring buffer of
   [StateTransition][io.talevia.core.agent.StateTransition]s.** Each
   `AgentRunStateChanged` event collected by the existing bus
   subscriber also pushes a `(epochMs, state)` entry into
   `historyFlowInternal`. FIFO-drop past `historyCap` (default 256 —
   comfortably covers a long agent run's Generating/Compacting
   loops without unbounded memory). Clock injected via constructor
   so tests drive deterministic timestamps. The already-public
   `currentState(sid)` API is unchanged.

2. **`history(sessionId, since: Long?): List<StateTransition>`** on
   the tracker filters the ring buffer by lower-bound epoch-millis;
   null returns the full buffer.

3. **New `SessionQueryTool.RunStateTransitionRow` + `SELECT_RUN_STATE_HISTORY`**
   routes to `runRunStateHistoryQuery(sessions, tracker, input, limit, offset)`
   in a new sibling file
   `core/.../tool/builtin/session/query/RunStateHistoryQuery.kt`.
   Mirrors the SpendQuery / StatusQuery shape exactly. Rejects if
   `tracker` was null at container-construction (test rigs), errors
   cleanly on unknown sessionId, supports `limit` + `offset`
   post-filter.

4. **New `Input.sinceEpochMs: Long?`** passed through to the
   tracker's filter. `rejectIncompatibleFilters` gains a guard so
   setting `sinceEpochMs` on any other `select` value produces the
   standard "field does not apply" error.

5. **Schema update** — `SESSION_QUERY_INPUT_SCHEMA` gains a
   `sinceEpochMs` property with a description that calls out
   "rejected for other selects". The `select` field's description
   grows to include `run_state_history` in the enum list.

Zero net tool growth. Same unified query primitive — one new
`select` value gaining ~55 tokens of schema.

**Alternatives considered.**

1. **Persist the history to SQLite** — rejected for this cycle. The
   ring buffer is a process-lifetime artefact by design: agent runs
   happen within sessions, and the "how often did we enter
   Compacting this afternoon?" question is about the current process
   / run, not archival history. SQLite persistence is a significantly
   bigger change (schema migration + retention + delete cascades)
   whose motivation hasn't surfaced. The in-memory buffer matches
   what other debug-lane signals (agent run state, current-project)
   do today.

2. **Expose as a `get_session_run_states` tool** — rejected on §3a
   rule 1 (no net tool growth without compensating removal). The
   unified `session_query` tool is the designated session-state read
   primitive — fragmenting it for every new read axis is the opposite
   of the consolidation that cycle 12 landed. A `select=`
   discriminator + Input field is ~30 tokens vs. ~150 for a new tool
   spec, and the agent already knows the shape of
   `session_query(...)`.

3. **Fire-and-forget via the bus, let subscribers build their own
   history** — already how UI / metrics sinks work, but the agent
   doesn't have a subscriber; the bus isn't re-queryable. An
   on-demand read against a tracker ring buffer is the right shape
   for "query this after the fact from inside a tool".

4. **Timestamp entries with the bus event itself** — rejected.
   `BusEvent.AgentRunStateChanged` doesn't carry a timestamp today
   and adding one would ripple through every publisher. The tracker
   stamps on collect via its injected `Clock`, which is the
   smallest-scope place for it.

**Coverage.** New `SessionQueryRunStateHistoryTest` — 6 cases, all
§3a rule 9 semantic-surface oriented:

- `missingSessionIdRejected` / `missingSessionErrors` — error arms
  mirror other sessionId-scoped selects.
- `sessionWithNoTransitionsReturnsEmptyRows` — zero-transition edge
  case.
- `multipleTransitionsReturnedOldestFirst` — happy path, asserts
  4-transition ordering + `Failed.cause` round-trips verbatim +
  timestamps monotonic.
- `sinceEpochMsFiltersOutOlderTransitions` — scripted clock drives
  `epochMs` 1000 / 2000 / 3000; cutoff at 1500 drops the first
  entry.
- `historyIsCappedPerSession` — overflow case (10 events, cap=4 →
  oldest 6 drop, 4 survive). Uses `runBlocking` + polling because
  `runTest`'s virtual clock doesn't advance Dispatchers.Default.
- `sinceEpochMsRejectedOnOtherSelects` — the
  `rejectIncompatibleFilters` cross-field guard.

Existing `SessionQueryStatusTest` and `AgentRunStateTrackerTest`
pass unchanged — the new `historyFlow` / `history()` additions are
purely additive.

**Registration.** No registration change — same `SessionQueryTool`
already wired in all 5 AppContainers. The tracker addition is a
one-file extension; all five containers already construct and inject
the tracker into `SessionQueryTool`.

LLM context cost (§3a rule 10): `run_state_history` enum value
(~15 tokens) + `sinceEpochMs` schema (~40 tokens) + help text
remains unchanged. ~55 tokens of schema addition, well under the
500-token threshold. The row shape is only serialised when the
LLM actually requests `select=run_state_history`.

Process-lifetime scope: **documented in the query's KDoc** so future
agents querying after a restart don't misread an empty buffer as "no
transitions ever happened". SQLite-backed history is queued
conceptually for a future cycle if debug need proves the in-memory
window insufficient.

---
