## 2026-04-23 — EventRouter runtime routing test (VISION §5.6 system health)

**Context.** `apps/cli/.../event/EventRouter.kt` subscribes to 5
`BusEvent` types today (PartDelta / AgentRetryScheduled /
SessionCompacted / AssetsMissing / PartUpdated; the bullet said 6 per
cycle-30's count, but a consolidation pass since dropped one — no
material difference to the guard). Before this cycle, each Renderer
method had its own test (`AssetsMissingNoticeTest`,
`MarkdownRepaintTest`, …) but the **routing itself** — "event X
arriving on the bus causes method Y on the Renderer to fire, and
nothing else" — had zero coverage. Adding a 7th subscription
(inevitable as Core's bus events grow) would have landed silently.

Rubric delta §5.6 (system health / regression guard): EventRouter
routing moves from **无** (no runtime test for the subscription layer;
a dropped / mis-routed subscription is invisible to CI) to **有** (9
test cases exercise each event type, the session-filter guard, and
two early-return edges).

**Decision.** New `apps/cli/src/test/kotlin/io/talevia/cli/event/EventRouterTest.kt`.
Test shape:

- Real `EventBus`, `SqlDelightSessionStore` (in-memory SQLite), and
  `Renderer` on a `DumbTerminal` whose stdout is captured into a
  `ByteArrayOutputStream`.
- `router.start(backgroundScope)`; wait a few `yield()` +
  `runCurrent()` beats so the 5 subscription coroutines install on
  the test dispatcher before any publish fires (replay=0 shared flow
  drops un-subscribed emits; documented kotlinx.coroutines gotcha
  noted inline).
- 9 cases:
  1. `partDeltaOnActiveSessionReachesStreamAssistantDelta` — happy
     path; delta text appears in stdout.
  2. `partDeltaOnOtherSessionIsFilteredOut` — session-filter guard;
     cross-session deltas must NOT render.
  3. `partDeltaWithNonTextFieldIsIgnored` — field-filter guard; only
     `field == "text"` streams to the assistant transcript (tool-
     input JSON deltas would otherwise leak into the text stream).
  4. `agentRetryScheduledRoutesToRetryNotice` — attempt + reason snippet visible.
  5. `sessionCompactedRoutesToCompactedNotice` — pruned count + summary length visible.
  6. `assetsMissingRoutesIgnoresSessionFilter` — AssetsMissing is
     project-scoped, not session-scoped; the router intentionally
     omits the session filter for it (commented in EventRouter).
     Test pins this invariant — an accidental "add session filter to
     every subscription" refactor would break it.
  7. `partUpdatedTextFinalizesOnAssistantMessage` — PartUpdated +
     `Part.Text` on an assistant-scoped message → finalizeAssistantText.
  8. `partUpdatedEmptyTextSkipsFinalize` — §3a #9 bounded-edge; an
     empty-text PartUpdated fires on TextStart and must NOT finalize
     (would race the delta subscriber and truncate streaming output;
     documented early-return in EventRouter line 80).
  9. `partUpdatedToolRunningSurfacesToolId` — PartUpdated +
     `Part.Tool(Running)` → toolRunning renders the toolId.

Assertions use **marker-based stdout matching** rather than mock /
stub Renderer. The existing neighbor tests
(`AssetsMissingNoticeTest`, `StreamingToolOutputTest`) do the same —
`Renderer` is a concrete class (not interface / open), and making it
extractable purely for tests would be API churn with no production
consumer. The marker approach pairs each Renderer method with a
distinctive human-readable string it writes (toolId appears verbatim,
pruned count appears as digits, retry attempt appears as the number).

**Axis.** n/a — net-new test, not a refactor.

**Alternatives considered.**

- **Extract `RendererContract` interface and pass a mock.** Rejected:
  `Renderer` is a 500-line concrete class with side-effectful terminal
  writes; extracting an interface purely for the router's 9 method
  surface would create a two-implementation world (real + mock) and
  the mock would have to be kept in sync with every new method the
  router subscribes to. The marker-based real-renderer approach
  requires zero production changes.

- **Unit test `EventRouter.start(...)` with a recording `CoroutineScope`
  and `bus.events.collect` assertions.** Rejected: that tests the
  flow wiring but not what actually lands on the Renderer surface.
  Tests that don't cross the flow → renderer boundary would let a
  subscription-installed-but-handler-mis-bound bug survive. End-to-end
  (publish → renderer-side-effect) matches what the bullet asked for.

- **Wait for `bus.events.subscriptionCount.value >= 5` to know
  subscriptions are live.** First attempt of this cycle; compile
  resolution of `subscriptionCount` on `SharedFlow<BusEvent>` via the
  `EventBus.events: SharedFlow<BusEvent>` property failed — likely a
  local Kotlin compiler quirk around narrowing. Fell back to
  `testScheduler.runCurrent()` + 8×`yield()` which deterministically
  drains the single-thread test dispatcher's pending start-up
  continuations. Recorded in `docs/ENGINEERING_NOTES.md` as a testing
  gotcha so next bus-subscriber test doesn't retry the subscriptionCount
  approach first.

**Coverage.** 9 `@Test` methods (see Decision above). §3a #9 bounded-
edge coverage specifically hits session-filter drop, field-filter
drop, and empty-text finalize no-op — the "routing pattern is subtle"
edges. `:apps:cli:test` green; `:core:jvmTest` + `:apps:server:test` +
`:apps:desktop:assemble` + `ktlintCheck` green.

**Registration.** No code changes to production — test-only cycle.
EventRouter wiring in `apps/cli/src/main/kotlin/io/talevia/cli/Repl.kt`
unchanged; the new test file exercises the existing code path.
