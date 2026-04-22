## 2026-04-22 — session_query(select=context_pressure) exposes token margin before the Compactor fires (VISION §5.4)

Commit: `d3a5832`

**Context.** `Agent.compactionTokenThreshold` defaults to 120_000
tokens — the auto-Compactor kicks in when the session's
non-compacted history crosses that line. But the LLM itself can't
see how close it is to that line: the signal shows up only **after**
compaction fires (`AgentRunState.Compacting` transition). That
lights up the run-state tracker but doesn't help the LLM make the
*prior* decision "this next tool call is going to add a lot of
context — should I summarise first or branch into a subtask?".

`select=status` already reports `estimatedTokens` +
`compactionThreshold` + `percent` (cycle 21 landed that), but three
frictions prevent it from being the right surface for this:

1. **`select=status` requires an `AgentRunStateTracker`** —
   `require(tracker != null)` at the top of the handler. Rigs
   without a full Agent wired (server endpoints publishing session
   health, pure-tool CLI harnesses, test fixtures) can't ask.
2. **`percent` is clamped to `[0.0, 1.0]`** — the over-threshold
   case (session accumulated past the threshold but Compactor
   hasn't been invoked yet) reads identical to "exactly at
   threshold". The agent needs to distinguish "just crossed" from
   "just below".
3. **No explicit `marginTokens`** — the LLM decision math is
   simpler with "how many tokens do I have left" than with ratio.
   A negative margin is the most actionable "act now" signal.

Backlog bullet (`session-query-context-pressure`) called for a new
`context_pressure` select returning
`(currentEstimate, threshold, ratio, marginTokens)` — this cycle
implements exactly that, plus `overThreshold` and `messageCount`
for terse rendering and orientation.

**Decision.** One new `select=context_pressure` on
`SessionQueryTool`. Zero new tools (§3a rule 1).

1. **`SessionQueryTool.ContextPressureRow`** —
   `(sessionId, currentEstimate, threshold, ratio, marginTokens,
   overThreshold, messageCount)`. `ratio` is **un-clamped** so
   over-threshold reads > 1.0; `marginTokens = threshold -
   currentEstimate` (negative when over); `overThreshold` is a
   terse boolean for quick UI rendering.

2. **`SELECT_CONTEXT_PRESSURE = "context_pressure"`** constant +
   `ALL_SELECTS` enum entry + `ContextPressureQuery.kt` sibling
   handler (parallel to `CacheStatsQuery`, `RunStateHistoryQuery`
   etc.).

3. **No tracker dependency.** `runContextPressureQuery` takes just
   `(sessions, input)` — unlike `runStatusQuery(sessions, tracker,
   input)`. The handler reads
   `sessions.listMessagesWithParts(sid, includeCompacted = false)`
   and feeds it through `TokenEstimator.forHistory`, matching the
   exact slice `Compactor` evaluates (compacted parts already fold
   into a `Part.Compaction` summary and don't double-count).

4. **Threshold source.** Reuses the `DEFAULT_COMPACTION_TOKEN_THRESHOLD =
   120_000` constant already defined by `StatusQuery.kt`. The "threshold
   propagation to per-run overrides" note in the `status` decision
   applies here too — if a container wires a bespoke Agent with a
   different threshold, this select keeps reporting the Core default.
   A future cycle can thread the actual threshold through when the
   concrete driver surfaces.

5. **Schema + helpText update.** `SESSION_QUERY_INPUT_SCHEMA`'s
   `select` enum gets `context_pressure` appended; `SessionQueryTool.helpText`
   gains a one-paragraph description of the new select.

LLM wire cost (§3a rule 10): `context_pressure` enum token (~6) +
helpText paragraph (~120 tokens) + row schema (~80 tokens) ≈ 210
tokens per turn. Well under the 500-token threshold the rule
reserves for per-tool additions.

**Alternatives considered.**

1. **Extend `select=status` with `marginTokens` + un-clamp `percent`
   instead of adding a new select.** Rejected on three counts:
   (a) the tracker dependency would still make the combined query
   unusable in Agent-less rigs; (b) backward-compat on the
   `percent` field (existing UI consumers assume `[0, 1]` — silently
   un-clamping breaks their progress-bar math); (c) responsibility
   collation — `status` is "what is the agent doing?", not "how
   heavy is this conversation?". Splitting keeps each row contract
   focused. The existing `status` row keeps its
   `estimatedTokens`/`percent` fields unchanged.

2. **New dedicated tool `describe_context_pressure(sessionId)`.**
   Rejected — `SessionQueryTool` is the designated session-state
   read primitive (per cycle 12's unification). Fragmenting the
   query surface for every new read axis is the opposite of the
   consolidation that cycle landed. A new `select=` costs ~200
   tokens of spec vs ~400+ for a separate tool with its own input
   schema + permission + registration. Also matches how
   `select=spend`, `select=cache_stats`, `select=run_state_history`
   already fit into `session_query` via select discriminator.

3. **Compute the estimate lazily via a callback on the Agent
   itself rather than re-evaluating from the session store.**
   Rejected — `Agent.autoCompactIfNeeded` ALREADY re-evaluates
   from the same store each turn (there's no cached "current
   estimate" on the Agent to read). Reading from the store on
   demand is O(all-non-compacted-messages) and cheap — sessions
   typically have ≤ low-hundreds of messages, each with ≤ tens of
   parts. No new caching layer needed.

4. **Report the Compactor's *actual* in-flight threshold** (which
   might be a per-run override, not just the Core default).
   Deferred — requires threading the Agent's instance-level
   `compactionTokenThreshold` through the SessionQueryTool
   constructor, which in turn requires five AppContainer updates.
   Noted as a follow-up in the decision — the Core default is the
   right shipped behavior today; containers that override the
   threshold also override the Compactor, and the two are then
   consistent by construction.

**Coverage.** New test file
`core/src/jvmTest/kotlin/io/talevia/core/tool/builtin/session/SessionQueryContextPressureTest.kt`
— 7 cases, §3a rule 9 semantic-boundary oriented:

- `missingSessionIdRejected` — mirrors every other sessionId-scoped select.
- `missingSessionErrors` — unknown id fails loud instead of returning empty.
- `emptySessionReportsZeroEstimateAndFullMargin` — the brand-new-session
  boundary. Confirms threshold = 120_000 (the ratchet test that fails if
  someone changes the default without updating the Agent).
- `belowThresholdReportsPositiveMargin` — 400 chars → ~100 tokens,
  ratio < 1, positive margin, overThreshold=false.
- `overThresholdReportsNegativeMarginAndUnclampedRatio` — 600k chars
  → ~150k tokens, ratio > 1.0 (critical: guards against a future
  regression that adds clamping), negative margin, overThreshold=true.
  The single-Part.Text construction exercises the forHistory →
  forPart → forText path without needing many messages.
- `incompatibleFilterRejected` — cross-field guard: `kind` (a
  `select=parts` filter) on `select=context_pressure` fails loud per
  the existing `rejectIncompatibleFilters` check. Didn't need a new
  guard — this select happens to take only sessionId, and the
  pre-existing `kind is parts-only` reject already covers it.
- `multipleMessagesSumAcrossHistory` — 3 messages totalling ~600
  tokens; exercises the `messages.sumOf { parts.sumOf(...) }` reduce
  in the estimator and confirms `messageCount` reflects the full
  non-compacted slice.

Existing `SessionQueryCacheStatsTest`, `SessionQueryStatusTest`,
`SessionQueryRunStateHistoryTest`, `SessionQueryTest` all pass
unchanged — additive change.

**Registration.** No AppContainer change needed. `SessionQueryTool`
is already wired in all 5 AppContainers (CLI / Desktop / Server /
Android / iOS); the new select is a pure internal dispatch
extension. New select becomes available everywhere automatically.

---
