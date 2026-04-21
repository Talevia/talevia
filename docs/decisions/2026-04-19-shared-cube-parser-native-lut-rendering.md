## 2026-04-19 — Shared `.cube` parser + native LUT rendering

**Context.** After the Media3 (Android) and AVFoundation (iOS) filter
parity passes, `lut` was the last Core filter both native engines
still skipped. Both were waiting on the same thing: a `.cube` file
parser (Adobe LUT v1.0). FFmpeg already renders LUTs via `lut3d=file=…`
because it reads the file itself; Media3's `SingleColorLut` wants a
pre-parsed `int[R][G][B]` cube, and iOS's `CIColorCube` wants
pre-packed `kCIInputCubeData` bytes. Writing the parser per-engine
would fork the format interpretation — not the right trade.

**Decision.** Add a single parser in `core.platform.lut.CubeLutParser`
(commonMain) that both native engines consume:

| Engine       | Conversion                                               |
|--------------|----------------------------------------------------------|
| Media3       | `Lut3d.toMedia3Cube()` → `int[R][G][B]` of packed ARGB   |
| AVFoundation | `Lut3d.toCoreImageRgbaFloats()` → float32 RGBA buffer    |
| FFmpeg       | unchanged — still passes the file path to `lut3d`        |

The parser supports `LUT_3D_SIZE`, default `DOMAIN_MIN/MAX`, and
comments. Non-default domains and 1D LUTs are rejected rather than
silently rendering against the wrong input range.

**Indexing sanity.** `.cube` files store entries in R-fastest order:
`(r=0,g=0,b=0), (r=1,g=0,b=0), …`. `Lut3d` preserves that order.
Media3's `SingleColorLut.createFromCube` expects `cube[R][G][B]` (per
the Media3 javadoc). iOS's `CIColorCube.inputCubeData` expects the
flat R-fastest order natively. The two conversions are unit-tested
against a known red/green/blue 2×2×2 cube to catch axis mix-ups.

**iOS bridging.** Naively, we'd hand Swift the parsed `FloatArray` and
let it pack into `Data`. That's 131k ObjC calls per 32³ LUT (one per
float). Instead, `parseCubeLutForCoreImage(text: String)` (in
`IosBridges.kt`) returns an `NSData` the Swift side casts to
`Foundation.Data` with zero per-element calls. Requires
`BetaInteropApi` + `ExperimentalForeignApi` opt-ins for
`NSData.create(bytes:length:)` — standard Kotlin/Native interop
boilerplate, not a sign of unsafe territory.

**Alternatives considered.**
- *Per-engine parsers in Swift / Kotlin (Android).* Forks the format
  interpretation. If the spec is wrong in one place, the engines
  disagree. Rejected.
- *Parse to a shared `Lut3d` but let each engine write its own
  conversion.* Kept the conversions in commonMain so the R-fastest
  vs. `[R][G][B]` mapping is tested once and can't drift.
- *Support non-default DOMAINs and 1D LUTs in v1.* Adds code paths
  nobody currently needs. The parser error message names the
  unsupported directive so when a real asset trips it, the fix is
  obvious.
- *Rely on FFmpeg-style `lut3d=file=`.* Neither Media3 nor Core Image
  accept a file path — both want pre-loaded cube data. So parsing has
  to happen in the engine layer either way.

**What still doesn't render.** Media3 `vignette` (no built-in; needs
a custom `GlShaderProgram`) and transitions on either native engine
— tracked in CLAUDE.md's "Known incomplete" section.

---
