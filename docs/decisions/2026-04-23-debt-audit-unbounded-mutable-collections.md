## 2026-04-23 — Unbounded mutable-collection audit across `core/commonMain` (VISION §5.7 / §5.6 rubric axes)

**Context.** R.6 #2 perf scan in the cycle-16 repopulate flagged 88
`mutableListOf` / `mutableMapOf` / `AtomicLong` / `AtomicInteger` /
`CounterRegistry` grep hits in `core/commonMain/kotlin`, with no
per-site proof of boundedness. The P2 bullet asked for a one-shot
audit classifying each hit into (a) bounded-by-scope and (b) truly
unbounded business-scope fields, with follow-up bullets opened for
the (b) findings.

Rubric delta §5.7: unbounded-growth surface "部分" → "有" (now
inventoried). Two genuine concerns identified, one of them pre-
existing (the metrics histogram list never prunes), one acknowledged
only in a source comment (agent-run state tracker grows with session
count).

Re-ran the scan at audit time — grep count 107 (up from 88 at cycle
16's repopulate; the +19 reflects cycles 17-23's added code
including the 5 consolidated action-dispatched tools, each with a
per-action pruning-set `mutableSetOf` + a `mutableListOf` output
accumulator, all transient).

**Audit result — classification by lifecycle.**

### (A) Bounded (transient scope or bounded by known cap) — 104 of 107 hits

The following patterns are all provably bounded:

1. **Per-method accumulators in `*Tool.kt` execute() bodies** — every
   video / project / source / session tool uses `mutableListOf` /
   `mutableSetOf` local to `execute()` to accumulate per-input
   results (e.g. `results += AddResult(...)` in
   `TransitionActionTool.executeAdd`). Die on method return; size
   bounded by input-list size. Examples: `AddClipTool`,
   `FilterActionTool`, `ProjectSnapshotActionTool`,
   `ProjectMaintenanceActionTool`, `SessionActionTool`,
   `AigcPipeline`, etc. ~70 hits.

2. **Per-method builders in `core/tool/builtin/**/query/*Query.kt`**
   — same pattern: transient accumulators in the query body.
   Examples: `ClipsForAssetQuery`, `SpendQuery`, `TimelineClipsQuery`,
   `AncestorsQuery`, `DagSummaryQuery`. ~20 hits.

3. **Fork / diff / validate helpers** — transient scope. Examples:
   `ForkProjectTool`, `DiffProjectsTool`, `TimelineDiffCompute`,
   `ValidateProjectTool`, `ProjectStaleness`. ~6 hits.

4. **Provider streaming de-multiplexers** — per-stream scope, die
   when the `Flow` terminates:
   - `AnthropicProvider` uses 4 `mutableMapOf<Int, *>`
     (lines 88-91) keyed by content-index; scope is one
     `stream()` call.
   - `OpenAiProvider` uses `mutableMapOf<Int, ToolBuf>` (line 90)
     in the same pattern.

5. **Per-turn dispatch state in `AgentTurnExecutor`** —
   `mutableMapOf<CallId, PendingToolCall>` (line 178),
   `mutableListOf<Job>` (line 188). Both scoped to one turn; cleared
   on turn end.

6. **`Agent.inflight: MutableMap<SessionId, RunHandle>`** — entries
   added at `agent.run()` entry (line 196), **removed in the
   `finally` block** (line 239). Bounded by concurrent
   in-flight sessions × 1 handle each.

7. **`PermissionService.pending: MutableMap<String, Pending>`** —
   entries added on `ask()` (line 82), **removed in
   `respond()`** (line 67). Bounded by concurrent permission
   prompts.

8. **`Compactor.candidates` + `drop`** — per-`prune()` scope, die
   with the method.

9. **`SqlDelightSessionStore.messageIdRemap`** — per-`fork()`
   scope.

10. **Singleton registries written only at container-init:**
    - `ToolRegistry.tools: mutableMapOf<String, RegisteredTool>`
      — populated in `AppContainer.init{}`, never grows at
      runtime.
    - `ProviderRegistry.list: mutableListOf<LlmProvider>` — same
      pattern.

11. **`AgentRunStateTracker.historyFlowInternal` inner list** —
    **bounded** by per-session ring buffer (line 85-89:
    `if (existing.size >= historyCap) existing.drop(existing.size
    - historyCap + 1) + transition`). `historyCap` is
    `DEFAULT_HISTORY_CAP = 128`. The list itself is bounded; the
    OUTER map is not (see finding (B2) below).

### (B) Unbounded business-scope fields — 3 of 107 hits

#### (B1) `Metrics.histograms` inner `MutableList<Long>` grows forever

`core/src/commonMain/kotlin/io/talevia/core/metrics/Metrics.kt:28`:

```kotlin
private val histograms = mutableMapOf<String, MutableList<Long>>()

suspend fun observe(name: String, ms: Long) {
    mutex.withLock { histograms.getOrPut(name) { mutableListOf() }.add(ms) }
}
```

Every `observe()` call appends a `Long` to the named histogram's
inner list. **No eviction path.** `reset()` exists (line 58) but is
not called by production code — grep'd `Metrics.*reset` across
`core apps` — zero production callers. Test-only.

A long-lived server process that observes (say) one latency per
tool dispatch would accumulate 10s of thousands of Longs per
histogram per hour. Each Long is 16B with object header → ~160KB
per 10k observations → not catastrophic but unbounded.

**Follow-up**: `debt-bound-metrics-histogram-ring-buffer` (added
to P2).

#### (B2) `AgentRunStateTracker._states` + `historyFlowInternal` outer maps grow with session count

`core/src/commonMain/kotlin/io/talevia/core/agent/AgentRunStateTracker.kt:59, 71`:

```kotlin
private val _states = MutableStateFlow<Map<SessionId, AgentRunState>>(emptyMap())
private val historyFlowInternal = MutableStateFlow<Map<SessionId, List<StateTransition>>>(emptyMap())
```

The comment at line 69 explicitly acknowledges "map grows
monotonically with session count". Delete flow: `SessionActionTool`
(action=delete) drops the session row + messages via
`sessionStore.deleteSession(sid)` but **does not signal the
`AgentRunStateTracker` to drop the stale entries**. Forking /
archiving likewise don't remove entries.

Per-session memory: `AgentRunState` is a sealed class (handful of
bytes), `List<StateTransition>` is bounded to 128 entries ×
~24 bytes each ≈ 3KB. So worst case ≈ 3KB per historical session
× N sessions. Thousands of sessions before it matters, but **it
does grow forever** on a long-lived process.

**Follow-up**: `debt-bound-agent-run-state-tracker-evict-on-delete`
(added to P2).

#### (B3) `Metrics.counters` map key-set grows with distinct counter names

`core/src/commonMain/kotlin/io/talevia/core/metrics/Metrics.kt:25`:

```kotlin
private val counters = mutableMapOf<String, Long>()
```

Counter NAMES come from hardcoded call sites (`counterName(event)`
in `BusMetricsWiring`, `provider.<id>.tokens.input` in `Agent`,
etc.). The name-space is bounded by the number of distinct metric
names in source. Grep'd: ≈ 15 distinct counter names across the
codebase. Key-set effectively bounded by code.

**Not a follow-up** — bounded by the static code surface. Note
in the audit so future added counters don't silently grow the
key space unbounded; a dynamic counter name with user-provided
suffix WOULD be a real bound violation. None today.

### Why the bullet asked for (a) + (b) triage

The audit pattern ("prove each use bounded or open a bullet") is
the VISION §5.7 safety invariant applied to memory growth. The
default Kotlin `mutableListOf` carries no bound proof — the type
system is silent on lifecycle. The only way to keep the invariant
verifiable is a per-site review, cached as this document so future
scans can diff against the known-bounded set.

**Decision.** No code changes this cycle (the bullet explicitly
says "本轮只 audit，不改代码"). The audit conclusions above stand
as the canonical classification; the two identified follow-up
bullets (`debt-bound-metrics-histogram-ring-buffer` +
`debt-bound-agent-run-state-tracker-evict-on-delete`) are
appended to BACKLOG P2 in the same commit. Next time the R.6 #2
scan fires, the check is "do these three findings still hold?
has anything new crept in?" — not a re-audit from scratch.

**Axis.** Count of mutable-collection sites with no bound proof.
Before: 88-107 (scan hit count), all unclassified. After: 3
identified, 2 follow-up-bulleted, 1 noted but acceptable. The
pressure source for re-triggering this audit is a grep-hit-count
jump of ≥ 20 (≈ 20% of current) — new additions from a refactor
could hide a new unbounded field in the noise.

**Alternatives considered.**

- **Lint-enforce every `mutableListOf` / `mutableMapOf` to carry
  a `// bounded-by:` comment.** More rigorous — every site
  documents its own scope. Rejected: noise cost is high (107
  comments), and transient uses in `execute()` bodies don't need
  per-site annotation — they're obviously method-scoped.
  Scanning + auditing once a cycle is lighter.

- **Add a `BoundedCollection<T>` wrapper type with explicit
  `maxSize` / `evictionPolicy`.** Encourages explicit bounds at
  type level. Rejected for this cycle: the 3 findings all have
  different natural remediation patterns (histogram wants a
  circular buffer; state tracker wants delete-hook; counter
  map wants no remediation) — a generic wrapper would be
  premature abstraction. If more findings accumulate, spin up
  a helper then.

- **Land one of the fixes opportunistically this cycle.** The
  bullet specifically said "本轮只 audit，不改代码". Staying in
  scope — the fixes land as their own follow-up cycles where
  the tight bullet-scoping keeps the change auditable.

**Coverage.** n/a — pure audit, no code changes. The two
follow-up bullets carry their own test-coverage expectations
when they land.

**Registration.** No tool / AppContainer change. Decision file +
2 new P2 bullets appended to BACKLOG + 1 bullet removal. 107
grep hits classified as (A) bounded = 104; (B) unbounded = 3
(2 follow-up-actionable, 1 accepted).
