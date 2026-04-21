## 2026-04-19 — AVFoundation filter rendering (iOS) — CIFilter parity pass

**Context.** After the Media3 partial parity pass, the iOS
`AVFoundationVideoEngine` was the last remaining "filters on the
timeline, no filters in the output" engine. VISION §5.2: native
platforms should render the same filter vocabulary as FFmpeg for
parity across the three engines. iOS's advantage over Media3 is
`CIVignette` — a built-in Core Image primitive — so iOS can match
FFmpeg on *four* filters where Media3 only hits three.

**Decision.** Build an `AVMutableVideoComposition` via
`AVMutableVideoComposition.videoComposition(with:applyingCIFiltersWithHandler:)`
(iOS 16+; deployment target is 17.0) whenever any clip in the
timeline carries filters. The per-frame handler looks up the owning
clip by matching `request.compositionTime` against each clip's
`[timelineStart, timelineStart + timelineDuration)` range, then
applies its filter chain as a sequence of `CIFilter`s on the request's
`sourceImage`. Mapping:

| Core filter  | CIFilter                                           |
|--------------|----------------------------------------------------|
| `brightness` | `CIColorControls` `inputBrightness` (clamped -1..1)|
| `saturation` | `CIColorControls` `inputSaturation` (intensity * 2, clamped 0..2) |
| `blur`       | `CIGaussianBlur` `inputRadius` (sigma verbatim or radius*10) |
| `vignette`   | `CIVignette` `inputIntensity` + `inputRadius`      |
| `lut`        | no-op (same `.cube` parser gap as Media3)          |

**Saturation remap.** Same rationale as the Media3 pass: Core's
`apply_filter` uses `0..1` intensity with `0.5 ≈ unchanged` to match
FFmpeg's eq filter. CI's `inputSaturation` is multiplicative centred
at `1.0`. Linear remap `intensity * 2` → 0.5 maps to 1.0, 1.0 to 2.0,
0.0 to 0.0.

**Bridging plumbing.** `IosVideoClipPlan` (the flat DTO
`toIosVideoPlan()` builds for Swift) gained a `filters:
List<IosFilterSpec>` field, where `IosFilterSpec(name, params:
Map<String, Double>)` exposes `Filter.params` as `Double` instead of
the domain's `Float` so Swift can feed them straight into `CIFilter`
without the `KotlinFloat.floatValue` dance. On the Swift side, each
plan's filters are copied into a pure-Swift `ClipFilterRange` struct
before being captured by the `@Sendable` filter handler — SKIE-bridged
Kotlin types aren't `Sendable`, so the copy is what lets the handler
cross into the concurrent Core Image work queue.

**Alternatives considered.**
- *AVAssetWriter with a custom per-frame compositor.* Would let us
  honour `OutputSpec.videoCodec` / `bitrate` too, but it's a larger
  rewrite than this task scopes. `AVAssetExportSession` still uses
  the preset approach for encoding; the videoComposition overlay is
  strictly about per-frame pixel processing.
- *Per-clip AVMutableVideoCompositionInstruction with a custom
  compositor class.* More flexibility (we could swap pipelines per
  clip) but `applyingCIFiltersWithHandler` is the built-in, well-
  trodden path and our filters are all CIFilter-friendly. Revisit
  only if we need non-Core-Image effects (shader-based transitions,
  custom blends, etc.).
- *Punt vignette to match Media3's scope.* Would keep the three
  engines on exactly the same feature set. Rejected because iOS has
  `CIVignette` built-in — declining to use it for symmetry's sake is
  the wrong trade. The parity goal is "FFmpeg filters render on
  native engines where possible", not "every engine renders exactly
  the same subset".

**What still doesn't render.** `lut` (awaiting a `.cube` → raw cube
data loader that Media3 will share), transitions on either native
engine, and subtitle rendering on Media3/AVFoundation — tracked in
CLAUDE.md's "Known incomplete" section.

---
