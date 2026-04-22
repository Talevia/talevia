## 2026-04-22 — FfmpegProxyGenerator emits audio-waveform PNGs for audio-only assets (VISION §5.3 rubric)

Commit: `bc0b5e5`

**Context.** `ProxyPurpose.AUDIO_WAVEFORM` existed in the enum but the
FFmpeg-backed proxy generator never produced one — audio-only assets
hit a bare `else -> return emptyList()` branch. The pre-cycle
`2026-04-22-asset-proxy-generation.md` decision called this out as the
expected follow-up. UI consumers (desktop scrub strip, iOS/Android
future audio-track renderer) have had to either decode the original
container to paint a waveform each time or skip the display entirely.

VISION §5.3 performance lane: 4K imports get thumbnails, audio imports
should also get a compact pre-rendered proxy so downstream UI doesn't
re-decode.

**Decision.** Extend `FfmpegProxyGenerator.generate()` to detect
audio-only assets (`videoCodec == null && audioCodec != null && duration > 0`)
and run `ffmpeg -filter_complex "showwavespic=s=WxH:colors=white"
-frames:v 1 waveform.png`, returning a single
`ProxyAsset(purpose=AUDIO_WAVEFORM, resolution=640×80)`.

1. **Fixed 640×80 canvas.** Sized for a compact scrub-strip banner
   (not album-art). 640px ≈ 2 pixels per second on a 5-minute track —
   enough peak resolution to read transient energy; 80px vertical keeps
   the PNG under a few KB even at higher amplitude ranges. These can
   become configurable later via `ProxyGenerator` constructor args,
   but a fixed default keeps this cycle's surface minimal.
2. **`colors=white` on transparent bg.** Readable on dark UI
   backgrounds (the default Talevia dark chrome); consumers can tint
   post-hoc. Ffmpeg's `showwavespic` defaults to a ~mid-grey which
   washes out against both extremes — explicit white is the most
   portable choice.
3. **Best-effort** per the existing `ProxyGenerator` contract —
   ffmpeg missing / unreadable audio / container-with-no-decodable-
   stream each produces null → caller returns an empty list, and the
   import still succeeds. Same swallow semantics as the thumbnail
   path.
4. **Zero-duration audio guard.** The isAudioOnly detector requires
   `duration > 0`; a zero-duration stamped-audio edge case falls
   through to the `else` arm (unchanged from pre-cycle behaviour) so
   the generator doesn't try to render a blank waveform from a
   0-second clip.

Incidentally-fixed side issue: running `:platform-impls:video-ffmpeg-jvm:test`
for this cycle exposed **three pre-existing broken tests** in
`ExportDeterminismTest` + `FfmpegEndToEndTest` — each dispatched
`import_media` without `projectId` in the JsonObject, relying on a
session-binding fallback that no longer exists post
`ctx.resolveProjectId` refactor (commit `be1c752` introduced the
fallback API but didn't update these FFmpeg e2e tests). Last ~9
iterate-gap cycles skipped the ffmpeg test suite in their verify
matrix; the regression had been silent since. Fixed by adding
`put("projectId", projectId.value)` to each `import_media` dispatch
(4 sites total). Counts as a necessary side-fix: the waveform work
can't commit cleanly on a red suite, and the fix is mechanical and
test-only. Called out here so the next cycle's reader can see it
without grepping history.

**Alternatives considered.**

1. **Variable waveform width proportional to duration** — rejected
   this round. `showwavespic=s=WxH` accepts any dimensions, but
   scaling the PNG to match actual audio length would mean a
   one-hour podcast produces a 7200×80 PNG (~500 KB encoded) that
   the UI then has to down-sample. Fixed 640×80 with a density
   clearly documented ("~2 px/sec on 5-min tracks") is the robust
   default. Callers wanting per-asset precision can re-generate with
   explicit args in a later cycle.

2. **`showwaves` (time-domain streaming filter) vs. `showwavespic`
   (single-frame)** — picked `showwavespic`. `showwaves` emits a
   time-continuous video frame stream suitable for live visualisation;
   `showwavespic` dumps a single-frame summary, which is exactly what
   a scrub-strip proxy needs. Matches the thumbnail pattern (one
   static image → one file).

3. **Generate the waveform as SVG instead of PNG** — rejected.
   `ffmpeg -filter_complex showwavespic` is PNG/bitmap only; an SVG
   variant would require parsing amplitude samples client-side and
   building the path ourselves. That's a separate, more invasive
   pipeline (cross-platform amplitude sampler) for marginal visual
   benefit — the scrub strip doesn't need vector scalability at
   its 80px render height.

4. **Two-pass — waveform PNG + embedded amplitude JSON sidecar** —
   deferred. The hypothetical "agent asks for loudness stats" use case
   doesn't exist yet; adding a JSON sidecar now would be speculative
   infrastructure. `ProxyAsset` can grow a second kind in a later
   cycle if the need surfaces.

**Coverage.** New `FfmpegProxyGeneratorWaveformTest` in
`platform-impls/video-ffmpeg-jvm/src/test/kotlin/` — 3 cases,
self-skip when ffmpeg isn't on PATH:

- `audioOnlyAssetProducesWaveformProxy` — happy path: generate a
  2-second sine via `ffmpeg -f lavfi -i sine=…`, run the proxy
  generator, assert one `ProxyAsset` back, `purpose=AUDIO_WAVEFORM`,
  PNG magic bytes present, non-empty file.
- `videoAssetStillProducesThumbnailNotWaveform` — negative
  assertion: the new branch must not intercept video paths. Generates
  a testsrc+anullsrc mp4, confirms one THUMBNAIL proxy.
- `audioMetadataWithoutDurationFallsThroughToEmptyNotWaveform` —
  edge: zero-duration audio falls through to empty (isAudioOnly
  guard).

Existing thumbnail tests (`ImportMediaProxyTest` in core) continue to
pass — the thumbnail branch is only reached when `!isAudioOnly`, and
I didn't touch the video/image path.

**Registration.** No registration change. `FfmpegProxyGenerator` is
already wired into CLI / Desktop / Server via `ImportMediaTool`'s
`proxyGenerator` constructor arg. iOS / Android continue to use
`NoopProxyGenerator` — wiring platform-native generators there is
tracked separately by `auto-generate-proxies-on-import-ios` and
`auto-generate-proxies-on-import-android` (P2).

---
