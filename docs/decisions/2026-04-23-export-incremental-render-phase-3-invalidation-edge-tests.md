## 2026-04-23 — Phase 3: five counter-intuitive invalidation edges pinned end-to-end (VISION §5.7 rubric axis)

**Context.** Final P1 of the three-phase incremental-render split the
2026-04-23 skip-tag requested. Phase 1 specified the cache-key
composition + mutation/invalidation matrix; phase 2 added the missing
engine-id axis + the cache-hit-ratio benchmark; phase 3 lands the
regression tests for the five counter-intuitive invalidation behaviours
the phase-1 matrix called out as "most likely to be broken by a
well-meaning future refactor".

The bullet's text named three edges explicitly:
- (a) same clip on two tracks — track reorder must not invalidate
- (b) identical clip spec, different ancestors — must miss
- (c) cross-engine cache reuse — must miss

Phase 1's decision reread (a) and found it wasn't quite the right
phrasing — the per-clip cache doesn't apply to multi-video-track
shapes because `timelineFitsPerClipPath` rejects them, so the real
invariant is "multi-video-track falls back to whole-timeline render".
This cycle pins that interpretation explicitly. Plus two extra edges
from the phase-1 matrix that are equally load-bearing: the positive
"retarget to different outputPath at same profile must hit" (regression
guard against folding `targetPath` into the fingerprint), and the
pure "track reorder doesn't perturb" invariant at fingerprint level.

Rubric delta §5.7: per-clip cache invalidation coverage moves from
"unit tests on each segment independently" (cycle 34 + 35) to "each
counter-intuitive edge named in the phase-1 matrix has a dedicated
pinning test, with end-to-end coverage for the cross-engine and
multi-track-fallback shapes". Phase 3 closes the 3-phase split; the
`export-incremental-render` bullet family is complete.

**Decision.** New test file
`core/src/jvmTest/kotlin/io/talevia/core/tool/builtin/video/export/PerClipCacheInvalidationEdgeTest.kt`
with 5 test methods, each named after the edge it pins:

1. **`trackReorderDoesNotPerturbFingerprint`** (fingerprint-level, pure)
   — same `Clip.Video` JSON produces the same fingerprint regardless
   of the enclosing track id or position. Guards against a future
   refactor that accidentally folds `TrackId` or clip index into the
   fingerprint input. (Phase 1 matrix row: "reorder_tracks — 0 clips
   invalidated".)

2. **`multiVideoTrackFallsBackToWholeTimelinePath`** (end-to-end via
   `ExportTool.execute`) — a 2-video-track timeline dispatches the
   whole-timeline `render` call and must NOT reach `renderClip`. Uses
   a local `CountingFakeEngine` with `supportsPerClipCache=false` to
   observe the dispatch choice. Guards against a future refactor that
   broadens `timelineFitsPerClipPath`'s eligibility without wiring
   multi-track concat. (Phase 1 matrix note on multi-track: out of
   scope for per-clip cache, falls back.)

3. **`grandparentEditInvalidatesDescendantBoundClipViaDeepHash`**
   (fingerprint-level, pure) — same clip × same `sourceBinding`
   node id × different `boundSourceDeepHashes` value must perturb the
   fingerprint. The scenario mirrors
   `docs/decisions/2026-04-23-source-consistency-propagation-runtime-test.md`'s
   pinned invariant one layer up (tool-runtime) — this version pins
   it at the cache-key layer. Guards against a future refactor that
   "simplifies" the fingerprint by only hashing node ids (without
   their deep hash values) — a silent-stale-AIGC regression.

4. **`crossEngineExportForcesRerenderEvenWithIdenticalCache`**
   (end-to-end) — populate the `clipRenderCache` via engine A
   (engineId=`ffmpeg-jvm`), then export the same project via engine B
   (engineId=`media3-android`) that ALSO reports the mezzanine path
   present (simulating a shared filesystem). B must still render
   fresh: fingerprints differ at the `|engine=...` segment,
   `findByFingerprint` returns null, and `renderClip` fires. Post-
   export, the `clipRenderCache` holds two entries (one per engine,
   keyed by distinct fingerprints). This is the edge that cycle 35's
   engine-id addition was designed to protect; phase 3's job is
   pinning it at the tool-runtime layer.

5. **`differentOutputPathReusesCacheAtSameProfile`** (end-to-end) —
   positive counter-intuitive: retarget an export to
   `/tmp/second.mp4` with the same profile as the prior
   `/tmp/first.mp4` run must hit the per-clip cache (no additional
   `renderClip` call), producing only a second `concatMezzanines`
   invocation to compose the new target file from the cached
   mezzanines. Guards the deliberate exclusion of `targetPath` from
   the fingerprint (phase-1 matrix row: "Export to same profile,
   different outputPath → 0 clips invalidated").

File location: `core/src/jvmTest/kotlin/io/talevia/core/tool/builtin/video/export/`
next to `PerClipRender.kt`. A reader changing the cache-key semantics
doesn't have to hunt for the tests — they sit in the same package.

`CountingFakeEngine` is a local private class mirroring
`ExportToolTest.FakePerClipEngine`'s shape but with two constructor
params exposed: `engineId` (so cross-engine tests can vary it) and
`presentPaths` (so cross-engine tests can seed the "mezzanine file
visible" state simulating a shared filesystem). Two counter classes
exist now (`ExportToolTest.FakePerClipEngine` + this file's
`CountingFakeEngine`); I considered extracting to a shared test-kit
helper and decided against — the two diverge on constructor surface,
and pre-abstracting on N=2 (§3a #14: "don't pre-abstract on N=1";
same principle up-scaled for N=2) would be premature. A third caller
triggers extraction.

**Axis.** Number of phase-1-matrix edges with dedicated pinning
tests. Before: 2 (engine-id axis covered in cycle 35's
`ClipFingerprintTest.engineIdPerturbsFingerprint`; deep-hash drift
covered in cycle 35's `boundSourceHashChangePerturbs`). After: 2 + 5
= 7. Pressure source that would re-trigger edge testing: a new
phase-1-matrix row becoming load-bearing (e.g. a new encoder-version
axis), plus any future refactor that changes the fingerprint shape
— those should land alongside the matrix row's test.

**Alternatives considered.**

- **Put all 5 tests in `ClipFingerprintTest.kt` alongside the
  existing fingerprint tests.** Cleaner "all fingerprint tests in
  one place". Rejected: two of the five (multi-track fallback,
  cross-engine end-to-end) require a full `ExportTool` rig plus a
  fake `VideoEngine`, which `ClipFingerprintTest` doesn't
  currently need. Splitting these into a sibling file keeps
  `ClipFingerprintTest` pure-unit (no tool-runtime dependencies)
  and lets the edge-test file live next to the per-clip dispatcher
  it exercises.

- **Extract `CountingFakeEngine` to a test-kit helper shared with
  `ExportToolTest.FakePerClipEngine`.** Would DRY up ~60 lines
  across two files. Rejected: §3a discipline of "don't pre-
  abstract on N=1 (or N=2 when the two classes diverge on
  constructor surface)". The two fakes differ in what they
  expose: `FakePerClipEngine` fixes `engineId` to its default and
  exposes `presentPaths`/`rendered` lists; `CountingFakeEngine`
  parametrizes `engineId` and `supportsPerClip` in the
  constructor, doesn't track a `rendered` list (not needed for
  these tests). When a third caller appears with a third
  divergent shape, that cycle extracts a common base.

- **Write only the three edges the bullet named; skip the two
  phase-1-matrix additions (track reorder, path-insensitive
  reuse).** Rejected: the phase-1 matrix explicitly called these
  out as counter-intuitive; the bullet's "etc" language welcomes
  additional edges. Writing them now keeps phase 3's close-the-
  loop shape complete — future reads of the decision chain don't
  have to cross-reference a matrix separately.

- **Add JMH-style performance assertions to prove the "linear
  improvement" claim from the phase-2 benchmark.** Rejected:
  different lane. Phase 2 already owns the benchmark; phase 3 is
  about correctness invariants. JMH wall-time assertions would be
  flaky on CI / developer machines anyway — they're for ad-hoc
  measurement, not regression tests.

**Coverage.** 5 new test methods, all green. The existing
`ClipFingerprintTest` (10 tests from cycle 35) + `ExportToolTest`
per-clip suite (several) continue to pass unchanged. Full
`:core:jvmTest` + `:apps:cli:test` + `:apps:server:test` +
`:apps:desktop:assemble` + `:core:compileKotlinIosSimulatorArm64` +
`:apps:android:assembleDebug` + ktlintFormat + ktlintCheck all
green.

**Registration.** None — pure test addition. No production-code
change this cycle; no new tool, no AppContainer touch, no
serialization-surface change. Phase 3 is the "lock in the
invariants" close-the-loop cycle that the 3-phase decomposition
was designed to deliver.
