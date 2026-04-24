## 2026-04-23 — typed `sessionScopedSubscribe<E : SessionEvent>` helper on EventBus (VISION §5.6 rubric axis)

**Context.** Pre-cycle-54, six CLI / iOS call sites each rewrote the same
`bus.subscribe<E>().filter { it.sessionId == sid }` pair by hand — four in
`EventRouter.start`, one in `StdinPermissionPrompt.start`, one in
`IosBridges.sessionPartUpdates`. Two gotchas compounded:

1. The `.filter` lambda captured `sessionId` as a value or `() -> SessionId`
   producer. Callers couldn't tell from the call shape whether a given
   instance was frozen at subscribe time or re-evaluated per event — both
   patterns appeared in the tree.
2. `BusEvent` has a project-scoped event (`AssetsMissing`) that is NOT a
   `SessionEvent`. `EventRouter.kt:56` wires it via the untyped
   `bus.subscribe<AssetsMissing>()` on purpose — if another caller reached
   for the session-filter pattern on a project-scoped event, nothing at
   compile time would catch the mistake; the silent result would be events
   never firing for operators (because `AssetsMissing.sessionId` doesn't
   exist — that call wouldn't even compile, but a hypothetical
   almost-session-scoped event like a mis-classified operator notice
   would).

§5.6 delta (helper surface for session-scoped bus consumption): **部分 → 有**.
Previously only the untyped `EventBus.forSession(sessionId):
Flow<BusEvent.SessionEvent>` was available — it preserved session filtering
but threw away type narrowing, so callers re-`filterIsInstance<E>` on top
of it. Now a single typed helper covers both axes.

**Decision.** Two overloads on `EventBus`:

```kotlin
inline fun <reified E : BusEvent.SessionEvent> sessionScopedSubscribe(
    sessionId: SessionId,
): Flow<E>

inline fun <reified E : BusEvent.SessionEvent> sessionScopedSubscribe(
    crossinline sessionIdProducer: () -> SessionId,
): Flow<E>
```

- First overload: static sessionId (iOS bridge — `sessionPartUpdates` is
  called with a specific session).
- Second overload: producer lambda re-invoked per event (CLI REPL uses
  this because `/resume` / `/new` swap `activeSessionId` without tearing
  down the `start()` subscriptions).
- `E : BusEvent.SessionEvent` bound is the value-add: calling
  `bus.sessionScopedSubscribe<BusEvent.AssetsMissing>(sid)` would fail at
  compile time (AssetsMissing isn't a SessionEvent). Accidentally
  session-filtering a project-scoped event becomes impossible.

Migrated 6 call sites:

- `core/src/iosMain/.../IosBridges.kt:567` — static-sid overload.
- `apps/cli/.../event/EventRouter.kt` × 4 — producer overload.
- `apps/cli/.../permission/StdinPermissionPrompt.kt` — producer overload.

Unused `kotlinx.coroutines.flow.filter` imports dropped from both migrated
files.

**Axis.** n/a — this is a helper extraction, not a refactor along a growth
axis. The helper will grow at `EventBus.kt` only if another cross-cutting
bus-consumer pattern appears (e.g. a rate-limiter wrapper, a
backpressure-logger). Each would be a new inline helper, not a resplit of
this one.

**Alternatives considered.**

- **Extend `forSession` instead of adding a sibling.** Rejected — renaming
  or changing `forSession`'s return type from `Flow<SessionEvent>` to a
  reified `Flow<E>` would break IosBridges' existing untyped usage
  (`.subscribe<PartUpdated>().filter { ... }` was doing the type narrowing
  itself). Adding a sibling `sessionScopedSubscribe` leaves `forSession`
  as the "all session events for this session" stream and `sessionScopedSubscribe`
  as the "events of type E for this session" one — two different use
  cases, two names.

- **Single overload taking `() -> SessionId`; static-sid callers pass
  `{ sid }`.** Rejected — the iOS bridge takes a `SessionId` argument, not
  a producer, and forcing `{ it }` everywhere adds a lambda allocation
  per call. Two overloads is the kotlinx.coroutines idiom (cf. `Flow.timeout`
  + `Flow.timeout(timeoutMillis: Long)`). The JVM can inline both cleanly.

- **Leave the subscribe+filter pairs as-is.** Rejected — the "~15 call
  sites" claim in the bullet was overstated (actual: 6), but the
  compile-time guard against session-filtering a non-session event is
  real value. A future contributor adding a project-scoped event next to
  `AssetsMissing` would inherit the guard for free.

**Coverage.** Two new tests in
`core/src/commonTest/.../bus/EventBusTest.kt`:

- `sessionScopedSubscribeFiltersByTypeAndSession` — static-sid overload
  drops events that fail EITHER the type bound OR the session match. Both
  axes exercised in one test.
- `sessionScopedSubscribeProducerVariantIsReEvaluatedPerEvent` — producer
  overload invokes the lambda per event (count-based assertion; the
  "active value swaps mid-stream" case would be timing-sensitive against
  the collector so we don't attempt it — the helper's contract is clear
  from the impl).

Gradle: `:core:jvmTest` + `:apps:cli:test` + `:apps:server:test` +
`:apps:desktop:assemble` + `:apps:android:assembleDebug` +
`:core:compileKotlinIosSimulatorArm64` + ktlintCheck all green.

**Registration.** No new tool. Helper is on `EventBus` which is already
wired everywhere. No AppContainer changes.

**§3a arch-tax check (#12).** No new tool. No new select on any
dispatcher. No new reject rule. Nothing triggers.
