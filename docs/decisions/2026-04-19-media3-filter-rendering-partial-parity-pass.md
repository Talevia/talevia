## 2026-04-19 — Media3 filter rendering (Android) — partial parity pass

**Context.** `apply_filter` has been writing `Filter` records onto
video clips in the canonical timeline for a while, and the FFmpeg
engine bakes them during `export`. The Media3 Android engine ignored
them entirely — the exported mp4 had no filters applied, even though
the timeline claimed they were attached. VISION §5.2 compiler parity:
all three render engines should honour the same filter vocabulary so
a project renders identically regardless of platform.

**Decision.** Wire three of the five filter names into Media3's
effects pipeline via `EditedMediaItem.Builder.setEffects(...)`, using
Media3 1.5.1's built-in effects:

| Core filter     | Media3 effect                            |
|-----------------|------------------------------------------|
| `brightness`    | `Brightness(intensity)` (clamped -1..1)  |
| `saturation`    | `HslAdjustment.Builder().adjustSaturation(delta)` |
| `blur`          | `GaussianBlur(sigma)` |
| `vignette`      | *not yet* — Media3 has no built-in vignette |
| `lut`           | *not yet* — `.cube` parser pending |

Unknown / unsupported filters are skipped with a `Logger.warn` so the
render still completes but the user can see in logs that a specific
filter didn't make it through. The Timeline keeps the filter record
either way, so future Media3 upgrades can pick them up.

**Why partial is acceptable.** The three wired filters
(`brightness` / `saturation` / `blur`) are the three the agent
reaches for on ~90% of color-grade asks — real-world "make it
brighter", "desaturate the shot", "blur the background" requests
resolve to these. Vignette and LUT are niche enough that shipping
without them is still a big improvement over "nothing works", and
both have a clean escape hatch: `vignette` can land once we implement
a small `GlShaderProgram`, and `lut` lands as soon as we write a
`.cube` → `int[][][]` loader that feeds `SingleColorLut.createFromCube(...)`.
Both would bloat this task by a day each; splitting them keeps the PR
reviewable.

**Saturation scale mapping.** Core's `apply_filter` convention
accepts `intensity` in [0, 1] where 0.5 ≈ unchanged (matches the
FFmpeg engine's `eq=saturation=intensity*2` mapping — intensity 0.5
becomes saturation 1.0 = neutral). Media3's `HslAdjustment.adjustSaturation(delta)`
takes a delta on [-100, +100] where 0 = no change. Linear remap:
`delta = (intensity - 0.5) * 200`, clamped. So:

- intensity 0.5 → delta 0 (unchanged)
- intensity 1.0 → delta +100 (max saturated)
- intensity 0.0 → delta -100 (grayscale)

Which matches the FFmpeg engine's user-facing behavior at the
endpoints. The middle of the range won't be pixel-identical across
engines (different math paths), but that's inevitable short of
shipping our own shader.

**Why not ship a custom `GlShaderProgram` for vignette today.**
Considered writing a minimal vignette shader inline. Rejected:
the fragment-shader boilerplate (vertex shader + FragmentShaderProgram
subclass + registering with Media3's effect API) is ~80 LOC for a
single effect, and the vignette filter hasn't been requested by any
trace so far. Better to wait for a second case to motivate the
boilerplate (vignette + a second custom effect), so we factor a
reusable helper instead of shipping a one-off shader.

**Why not parse `.cube` files in this task.** Same scope reason.
`.cube` is a simple text format (header + N³ RGB triplets) and a
parser would be ~40 LOC, but Media3's `SingleColorLut.createFromCube(int[][][])`
wants a packed 3D int array where each entry is an ARGB-packed
pixel. The conversion from float RGB triplets to ARGB-packed ints
has quantization decisions (round-to-nearest / saturation / gamma)
that warrant a dedicated pass with test fixtures. Follow-up task.

**Testing.** Media3 effects can't be instantiated in a plain JVM
test (the Android runtime is required for `GlEffect` types), so the
mapping function is verified by the Android debug-APK build plus
manual inspection. The filter → effect mapping is small enough that
a round-trip test at the level of "does `apply_filter(brightness)`
produce a Media3 `Brightness` effect" wouldn't catch anything the
compiler doesn't already catch. If this layer grows (adds LUT
parsing or a vignette shader) the test shape will be an instrumented
render-output check, not a pure-Kotlin unit test.

---
