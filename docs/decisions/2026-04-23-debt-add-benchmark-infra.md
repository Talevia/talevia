## 2026-04-23 — kotlinx-benchmark infra landed via new :benchmark JVM module (VISION §5.7 rubric axis)

**Context.** R.6 #4 perf scan for this repopulate returned empty for
`find core platform-impls apps -type f \( -name '*Benchmark*.kt' -o
-name '*Perf*.kt' -o -name '*Latency*.kt' \)`. The agent loop +
`ExportTool.render()` + `AgentTurnExecutor` + `FileProjectStore.openAt`
are VISION §5.7's core paths — a stray refactor that turns
whole-timeline render into O(N²) can pass every existing test and
only surface in production latency reports.

The prior `debt-add-benchmark-core-paths` bullet was P0 but
skip-tagged because its scope (plugin + baselines + CI diff) was
too large for one cycle. The repopulate that just preceded this
cycle decomposed it per the skip-tag hint into three sub-bullets
(`infra` + `agent-loop` + `export-tool`); this cycle is the first
of the three and lands the plugin-level infrastructure only.

Rubric delta §5.7: benchmark coverage "无 → 部分" (infrastructure is
in, zero meaningful baselines yet — those land on the two follow-up
bullets that depend on this one).

**Decision.** New JVM-only module `:benchmark/` at repo root:

- `benchmark/build.gradle.kts`: applies `kotlin-jvm` +
  `kotlinx-benchmark` (0.4.13) + `kotlin-allopen`. `allOpen`
  auto-opens classes annotated with `@State` so JMH's generated
  subclasses can override `@Benchmark` methods without each bench
  explicitly marking itself `open`.
- Dependency: `implementation(project(":core"))` +
  `kotlinx-benchmark-runtime` + `kotlinx-coroutines-core`.
- `benchmark { targets.register("main") }` creates the
  `:benchmark:mainBenchmark` task. The aggregate
  `:benchmark:benchmark` task runs all registered targets.
- Smoke benchmark `NoopBenchmark.noop()` in
  `benchmark/src/main/kotlin/io/talevia/benchmark/NoopBenchmark.kt`
  proves end-to-end wiring: `./gradlew :benchmark:benchmark`
  produces a JSON report at
  `benchmark/build/reports/benchmarks/main/<timestamp>/main.json`
  — the path the prior bullet asked for.
- Configuration kept minimal (warmups=1, iterations=2,
  iterationTime=1s) so a smoke run completes in ~15 seconds.
  Real baselines on the two follow-up bullets will raise these
  per-benchmark.
- Plugin aliases added to `gradle/libs.versions.toml`:
  `kotlinx-benchmark = "0.4.13"`, runtime library entry,
  plugin entries for `kotlinx-benchmark` and `kotlin-allopen`
  (the latter uses the same kotlin version `2.1.20`).
- Root `build.gradle.kts` gains `apply false` entries for the two
  new plugins so subprojects can `alias(...)` them.
- `settings.gradle.kts` adds `include(":benchmark")`.

Smoke run result (sanity only, not a baseline): `NoopBenchmark.noop`
≈ 4.0 ns/op (avgt, 2 iterations). The number exists to confirm
the pipeline produced a measurement; future commits will not
chase it.

**Axis.** "Zero benchmarks under core-path files." Before: no
benchmark scaffolding, so any perf-regression guard requires
bootstrapping JMH first before writing the actual bench. After:
`debt-add-benchmark-agent-loop` and `debt-add-benchmark-export-tool`
just add `@Benchmark`-annotated classes to the existing module;
no gradle config changes. The pressure source for re-triggering
this infra bullet is a platform-level shift (e.g. moving to
KMP-wide benchmarks, or iOS on-device benchmark target), not
new benchmark cases.

**Alternatives considered.**

- **Add `kotlinx-benchmark` directly to `:core` as a new KMP
  benchmark sourceset / target.** Idiomatic kotlinx-benchmark KMP
  usage: `kotlin { jvm { compilations { register("benchmark") … } } }`
  plus a `benchmark { targets.register("jvmBenchmark") }`. Rejected:
  perturbing `:core`'s KMP sourceSet graph (which currently has
  `commonMain` / `jvmMain` / `iosMain` / `androidMain` + their
  test counterparts + a `jvmTest` that already exercises the
  shared model) risks breaking every downstream consumer
  (`:platform-impls:video-ffmpeg-jvm` / all 5 `AppContainer`
  modules) with a hard-to-debug dependency-configuration error.
  A separate JVM-only `:benchmark` module depends on `:core`
  like any other consumer — if the benchmark wiring breaks, only
  the benchmark module is affected, and `:core:compileKotlinJvm` /
  `:core:jvmTest` stay untouched. The trade-off is one extra
  module directory; the safety is that `:core` remains ABI-stable.
  KMP-idiomatic benchmarks become worthwhile when we need iOS /
  Android benchmark targets, which is a different bullet.

- **Use JMH directly without kotlinx-benchmark (just apply
  `me.champeau.jmh` gradle plugin).** Pure JMH avoids the
  kotlinx wrapper's additional conventions. Rejected: the
  kotlinx-benchmark wrapper adds two things we'll use:
  (a) the `allOpen` + `@State` auto-open integration (pure JMH
  requires every bench class to be explicitly `open`, which is
  friction and easy to miss), and (b) KMP-ready annotations
  (`kotlinx.benchmark.Benchmark` vs `org.openjdk.jmh.annotations.Benchmark`)
  so the follow-up bullets could pivot to KMP targets without
  re-annotating their bench classes. Both save multi-cycle cost
  downstream; the wrapper's overhead today is ~20 lines of
  gradle config.

- **Park benchmarks under `:core/src/jvmTest/` alongside unit
  tests, gated by a gradle property.** Avoids a new module
  entirely. Rejected: benchmark runs have fundamentally
  different lifecycle constraints (long wall-time, no
  assertion-style fail/pass semantics, JMH fork / warmup
  discipline). Mixing them into `jvmTest` means either every
  CI `:core:jvmTest` run takes minutes longer, or the gate
  silently skips benchmarks and the system drifts to the same
  "zero benchmark signal" failure mode this bullet exists to
  prevent. Dedicated module + dedicated task makes the lifecycle
  decision explicit.

**Coverage.** `./gradlew :benchmark:tasks --all` enumerates the
expected `mainBenchmark` / `assembleBenchmarks` / `benchmark`
tasks. `./gradlew :benchmark:ktlintFormat :benchmark:ktlintCheck`
green. `./gradlew :benchmark:benchmark` green — JSON report
produced at `benchmark/build/reports/benchmarks/main/<timestamp>/main.json`.
No existing test suite affected (`:core:*`, `:apps:*:test`,
`:platform-impls:*:test` untouched — only gradle metadata added).

**Registration.** No tool registration. Build-level changes:
- `gradle/libs.versions.toml` — new `kotlinx-benchmark` version +
  library + plugin aliases, new `kotlin-allopen` plugin alias.
- `build.gradle.kts` — `apply false` entries for the two new
  plugins.
- `settings.gradle.kts` — `include(":benchmark")`.
- New files: `benchmark/build.gradle.kts`,
  `benchmark/src/main/kotlin/io/talevia/benchmark/NoopBenchmark.kt`.
