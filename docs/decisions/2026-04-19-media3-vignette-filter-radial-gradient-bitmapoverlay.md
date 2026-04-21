## 2026-04-19 — Media3 vignette filter — radial-gradient `BitmapOverlay`

**Context.** Final cross-engine compiler-parity gap (CLAUDE.md "Known
incomplete"). FFmpeg and iOS render `vignette`; Android
`Media3VideoEngine.mapFilterToEffect` was a no-op with a warn log. Task 7
of the current gap list — and the last remaining item from `CLAUDE.md`'s
known-incomplete list.

**Decision.**
- Add `VignetteOverlay : BitmapOverlay` that bakes a full-frame ARGB
  bitmap at `configure(videoSize)` time, painted with a
  `RadialGradient` (transparent → `argb(edge, 0, 0, 0)`). The bitmap is
  reused across frames; one GL texture upload per clip.
- In `mapFilterToEffect`, the `"vignette"` branch returns
  `OverlayEffect(listOf(VignetteOverlay(intensity)))`. Added to the
  videoEffects chain alongside other filters; subtitles/transitions
  already build a *second* `OverlayEffect` further along the chain, so
  vignette stays under any caption.
- Intensity (`0..1`) drives two knobs together: edge alpha and the
  inner stop at which the gradient starts fading from clear. Higher
  intensity = pitch-black corners *and* smaller bright centre —
  matches FFmpeg `vignette`'s perceived strength curve better than
  darkening edges alone would.

**Alternatives considered.**
- **Custom `GlShaderProgram`** (pixel-accurate, any resolution). The
  "proper" path but needs shader code + texture format negotiation +
  lifecycle wiring for a one-liner filter. Worth doing when we need
  effects that a bitmap overlay can't express (e.g. per-frame
  animated noise / distortion). Vignette isn't that.
- **`MatrixTransformation` brightness ramp.** Rejected for the same
  reason we rejected it on iOS transitions: a brightness matrix
  darkens uniformly; vignette needs a spatially-varying darkness.
- **Pre-render a small 256×256 vignette PNG and `setScale` it.** In
  principle lighter, but Media3 1.5.1 `OverlaySettings.setScale`
  semantics tie scale to the overlay's own pixel dimensions, so
  you'd hardcode the stretch ratio per video resolution — brittle.
  Baking at video size is ~8 MB at 1080p, fine for one per clip.

**Known limitations.**
- Gradient stops are linear (hard-coded at inner→1.0). More cinematic
  vignette curves (quartic / sigmoidal falloff) would need a second
  colour stop or a shader. Not perceptible at the default intensities
  we ship, so shipping the simple version.
- The bitmap is allocated per-clip: multiple vignette clips in one
  export each pay the allocation. A shared cache keyed on
  `(videoSize, intensity)` is an easy follow-up if that shows up.

**Follow-ups.**
- If we ever need two different vignette shapes (elliptical, shifted
  center), that's when the GlShaderProgram path pays off — the
  per-clip BitmapOverlay gets quadratic in shape count.
- Closes `CLAUDE.md` "Known incomplete" — the whole cross-engine
  filter / transition / subtitle / LUT parity matrix is now green.

---
