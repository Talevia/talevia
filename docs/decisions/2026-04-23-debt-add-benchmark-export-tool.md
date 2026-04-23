## 2026-04-23 — ExportTool 10-clip baseline benchmark (VISION §5.7 rubric axis)

**Context.** R.6 #4 scan sub-bullet of 3: `ExportTool.render()` +
`FileProjectStore.openAt` are the second core path for VISION §5.7
(directly the "编辑 → 看成片" latency the user perceives). Cycle 17
landed the agent-loop baseline on top of the `:benchmark` infra
from cycle 16; this cycle closes the decomposition by adding the
export-side baseline.

The bullet's load-bearing concern is the O(N²) regression vector:
a refactor that naively walks clips inside clips (e.g. per-clip
hash recompute that reads whole timeline each time, or a nested
stale-check over lockfile × clips), still passing `ExportToolTest`
(N=1 clip) but catastrophically slowing down any real
10-clip project. Only a benchmark at the non-trivial N catches
that.

Rubric delta §5.7: export-path regression-guard coverage "无 → 有".
Sibling §5.6 #10 critical-path coverage: ExportTool.execute's
orchestration path now has a measured baseline. The benchmark
decomposition bullet set is now complete (`infra` landed cycle 16,
`agent-loop` cycle 17, `export-tool` this cycle).

**Decision.** `benchmark/src/main/kotlin/io/talevia/benchmark/ExportToolBenchmark.kt`:

- `@State(Scope.Benchmark) @BenchmarkMode(Mode.AverageTime)
  @OutputTimeUnit(MILLISECONDS) class ExportToolBenchmark`
- `@Setup(Level.Invocation)` rebuilds fresh storage per measured
  call: Okio `FakeFileSystem` → `RecentsRegistry` →
  `FileProjectStore` (via the same wiring `ProjectStoreTestKit.create`
  uses in tests, but inlined because the testkit lives in
  `commonTest`) → `project.upsert` with a single-track 10-clip
  `Timeline`.
- `@Benchmark fun tenClipExport()` calls `tool.execute(input, ctx)`
  in `runBlocking`. Input sets `forceRender = true` so every
  invocation exercises the full dispatch path (stale-guard →
  render-cache lookup → `engine.render` → cache store → result
  assembly); without it, the second iteration would short-circuit
  on a cache hit and measure nothing useful.
- `InstantVideoEngine` (file-private) is a minimum-viable
  `VideoEngine` that emits `Started` + `Completed` and returns
  instantly. The bullet's concern is orchestration-cost
  regressions, not ffmpeg-kernel ones; real ffmpeg (~200ms per
  encode × 10 clips = 2s) would mask any orchestration drift
  and consume most of the bench iteration time. End-to-end
  ffmpeg benchmarking is a separate follow-up if warranted.

Build-level: added `libs.okio` + `libs.okio.fakefilesystem` to
`:benchmark/build.gradle.kts` dependencies (the first is
transitively already there via `:core`, but stating it explicitly
is safer for an independent JVM-only consumer). kotlinx-benchmark
infra from cycle 16 unchanged.

**Baseline (reported here; future CI diffs compare against this).**

- Host: Apple M-series, JDK 21.
- Tool: kotlinx-benchmark 0.4.13 (JMH backend).
- Config: `warmups=1 × 1s, iterations=2 × 1s` — same smoke-grade
  config from the `:benchmark` module default.
- **Result: `ExportToolBenchmark.tenClipExport ≈ 0.913 ms/op`**
  for a 10-clip single-track Timeline through the full
  orchestration path (stale-guard + hash compute + render-cache
  lookup + stubbed engine dispatch + provenance synthesis +
  render-cache store).
- Per-clip orchestration cost ≈ 90 μs. Dominated by JSON
  encode/decode of the Project through FileProjectStore (the
  bundle-write path writes `talevia.json` on every `upsert` +
  reads it on every `get`).
- Sanity: `NoopBenchmark.noop ≈ 3.3 ns/op` unchanged,
  `AgentLoopBenchmark.tenTurnLoop ≈ 37.6 ms/op` within 10% of
  cycle 17's 41.8 ms baseline (run-to-run variance at smoke
  iteration counts).

A CI diff showing > +20% over this export baseline = real
regression; bisect and fix. If the slowdown comes from
`FileProjectStore.upsert` / `.get`, that's the "bundle I/O grew"
axis — a separate investigation (possibly revisiting
`bundle-talevia-json-split`). If it comes from clip iteration
inside ExportTool itself, that's the primary O(N²) regression
vector this bullet exists to catch.

**Axis.** Clip count in the scripted timeline. Before: 1 clip
(in tests) or 0 clips (no bench at all). After: 10 clips as the
regression-guard baseline. The pressure source for re-triggering
this bullet is "we need a higher N to catch deeper regressions"
— e.g. a 100-clip variant. When that lands, it's a sibling
benchmark method on the same state surface, not a re-split of
this file.

**Alternatives considered.**

- **Use a real FFmpeg `VideoEngine` (the JVM impl) instead of
  `InstantVideoEngine`.** Real numbers, end-to-end signal.
  Rejected: (a) requires `ffmpeg` on PATH, adding a host
  precondition the bench doesn't need; (b) ffmpeg per-clip
  encode is ~200 ms, dwarfing the orchestration regression
  signal the bullet actually wants to catch; (c) bench would
  take ~2–5 seconds per invocation, slow enough to discourage
  running it. The `ExportDeterminismTest` in
  `platform-impls/video-ffmpeg-jvm/src/test` already exercises
  the ffmpeg path end-to-end for correctness; this benchmark
  is for the orthogonal orchestration-cost axis.

- **Run `ExportTool` twice in a single `@Benchmark` — first
  call populates the cache, second measures cache-hit cost.**
  Rejected: cache-hit is trivial (single map lookup in Kotlin
  memory, sub-microsecond) and wouldn't catch the O(N²) path
  that bypasses the cache on fresh runs. Measuring the
  fresh-render path is what the bullet asked for; that's the
  one where the regression risk lives.

- **Parametrize N via `@Param({"1","10","100"})` and measure
  the whole curve.** Cleanest way to detect non-linear scaling.
  Rejected for now: kotlinx-benchmark 0.4.13 `@Param` support
  works, but the bullet scope was "10-clip baseline"; a
  parametrized sweep is a follow-up bullet that would naturally
  pair with `export-incremental-render` (incremental rendering
  is the point where the curve's non-linearity matters most).

**Coverage.** `:benchmark:ktlintFormat` + `:benchmark:ktlintCheck`
green. `:benchmark:benchmark` green — all three rows
(Noop ≈ 3.3 ns, AgentLoop ≈ 37.6 ms, ExportTool ≈ 0.913 ms) land
in `benchmark/build/reports/benchmarks/main/<timestamp>/main.json`.
No existing test suite affected — benchmark module remains a leaf
consumer of `:core`.

**Registration.** No tool / AppContainer change. Build-level:
- `benchmark/build.gradle.kts` adds `libs.okio` +
  `libs.okio.fakefilesystem` to dependencies.
- New file: `benchmark/src/main/kotlin/io/talevia/benchmark/ExportToolBenchmark.kt`.

The three decomposition bullets
(`debt-add-benchmark-infra` / `agent-loop` / `export-tool`) are
now all landed, which closes the original `debt-add-benchmark-core-paths`
coverage gap.
