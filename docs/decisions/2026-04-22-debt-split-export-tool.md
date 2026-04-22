## 2026-04-22 — ExportTool split: render runners to `video/export/` siblings

Commit: `73ddf11`

**Context.** `ExportTool.kt` sat at 543 lines — past the 500-line
"forced debt" threshold from the R.5.3 rubric and the second time
it had crossed the line (cycle 32 added the progressive-export-preview
branch which nudged it over). The backlog bullet `debt-split-export-tool`
called for extracting `runWholeTimelineRender` / `runPerClipRender` to
sibling files and leaving `ExportTool` as dispatch + validation + return
assembly.

**Decision.** Two new sibling files under
`core/src/commonMain/kotlin/io/talevia/core/tool/builtin/video/export/`:

- `WholeTimelineRender.kt` — top-level `internal suspend fun
  runWholeTimelineRender(engine, timeline, output, ctx, clock)`. Consumes
  `engine.render(...)` Flow and forwards each `RenderProgress.*` as a
  `Part.RenderProgress` via `ctx.emitPart`.
- `PerClipRender.kt` — top-level `internal suspend fun runPerClipRender(
  engine, store, project, shape, output, outputPath, ctx, clock):
  PerClipStats`, plus `internal data class PerClipStats`,
  `internal data class PerClipShape`, `internal fun timelineFitsPerClipPath`,
  `internal fun mezzanineDirFor`.

`ExportTool.execute(...)` now calls the two top-level fns directly,
passing `engine`/`store`/`clock` as explicit parameters rather than
closing over them as private method context. `ExportTool.kt` kept:
schema, helpText, execute dispatch, render-cache append,
provenance-manifest computation, fingerprint hash, mime mapping, Input /
Output types. Final size: **335 lines** (was 543; −208).

**Alternatives considered.**

1. **Keep inline**: `ExportTool.kt` already dispatches to helpers, so
   "just leave it" was the minimum-churn path. Rejected: the file had
   now crossed 500 twice, and the render-runner blocks (170+ lines
   combined) have no other call sites in the tool body beyond
   `execute` — they're textbook extraction candidates. §5.2 "工具压
   缩" prefers smaller surfaces when the split is honest.

2. **Move runners to `core.platform.video.export` or similar
   platform-agnostic package**: also viable but overreaches — these
   are `ExportTool` implementation details (they construct
   `Part.RenderProgress` events, use `ctx.emitPart`, depend on the
   `Input.outputPath`/`forceRender`/`allowStale` contract). Keeping
   them co-located under
   `tool/builtin/video/export/` keeps ownership clear.

3. **Merge PerClipRender into VideoEngine contract**: tempting because
   the mezzanine-fingerprint + cache-append logic is "about" rendering,
   but `ClipRenderCache` lives on `Project` and is mutated through
   `ProjectStore`, which sits above the engine layer. Pushing this
   into the engine would force every future engine (Media3, AVFoundation)
   to re-implement the same cache dance. Keeping it in the tool layer
   means the engine only owns `renderClip` / `concatMezzanines` primitives.

**Parameterisation shape.** The two runners take concrete dependencies
(`engine`, `store`, `clock`) instead of an `ExportTool` handle —
Kotlin idiomatic, no Effect.js-style context object. This also makes
the runners directly unit-testable without constructing an
`ExportTool` shell (no tests yet; the full `ExportToolTest` suite
exercises them via `execute()`, which stays green).

**Impact.**

- `ExportTool.kt`: 543 → 335 lines. Below 500.
- `core/tool/builtin/video/export/WholeTimelineRender.kt`: 59 new lines.
- `core/tool/builtin/video/export/PerClipRender.kt`: 187 new lines.
- No tests modified. `:core:jvmTest`, `:platform-impls:video-ffmpeg-jvm:test`,
  `:core:ktlintCheck` all green.
- No API surface change: everything extracted was `private`, now
  `internal` and package-local — still invisible to callers.

**Follow-ups.** None required. If a future engine grows its own
per-clip cache shape (e.g. Android Media3 wants to key fingerprints
differently), the extracted `clipMezzanineFingerprint` / cache append
can move up a level (into `core.domain.render`) rather than staying
embedded in a tool.
