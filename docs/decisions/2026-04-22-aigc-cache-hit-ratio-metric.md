## 2026-04-22 — Publish BusEvent.AigcCacheProbe + Prometheus hit/miss counters (VISION §5.3 Observability)

**Context.** Backlog bullet `aigc-cache-hit-ratio-metric`. `AigcPipeline.findCached`
is the hot path that decides whether an AIGC tool short-circuits on a
lockfile hit or pays the provider. When users lock a seed and re-run, the
cache _should_ hit — but today there's no observable signal confirming it
did. The only way to debug "did my cache actually hit?" is reading the
session log for the tool's `outputForLlm` string, which is both high-friction
and only works after the fact. Ops dashboards on the server's `/metrics`
endpoint had no way to surface cache efficiency at all.

Rubric delta: §5.3 Observability "AIGC cache efficiency" 无 → 有.

**Decision.** Four-part wiring:

1. **New `BusEvent.AigcCacheProbe(toolId: String, hit: Boolean)`** — top-level
   `BusEvent` (not a `SessionEvent`; findCached is project-scoped and
   session-agnostic). Emitted **once per attempted AIGC dispatch**, right
   after `AigcPipeline.findCached` returns, before any provider call. Miss
   path later pairs with the existing `AigcCostRecorded`; hit path
   short-circuits without further events (the cost was already recorded on
   the original generation, as already documented on `AigcCostRecorded`).

2. **`EventBusMetricsSink` maps probes to Prometheus counters.** Base:
   `aigc.cache.hits.total` / `aigc.cache.misses.total` — scraped as
   `talevia_aigc_cache_hits_total` / `talevia_aigc_cache_misses_total` by
   the server's existing `/metrics` translator (`.` → `_`, `talevia_` prefix).
   Plus per-tool breakdown `aigc.cache.<toolId>.<hit|miss>.total` following
   the same pattern as `aigc.cost.<toolId>.cents`. Operators can compute
   hit ratio as `hits / (hits + misses)` at either base or per-tool
   granularity.

3. **Five AIGC tools publish the probe** via the existing `ctx.publishEvent`
   plumbing (already wired through `AgentTurnExecutor.ToolContext`):
   `GenerateImageTool`, `GenerateVideoTool`, `GenerateMusicTool`,
   `UpscaleAssetTool`, `SynthesizeSpeechTool`. Change at each call site is
   one added line between `findCached` and the `if (cached != null) return
   hit(...)`:
   ```kotlin
   val cached = AigcPipeline.findCached(projectStore, pid, inputHash)
   ctx.publishEvent(BusEvent.AigcCacheProbe(toolId = id, hit = cached != null))
   ```

4. **`isReplay`-aware.** The publish sits inside `if (!ctx.isReplay)` — same
   guard as the `findCached` call — so `ReplayLockfileTool` runs don't
   pollute the cache metric with artificial misses. The replay path
   bypasses cache entirely by design; counting those as "misses" would
   double-count every replay attempt.

**Alternatives considered.**

1. **Call `registry.increment` directly from `AigcPipeline.findCached`.**
   Rejected: couples the pure-function pipeline to a concrete
   `MetricsRegistry` singleton, breaks every tool test that constructs
   `AigcPipeline` without a registry, and requires a global lookup.
   Bus-based indirection is already the established pattern (same shape
   as `AigcCostRecorded` → `EventBusMetricsSink`); staying consistent is
   worth more than a direct shortcut.
2. **Encode hit/miss in a single counter with a label.** Prometheus
   convention is labels (`aigc_cache_total{outcome="hit"}`), but the
   existing server scrape is label-less Prometheus text. Adding label
   support just for this signal pays a disproportionate complexity cost;
   two separate counters (`_hits_total` / `_misses_total`) are the same
   observation in the vocabulary the rest of the scrape uses.
3. **Sample probes to reduce bus volume** (e.g. only publish every 10th
   probe). Rejected: AIGC tool calls are tens-per-session at most, and
   every probe encodes a distinct user action worth keeping. If this ever
   becomes a bus-pressure issue, downsampling should happen in the sink
   (dropping every Nth) not the publisher (losing per-tool fidelity).
4. **Include `projectId` + `sessionId` on the event for per-session
   dashboards.** Deferred: the immediate ask is ratio visibility, and
   per-session would change `AigcCacheProbe` from `BusEvent` to
   `SessionEvent`. Nothing prevents adding those fields later with default
   nulls if that need materialises.

业界共识对照:
- Prometheus naming: counters ending in `_total`, unit implicit in name,
  `area_subarea_thing_total`. Matches `talevia_agent_run_failed` and the
  broader scrape already emits. The backlog bullet specifies
  `talevia_aigc_cache_hits_total` / `talevia_aigc_cache_misses_total` —
  this decision produces exactly those names.
- OpenCode's observability story (`packages/opencode/src/bus/index.ts`)
  publishes typed events on a bus that metrics + UI both consume; this is
  the same mental model (and the Effect.js-free Kotlin equivalent).
- `AigcCostRecorded`'s existing per-tool breakdown (`aigc.cost.<toolId>.cents`)
  is the direct precedent for `aigc.cache.<toolId>.<hit|miss>.total`.

**Coverage.** `MetricsTest.aigcCacheProbeSplitsHitMissAndTrackPerTool`:
- Publishes 2 hits + 1 miss for `generate_image` and 1 miss for
  `synthesize_speech` on a real `EventBus` wired to `EventBusMetricsSink`.
- Asserts `aigc.cache.hits.total == 2`, `aigc.cache.misses.total == 2`
  (headline totals roll up across tools).
- Asserts per-tool counters: `generate_image.hit.total == 2`,
  `generate_image.miss.total == 1`, `synthesize_speech.miss.total == 1`,
  `synthesize_speech.hit.total == 0` (explicit zero so an unused-tool
  row doesn't get confused with a mis-tagged event).
- Asserts a completely unused tool counter `generate_video.hit.total == 0`
  (i.e. we don't accidentally increment for tool ids we never probed).
- Full `:core:jvmTest`, `:apps:cli:test`, `:apps:desktop:assemble`,
  `:apps:android:assembleDebug`, `:core:compileKotlinIosSimulatorArm64`
  all green. `ktlintCheck` green.

**Registration.** No container wiring — `EventBusMetricsSink` is already
attached on all 5 `AppContainer` composition roots (it was wired when the
metrics subsystem shipped). Adding a new `BusEvent` case to `counterName`
is automatic once the sink is live; the 5 AIGC tools publish through the
existing `ctx.publishEvent` channel `AgentTurnExecutor` already plumbs.
