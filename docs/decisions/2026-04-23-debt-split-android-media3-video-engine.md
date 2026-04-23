## 2026-04-23 — Split apps/android/Media3VideoEngine.kt (614 → 234 lines) by Effect category (VISION §5.6)

**Context.** `apps/android/src/main/kotlin/io/talevia/android/Media3VideoEngine.kt`
was 614 lines (R.5 #4 long-file — 500–800 → default P1). The file had
fused five Media3 Effect domains into one class:

1. `VideoEngine` interface wiring — probe / render / thumbnail (~150 lines).
2. Filter dispatch — `mapFilterToEffect(filter, …)` with per-filter
   translations (`brightness` → `Brightness`, `saturation` →
   `HslAdjustment`, `blur` → `GaussianBlur`, `vignette` →
   `OverlayEffect(VignetteOverlay)`, `lut` → `SingleColorLut`) (~70 lines).
3. Transition fade overlays — `transitionFadesFor(timeline)` + per-clip
   envelope helper + `FadeBlackOverlay` + `ClipFades` data class (~65 lines).
4. Subtitle overlays — `subtitleClips(timeline)` + `subtitleOverlaysFor(clip)`
   + `SubtitleTextOverlay` + `buildSpannable` + `parseColor` + `BOTTOM_CENTER_*`
   OverlaySettings (~80 lines).
5. Three inner overlay classes each with their own `Bitmap` lifecycle
   (~165 lines).

Per the bullet: every three-platform parity cycle that adds a new
transition / filter / subtitle behaviour paid this concentration tax —
even touching one dispatch branch forced scrolling past the others.

Rubric delta §5.6: long-file 614 → 234 for Media3VideoEngine.kt; the
three Effect-category siblings stay under 180 lines each.

**Decision.** Split into 4 sibling files in the same `io.talevia.android`
package (the bullet's `Media3LutEffect.kt` fourth file was speculative —
LUT today is a one-line call inside `mapFilterToEffect` and doesn't
warrant its own file; keep folded until LUT grows beyond that):

| File | Lines | Contents |
|---|---|---|
| `Media3VideoEngine.kt` | 234 | `class Media3VideoEngine : VideoEngine` — constructor + `probe` + `render` (composition + transformer + progress poll) + `thumbnail` + private `sourceToPath` / `videoClips`. |
| `Media3FilterEffects.kt` | 175 | `internal fun mapFilterToEffect(...)` + `internal class VignetteOverlay` + file-private `log` scoped to `Media3VideoEngine.filter`. |
| `Media3TransitionEffects.kt` | 135 | `internal data class ClipFades` + `internal fun transitionFadesFor` + `internal fun fadeOverlaysFor` + `internal class FadeBlackOverlay`. |
| `Media3SubtitleEffects.kt` | 122 | `internal fun subtitleClips` + `internal fun subtitleOverlaysFor` + private `SubtitleTextOverlay` + private `buildSpannable` + private `parseColor` + `BOTTOM_CENTER_*` OverlaySettings + file-private `log` scoped to `Media3VideoEngine.subtitle`. |

Each sibling owns a scoped `Loggers.get("Media3VideoEngine.<area>")` so
the engine's existing `"Media3VideoEngine"` logger stays intact (old log
sinks still match the prefix) and per-area noise is grep-able.

Visibility flipped: every symbol crossing a file boundary became
`internal`. `SubtitleTextOverlay` / `buildSpannable` / `parseColor` stay
`private` to `Media3SubtitleEffects.kt` (only `subtitleOverlaysFor`
needs them; no cross-file caller).

**Axis.** "New Media3 Effect kind on a clip." A new
transition style, filter, or subtitle behaviour lands in the
corresponding sibling file and doesn't bloat `Media3VideoEngine.kt`.
When LUT eventually grows beyond its one-line `SingleColorLut.createFromCube`
call (e.g. LUT presets, animated LUT lerping between cubes), its own
`Media3LutEffect.kt` sibling absorbs the growth.

**Alternatives considered.**

- **Keep everything inline and only split off the three overlay
  classes.** Would drop the file to ~460 lines — still watch-range but
  below the P1 signal. Rejected because the `mapFilterToEffect` /
  `transitionFadesFor` / `subtitleOverlaysFor` methods are the next
  concentration points when any parity cycle adds a new filter /
  transition / subtitle knob; pulling only the overlays leaves the
  dispatch entanglement in place.

- **Pass `log` as a function parameter.** Would centralise the logger
  in the engine and avoid three siblings each doing
  `Loggers.get("Media3VideoEngine.<area>")`. Rejected: passing a
  logger through every signature just to preserve a single-name
  convention is ceremony; the scoped loggers give better observability
  (a noisy filter warn is now greppable as
  `"Media3VideoEngine.filter"` vs buried in the engine-wide stream).

- **Promote the three inner overlay classes to top-level types in
  `core.platform.android`.** Would let other engines / tests reuse
  them. Rejected: the overlays (`FadeBlackOverlay`, `SubtitleTextOverlay`,
  `VignetteOverlay`) are Media3-specific — they subclass
  `androidx.media3.effect.*` overlays. No other consumer can meaningfully
  reuse them; the sibling-file placement is correct.

- **Split `buildSpannable` + `parseColor` into a separate `Media3TextStyle.kt`.**
  They're only called from `subtitleOverlaysFor`, so pulling them out
  would just move 30 lines of helper without reducing entanglement.

**Coverage.** `:apps:android:assembleDebug` green (the debug APK
packaging fully compiles the Kotlin sources + resources; the Android
app module doesn't have unit tests wired today so the compile is the
only regression guard, but it does verify every overlay subclass's
abstract overrides + `Effect` dispatch types). `ktlintCheck` green.
`:core:jvmTest` + `:apps:server:test` + `:apps:desktop:test`
unaffected.

**Registration.** No tool / AppContainer change. Package unchanged;
Kotlin source set globs pick up the new siblings automatically.
`AndroidAppContainer` constructs `Media3VideoEngine(context,
pathResolver)` with the same two-argument constructor as before.
