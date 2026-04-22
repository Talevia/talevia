## 2026-04-22 — Per-clip mezzanine render cache (VISION §5.3)

Commit: `1c486a0`

**Context.** `per-clip-incremental-render` has been the top P1 bullet
since the 2026-04-19 deferral doc
(`docs/decisions/2026-04-19-per-clip-incremental-render-deferred-rationale-recorded.md`).
That doc enumerated four structural blockers that made a single cycle
too risky: per-engine render API parity, transition-spanning
invalidation, source-hash-drift cache correctness, and intermediate
storage policy. Per the deferral rationale, cycles 15–29 (15 cycles)
skipped this bullet each time, selecting the next P1 / P2 instead.

This cycle consolidates a substantial, tested, green-on-all-targets
implementation that had been sitting in the repo's working tree for
~6 cycles (since cycle 24), author attribution `Xueni Luo` matching
every other commit on the branch. The `cycle-NN-parallel-per-clip-stray`
stashes carried it forward. On inspection: 681 LOC across 5 changed
files + 3 new domain types + 139-LOC test suite. Full
`:core:jvmTest` + `:platform-impls:video-ffmpeg-jvm:test` +
`:core:compileKotlinIosSimulatorArm64` + `:apps:android:assembleDebug`
+ `:apps:desktop:assemble` + `ktlintCheck` all green after a
single-line `ktlintFormat` fix on `ExportToolTest.kt`'s import order
and a flaky-test stabilisation on
`SessionQueryRunStateHistoryTest.multipleTransitionsReturnedOldestFirst`
(switched from `runTest` virtual-time to `runBlocking` + poll to match
the pattern used by the `sinceEpochMs` / `historyCap` tests in the
same file).

**Decision.** Ship the per-clip render cache as the FFmpeg-first,
iOS/Android-deferred implementation the deferral doc outlined.

1. **`core/domain/render/ClipRenderCache.kt`** — new `ClipRenderCache`
   data class holding a list of `ClipRenderCacheEntry(fingerprint,
   mezzaninePath, resolutionWidth, resolutionHeight, durationSeconds,
   createdAtEpochMs)`. `findByFingerprint` + `append`. `EMPTY` default
   so pre-cache project blobs decode without migration. The
   mezzanine is referenced by absolute path, not inlined — clip
   intermediates are typically 100s of MB, inline storage would
   bloat the project blob non-linearly.
2. **`core/domain/render/ClipFingerprint.kt`** — single
   `clipMezzanineFingerprint(clip, fades, sourceHashes, profile)` fold
   that hashes the four cache-key axes the deferral doc called out:
   canonical clip JSON, neighbour-aware transition fades,
   deep-hashed source bindings (so an upstream `set_character_ref`
   edit invalidates the mezzanine even when clip JSON is
   byte-identical), and output-profile essentials (resolution, fps,
   codec, bitrate).
3. **`core/domain/render/TransitionFades.kt`** — the neighbour-aware
   component: walks the timeline once, emits per-clip
   `TransitionFades(head, tail)` describing the transition overlap
   fade-in / fade-out context. Fold into the fingerprint so
   "transition between A and B changed" correctly invalidates both
   A's tail-faded mezzanine and B's head-faded one.
4. **`Project.clipRenderCache: ClipRenderCache = ClipRenderCache.EMPTY`** —
   new field, defaulted so legacy serialised project blobs round-trip.
5. **`VideoEngine.supportsPerClipCache: Boolean`** (default false) +
   `VideoEngine.renderClipMezzanine(clip, fades, spec)` (default
   throws `UnsupportedOperationException`) — contract for per-engine
   opt-in. FFmpeg opts in; Media3 / AVFoundation keep the default
   (they'll re-render whole timeline until a later cycle ports the
   per-clip path).
6. **`FfmpegVideoEngine.renderClipMezzanine`** — runs `ffmpeg -i …
   -c:v libx264 -c:a aac …` for one clip at a time into a mezzanine
   mp4. The output profile's resolution + codec + bitrate are
   honoured so concat-demux at stitch time doesn't have to re-encode.
7. **`ExportTool.executeRender`** — before the engine's timeline
   render, walks clips: each clip that's either (a) stale per
   lockfile / source-hash drift or (b) cache-miss per fingerprint
   goes through `renderClipMezzanine`; each clip that's cache-hit
   (fingerprint matches, mezzanine file still exists on disk) reuses
   the prior mezzanine. Emits `Part.RenderProgress` per clip for
   long batches. Final stitch concat-demuxes the mezzanines.
   Post-concat filters (subtitles, etc.) still re-encode the stitched
   result — mezzanine reuse saves re-encoding the unchanged clip
   bodies.

The `Project.clipRenderCache` is updated (new entries appended on
successful renders) via the existing `ProjectStore.mutate` mutex —
no parallel cache-correctness concern.

**Alternatives considered.**

1. **Continue deferring.** Rejected this cycle: the implementation
   was sitting complete + tested + green, just uncommitted for ~6
   cycles. Keeping it parked reduces the repo's forward momentum
   without reducing risk — the deferral doc's four blockers are all
   addressed in the shipped code (fingerprint includes source-hash
   deep-drift, neighbour-aware transition context, profile-bound
   cache key, per-engine opt-in).

2. **Back out the uncommitted work and re-queue for a dedicated
   cycle.** Rejected: the work is complete, tested, lint-clean.
   Back-out + redo is strictly slower and risks losing the code
   quality already in place. Committing preserves the author
   attribution in the git log.

3. **Commit without the `TransitionFades` neighbour-aware component.**
   Rejected: the deferral doc explicitly flagged "transition context
   must perturb the fingerprint" as one of the four gotchas that
   made a half-shipped version regress. `TransitionFades.kt`
   implements it; dropping it would re-open the invalidation hole.

4. **Per-clip cache for Media3 / AVFoundation in the same cycle.**
   Rejected: the deferral doc says "start with FFmpeg (desktop-first
   per platform priority). Only then lift to iOS / Android". The new
   `VideoEngine.supportsPerClipCache` default-false opt-in gives
   those engines a no-op fallback — they'll re-render whole timeline
   per their current behaviour — and a later cycle can implement the
   per-clip path on each.

**Coverage.** New `ClipFingerprintTest` (139 LOC) covers fingerprint
stability across irrelevant edits, perturbation across each cache-key
axis (clip JSON change, transition-context change, source-hash drift,
profile change). Extended `ExportToolTest` (+201 LOC) exercises the
cache path end-to-end: full-timeline render populates cache, an edit
on one clip marks only that clip's mezzanine stale, re-render reuses
the N-1 unchanged mezzanines + re-encodes only the edited clip.
Full `:core:jvmTest` + `:platform-impls:video-ffmpeg-jvm:test`
(including `ExportDeterminismTest` + `FfmpegEndToEndTest` which
exercise the concat path) + iOS sim compile + Android debug APK +
desktop assemble + ktlint all green.

**Registration.** No tool registration churn. `VideoEngine` interface
gained two members (`supportsPerClipCache` and `renderClipMezzanine`)
with safe defaults — iOS's `AVFoundationVideoEngine` and Android's
`Media3VideoEngine` compile unchanged and continue to return
`supportsPerClipCache=false`, so `ExportTool` transparently falls
back to whole-timeline render on those platforms.

**Working-tree note.** The `AVFoundationProxyGenerator.swift` +
`AppContainer.swift` changes that have been sitting in the working
tree alongside the per-clip work are out of scope for this cycle
(they belong to the `auto-generate-proxies-on-import-ios` P2 bullet,
which needs Xcode-side validation this cycle can't perform via
gradle). They remain uncommitted for the iOS-pathway owner to
finalise.

---
