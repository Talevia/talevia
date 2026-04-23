## 2026-04-23 — Emit `BusEvent.SessionCompacted` from `Compactor` + CLI renders a "compacted N outputs" notice (VISION §5.4 rubric axis)

**Context.** P2 backlog bullet `compaction-drop-telemetry` (added in the
cycle-16 repopulate). Symptom: `Compactor.process()` already wrote
`Part.Compaction` + called `store.markPartCompacted(prunedId, now)` for
each dropped part, but **did not publish any `BusEvent`** afterward.
Upstream, `SessionCompactionAuto` fires *before* the pass — signalling
overflow — and `AgentRunStateChanged(Compacting)` brackets the pass
from the Agent side, but neither tells the UI what the pass actually
did. From the CLI / Desktop operator's perspective: a long pause
followed by a silently-shrunken context. The user can't audit "what
got dropped?" or "did the summary even work?" without reading SQL.

Rubric delta §5.4 (system observability / operator feedback): the
post-compaction result surface goes from **无** (zero signal — the
drop happened off-transcript) to **部分** (bus-published event with
prunedCount + summary length; CLI renders a one-line notice; Desktop
can subscribe later via the same event). Not "有" yet because the
notice shows a count but not *which* tool outputs got dropped —
surfacing the dropped toolId list belongs to a follow-up (a
`session.compacted.detail` verb, or reusing the `Part.Compaction`
metadata in the timeline renderer, depending on what Desktop UX
needs). Today's change covers the "it happened" signal; the
"what exactly?" deeper view is a separate cycle.

OpenCode reference: `packages/opencode/src/session/compaction.ts`
emits a `session.compacted` bus event with the same shape
(`{ sessionID, prunedCount, summaryLength }`) for the same reason.
We already diverged on the trigger signal (our
`SessionCompactionAuto` predates that call site and carries the
overflow metrics OpenCode doesn't) — pairing it with a post-pass
`SessionCompacted` matches OpenCode's "pre + post" bracket shape.

**Decision.**

1. New sealed-interface member in `BusEvent.kt`:
   ```kotlin
   data class SessionCompacted(
       override val sessionId: SessionId,
       val prunedCount: Int,
       val summaryLength: Int,
   ) : SessionEvent
   ```
   `prunedCount` is the number of `Part.Tool` parts moved to the
   compacted state in this pass. `summaryLength` is **characters**,
   not tokens — characters are tokenizer-free; a UI that wants tokens
   can re-estimate. Matches OpenCode's `summaryLength` shape.

2. `Compactor.process()` publishes it immediately after
   `store.upsertPart(compactionPart)`, before returning
   `Result.Compacted`. `Result.Skipped` branches do NOT emit (they
   are by construction "no-op pass"). This means one compaction pass
   = exactly zero or one `SessionCompacted` events, correlated to
   the pass that `SessionCompactionAuto` (if the trigger was
   overflow) or an explicit `/compact` user invocation started.

3. `Metrics.counterName` gets `SessionCompacted → "session.compacted"`
   so the Prometheus scrape has a counter for compaction frequency
   (paired with the existing `session.compaction.auto` trigger
   counter — the ratio of the two is the "how many triggers
   actually produced a compaction?" signal; a future perf-watch
   dashboard can alert on divergence).

4. `apps/server/src/main/kotlin/io/talevia/server/ServerDtos.kt`
   extended:
   - `eventName(BusEvent.SessionCompacted)` → `"session.compacted"`.
   - `BusEventDto` gets two new optional fields (`prunedCount`,
     `summaryLength`).
   - `BusEventDto.from(BusEvent.SessionCompacted)` fills them.
   Existing SSE subscribers immediately see the new event in the
   stream; existing JSON shape stays backward-compatible because
   the two new fields default null (rule §3a-7).

5. `apps/cli/src/main/kotlin/io/talevia/cli/event/EventRouter.kt`
   adds a fifth subscription job matching the existing pattern
   (filter by `activeSessionId`, forward to Renderer). It sits
   between the `AgentRetryScheduled` subscriber and the
   `AssetsMissing` subscriber — compaction + retry are both
   "explanation for a pause" signals so keeping them adjacent
   aids review.

6. `apps/cli/src/main/kotlin/io/talevia/cli/repl/Renderer.kt`
   gains `suspend fun compactedNotice(prunedCount, summaryLength)`.
   Uses the existing `breakAssistantLineLocked` +
   `markAllPartsUnrepaintableLocked` + `invalidateBottomLocked`
   preamble so the notice sits on its own line and doesn't corrupt
   a mid-streaming assistant part. Output format follows the
   existing `retryNotice` / `assetsMissingNotice` style:
   `  ⋯ compacted N tool outputs — summary NNN chars`. Marker is
   the ASCII `⋯` ellipsis (not an emoji) — consistent with the
   Renderer's existing char-only marker set (`⟳`, `✓`, `✗`, `!`,
   `·`); the user's global CLAUDE.md bars emoji-in-files unless
   explicitly asked.

**Axis.** Number of post-compaction observable signals on the bus.
Before: zero — the pass was invisible downstream. After: one
`SessionCompacted(prunedCount, summaryLength)` event + one
Prometheus counter tick per successful pass. Pressure source that
would re-trigger a tightening here: a future request for
"show me which tool outputs got dropped" (either a dropped-toolId
list on the event or a separate detail query). Don't pre-expand
this event with speculative fields — when the "what" UX lands,
decide whether to fatten `SessionCompacted` or add a sibling
`SessionCompactedDetail`.

**Alternatives considered.**

- **Put the event inside `store.markPartCompacted`.** Would fire
  once per dropped part, granular. Rejected: wrong granularity —
  the UI wants "one notice per pass", not a flurry of N events.
  Also couples the storage verb to the behavioural bus signal;
  the clean cut is storage does storage, Compactor owns the
  lifecycle event.

- **Emit `SessionCompacted` from the Agent** (after `compactor.process`
  returns). Keeps Compactor bus-free. Rejected: then manual
  `/compact` paths that call `Compactor.process` directly wouldn't
  emit, and we'd need two emit sites. Compactor is the single
  authoritative place — anyone who calls `process()` gets the
  event automatically.

- **Include a `toolIds: List<String>` on the event** (which tools
  got dropped). Useful for the Desktop UI the next time we land
  there. Rejected this cycle: scope creep. The ring-buffer of
  dropped toolIds lives in `Compactor.prune` temporarily and
  isn't kept. Adding it means threading the info through
  `Result.Compacted` → event; doable but a separate cycle with
  its own decision.

- **Wire Desktop subscriber in the same cycle.** Desktop has no
  banner surface yet for this; CLAUDE.md platform-priority says
  Desktop is non-regression only until CLI reaches the "相对完善
  可用" bar. CLI is the right place for the parity-moving step;
  Desktop subscribes later against the same event when its UI
  banner surface lands (natural follow-up under
  `debt-desktop-missing-asset-banner`'s sibling).

**Coverage.**

- `core/src/jvmTest/kotlin/io/talevia/core/compaction/CompactorTest.kt`
  gets `publishesSessionCompactedEventOnSuccessfulCompaction`. Uses
  the same "subscribe before process + `launchIn(this)` + `yield()`
  + `advanceUntilIdle()` + `job.cancel()`" idiom as
  `AgentCompactionTest.kt` so the `StandardTestDispatcher` +
  SharedFlow-no-replay combo doesn't drop events. Asserts:
  - Exactly one `SessionCompacted` in the captured list.
  - `sessionId` matches.
  - `prunedCount` matches `Result.Compacted.prunedCount` (so the
    event and the return value can't drift).
  - `summaryLength == summaryBody.length` (the summary body is a
    known fixture string, so this pins the character-not-token
    semantics).
  - `prunedCount > 0` (this fixture forces a drop — positive
    control against a regression where the event fires but
    always carries 0).
- `apps/cli/src/test/...` — no new test. `EventRouter`'s existing
  test surface doesn't cover individual subscription routing (it's
  currently a straight pass-through, the Renderer-side behaviours
  are covered in `RendererTest.kt` and the routing is visible
  via the CLI E2E recipe); adding a bespoke routing test per new
  subscription would be scaffolding without a known regression
  shape. If an EventRouter regression bug lands, that's the
  cycle to extract `EventRouterTest`.
- `apps/server/src/test/...` — the existing `ServerDtosTest.kt`
  (if any) isn't touched; the new DTO fields default null and
  the `from` + `eventName` branches are one-liners that trip
  exhaustiveness at compile time. Compile-time exhaustiveness
  is the right gate here — a runtime regression would surface
  as "SSE stream missing the event" which belongs to an SSE
  integration test, out of scope.

Gradle: `:core:jvmTest` + `:apps:cli:test` + `:apps:server:test` +
`:apps:desktop:assemble` + `:core:compileKotlinIosSimulatorArm64` +
`:apps:android:assembleDebug` + `ktlintFormat` + `ktlintCheck`
all green.

**Registration.** No tool / AppContainer change — this is a
platform-signal surface, not a tool. The five containers are
unaffected. Wiring is Compactor (publish) → CLI EventRouter
(subscribe) → Renderer (render). Server SSE forwards
automatically via the existing `BusEvent → BusEventDto` pipe.

**Follow-ups (don't open backlog bullets now; wait for the
triggering need):**
1. "Which tool outputs got dropped?" — fatten the event with
   `toolIds: List<String>` or add a sibling detail event when
   Desktop UX asks.
2. Desktop banner subscriber parallel to this CLI one — opens
   after Desktop crosses CLI's "相对完善可用" bar, same pattern
   as the existing `debt-desktop-missing-asset-banner` bullet.
