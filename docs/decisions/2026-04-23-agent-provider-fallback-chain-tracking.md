## 2026-04-23 — AgentProviderFallbackTracker + `RunFailureRow.fallbackChain` (VISION §5.4 rubric axis)

**Context.** Since cycle-50 landed `session_query(select=run_failure)`
(post-mortem aggregation for failed assistant turns), its decision file
and this cycle's kdoc explicitly called out that `fallbackChain` was
deferred because `BusEvent.AgentProviderFallback` was bus-only — events
flew past but nobody captured them for post-mortem reads. Late
subscribers (UI cold-boot, a `session_query` run after the turn already
failed) couldn't answer "which providers did the Agent try before
giving up?" without having tailed the bus from before the failure.
Operators hit this in practice: `run_failure` would report "primary
provider error" but nothing about whether fallback A→B→C happened or
the Agent just gave up on the first hit.

§5.4 delta (post-mortem observability for multi-provider chains):
**部分 → 有**. The tracker now captures every fallback hop per session,
and `run_failure` joins hops whose epoch falls in the failed turn's
wall-clock window.

**Decision.** New sibling class to `AgentRunStateTracker` in the same
package:

```kotlin
class AgentProviderFallbackTracker(
    bus: EventBus,
    scope: CoroutineScope,
    clock: Clock = Clock.System,
    historyCap: Int = DEFAULT_HISTORY_CAP,  // 32
) {
    val hops: StateFlow<Map<SessionId, List<FallbackHop>>>
    fun hops(sessionId: SessionId): List<FallbackHop>

    companion object {
        fun withSupervisor(bus: EventBus): AgentProviderFallbackTracker
    }
}

data class FallbackHop(
    val fromProviderId: String,
    val toProviderId: String,
    val reason: String,
    val epochMs: Long,
)
```

Same bounded-memory invariant as `AgentRunStateTracker`: per-session
ring buffer with cap + `SessionDeleted` eviction. `withSupervisor`
convenience for iOS / Swift callers that can't easily hand over a
`CoroutineScope` across the language boundary.

`RunFailureRow` gains a trailing defaulted field:

```kotlin
val fallbackChain: List<FallbackHopEntry> = emptyList()

@Serializable data class FallbackHopEntry(
    val fromProviderId: String,
    val toProviderId: String,
    val reason: String,
    val epochMs: Long,
)
```

`runRunFailureQuery` grows a `fallbackTracker: AgentProviderFallbackTracker?`
parameter; when wired, it filters `tracker.hops(sid)` to
`[message.createdAt, nextMessage.createdAt)` exactly the same way it
already filters `runStateTerminalKind`. When unwired (null), chain stays
empty — additive behavior, no regression.

Wiring in 4 AppContainers (CLI / Desktop / Server / Android):

- Each container adds `val fallbackStates = AgentProviderFallbackTracker(bus, scope)`
  alongside the existing `val agentStates = AgentRunStateTracker(bus, scope)`.
- `registerSessionAndMetaTools(...)` gains a trailing defaulted
  `fallbackTracker` param; containers pass `fallbackStates`.

iOS bridge (`newIosAgent` in `IosBridges.kt`) currently doesn't wire
`AgentRunStateTracker` at the bridge layer either — it expects Swift
callers to construct their own via `withSupervisor` and inject. Same
pattern applies for `AgentProviderFallbackTracker`; no bridge signature
change needed.

**Axis.** n/a — net-new tracker + additive JSON field. The growth axis
for `RunFailureRow` is "new post-mortem facets" (retry counts, fallback
chains, compaction hits, …). Each new facet should come in as an
additive defaulted field, matching this cycle's shape. The additive
JSON default guarantee protects older clients reading
forward-serialised rows.

**Alternatives considered.**

- **Persist fallback hops to SQLite instead of an in-memory tracker.**
  Rejected — matches `AgentRunStateTracker`'s axis decision (cycle-41).
  Hops are diagnostic signal, not domain state; surviving a process
  restart isn't load-bearing. An in-memory ring buffer capped at 32
  per session is O(sessions × 32) total memory; SQLite persistence
  would add a migration + a write path + a reader for ~zero product
  value over the bus event stream + capped tracker.

- **Extend `AgentRunStateTracker` to ALSO track fallback events.**
  Rejected — the tracker's single-responsibility is "current AgentRunState
  + transition history". Coupling fallback-hop storage to run-state
  transitions would force both concerns onto the same cap and the
  same event stream, and existing call sites that only care about one
  would pay for the other. Sibling class + independent
  `historyCap` scales cleanly if a third "observation tracker" (tool
  dispatch, retry events, anything) shows up later.

- **Emit a persistent `Part.FallbackHop` on every fallback event.**
  Rejected — parts are attached to messages, but a fallback between
  turns (rare but possible; e.g. a mid-retry handoff) has no message
  to attach to. Parts are also heavier (carry a `PartId` + compaction
  columns); fallback events are lighter-weight and better suited to
  a sidecar tracker.

- **Track fallback chain on a `Session` field directly instead of a
  tracker.** Rejected — §3a #3 (Project blob bloat) analog: a
  per-session append-only list on `Session` would mutate `Session` on
  every hop, fighting the session-store's normal write path. The
  tracker's in-memory shape avoids SQLite write amplification on a
  diagnostic path.

**Coverage.** 5 new tests in `AgentProviderFallbackTrackerTest`:

1. `noHopsReturnsEmpty` — never-fired session.
2. `fallbackPublishAccumulatesHopsInOrder` — two hops, ordered oldest-first, monotonic epochMs.
3. `separateSessionsDoNotCrossContaminate` — per-session isolation.
4. `sessionDeletedEvictsTrackedHops` — the bounded-memory invariant.
5. `ringBufferDropsOldestWhenCapExceeded` — cap enforcement.

Plus 2 new tests in `SessionQueryRunFailureTest`:

- `fallbackChainPopulatesWhenTrackerWired` — in-window hops populate
  the chain; cross-session hops stay isolated.
- `fallbackChainDefaultsEmptyWhenTrackerNotWired` — containers that
  don't wire the tracker get empty chain, no throw (mirrors the
  `runStateTerminalKind` null path for unwired `AgentRunStateTracker`).

Gradle: `:core:jvmTest` + `:apps:cli:test` + `:apps:server:test` +
`:apps:desktop:assemble` + `:apps:android:assembleDebug` +
`:core:compileKotlinIosSimulatorArm64` + ktlintCheck all green.

**Registration.** No new tool. Existing `SessionQueryTool` gains a
defaulted `fallbackTracker` constructor param. 4 AppContainers
(CLI / Desktop / Server / Android) wire the tracker alongside their
existing `agentStates`. `registerSessionAndMetaTools` gains a
defaulted trailing param; pre-cycle-57 callers still compile.

**§3a arch-tax check (#12).** No new tool. No new select. No new
reject rule. `ToolSpecBudgetGateTest` unchanged (additive row field
is JSON output only; schema + helpText untouched). Nothing triggers.
