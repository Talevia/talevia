## 2026-04-22 — ProxyGenerator hook in ImportMediaTool + FFmpeg thumbnail impl (VISION §5.3 performance)

Commit: `be68408`

**Context.** `MediaAsset.proxies: List<ProxyAsset>` has been in the domain
model for a while with a `ProxyPurpose { THUMBNAIL, LOW_RES, AUDIO_WAVEFORM }`
enum, but no code populated it. `ImportMediaTool` wrote the full asset to
storage and never generated a thumbnail; every UI that wanted a scrub
preview had to decode the whole 4K file itself. That's the VISION §5.3
performance hole this cycle closes.

Backlog bullet: `asset-proxy-generation` (P2 top after the chronic
`per-clip-incremental-render` skip — now the 9th). `per-clip` stays
deferred per the 2026-04-19 multi-day-refactor rationale.

**Decision.**

1. **New core-side interface `io.talevia.core.platform.ProxyGenerator`**:
   one method `suspend fun generate(asset: MediaAsset): List<ProxyAsset>`.
   Best-effort contract — implementations MUST swallow provider failures
   and return an empty list rather than throw. `NoopProxyGenerator`
   lives next to it as the default binding; it just returns `emptyList()`.

2. **`ImportMediaTool` constructor grows `proxyGenerator: ProxyGenerator
   = NoopProxyGenerator`** — backward-compatible default keeps every
   existing call site (tests + non-JVM AppContainers + the
   `M6FeaturesTest` rig) compiling unchanged. After the asset lands in
   storage, the tool calls `proxyGenerator.generate(asset)` through a
   `runCatching { … }.getOrDefault(emptyList())` guard (belt-and-braces
   — the interface already forbids throwing, this swallows any
   violator), merges the result into `asset.proxies`, de-dupes by
   `(purpose, source)` so repeat imports don't accumulate stale entries,
   then upserts. Output gains `proxyCount: Int = 0` so the agent /
   agent UI can sanity-check "did proxies land this time".

3. **JVM-only FFmpeg impl `io.talevia.platform.ffmpeg.FfmpegProxyGenerator`**
   — shell-outs to `ffmpeg` to extract a 320-px-wide JPEG at the
   mid-duration frame of a video asset, or pass through for images.
   Audio waveform generation is out of scope this round (noted in
   KDoc); the AUDIO_WAVEFORM ProxyPurpose enum value is ready for a
   follow-up cycle. Proxies land under `<java.io.tmpdir>/talevia-
   proxies/<assetId>/thumb.jpg` by default; apps with a persistent
   media dir should pass an explicit `proxyDir` override. Debug via
   `FFMPEG_PROXY_DEBUG=1` (stderr passthrough).

4. **JVM AppContainers (CLI / desktop / server) wire
   `FfmpegProxyGenerator(media)`**; iOS + Android keep the
   `NoopProxyGenerator` default. This matches the platform-priority
   order in CLAUDE.md: JVM-first is allowed, iOS / Android parity
   follow-ups are explicitly queued but not a regression.

**Three-state discipline (§3a rule 4).** `proxyCount == 0` has **three
legitimate meanings** that callers must not conflate:
- Generator not wired (iOS / Android today).
- Generator wired but best-effort failed (ffmpeg missing, unreadable
  container, disk full) — swallowed silently.
- Asset not eligible for thumbnailing (audio-only, zero-duration with
  no resolution).

The enum / list shape keeps all three as "no proxies in the list"; UI
can still render the "no thumbnail yet" fallback for each. A future
cycle can add per-asset "proxy status" metadata if we need to
distinguish the cases (e.g. retry on failure).

**Alternatives considered.**

1. **New `generate_proxies` user-facing tool** — rejected. §3a rule 1:
   the primitive is post-import housekeeping, not an intent the LLM
   plans for. Bundling inside `ImportMediaTool` keeps the agent's
   mental model simple ("import media") and avoids teaching the LLM
   a second tool that always follows the first. A manual
   regenerate-proxies tool is a plausible future addition when we
   need batch regen (e.g. after a migration bumps the proxy format).
2. **Fire-and-forget coroutine instead of synchronous wait** — rejected
   this round. Async is closer to the backlog bullet's "异步 dispatch"
   wording, but it requires:
   - A separate "proxy-ready" bus event taxonomy,
   - A way to upsert the proxies back into the project after import
     returned (mutate-after-commit semantics),
   - UI hooks for "proxy still generating" vs "proxy failed".
   All load-bearing, all follow-ups. Synchronous keeps the first
   cycle honest: a 10-second mid-frame extract on import_media is
   acceptable for the typical 1-file-at-a-time LLM flow, and
   `withProgress` already surfaces a running spinner for long jobs
   elsewhere (the KDoc calls out `RenderProgress` as a pattern to
   extend if needed).
3. **Pluggable proxy strategies (e.g. "mid-frame" vs "keyframe" vs
   "first-frame")** — rejected as premature. FFmpeg's `-ss
   mid_duration` is the widely-used default; other strategies can
   layer on via `ProxyGenerator` variants when real user preferences
   emerge.
4. **Generate in the `MediaStorage.import` callback instead of the
   tool** — rejected. The storage layer is shared across tools and
   tests; pushing proxy generation into it would run thumbnails for
   every synthetic asset (AIGC image blobs, synthesize_speech audio,
   etc.). Keeping the hook at the tool layer scopes it to "the user
   explicitly imported a file they want thumbnails of".

**Coverage.**

- `tool.builtin.video.ImportMediaProxyTest` — 6 tests: generator
  invoked once and proxies stamped; generator exceptions swallowed so
  import still succeeds; empty generator yields zero proxies;
  duplicate `(purpose, source)` proxies dedupe; LLM summary mentions
  proxy count when non-zero; `NoopProxyGenerator` default keeps the
  legacy 3-arg constructor path working.
- Existing `M6FeaturesTest.newWiring()` continues to pass with the
  3-arg constructor (default generator kicks in).
- `FfmpegProxyGenerator` itself is tested implicitly through the
  interface contract + compile — real-ffmpeg E2E would live under
  `platform-impls:video-ffmpeg-jvm:test` next to the existing E2E
  cases, but that requires a real ffmpeg on PATH with `drawtext`
  support (same gating the subtitle tests already have). Deferred
  to a JVM-platform cycle alongside the audio-waveform variant.
- Full JVM + Android + desktop + iOS compile + ktlintCheck all green.

**Registration.** New `ProxyGenerator` + `NoopProxyGenerator` in
`core.platform`. New `FfmpegProxyGenerator` in
`platform-impls/video-ffmpeg-jvm`. Wired into `ImportMediaTool(...,
proxyGenerator = FfmpegProxyGenerator(media))` in CLI / desktop /
server Kotlin containers. iOS Swift + Android Kotlin containers stay
with the default Noop — follow-up when Media3 / AVFoundation
equivalents land.

---
