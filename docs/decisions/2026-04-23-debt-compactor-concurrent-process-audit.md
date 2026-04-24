## 2026-04-23 — Compactor per-session inflight guard (VISION §5.6 system health)

**Context.** `Compactor.process()` is `suspend` and could theoretically
be invoked concurrently for the same session (manual `/compact` firing
mid-way through an auto-compaction pass that hit the 85 % context
threshold). The individual `SessionStore` writes are per-call under
mutex, but the prune → summarise → upsertPart triple isn't atomic:

```
prune(history)        // pure
markPartCompacted()   // per-call mutex, OK
... provider.stream().collect { ... }     // slow, seconds
upsertPart(Part.Compaction)
bus.publish(SessionCompacted)
```

Two racing passes could double-drop overlapping candidate sets
(`markPartCompacted` is idempotent so that alone is benign),
double-bill the provider summarisation call (each pass spends tokens
and money on its own summary), and persist two `Part.Compaction`
parts whose `replacedFromMessageId` / `replacedToMessageId` ranges
overlap — ambiguous for `select=compactions` callers asking "which
pass covered this range?".

Rubric delta §5.6 (system health — atomic composition invariant):
moves from **无** (no guard, hazard documented in the backlog but not
enforced in code) to **有** (single-mutex inflight tracker serialises
concurrent callers to exactly one per session).

**Decision.** Per-session inflight tracking on `Compactor`:

```kotlin
private val inflightMutex = Mutex()
private val inflightSessions = mutableSetOf<SessionId>()

suspend fun process(...): Result {
    val acquired = inflightMutex.withLock {
        if (sessionId in inflightSessions) false
        else { inflightSessions.add(sessionId); true }
    }
    if (!acquired) return Result.Skipped("compaction already in progress for session ${sessionId.value}")
    try {
        // existing prune + summarise + upsert + publish
    } finally {
        inflightMutex.withLock { inflightSessions.remove(sessionId) }
    }
}
```

**Try-acquire**, not blocking-acquire. A second concurrent call
returns `Result.Skipped("compaction already in progress for session
<id>")` immediately instead of queueing. Rationale: if the user
pressed `/compact` while the Agent was already auto-compacting,
queueing would spawn a second summary pass over history that's about
to change under it — wastes a provider call and muddies the audit
trail. Returning `Skipped` with a distinct reason lets the manual
caller decide whether to retry after the first one finishes.

**Axis.** `new concurrent entry-points to Compactor.process` — if a
future feature adds a third `process` caller beyond "Agent auto-
trigger" + "manual /compact" (e.g. per-turn pre-compaction hint, or a
background maintenance pass), the same guard covers it for free. The
inflight set grows per-session, not per-caller-kind, so adding a new
caller doesn't resplit the lock.

**Alternatives considered.**

- **Per-session `MutableMap<SessionId, Mutex>` with blocking acquire.**
  Rejected: each entry accumulates a `Mutex` object forever (one per
  session ever compacted — over a long-running server, that's every
  session that ever existed). More importantly, blocking-acquire
  queues the second call, and when it eventually runs it's summarising
  stale history the first pass already covered. Try-acquire with a
  single mutex + set membership is simpler and has the right
  semantics.

- **Queue + coalesce — first call proceeds, second call waits for it,
  then receives the SAME `Result` as the first.** Rejected: the
  second call may have been invoked with a different `history` list
  (auto pass saw one snapshot, manual pass saw a later one). Pretending
  the first caller's result is the second caller's result assumes
  those two histories are equivalent, which they aren't in general.
  The cleaner contract: "someone else is running, try again later" —
  lets the caller decide whether a retry is warranted.

- **Push the lock down into `SessionStore`.** Rejected: SessionStore
  already has per-operation mutex guards; the atomicity needed here
  is at the Compactor-level verb boundary, not the write-operation
  boundary. Adding a "compaction inflight" concept to SessionStore
  couples two layers for one verb.

- **Assume Agent.runLoop is the single caller (no manual /compact
  support yet).** Rejected: manual `/compact` is a stated VISION
  affordance (`compaction-diagnosis-query` bullet / this week's
  session_query(compactions)), and the hazard is a defensive invariant
  regardless of whether two callers exist today. Dollar cost of
  losing this race is "one wasted LLM call per overlap" — the guard
  costs two `Mutex.withLock` calls per process invocation (nil).

**Coverage.**

- `CompactorTest.concurrentProcessOnSameSessionWinsOnceAndSkipsOthers`
  (new). Uses a `GatedSummaryProvider` whose `stream` suspends on a
  `CompletableDeferred` so the first `process()` is parked mid-
  summarisation while the second is launched. Asserts: exactly one
  `Result.Compacted`, exactly one `Result.Skipped`, Skipped reason
  contains "already in progress", exactly one `Part.Compaction`
  persisted.
- `CompactorTest.inflightGuardReleasesAfterSuccessfulProcess` (new,
  §3a #9 bounded-edge). Pin for the `finally { remove }` release: a
  second sequential call after the first one finishes must NOT claim
  "already in progress". If the release were broken, the first run
  would succeed and every subsequent pass would be perpetually
  blocked.

All existing `CompactorTest` cases still pass — the guard is
transparent for single-caller scenarios.

**Registration.** No registration changes — `Compactor` is already
wired at every AppContainer that runs the Agent loop (CLI / Desktop /
Server / Android). iOS doesn't run the Agent today (VISION §5.4 /
mobile non-regression window) so no iOS wiring touched.

Gradle: `:core:jvmTest` + `:apps:cli:test` + `:apps:server:test` +
`:apps:desktop:assemble` + `:core:compileKotlinIosSimulatorArm64` +
`:apps:android:assembleDebug` + ktlintCheck all green.
