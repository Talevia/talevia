## 2026-04-19 — Media3 subtitle rendering (Android) — `TextOverlay` + `OverlayEffect`

**Context.** After the filter parity pass, `Track.Subtitle` was the
last major gap on Android: `add_subtitle` / `add_subtitles` wrote
`Clip.Text` onto the timeline, but `Media3VideoEngine` never touched
them — exports on Android dropped all captions while the FFmpeg
engine baked them via `drawtext`. Media3 1.5.1 ships a built-in
`TextOverlay` (subclass of `BitmapOverlay`) and a matching
`OverlayEffect` that plugs into `Effects.videoEffects`, so we do not
need a custom `GlShaderProgram` for a v1 caption renderer.

**Decision.** Per video clip, find every subtitle whose timeline
range overlaps the clip, then attach one `TextOverlay` per overlap
to that clip's `Effects(emptyList(), videoEffects)` list inside a
single `OverlayEffect(overlays)`. The overlay's **local** window
(in clip-presentation-time microseconds) is `max(sub.start,
clip.start) - clip.start` … `min(sub.end, clip.end) - clip.start`.

**Time gating.** `TextOverlay.getText(presentationTimeUs)` is called
on every frame, and its base class caches the rasterised bitmap
keyed on `SpannableString.equals`. To avoid re-rasterising every
frame, we keep `getText` constant and toggle visibility via
`OverlaySettings.alphaScale`:
- `BOTTOM_CENTER_VISIBLE` — `alphaScale = 1f`, used inside the window.
- `BOTTOM_CENTER_HIDDEN`  — `alphaScale = 0f`, used outside the window.
Result: each spannable rasterises once per clip; the GPU blend skips
the overlay outside the window.

**Style mapping (`TextStyle` → Android `Spanned`).**
| `TextStyle` field | Span                                    |
|-------------------|-----------------------------------------|
| `color`           | `ForegroundColorSpan` (parsed via `Color.parseColor`) |
| `backgroundColor` | `BackgroundColorSpan` (optional)        |
| `fontSize`        | `AbsoluteSizeSpan(px, dip=false)`       |
| `bold`/`italic`   | `StyleSpan(BOLD/ITALIC/BOLD_ITALIC)`    |
| `fontFamily`      | `TypefaceSpan` (skipped when "system")  |

Unparseable colors fall back to the platform default and log a
warning rather than crashing the export.

**Positioning.** `OverlaySettings.Builder().setBackgroundFrameAnchor(0f, -0.8f)`
mirrors the FFmpeg MVP (bottom-center, ~10% up from the frame's
bottom edge). Custom per-`TextStyle` positioning is a later knob —
`TextStyle` has no position fields in v1.

**Alternatives considered.**
- **`createStaticTextOverlay(spannable, settings)`** — simplest, but
  doesn't support a time-gated window; we would have to build a
  separate `EditedMediaItem` per subtitle segment or let the caption
  show for the entire clip. Rejected.
- **Return empty `SpannableString` outside the window** — plausible
  (`getBitmap`'s cache equality check would still short-circuit),
  but creating a 0×0 bitmap on some Android API levels is reported
  to throw. `alphaScale=0` is robust across versions without risking
  that path.
- **Custom `GlShaderProgram`** — overkill for captions; reserve that
  route for `vignette` / transitions where no built-in effect exists.

**What still doesn't render.** iOS `AVFoundationVideoEngine` still
ignores `Track.Subtitle` at render time (follow-up: `CATextLayer`
through `AVVideoComposition.animationTool`). Transitions remain a
gap on both native engines.

---
