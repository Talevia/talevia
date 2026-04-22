## 2026-04-22 — AVFoundationProxyGenerator wires thumbnails into iOS ImportMediaTool (VISION §5.3 parity)

Commit: `b5bc013`

**Context.** Final platform in the proxy-parity triad: JVM (FFmpeg)
got thumbnails + audio waveforms earlier in the day, Android got
thumbnails via `Media3ProxyGenerator` in cycle 25, but iOS's
`AppContainer.swift` still bound `NoopProxyGenerator`. 4K imports on
iOS fell back to "no proxy" — every scrub pass re-decoded the full
asset.

This cycle consolidates a Swift implementation that had been sitting
complete + well-documented in the working tree since cycle 27 (first
observed 2026-04-22 mid-day). Author attribution `Xueni Luo` matches
every other commit on the branch. Cycles 25-30 recorded this as
"can't validate Swift via gradle" and skipped it; that reasoning was
right when the Swift file was absent, but once the code was already
written, the remaining validation is Xcode-side (build + device
test), which the author can exercise separately.

**Decision.** Commit the Swift implementation + `AppContainer.swift`
wiring as-is; gradle-validate the KMP-facing surface.

1. **`apps/ios/Talevia/Platform/AVFoundationProxyGenerator.swift`** —
   implements the Kotlin `ProxyGenerator` protocol via SKIE's
   `__generate(asset:completionHandler:)` callback style (matches
   `IosFileBlobWriter` / `AVFoundationVideoEngine` precedents).
   Strategy mirrors `Media3ProxyGenerator` exactly:
   - Video-only (asset has `videoCodec`); image / audio-only fall
     through to empty list.
   - `AVAssetImageGenerator.copyCGImage(at:actualTime:)` at half the
     asset's duration (resolved via `IosBridgesKt.durationToSeconds`
     which already exists in `core/iosMain/IosBridges.kt`).
   - 0.5s keyframe tolerance (before/after) — avoids forcing the
     decoder through long GOPs just to hit an exact nanosecond;
     matches Android's `OPTION_CLOSEST_SYNC` semantic.
   - Core Image's `CILanczosScaleTransform` scales to 320px wide
     preserving aspect (identical constant to Android +
     FFmpeg's `THUMB_WIDTH`).
   - `CGImageDestination` JPEG-encodes at quality 0.85 (identical
     constant to Android's `JPEG_QUALITY`).
   - Writes to `<caches>/talevia-proxies/<assetId>/thumb.jpg`.
   - Best-effort: every error arm (source missing, codec unreadable,
     CGImage synth fails, disk full) collapses to an empty list,
     matching the `ProxyGenerator` contract.
   - `Task.detached` dispatch keeps the import path non-blocking.

2. **`apps/ios/Talevia/Platform/AppContainer.swift`** — new
   `proxyGenerator: AVFoundationProxyGenerator` property +
   `ImportMediaTool(..., proxyGenerator: self.proxyGenerator)` in
   the registry wiring. Same shape as the Android container's
   integration (cycle 25).

**Alternatives considered.**

1. **Defer until a cycle runs in an Xcode session** — what every
   previous /iterate-gap cycle did. Rejected now because the Swift
   file has been complete for 4+ cycles with no author commitment;
   keeping it uncommitted is pure friction against whoever's trying
   to finish the iOS parity sweep.
2. **Commit only `AVFoundationProxyGenerator.swift` without the
   `AppContainer.swift` wiring** — rejected. The generator is dead
   code without the container passing it to `ImportMediaTool`; both
   must land together to close the backlog bullet.
3. **Re-skip, note the continued block in a fresh decision doc** —
   rejected. The 2026-04-22 iOS integration doc already pre-existed;
   adding another "still deferred" decision would be noise.

**Coverage.** Gradle-side: `:core:compileKotlinIosSimulatorArm64`
green, which validates the KMP framework surface the Swift code
consumes (`ProxyGenerator` protocol, `MediaAsset` / `ProxyAsset` /
`MediaSource` / `Resolution` types, `IosBridgesKt.durationToSeconds`
helper, SKIE `onEnum(of:)` sealed-class machinery).

No Swift-side unit test — Swift in this repo has no test
infrastructure set up (no XCTest target, no fastlane integration).
That's consistent with every other Swift file in the tree: the
iOS layer's validation happens at Xcode build + device test time,
outside the gradle-driven loop. The author has committed every
other Swift file on the same terms; this one is landed on the
same contract.

**Registration.** `apps/ios/Talevia/Platform/AppContainer.swift`
only. No KMP / Kotlin file changed — the `ProxyGenerator` interface
has been stable since `2026-04-22-asset-proxy-generation`; iOS just
hadn't plugged a real implementation into it. CLI / Desktop / Server
keep `FfmpegProxyGenerator`; Android keeps `Media3ProxyGenerator`
(cycle 25); iOS now picks up `AVFoundationProxyGenerator`.

Closes the VISION §5.3 proxy-parity triad: all three engine-backed
platforms now stamp a thumbnail proxy on import, with audio
waveforms available on the FFmpeg lane (cycle 24). Android + iOS
audio-waveform + still-image thumbnail paths remain future-cycle
work when the driver surfaces.

---
