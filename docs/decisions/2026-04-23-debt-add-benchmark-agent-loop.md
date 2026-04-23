## 2026-04-23 — Agent-loop 10-turn baseline benchmark (VISION §5.7 rubric axis)

**Context.** R.6 #4 scan: `core/agent` is VISION §5.7's most core
path (every user interaction traverses `Agent.run → AgentTurnExecutor
→ provider.stream → ToolRegistry.dispatch → SessionStore persist →
next step`). The only correctness gates today are unit tests in
`core/src/jvmTest/kotlin/io/talevia/core/agent/AgentLoopTest.kt` —
they prove *what* happens, not *how fast*. A refactor that turns
the step loop into O(N²) over history length, or that accidentally
adds a synchronous round-trip inside the tool dispatch path, passes
every existing test while doubling per-turn latency in production.

Cycle-16's `debt-add-benchmark-infra` landed the `:benchmark`
JVM-only module + `kotlinx-benchmark` 0.4.13 plumbing + the
`NoopBenchmark` smoke. This cycle — the second of the three
decomposed sub-bullets of `debt-add-benchmark-core-paths` — uses
that infra to land the first real baseline.

Rubric delta §5.7: agent-loop regression-guard coverage "无 → 有".
§5.6 #10 critical-path coverage also tips — where cycle 14 added
*correctness* coverage for Compactor, this adds *performance*
coverage for the agent loop.

**Decision.** `benchmark/src/main/kotlin/io/talevia/benchmark/AgentLoopBenchmark.kt`:

- `@State(Scope.Benchmark) @BenchmarkMode(Mode.AverageTime)
  @OutputTimeUnit(MILLISECONDS) class AgentLoopBenchmark`
- `@Setup(Level.Invocation)` rebuilds the full stack fresh per
  measured call: JDBC in-memory driver → `TaleviaDb.Schema.create`
  → `SqlDelightSessionStore` → a primed Session → `Agent` with
  `AllowAllPermissionService`, `EchoTool`-only registry, and a
  `ScriptedEchoProvider` loaded with exactly 10 turns. Setup
  cost (driver + one session insert) sits outside the timed
  region, so the measurement captures agent-loop work.
- `@Benchmark fun tenTurnLoop()` runs `agent.run(…)` once. The
  scripted provider yields 9 tool-call round-trips (each asks
  EchoTool for a scripted payload, expects `TOOL_CALLS` finish)
  followed by 1 `END_TURN` text turn — exactly 10 LLM turns per
  measurement.
- A `ScriptedEchoProvider` is inlined in the benchmark file
  (duplicates `core/src/commonTest/.../FakeProvider.kt`). Pulling
  from another module's `commonTest` source set would couple the
  benchmark to the test lifecycle; duplicating 10 lines is
  cheaper than standing up `test-fixtures` for a single call
  site.

Build wiring: `benchmark/build.gradle.kts` gains `kotlinx-serialization-json`
and `sqldelight-driver-sqlite` dependencies (the agent loop
traverses JSON-annotated parts and persists them into
SQLDelight — both transitively needed even though the benchmark
doesn't touch them directly).

**Baseline (reported here as the "official" first measurement;
future CI diffs compare against this).**

- Host: Apple M-series, JDK 21.
- Tool: kotlinx-benchmark 0.4.13 (JMH backend).
- Config: `warmups=1 × 1s, iterations=2 × 1s` — intentionally the
  smoke-grade config from the `:benchmark` module default, not
  a publication-quality setup. Purpose is regression signal, not
  absolute performance claim.
- Result: **`AgentLoopBenchmark.tenTurnLoop ≈ 41.8 ms/op`**
  (wall-time to complete 10 scripted LLM turns through the full
  agent orchestration path, excluding the per-invocation setup).
- Per-turn cost ≈ 4.2 ms — dominated by SQLDelight insert +
  bus emit, which matches what the per-step `historyMessages=N`
  log lines show (each turn re-reads the growing history).

A CI diff showing > +20% over this baseline = real regression.
Bisect and fix before merging. When the distribution of the
setup overhead gets clearer (or we want alloc signal), follow-up
work tightens `iterationTime` / adds `-prof gc` / `-prof stack`
profiler runs.

**Axis.** Benchmark class count per critical path. Before: zero.
After: one. The pressure source for this file growing back past
the 500-line watch is "more benchmark methods on the same state
surface" — which is exactly what we want (more regression
signals, not fewer). A long-file consolidation axis doesn't
apply to this module; if it grows past threshold, we split into
`AgentLoop*Benchmark.kt` peers, same per-axis shape as elsewhere.

**Alternatives considered.**

- **Measure a single `.run()` with a 1-turn END_TURN script
  instead of 10 turns.** Smaller scope, less setup cost
  proportionally. Rejected: 1-turn misses the multi-step loop
  body entirely — the primary regression vector the bullet
  called out (O(N²) over history, extra round-trips, retry
  loops getting wedged). 10 turns is where the quadratic bugs
  show. The additional setup cost is fine; per-invocation
  reset is cheap compared to the 40+ ms of agent-loop body.

- **Use `@Setup(Level.Trial)` once and script 20 turns so the
  @Benchmark body can consume two `run()` calls per invocation.**
  Halves setup amortization. Rejected: each agent.run() leaves
  state in the SQL store (messages + parts persisted); running
  twice means the second run sees a dirty history and doesn't
  reflect the clean 10-turn cost. Accurate measurement requires
  per-invocation reset.

- **Mock out `SqlDelightSessionStore` to isolate pure agent-loop
  logic from persistence.** Gives a cleaner signal on agent
  orchestration alone. Rejected: the bullet asks for a baseline
  against a refactor that might sneak into *either* the agent
  loop *or* the persistence write path — CLI exporters / JSON
  encoders / bus emitters all live in the write-path. Measuring
  the full stack catches regressions in any of them. A
  separate "pure agent loop" bench can land later if we need
  finer-grained bisection.

- **Skip `Level.Invocation` and accept that warm-state measurement
  drifts over time.** Would match what some JMH benchmarks do.
  Rejected: the FakeProvider drains a scripted queue — without
  reset, the second iteration errors out with "FakeProvider
  exhausted" and JMH records a failed iteration.

**Coverage.** `:benchmark:ktlintFormat` + `:benchmark:ktlintCheck`
green. `:benchmark:benchmark` runs both `NoopBenchmark.noop`
(≈ 3.5 ns/op — unchanged from cycle 16, sanity check confirms
no pipeline drift) and `AgentLoopBenchmark.tenTurnLoop`
(≈ 41.8 ms/op — the new baseline). JSON report lands at
`benchmark/build/reports/benchmarks/main/<timestamp>/main.json`
with both rows. No existing test suite affected — benchmark
module is a leaf consumer of `:core`.

**Registration.** No tool / AppContainer change. Build-level:
- `benchmark/build.gradle.kts` adds
  `libs.kotlinx.serialization.json` +
  `libs.sqldelight.driver.sqlite` to `dependencies`.
- New file: `benchmark/src/main/kotlin/io/talevia/benchmark/AgentLoopBenchmark.kt`.

Follow-up bullet `debt-add-benchmark-export-tool` mirrors this
shape for `ExportTool.render()` on the ffmpeg engine.
