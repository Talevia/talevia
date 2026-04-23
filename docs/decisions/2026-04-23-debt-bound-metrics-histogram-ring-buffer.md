## 2026-04-23 — Bound `MetricsRegistry.histograms` with a 1024-sample ring buffer per named histogram (VISION §5.7 rubric axis)

**Context.** P2 backlog bullet `debt-bound-metrics-histogram-ring-buffer`
— the first (B1) follow-up from the 2026-04-23 unbounded-collection
audit (`docs/decisions/2026-04-23-debt-audit-unbounded-mutable-collections.md`).
Symptom: `core/src/commonMain/.../metrics/Metrics.kt:28` kept each named
histogram as a `MutableList<Long>` that `observe(name, ms)` appended to
unconditionally. `reset()` was the only eviction path, and grep confirmed
zero production callers — `reset()` is test-only. Two production call
sites feed the histograms at a high rate:

- `AgentTurnExecutor:396` — `metrics?.observe("tool.${event.toolId}.ms", toolMs)` on every tool dispatch.
- `Agent:238` — `metrics?.observe("agent.run.ms", runMs)` on every agent-run completion.

A long-lived server process that runs one tool per minute for a week
would accumulate ~10 000 Longs per histogram × N distinct tool ids —
per the audit, ~160 KB per 10 k observations, so ~5-10 MB/week at
steady state. Not catastrophic but strictly unbounded, and the audit
explicitly tagged it as the highest-urgency B-finding.

Rubric delta §5.7: unbounded-growth surface 1 of the 3 B-findings
resolved (histogram ring buffer done; the state-tracker evict-on-delete
and counter-map notes remain). Audit's tracked "3 findings" → "2
findings outstanding".

**Decision.** Replaced the inner `MutableList<Long>` with a fixed-
capacity `ArrayDeque<Long>`; cap defined as
`MetricsRegistry.HISTOGRAM_CAP_PER_NAME = 1024`. `observe()` checks
`size >= HISTOGRAM_CAP_PER_NAME` and calls `removeFirst()` before
`addLast()`. Retention semantics: oldest observation evicts first —
the window tracks the **most recent 1024 observations**. Percentile
estimates (`histogramSnapshot()`) compute over the current window,
not lifetime.

Why `ArrayDeque` specifically: both-end O(1) append + removeFirst;
Kotlin stdlib; no extra dependency. A hand-rolled circular array
would be marginally faster on modern JITs but harder to read, and
`histogramSnapshot()` sorts a copy anyway — the per-observation cost
is dominated by the mutex and the copy-for-sort, not the deque
bookkeeping.

Docstring updates:

- Class-level `histograms` field doc explains the retention choice
  ("P50/P95/P99 track recent behaviour rather than lifetime — a
  3-hour-old P99 spike is paged; a week-old one is history").
- `observe()` doc calls out the eviction.
- `histogramSnapshot()` doc notes `count` is window size (≤ cap),
  not lifetime observation count — the matching `area.event`
  counter already tallies lifetime counts so a scrape consumer
  that needs "how many tool dispatches have I seen?" reads
  `counters["tool.${toolId}.ms"]`-equivalent, not the histogram
  count.

Cap `1024` rationale: 1024 Longs ≈ 16 KB per named histogram. With
~20 active named histograms (~20 tool ids × 1 + agent.run.ms =
~20), worst-case footprint ≈ 320 KB — comfortable on every target
platform including phones. Percentile stability needs ~30-50
samples for P95/P99; 1024 is ~20-30× that, plenty of headroom for
the occasional large spike not to dominate the window. Cap is a
`const val` for cheap inlining + test access.

**Axis.** Size of the per-histogram observation buffer as a
function of process uptime. Before: unbounded (grows forever).
After: bounded at 1024 per named histogram. Pressure source that
would re-invalidate this bound: if a future caller passes a
user-provided / tool-id-derived name with unbounded cardinality
to `observe()` — then the **outer map** grows unbounded with
names, each with a bounded 16 KB inner buffer. The current two
call sites use known-bounded name spaces (fixed tool-ids + one
hardcoded `agent.run.ms`), so the outer map is de-facto bounded
by code surface. If a third caller uses user-input in the name,
that caller must pre-bucket — enforce at review time.

**Alternatives considered.**

- **Cap at 256 samples per histogram.** Quarter the memory.
  Rejected: 256 samples is thinner for stable high-percentile
  estimates (a single outlier dominates P99 when n is small).
  The memory savings from 1024 → 256 (~48 KB total for 20
  histograms) aren't load-bearing on any target device.

- **Time-window retention** (evict observations older than N
  minutes rather than capping count). More principled for "recent
  behaviour". Rejected this cycle: requires wall-clock stamping
  every observation (doubling memory + introducing clock-source
  dependency in what's been a pure-data class), and the
  eviction on the scrape path is N extra comparisons per
  observation. Count-cap is a simpler first bound; a follow-up
  cycle could upgrade if real-world dashboards need time-windowed
  rather than sample-windowed percentiles.

- **Keep the biased-reservoir / rank-based streaming percentile
  estimators (t-digest, HDR histogram).** The canonical "do
  percentiles without storing raw samples" approach — constant
  memory, sub-1% percentile error. Rejected: these add a
  dependency (HdrHistogram JVM lib isn't KMP-ready; t-digest
  would need a commonMain port), and the 16 KB/name cost of
  the ring buffer isn't big enough to justify the library
  swap. If the metrics surface later grows to hundreds of
  named histograms this becomes the right upgrade — for now,
  the ring buffer is the minimum change that bounds the
  growth.

- **Periodic snapshot + reset** (cron-style: every N minutes,
  persist percentiles, clear the raw samples). Also simple.
  Rejected: introduces a scheduling requirement (who drives
  the timer, what's the interval, what if no scrape has
  happened in the interval?). Ring buffer is stateless — no
  scheduling dependency.

**Coverage.** New
`core/src/jvmTest/kotlin/io/talevia/core/metrics/MetricsRegistryTest.kt`
pins five invariants:

1. `observeUnderCapRetainsAllSamples` — pre-cap behaviour
   unchanged (200 samples → all retained; exact P50/P95/P99
   values on 1..200 input pinned to the existing `percentile(pct)`
   helper formula, so a silent rewrite of the percentile math
   trips this test).
2. `observeAtCapEvictsOldestAndKeepsCap` — cap + 500 overflow
   observations must retain exactly 1024 samples, with
   percentiles shifted upward to reflect the retained newer
   window (assertions use `>=` on the oldest-retained lower
   bound to tolerate ±1 index drift in the percentile helper
   without being flaky).
3. `observeManyTimesCapMemoryDoesNotExplode` — 10× cap
   observations (10 240 pushes) still report exactly
   `HISTOGRAM_CAP_PER_NAME`. The load-bearing stress-invariant
   test.
4. `resetClearsCountersAndHistograms` — `reset()` still wipes
   both tables. Existing contract unchanged.
5. `independentHistogramsDoNotCrossContaminate` — per-name ring
   buffers — an overflow in one named histogram must not
   evict from another's window.

Gradle: `:core:jvmTest` + `:apps:cli:test` + `:apps:server:test` +
`:apps:desktop:assemble` + `:core:compileKotlinIosSimulatorArm64` +
`:apps:android:assembleDebug` + ktlintFormat + ktlintCheck all
green.

**Registration.** Single file change in production
(`core/src/commonMain/.../metrics/Metrics.kt`) — no AppContainer
update needed (`MetricsRegistry` constructor signature
unchanged). Two new lines of docstring on the class field + two
production methods. Internal-only type swap from `MutableList`
→ `ArrayDeque`. Test file is new.

**Side-decision (logged here, not carried): skip-tag applied this cycle.**

Before picking this bullet, the top two P2 candidates — `debt-apply-lut-remove-pad`
and `debt-subtitle-add-remove-pad` — were both reviewed and skip-tagged
in the same commit (per §3). Rationale:

- `debt-apply-lut-remove-pad`: ApplyLutTool writes a `styleBibleId` into
  `clip.sourceBinding` only on the `styleBibleId=` input path (not the
  `lutAssetId=` direct path). "Removing the LUT" via
  `filter_action(action=remove, filterName=lut)` correctly clears the
  filter but does not touch `sourceBinding`. Whether that cleanup
  should happen automatically depends on a product-choice about
  whether `clip.sourceBinding` is "user-controlled metadata" (don't
  auto-clean) or "tool-managed cascading metadata" (auto-clean).
  Further complicated by clips bound to multiple style_bibles — which
  to drop? That's a product decision outside iterate-gap's zero-
  question budget. Skip-tag reason: "§3 命中：产品抉择 — LUT 移除
  sourceBinding 清理策略待定 + multi-binding 取舍需用户指定".

- `debt-subtitle-add-remove-pad`: "评估是否值得造 SubtitleActionTool"
  — evaluation-style bullet. The default position (subtitles are
  regular clips, use `remove_clips`) is defensible, but committing
  to a "no, keep the status quo" decision without a driving need is
  premature closure. Skip-tag reason: "§3 命中：设计评估 —
  RemoveSubtitlesTool 需求信号不明，待用户驱动场景出现".

Both bullets remain in BACKLOG with skip-tags so future cycles auto-
skip them until the gating signal appears. If the gating signal
never arrives through three cycles, the next repopulate's connected-
skip triage (§R) will promote them to `re-evaluate-<slug>` meta-
bullets for user decision.
