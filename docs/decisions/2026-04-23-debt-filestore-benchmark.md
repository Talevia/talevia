## 2026-04-23 — `FileProjectStoreBenchmark`: wall-time baselines for openAt / upsert / list at 10 / 100 / 500 clips (VISION §5.7 rubric axis)

**Context.** P1 bullet `debt-filestore-benchmark` from the cycle-31
repopulate. `core/domain/FileProjectStore.kt` is the hottest-path store
(every tool mutation calls `mutate()`, every session open calls `get()` /
`openAt()`, every project list call walks the registry), and carried
zero wall-time benchmark. A refactor that accidentally makes `openAt`
/ `upsert` O(size²) — e.g. re-reading the envelope inside a
`forEach`, or re-serialising the full project on every clip add —
would slip in unnoticed until real-world projects start to lag.

Rubric delta §5.7: `FileProjectStore` benchmark coverage goes from
**无** to **有**. Other core-path benchmarks present:
`AgentLoopBenchmark` (cycle 17), `ExportToolBenchmark` (cycle 18),
`PerClipRenderCacheBenchmark` (cycle 35). `FileProjectStore` was the
last "hottest-path core primitive" without wall-time guardrails.

**Decision.** New `benchmark/src/main/kotlin/.../FileProjectStoreBenchmark.kt`
with three `@Benchmark` methods × three `@Param("10", "100", "500")`
clip-count sweeps = nine data points per run. Storage: `FakeFileSystem`
(same convention as `ExportToolBenchmark` / `PerClipRenderCacheBenchmark`
— the benchmark measures pure encode/decode + mutex + registry cost,
not OS write latency).

Three methods:
- **`openAtBundle()`** — measures `store.openAt(path)` on a pre-seeded
  bundle. Tests `talevia.json` deserialisation + lockfile + snapshots
  + source DAG decode.
- **`upsertProject()`** — idempotent overwrite of an N-clip project.
  Tests JSON encode + fs write + `recents.json` touch.
- **`listProjects()`** — walks `recents.json` registry, decodes each
  bundle's envelope. Tests the N-bundle enumeration path that
  `list_projects` tool and CLI `/list` use.

Setup strategy: `@Setup(Level.Trial)` (one-shot per benchmark class
run) constructs 10 peer projects + 1 primary project at the sweep's
clip count. Peers stay 10-clip so `listProjects` benchmark measures
"N peer bundles + 1 primary at sweep size" rather than "11 bundles
all at sweep size" — cleaner signal for the per-bundle size curve.

**Baseline numbers** (smoke run, 2 iterations, JDK 21 on M-series,
FakeFileSystem):

| Operation | 10 clips | 100 clips | 500 clips | Scaling |
|---|---|---|---|---|
| `openAtBundle` | 0.206 ms/op | 1.318 ms/op | 7.234 ms/op | ≈6.4× for 5× size → super-linear on decode |
| `upsertProject` | 0.290 ms/op | 1.195 ms/op | 5.642 ms/op | ≈4.7× for 5× size → roughly linear on encode |
| `listProjects` | 0.938 ms/op | 1.592 ms/op | 4.468 ms/op | scales with primary bundle size (10 peer bundles fixed at 10 clips each) |

The super-linear `openAtBundle` is consistent with
`kotlinx.serialization`'s JSON decode cost (allocator pressure grows
with object graph depth, not just size). A future `bundle-talevia-json-split`
bullet (trigger-gated at P2) would change this — sub-files mean
openAt only touches the envelope + lazy-loads sub-contents — at which
point this benchmark proves the O(delta) claim quantitatively.

Running: `./gradlew :benchmark:mainBenchmark` for all benchmarks, or
filter with `--tests '*FileProjectStore*'`. Numbers land in
`benchmark/build/reports/benchmarks/main/<timestamp>/main.json`. Same
on-demand convention as `ExportToolBenchmark` — not wired into CI
assertions; landing the infra + initial baseline is the deliverable.

**Axis.** n/a — pure additive benchmark, no structural refactor.
Pressure source that would re-trigger benchmark work on this primitive:
`bundle-talevia-json-split` landing would require re-measurement to
prove the shift to O(delta) encode/decode. Also a new persistence
format (e.g. SQLite-backed project store) would warrant rewriting
these benchmarks against the new shape.

**Alternatives considered.**

- **Use real filesystem (System.SYSTEM) instead of FakeFileSystem.**
  Closer to production. Rejected: OS write latency dominates on small
  payloads (~1-5ms depending on disk), swamping the encode/decode
  cost the benchmark is designed to catch. ExportToolBenchmark /
  PerClipRenderCacheBenchmark both established FakeFileSystem as
  the convention for orchestration-level benchmarks.

- **Parametrise `listProjects` over peer-project count instead of
  clip count.** Would produce a pure "how much does N bundles scale"
  curve. Rejected for scope: the bullet specifically asked
  `list()` as a baseline, not a scaling study. Adding a second
  `@Param("peerCount")` axis would 3×3=9 the benchmark matrix for
  marginal insight. Future cycle can add it when someone needs to
  answer "how bad is listSummaries at N=500 bundles?" — and that's
  what the skip-gated `recents-registry-list-summaries-index`
  bullet will address when triggered.

- **Run a full `./gradlew :benchmark:mainBenchmark` with warm-up
  iterations + long run to get publication-quality numbers.**
  Rejected: the benchmark module's default config is `warmups=1,
  iterations=2, iterationTime=1s` (smoke-run friendly). The decision
  file documents the smoke numbers as "infra landed + initial
  baseline captured"; running under tighter config is an on-demand
  flow for anyone investigating a regression.

- **Add a runtime pass/fail assertion (e.g. `openAtBundle < 10ms`).**
  Rejected: timing assertions are CI-machine-dependent and prone to
  flakiness. Regression detection belongs to humans running the
  benchmark ad hoc when a suspected regression lands; baking
  assertions would produce false-positive CI failures more often
  than true-positive regression catches.

**Coverage.** The benchmark is itself the coverage for the invariant
"`FileProjectStore` primitives have measured wall-time baselines". It
runs under the existing `:benchmark:mainBenchmark` task alongside the
4 prior benchmarks. All build verifications passed:
`:core:jvmTest` + `:apps:cli:test` + `:apps:server:test` +
`:apps:desktop:assemble` + `:core:compileKotlinIosSimulatorArm64` +
`:apps:android:assembleDebug` + ktlintFormat + ktlintCheck green.

**Registration.** None — `:benchmark` module is wired once by the
existing `benchmark/build.gradle.kts`. New `@Benchmark`-annotated
methods are auto-discovered by kotlinx-benchmark's JMH generator.

**Side observation (also queued as a P2 debt).** When running the
full benchmark suite to validate this change, `PerClipRenderCacheBenchmark`
(cycle 35) showed: preCachedClips=0 at 1.360 ms/op < preCachedClips=5
at 1.606 ms/op < preCachedClips=10 at 1.920 ms/op. That's the
**opposite** of phase-2's decision's claim "linear improvement with
reused-clip ratio". Root cause (likely): `ClipRenderCache.findByFingerprint`
is an O(N) linear scan; with 10 pre-seeded entries and 10 clips to
export, the lookup work dominates `CountingPerClipEngine.renderClip`
which is a no-op. A real engine with real render cost would invert
the curve — cache-hit-skip-real-render dominates O(N) lookup — but
the stub engine doesn't. The benchmark as-is measures orchestration
overhead only, and under that lens MORE cache = MORE lookup = slower.
This is a real coverage gap: the benchmark's claim is unverified by
its own numbers. Queued as
`debt-perclip-benchmark-inverted-scaling-under-stub-engine` in P2
for a follow-up cycle to address (either: add a simulated-render
cost to CountingPerClipEngine, OR switch ClipRenderCache to a
Map lookup, OR drop the benchmark as "proves nothing under this
setup" and replace with a micro-benchmark that measures the
fingerprint + lookup path in isolation).
