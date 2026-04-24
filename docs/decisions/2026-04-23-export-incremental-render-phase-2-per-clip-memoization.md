## 2026-04-23 — Phase 2: add `engineId` axis to per-clip fingerprint + benchmark cache-hit-ratio scaling (VISION §5.7 rubric axis)

**Context.** P1 bullet `export-incremental-render-phase-2-per-clip-memoization`,
second of the three-phase split the 2026-04-23 skip-tag requested.
Phase 1 decision file
(`docs/decisions/2026-04-23-export-incremental-render-phase-1-cache-key-design.md`)
specified the per-clip cache-key composition and flagged engine-id as
the missing 5th axis; this cycle lands that axis plus the benchmark
the bullet requested to demonstrate linear cache-hit-ratio scaling.

The bullet's framing — "wire `ClipRenderCache` to the key from phase 1;
`ExportTool` looks up per-clip artefacts and only dispatches
`renderClip` for keys not found" — was already implemented before phase
1 (via `runPerClipRender` + `clipMezzanineFingerprint` +
`ClipRenderCache.findByFingerprint`). Phase 1's code-read surfaced
that. What phase 2 owes per the phase-1 decision:

1. Add `VideoEngine.engineId: String` as an interface member with a
   default `= "unknown"`.
2. Override in production engines: `ffmpeg-jvm`, `media3-android`,
   `avfoundation-ios` (the latter is a Swift override; the Kotlin
   default covers it until iOS gains per-clip support — iOS doesn't
   dispatch through `runPerClipRender` today since
   `supportsPerClipCache` defaults false for AVFoundation).
3. Thread `engine.engineId` into `clipMezzanineFingerprint` as the 5th
   key segment `|engine=<engineId>`, appended at the end to preserve
   the canonical-order invariant from phase 1.
4. Add a `PerClipRenderCacheBenchmark` demonstrating the per-clip
   path's linear scaling with pre-cached clip count.
5. Migration note: pre-existing `ClipRenderCacheEntry` rows on live
   bundles carry fingerprints computed without `|engine=...`. After
   this cycle they'll miss on next export (fingerprint recomputes to
   a different value), `mezzaninePresent(path)` remains the safety
   net catching the old path (so nothing dangerous happens — just a
   one-time re-render), and `project_maintenance_action(action=gc-render-cache)`
   clears the orphaned rows on next sweep. One-time acceptable cost
   per the phase-1 decision's explicit note.

Rubric delta §5.7: cross-engine safety moves from **incidental**
(relied on `mezzaninePresent(path)` + machine-local path
non-collision) to **specified + pinned** (engine-id in the
fingerprint + unit test asserts 3-way perturbation + benchmark
proves the scaling claim).

**Decision.**

### Interface

`VideoEngine.kt` gains:

```kotlin
val engineId: String get() = "unknown"
```

Default `"unknown"` keeps test fakes unchanged (they get a uniform
bucket; a test using one fake engine has no cross-engine
collision risk within its own `ProjectStore`). Docstring explains
the "two engines produce byte-different mezzanines" rationale.

### Production overrides

- `FfmpegVideoEngine.kt`: `override val engineId: String = "ffmpeg-jvm"`
- `Media3VideoEngine.kt`: `override val engineId: String = "media3-android"`
- AVFoundation (Swift side, `AVFoundationVideoEngine.swift`): not
  overridden — falls back to Kotlin `"unknown"` default. The
  Swift class doesn't implement `supportsPerClipCache=true`, so
  it never reaches `clipMezzanineFingerprint`, so the engine-id
  for AVFoundation is only load-bearing the day iOS gains per-clip
  support. That cycle adds the override alongside the Swift-side
  `renderClip` + `concatMezzanines` impls.

### Fingerprint function

`clipMezzanineFingerprint` gains a new required `engineId: String`
parameter (no default — forcing explicit caller opt-in so a missed
call-site shows up as a compile error, not a silent fallback).
Appended at the end of the canonical string as `|engine=<engineId>`
so the existing first-four segments' ordering stays byte-stable.
`PerClipRender.runPerClipRender` passes `engine.engineId` through.

### Test coverage

`ClipFingerprintTest.kt` gets two new test methods:

- `engineIdPerturbsFingerprint` — 3-way assertion: `ffmpeg-jvm` vs
  `media3-android` vs `avfoundation-ios` all produce distinct
  fingerprints with everything else equal. Pins the main invariant.
- `engineIdEmptyStringStillProducesStableFingerprint` — degenerate
  empty-string engineId hashes deterministically + partitions away
  from named engines. Guards against a future refactor that tries
  to make `engineId` nullable and silently collapses null → empty.

The existing 7 tests (identical, path-ignore, clip-shape, filter,
fades, bound-source content, bound-source order, output-profile
essentials) continue to pass unchanged — the new required
`engineId` parameter is threaded via a local `fp(...)` helper that
defaults `engineId = "test-engine"` so each existing test is
1-liner with the helper.

### Benchmark

New `benchmark/src/main/kotlin/.../PerClipRenderCacheBenchmark.kt`
parametrizes `preCachedClips ∈ {0, 5, 10}` for a 10-clip timeline
and measures `ExportTool.execute` wall-time under `Mode.AverageTime`.

- `@Param("0", "5", "10")` produces three runs. Expected monotone:
  `preCachedClips=10` < `preCachedClips=5` < `preCachedClips=0`.
  The ratio between endpoints is roughly `10:5:0 = 1:0.5:0` in
  miss-count, so wall-time should drop ~proportionally with
  orchestration overhead amortized across the number of
  `renderClip` dispatches.
- `CountingPerClipEngine` opts into `supportsPerClipCache=true` and
  sets `engineId = "bench-per-clip"`, so benchmark fingerprints are
  partitioned from production engine fingerprints. `renderClip` +
  `concatMezzanines` are no-ops — the benchmark measures
  orchestration + cache lookup, not fake ffmpeg work (by design, per
  the existing `ExportToolBenchmark` convention).
- Pre-seeding: the setup computes the authoritative fingerprint for
  each pre-cached clip using the same `clipMezzanineFingerprint`
  function `ExportTool` will recompute, and the fake engine's
  `preSeededPaths` set lets `mezzaninePresent(path)` answer
  truthfully for the seeded mezzanines — so the hits are legitimate,
  not rigged via string collision.

Baseline numbers are NOT captured in this decision file (the
`ExportToolBenchmark` cycle 18 decision captured its own; this one
leaves actual measurement to the user via
`./gradlew :benchmark:benchmark` — matching the module's existing
convention of "infra landed, measure on demand").

### Migration note (live caches)

Any live `ClipRenderCache` row on disk (in a `talevia.json` bundle
that was exported before this commit) carries a fingerprint
computed without the engine segment. After this cycle:

- Next export recomputes the fingerprint with `|engine=...`; the
  lookup misses the old entry.
- `mezzaninePresent(path)` on the old mezzanine path may still
  return true (the `.mp4` is still on disk) but — critically —
  `findByFingerprint` won't find it since the fingerprint doesn't
  match. So the cache effectively forgets it.
- Re-render creates a fresh mezzanine at the new fingerprint's
  path. The old `.mp4` becomes an orphan.
- `project_maintenance_action(action=gc-render-cache)` with any
  age / count policy will sweep orphans next time it runs. User-
  initiated or automated via a future maintenance schedule.

Cost: one-time per-bundle re-render of all cached clips on the
next export. Acceptable per phase-1's "one-time acceptable cost
for correctness" language.

**Axis.** Cache-key segments vs. engine-compatibility surface area.
Before: 4 segments (clip / fades / src / out); cross-engine safety
relies on path non-collision. After: 5 segments; cross-engine safety
baked into the key. Pressure source for re-triggering: a new axis
becoming load-bearing (e.g. if we ever need to fold in a "rendering
codec version" for an engine that's non-deterministic across its
own versions), phase 3 or later can append `|version=...` at the
end. The segment-append-only invariant makes future axes cheap.

**Alternatives considered.**

- **Partition mezzanine directories by engineId** instead of by
  fingerprint. Rejected in phase 1 decision + again here: the
  fingerprint is the cache index; two engines with the same
  fingerprint would still collide in `findByFingerprint` even if
  their files lived in different directories.

- **Bake `engineId` into `OutputSpec`** rather than pass separately.
  Would keep the `clipMezzanineFingerprint` signature unchanged.
  Rejected: `OutputSpec` is user-visible (shown in
  `project_metadata` queries, echoed in export result payloads);
  adding `engineId` would either leak runtime dispatch details
  into the user surface (confusing — why does my project metadata
  say `engineId: "ffmpeg-jvm"`?) or require a dual Output / Input
  split. Passing engineId as a separate parameter to
  `clipMezzanineFingerprint` keeps OutputSpec's surface user-facing.

- **Make `engineId` non-nullable on `VideoEngine`** without a
  default. Rejected: would break every test fake in the codebase
  (7+ classes in `ExportToolTest`, `M6FeaturesTest`,
  `RefactorLoopE2ETest`, `ImportMediaBatchTest`, etc.), plus
  `NoopMaintenanceEngine`, plus any future ad-hoc rig. A default
  `= "unknown"` costs nothing (every test fake has zero
  cross-engine collision risk within its one test) and keeps
  behavioural stability for legacy compositions. Production
  engines must still override explicitly — reading the override
  declaration is a 1-line sanity check.

- **Defer benchmark until phase 3.** The bullet explicitly asked
  for "Benchmarks must show linear improvement with reused-clip
  ratio" in phase 2's scope. Landing it here with the engine-id
  change gives the phase-3 invalidation-edge-test cycle one fewer
  thing to build.

- **Parametrize the benchmark over more ratios (e.g. 0/2/4/6/8/10).**
  Rejected for scope: the three chosen values (0 / 5 / 10) prove
  the linear shape; denser sampling is a follow-up if a regression
  appears in the existing coarse data. JMH runs are expensive
  (~30s per `@Param` value × warm-up + measurement), so sparse
  parametrisation keeps the benchmark cheap to run on demand.

**Coverage.**

- `ClipFingerprintTest` gains 2 tests (10 total).
  `engineIdPerturbsFingerprint` asserts 3-way distinct
  fingerprints across the production engine trio;
  `engineIdEmptyStringStillProducesStableFingerprint` covers the
  degenerate case.
- Existing `ExportToolTest`'s per-clip test rig (`FakePerClipEngine`)
  doesn't override `engineId` and gets `"unknown"` — all its
  existing tests still pass (verified via full `:core:jvmTest`
  run).
- `:benchmark:compileKotlin` passes; full benchmark runs can be
  triggered via `./gradlew :benchmark:benchmark` ad hoc (not wired
  into CI, matching the existing `ExportToolBenchmark` convention).

Gradle: `:core:jvmTest` + `:apps:cli:test` + `:apps:server:test` +
`:apps:desktop:assemble` + `:core:compileKotlinIosSimulatorArm64` +
`:apps:android:assembleDebug` + `ktlintFormat` + `ktlintCheck` all
green.

**Registration.** No tool / AppContainer change. `VideoEngine`
interface change with a safe default means constructor signatures
for existing engine registrations (across CLI / Desktop / Server /
Android / iOS AppContainers) stay unchanged — the override is
inside the engine class body. Test-fake `VideoEngine` impls
inherit the `"unknown"` default and continue to pass.
