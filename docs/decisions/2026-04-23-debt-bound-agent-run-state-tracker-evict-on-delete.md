## 2026-04-23 — Evict `AgentRunStateTracker._states` + `historyFlowInternal` on `BusEvent.SessionDeleted` (VISION §5.7 rubric axis)

**Context.** P2 backlog bullet
`debt-bound-agent-run-state-tracker-evict-on-delete` — the second (B2)
follow-up from the 2026-04-23 unbounded-collection audit
(`docs/decisions/2026-04-23-debt-audit-unbounded-mutable-collections.md`).
Symptom: `core/src/commonMain/.../agent/AgentRunStateTracker.kt:59-71`
keeps two maps keyed by `SessionId` — `_states` (most recent
`AgentRunState` per session) and `historyFlowInternal`
(per-session ring-buffer of `StateTransition`). Both are populated by
a single collector on `BusEvent.AgentRunStateChanged`, but nothing
evicts entries when a session is later deleted. `SessionActionTool(action=delete)`
→ `SqlDelightSessionStore.deleteSession(sid)` drops the row and
*already publishes* `BusEvent.SessionDeleted(sid)` (line 73 of
SqlDelightSessionStore.kt), but the tracker wasn't subscribing.

Per-session memory cost: `AgentRunState` is a sealed head-value
(handful of bytes); `List<StateTransition>` is bounded at
`DEFAULT_HISTORY_CAP = 256` × ~24 bytes = ~6 KB. So ~6 KB per
historical session × N deleted sessions leaks indefinitely on a
long-lived process. Thousands of sessions before it matters, but
the audit's B1 (Metrics histograms, resolved yesterday) was a
similar order-of-magnitude issue — the pattern was simply "nothing
cleans up", and the cleanup plumbing already existed.

Rubric delta §5.7: unbounded-growth surface — 2 of the audit's 3
B-findings resolved (B1 histogram ring buffer yesterday; B2 this
cycle; B3 "counter-map key-set" remains acceptable by design).
Audit's tracked "3 findings" → "1 finding outstanding (accepted)".

**Decision.** `AgentRunStateTracker.init{}`'s collector switches
from a single `if (event is BusEvent.AgentRunStateChanged)` branch
to a `when` over the two event classes we care about:

```kotlin
when (event) {
    is BusEvent.AgentRunStateChanged -> { /* upsert as before */ }
    is BusEvent.SessionDeleted -> {
        _states.update { prev -> prev - event.sessionId }
        historyFlowInternal.update { prev -> prev - event.sessionId }
    }
    else -> Unit
}
```

No change to the collector's lifecycle (still a single `launch`
on the injected scope), no change to the tracker's public API,
no new dependency. `Map.minus(key)` is the idiomatic Kotlin
"drop this key"; on a key that was never present it returns the
same map, so `MutableStateFlow.update` short-circuits the
downstream emission — untracked-session deletes don't spuriously
wake subscribers watching `tracker.states`.

Class-level docstring updated: the old comment ("evict by session
deletion if that becomes load-bearing; today we do not") now
documents the eviction that's in place plus the audit reference.

Why subscribe in-tracker rather than have the AppContainer wire
it up: the tracker owns both `_states` and `historyFlowInternal`
— it's the only class that can touch them. A composition-root
subscription would need to either expose an internal `dropSession`
method (leaking state-management surface) or duplicate the
`Map.update` logic. Keeping the subscription inside the tracker
matches the existing collector-per-tracker pattern.

**Axis.** Entries in `_states` + `historyFlowInternal` over process
uptime. Before: unbounded (grows forever as sessions are created
and deleted). After: bounded by "concurrently live sessions + any
for which `SessionDeleted` hasn't fired yet". Pressure source that
would re-invalidate this bound: any future session-delete path
that bypasses the bus (directly `DELETE FROM session WHERE id = ?`
without publishing `BusEvent.SessionDeleted`). Today
`SqlDelightSessionStore.deleteSession` is the single delete entry
point and always publishes; any sibling delete path added later
must either publish the event or accept the leak.

**Alternatives considered.**

- **Add a weak-reference cache** (WeakHashMap-style) so entries
  drop when the `SessionId` is otherwise ungcable. Rejected:
  `SessionId` is a value class around a string — it's cheap to
  re-create, so holding one is cheap; weak references wouldn't
  help. The problem isn't "holding one session id too long",
  it's "holding one per session ever seen".

- **Time-based eviction** (drop entries whose last transition is
  older than N minutes). Simpler than subscribing to a deletion
  event, but picks wrong for the "session still live but dormant"
  case — a user with an open session idle for an hour would lose
  tracked state and then race the next transition to re-create
  an empty history. Deletion is the correct signal.

- **Explicit `dropSession(sid)` method called from
  `SessionActionTool.executeDelete`.** Imperative wiring; one
  fewer bus hop. Rejected: couples the session-action tool to
  the tracker's implementation detail (the tool already publishes
  via its store's `deleteSession`; adding a second call point is
  duplicate state-sync plumbing). The bus-subscribe decouples
  them — any future delete path (e.g. a server-side admin
  endpoint that drops a stale session via raw SQL +
  `bus.publish(BusEvent.SessionDeleted(sid))`) gets eviction
  for free.

- **Emit a new `BusEvent.SessionTrackerEvicted` after the
  eviction** so downstream UI can reset any cached state derived
  from the tracker. Rejected as scope creep: no downstream
  consumer today subscribes to the tracker's map changes through
  the bus (they subscribe to `StateFlow.value` directly); adding a
  new event class without a concrete reader is speculative.

**Coverage.** New
`core/src/jvmTest/kotlin/io/talevia/core/agent/AgentRunStateTrackerEvictionTest.kt`
pins three invariants:

1. `sessionDeletedEvictsBothMaps` — publish
   `AgentRunStateChanged`, verify the session is in both maps,
   publish `SessionDeleted`, verify both drop the key (not
   "empty list", not "null state" — the key is gone).
2. `sessionDeletedOnUntrackedSessionIsNoOp` — publishing a
   delete for a never-seen session id must not crash, must not
   create a phantom entry, and must not disturb an unrelated
   tracked session's state.
3. `createDeleteCreateRebuildsCleanState` — delete-then-recreate-
   same-id starts with a FRESH history (not carrying leftover
   transitions). The regression shape this guards: an eviction
   that drops `_states` but leaves `historyFlowInternal`
   populated (or vice versa) would let a stale transition from
   the pre-delete life bleed into the post-delete session.

Gradle: `:core:jvmTest` + `:apps:cli:test` + `:apps:server:test` +
`:apps:desktop:assemble` + `:core:compileKotlinIosSimulatorArm64` +
`:apps:android:assembleDebug` + ktlintFormat + ktlintCheck all
green.

**Registration.** Single file change in production
(`core/src/commonMain/.../agent/AgentRunStateTracker.kt`) — no
AppContainer update needed (constructor signature unchanged, no
new dependencies). Test file is new.

**Engineering note.** While writing the test, the first cut called
`TestScope(coroutineContext)` inside `runTest` to build a fresh
scope for the tracker's collector — which is explicitly rejected
by `kotlinx-coroutines-test` (1.8+): "A CoroutineExceptionHandler
was passed to TestScope. Please pass it as an argument to a
`launch` or `async` block on an already-created scope". Correct
pattern inside `runTest`: use the built-in `backgroundScope`,
which `runTest` provides specifically for forever-running
background work like bus collectors. It auto-cancels at test end
so the test doesn't hang on a pending collector. Recorded in
`docs/ENGINEERING_NOTES.md` with a second subtlety also hit this
cycle — `EventBus`'s `MutableSharedFlow` has no replay, so a
publish that races ahead of the collector's first scheduled
resumption is silently dropped; an `advanceUntilIdle()` + `yield()`
after tracker construction (BEFORE the first publish) is required
to let the collector register.
