## 2026-04-23 — Split ForkProjectTool: extract variant-reshape + language-regen helpers (VISION §3a-3 hygiene)

**Context.** `ForkProjectTool.kt` sat at 538 lines — past the 500-line
long-file threshold the debt scan flags. The file mixed three distinct
concerns: the tool's public surface (Input / VariantSpec /
LanguageRegenResult / Output + JSON Schema + dispatcher), the
variant-spec reshape math (`applyVariantSpec` + 5 private helpers for
aspect-ratio + duration-cap trimming), and the TTS-language
regeneration side-effect (`regenerateTtsInLanguage` dispatching
`synthesize_speech` per text clip). Only the first is load-bearing
for the tool-spec surface the LLM sees; the latter two are
reshape/side-effect implementation that grows with every new variant
feature. Rubric delta §3a-3: long-file signal dropped 538 → 376 (main)
with every sibling ≤ 123 lines.

Different extraction axis from cycles 1 + 2 (ProjectQueryTool /
SessionQueryTool resplits): those cycles peeled off **row data
classes** used by per-select handlers. ForkProjectTool has no
per-select dispatch; it's a single-verb tool whose bulk comes from
reshape math, so the axis here is **helpers that would grow with
variant features** (not the public Input/Output surface).

**Decision.** Created `core/tool/builtin/project/fork/` with two
sibling files, both as top-level `internal` helpers in the
`io.talevia.core.tool.builtin.project.fork` package:

- `VariantReshape.kt` (123 lines) — contains `internal data class
  VariantReshape(project, clipsDropped, clipsTruncated)` plus
  `internal fun applyVariantSpec(project, spec: ForkProjectTool.VariantSpec):
  VariantReshape` and its private helpers (`resolveAspectPreset`,
  `trimTrackClips`, `applyDurationTrim`, `withTrackClips`). Pure-data
  reshape: takes a project, returns a reshaped copy + drop/truncate
  counts.
- `LanguageRegeneration.kt` (74 lines) — contains `internal suspend
  fun regenerateTtsInLanguage(registry: ToolRegistry?, forkId,
  fork, language, ctx): List<ForkProjectTool.LanguageRegenResult>`.
  Takes `registry` as an explicit parameter (was a class field)
  because the helper now lives outside the class; callers pass
  their own registry reference.

Main `ForkProjectTool.kt` (376 lines) keeps: Input / VariantSpec /
LanguageRegenResult / Output (the serializable public surface),
`helpText`, the input JSON Schema, `PersistResult` inner data class,
and the `execute` dispatcher. `VariantSpec` and `LanguageRegenResult`
stay as public nested types on `ForkProjectTool` so existing
callers / tests decode via `ForkProjectTool.VariantSpec.serializer()`
etc.; the sibling files reference them through the `ForkProjectTool.`
prefix.

**Alternatives considered.**
- **Also peel off the tool spec / schema block (Input + VariantSpec
  KDocs + JSON Schema builder) into a `ForkProjectToolSchema.kt`**,
  mirroring the ProjectQueryTool / SessionQueryTool split convention.
  Rejected: the remaining 376-line main file is already below
  threshold, and the schema block here is ~70 lines of `putJsonObject`
  vs ProjectQueryTool's ~170. Pre-split tax without the offsetting
  "new filter fields will keep landing" pressure that motivated the
  schema file in the query tools. Revisit if the main file grows
  past 500 again.
- **Keep the helpers nested as `private fun`s but extract only the
  top-level `data class VariantReshape`.** Rejected: the nested
  helpers are what actually make the file long (≈100 lines), and
  leaving them in blocks the only real size win. Moving just the
  1-line `data class` is decorative.
- **Extract to a deeper module structure (`project/fork/reshape/` +
  `project/fork/regen/`).** Rejected: N=2 helpers doesn't justify
  directory nesting. One `fork/` level is the minimum that
  communicates "fork-internal helpers"; deeper is premature.

**Coverage.** `:core:jvmTest` green — the existing
`ForkProjectToolTest` suite (including the test unskipped in
cycle 3, `variantSpecDurationDropsTailClipsAndTruncatesStraddlers`)
exercises both the `applyVariantSpec` and `regenerateTtsInLanguage`
paths through the tool's public surface, so the extraction is
verified end-to-end without adding new test code. `:core:compileKotlinIosSimulatorArm64`
and `:apps:android:assembleDebug` green (confirms the commonMain
split compiles on native + Android). `./gradlew ktlintCheck` green
(after one auto-fix of a stray blank-line-before-rbrace the mass
edit produced).

**Registration.** No change. Tool id `fork_project` is unchanged;
every `AppContainer` still registers `ForkProjectTool(projects,
registry)` the same way. `registry` is the same ctor arg it was
before — the helpers just take it as a parameter now.
