## 2026-04-19 — AVFoundation transition rendering (iOS) — CI color-matrix dim in the filter handler

**Context.** The FFmpeg engine now renders `add_transition` as a dip-to-black
fade at the boundary between two adjacent clips. For iOS parity we need the
same visual on AVFoundation — without changing the transition data model
(which keeps clips strictly sequential and puts the transition on a separate
Effect track).

**Decision.**
- Extend `IosVideoClipPlan` with `headFadeSeconds` / `tailFadeSeconds` so the
  Swift engine receives the pre-computed fade envelope per clip. Kotlin-side
  `Timeline.toIosVideoPlan()` scans `Track.Effect` for `assetId.value`
  starting with `"transition:"` and assigns each adjacent video clip half
  the transition's duration (mirroring the FFmpeg logic).
- The Swift `ClipFilterRange` gains `headFade` / `tailFade` alongside its
  filter specs, and the activation predicate changes from `plan.filters not
  empty` to `plan.filters not empty || hasFades`. Clips with *only* a fade
  now flow through the `applyingCIFiltersWithHandler` path.
- Inside the handler: after applying the clip's filter chain, compute
  `alpha = transitionAlphaAt(t, clip)` — a piecewise-linear ramp that is
  `0..1` over the head window and `1..0` over the tail window, and `1.0`
  everywhere else. When `alpha < 1`, pass the frame through a `CIColorMatrix`
  with `R/G/B` vectors scaled by `alpha` (zero bias) — this multiplies RGB
  toward black while preserving the alpha channel.

**Alternatives considered.**
- **`setOpacityRamp(fromStartOpacity:toEndOpacity:timeRange:)` on an
  `AVMutableVideoCompositionLayerInstruction`.** Rejected: the timeline
  already uses the `applyingCIFiltersWithHandler` path when *any* filter
  exists, and those two paths aren't composable (`applyingCIFiltersWithHandler`
  builds its own per-track instructions). Forcing fades through
  layer-instruction opacity would require maintaining two parallel setup
  branches depending on whether filters are present — more code, more
  drift risk, and the CIColorMatrix dim renders identically for the mp4.
- **`CIColorControls.inputBrightness = -alpha`.** Rejected: brightness is
  additive (shifts toward -1 = black), so partial fades look *gray-washed*
  rather than dipping cleanly to black. A multiplicative color matrix is the
  physically correct scale-to-black.
- **`CIConstantColorGenerator` + `CISourceOverCompositing` with alpha
  interpolation.** Rejected: equivalent output to the matrix approach but
  allocates an extra image per frame and requires an extra compositing
  filter. One `CIColorMatrix` pass is simpler.

**Scope / what still doesn't render.**
- The transition `name` is ignored — every name (`fade`, `dissolve`, `slide`,
  `wipe`, …) becomes a dip-to-black fade. This is the documented cross-engine
  parity floor; richer transitions (actual crossfade, directional wipes)
  need a timeline-model change (overlap between A/B) and are tracked as a
  VISION §5.2 follow-up.
- Verification: `DEVELOPER_DIR=... ./gradlew
  :core:linkDebugFrameworkIosSimulatorArm64` + `xcodebuild … Talevia` build
  cleanly; the transition path flows through the existing
  `renderWithFilterProducesVideo` shape (no new iOS test scaffold — the
  Kotlin-side `toIosVideoPlan` logic mirrors
  `FfmpegVideoEngine.transitionFadesFor` which has direct unit coverage in
  `TransitionFadesTest`).

---
