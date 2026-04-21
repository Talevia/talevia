## 2026-04-19 — AVFoundation subtitle rendering (iOS) — `CATextLayer` via animationTool

**Context.** The Media3 pass closed the Android caption gap; the same
feature was still no-op on iOS. `AVFoundationVideoEngine` wrote video
and audio tracks but never touched `Track.Subtitle` clips, so exports
on iOS silently dropped captions. AVFoundation's documented path for
burning text overlays into a composition is
`AVVideoCompositionCoreAnimationTool` with a Core Animation layer
hierarchy — a built-in primitive, no custom CIFilter needed.

**Decision.**
- Add `IosSubtitlePlan` + `Timeline.toIosSubtitlePlan()` in
  `IosBridges.kt` (mirrors `IosVideoClipPlan` / `toIosVideoPlan`) so
  Swift consumes a flat, Sendable DTO instead of crossing the SKIE
  sealed-class boundary for `Clip.Text` / `TextStyle`.
- In `AVFoundationVideoEngine.runExport`, after the filter pass:
  1. If there are subtitles but no filter pass, build an
     `AVMutableVideoComposition` via `videoComposition(withPropertiesOf:)`
     (so the animation tool has somewhere to attach).
  2. Build `(parent, video)` layers — `parent.isGeometryFlipped = true`
     so `y = margin` counts from the bottom (matches FFmpeg's
     `y = h - text_h - margin`).
  3. Per subtitle, create a `CATextLayer` at bottom-center, `opacity = 0`,
     plus a `CABasicAnimation` on `opacity` (from=1, to=1, `beginTime`
     = `startSeconds` or `AVCoreAnimationBeginTimeAtZero` when start is
     0, `duration` = `end - start`). Model opacity stays 0 so the text
     is invisible outside the animation window; inside the window the
     animation's presentation value of 1 reveals it.
  4. Attach via `vc.animationTool = AVVideoCompositionCoreAnimationTool(
     postProcessingAsVideoLayer: video, in: parent)`.

**Style mapping (`TextStyle` → UIKit).**
| `TextStyle` field | Swift                                                    |
|-------------------|----------------------------------------------------------|
| `color`           | `NSAttributedString.Key.foregroundColor` (UIColor.cgColor) |
| `backgroundColor` | `NSAttributedString.Key.backgroundColor` (optional)      |
| `fontSize`        | `UIFont.systemFont(ofSize:)` / `UIFont(name:size:)`      |
| `bold`            | `.traitBold` (or `systemFont(.bold)`)                    |
| `italic`          | `.traitItalic`                                           |
| `fontFamily`      | `UIFont(name:size:)`; skip default "system"              |

Hex colours are parsed with a custom scanner (`#RRGGBB` or
`#RRGGBBAA`), falling back to white on malformed input rather than
crashing the export.

**Geometry flip.** `AVVideoCompositionCoreAnimationTool` on iOS
composites in a flipped-Y coordinate space vs. the default UIView
top-left origin. Setting `parent.isGeometryFlipped = true` makes
sublayer positions count from the bottom, which is what the FFmpeg
engine already does. Text inside each `CATextLayer` is unaffected
(flip propagates to sublayer *position*, not the layer's own
contents) so captions read left-to-right normally.

**Alternatives considered.**
- **Rasterise text into a CIImage inside the CI filter handler** —
  plausible when filters are already in play, but doubles the
  rendering cost (GPU CI pipeline + CPU text rasterisation per
  frame) and forks the code path based on whether filters exist.
- **`AVAssetWriter` + manual per-frame compositing** — more control
  but a much larger rewrite; not worth it for captions alone.
- **Custom `AVVideoCompositing` protocol impl** — necessary if we
  wanted to avoid CI filter handler entirely, but filters + overlays
  coexist via animationTool today so this is deferred.

**What still doesn't render.** Transitions remain a gap on both
native engines — `add_transition` writes to the timeline but the
exported mp4 still has hard cuts. Wiring Media3 opacity-ramp custom
effects / AVFoundation `setOpacityRamp` is the follow-up.

---
