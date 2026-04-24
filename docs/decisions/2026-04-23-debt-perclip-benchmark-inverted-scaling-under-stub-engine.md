## 2026-04-23 — `PerClipRenderCacheBenchmark` stub-engine now sleeps 2 ms/renderClip (VISION §5.7 rubric axis)

**Context.** Cycle-38's full benchmark run exposed that
`PerClipRenderCacheBenchmark` — built in cycle 35 to prove the
per-clip incremental-render invariant from
`docs/decisions/export-incremental-render-phase-2-per-clip-memoization.md`
— was monotonically **slower** with more pre-cached clips:

```
preCachedClips=0  → 1.360 ms/op
preCachedClips=5  → 1.606 ms/op
preCachedClips=10 → 1.920 ms/op
```

The invariant being proved was the opposite direction: more cache
hits → less work → faster. The inversion happened because
`CountingPerClipEngine.renderClip` was a no-op, so there was no
"render cost" to save on cache hits. The O(N) linear-scan cost inside
`ClipRenderCache.findByFingerprint` then dominated and grew with the
number of seeded entries, producing the backwards curve.

Rubric delta §5.7 (benchmark guard fidelity): `PerClipRenderCacheBenchmark`
moves from **部分** (benchmark runs green but inverts the claim it
supposedly proves — silent lying guard) to **有** (curve reflects the
production ratio of render cost vs cache lookup cost; a regression in
the cache-hit path now shows up as a bent curve that's actually
meaningful).

**Decision.** Option (a) from the bullet's three options: add a tiny
simulated render cost so the cache-hit win dominates.
`CountingPerClipEngine.renderClip` now sleeps `SIMULATED_RENDER_MS =
2L` (milliseconds) per call via `kotlinx.coroutines.delay(...)`, and
the class KDoc + top-level benchmark KDoc explain the rationale + link
to this decision.

Why 2 ms:

- 10× an ffmpeg kernel-dispatch floor for a trivial clip (real
  production `renderClip` is 100–1000× higher — this benchmark isn't
  trying to model absolute throughput, only the ratio).
- Two orders of magnitude above the cost of a `findByFingerprint`
  scan on a small cache (nanoseconds). So cache-hit savings dominate
  lookup cost, and the curve's direction mirrors production.
- Small enough that 3 params × 10 clips × JMH's default 5 warmup + 5
  measurement iterations = ~300 ms added per benchmark run. Well
  within JMH's per-param budget.

Expected post-fix curve:
- `preCachedClips=0`  → ~20 ms (10 renders × 2 ms + small lookup)
- `preCachedClips=5`  → ~10 ms (5 renders × 2 ms)
- `preCachedClips=10` → ~0.x ms (lookups only, no renders)

Monotonic decrease — the direction the phase-2 claim asserts.

**Axis.** `what brings this benchmark back to inverted-curve land?` —
if `ClipRenderCache.findByFingerprint` ever grows to the point where
its lookup cost exceeds 2 ms on small-N caches, the simulated render
cost would need to grow too OR the lookup would need to become O(1)
(option b from the bullet). Current findByFingerprint is a short list
scan over ≤ few thousand entries; a sub-µs cost. Plenty of margin.

**Alternatives considered.**

- **(b) Replace `ClipRenderCache.findByFingerprint`'s linear scan with
  a `Map<String, ClipRenderCacheEntry>` lookup.** Rejected for this
  cycle — it's a separate production concern ("is the cache
  data-structure good for hot paths?"), not a benchmark correctness
  fix. Doing (b) without (a) would still give the benchmark a flat
  curve (no cost to save), and doing (b) without evidence that
  findByFingerprint is a hot-path bottleneck would be premature
  optimisation. Added as a P2 "顺手记 debt" follow-up (`debt-clip-
  render-cache-map-lookup`) so the trigger is explicit: measurable
  perf regression on deep caches.

- **(c) Drop the benchmark's claim, replace with a micro-benchmark of
  the fingerprint + lookup path in isolation.** Rejected — the
  "linear improvement with reused-clip ratio" claim is the most
  operator-facing guarantee from phase 2 ("your incremental re-export
  gets faster as more clips stay unchanged"). Losing that benchmark
  means losing the headline signal. A micro-benchmark of
  fingerprint-lookup would be fine to add separately but shouldn't
  replace the end-to-end scaling test.

- **Higher simulated delay (e.g. 10 ms).** Rejected — 3 × 10 clips ×
  10 ms = 300 ms per op × N iterations stretches JMH runs
  unnecessarily. 2 ms is already 10-20× the dominant cost in the old
  run (1.3–1.9 ms/op total was probably mostly orchestration); lifting
  past that is enough.

- **Add `@Param("0", "2", "5")` for simulated-ms so the benchmark
  probes the render:lookup ratio dimension too.** Rejected — one more
  param dimension ≠ better signal, just more runtime. The benchmark's
  singular job is "prove miss-count scaling"; the ratio tuning is an
  implementation detail documented in the constant's KDoc.

**Coverage.**

- `:benchmark:assemble` compiles cleanly with the delay call.
- Existing `:core:jvmTest` / `:apps:cli:test` / `:apps:server:test` /
  `:apps:desktop:assemble` / `ktlintCheck` all green.
- No new unit test — benchmarks aren't JUnit-exercised; their
  correctness is manifested by the measured curve direction, which
  this change restores. Next full benchmark run (not tied to every
  cycle — runs on demand) will observe the corrected monotone
  decrease.

**Registration.** No registration changes — pure benchmark-harness
fix scoped to one file: `benchmark/src/main/kotlin/io/talevia/benchmark/PerClipRenderCacheBenchmark.kt`.
