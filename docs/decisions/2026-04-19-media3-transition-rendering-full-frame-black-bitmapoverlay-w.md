## 2026-04-19 — Media3 transition rendering (Android) — full-frame black `BitmapOverlay` with ramped `alphaScale`

**Context.** FFmpeg and AVFoundation now render `add_transition` as a dip-to-
black fade. Android was the last gap — `Media3VideoEngine` ignored
Effect-track transition clips and exported hard cuts, breaking VISION §5.2
compiler parity for the third platform.

**Decision.**
- Add a `transitionFadesFor` helper to `Media3VideoEngine` that mirrors the
  FFmpeg / iOS logic: scan `Track.Effect` for clips whose `assetId.value`
  starts with `"transition:"`, locate the two adjacent video clips by
  boundary equality, and assign each side `halfDur = duration / 2` as a
  head/tail fade.
- Implement `FadeBlackOverlay : BitmapOverlay`. `configure(videoSize)`
  receives the input frame size — allocate one `ARGB_8888` bitmap of that
  size, `eraseColor(BLACK)`, and reuse it across frames so the GL texture
  uploads once per clip. `getOverlaySettings(presentationTimeUs)` returns
  a fresh `OverlaySettings` whose `alphaScale` is the linear ramp between
  `startAlpha` and `endAlpha` over `[startUs, endUs]` in the clip's local
  microsecond timeline (Media3 hands per-clip presentation times into the
  overlay's getters).
- Wire fade overlays **before** subtitle overlays in the per-clip
  `OverlayEffect` list. Media3 composites overlays bottom-up, so subtitles
  sit on top of the dip-to-black — captions stay legible even at peak fade,
  matching the FFmpeg pipeline (drawtext runs after the per-clip `fade`
  filter).

**Alternatives considered.**
- **Custom `GlEffect` / `GlShaderProgram` that scales RGB by alpha.** This
  is the "proper" path but Media3 1.5.1's GL effect API requires writing a
  shader, lifecycle wiring, and texture-format negotiation. A black overlay
  with `alphaScale` produces the identical visual via two existing
  primitives (`BitmapOverlay` + `OverlaySettings`) — no shader code, no GL
  lifecycle. Worth revisiting if we ever need RGB-only dimming (preserving
  the underlying alpha channel in transparent media).
- **`MatrixTransformation` to fade via a brightness matrix.** Rejected:
  same gray-wash problem as `CIColorControls.inputBrightness` on iOS —
  additive shift toward `-1` doesn't produce a clean black at partial
  alpha. The overlay approach is multiplicative-equivalent (overlay alpha
  `α` produces a frame that is `(1-α) * source + α * black`).
- **Tiny bitmap (`16×16`) stretched via `OverlaySettings.scale`.** Rejected:
  `setScale` semantics are tied to the overlay's pixel dimensions in
  Media3 1.5.1, not to NDC fractions — using `(videoW/16, videoH/16)`
  would work in principle but couples to bitmap size in a brittle way.
  Allocating a full-frame ARGB bitmap is ~8 MB at 1080p, fine for one
  short-lived overlay per fading clip.

**Scope / what still doesn't render.**
- The transition `name` is collapsed to fade-to-black just like the other
  two engines. Real crossfades, slides, wipes need the timeline-model
  change tracked in the FFmpeg decision below.
- `vignette` filter is still the lone Android-only gap — `BitmapOverlay`
  doesn't help here (vignette needs a shader); leave it for the same
  follow-up that adds custom `GlShaderProgram`s.

---
