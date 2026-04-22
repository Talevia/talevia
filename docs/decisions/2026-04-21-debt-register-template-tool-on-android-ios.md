## 2026-04-21 — Register CreateProjectFromTemplateTool on Android + iOS (§3a #8 five-end parity)

Commit: `faca634`

**Context.** The `auto-author-first-project-from-intent` cycle
(commit `0975461`, decision
`2026-04-21-auto-author-first-project-from-intent.md`) surfaced a
pre-existing 5-end wiring gap: `CreateProjectFromTemplateTool` was
registered in CLI / Desktop / Server but NOT in `AndroidAppContainer`
nor `apps/ios/Talevia/Platform/AppContainer.swift`. The mobile
platforms were silently missing both the explicit genre-template
bootstrap AND the new `template="auto"` + `IntentClassifier` novice
path. §3a #8 ("五端装配不能漏") is a hard rule precisely to prevent
this kind of silent feature loss — the debt bullet noted this during
the prior cycle and this cycle clears it.

(Why this cycle and not `per-clip-incremental-render` at P1 top: that
bullet is deferred by
`2026-04-19-per-clip-incremental-render-deferred-rationale-recorded.md`
as a multi-day cross-engine refactor that cannot ship safely in a
single cycle; partial paths explicitly rejected. Skipped per the
iterate-gap rule "plan must not require skipping red lines; take the
next bullet".)

**Decision.** Two tiny wiring edits:

1. `apps/android/src/main/kotlin/io/talevia/android/AndroidAppContainer.kt`
   — add `import io.talevia.core.tool.builtin.project.CreateProjectFromTemplateTool`
   and `register(CreateProjectFromTemplateTool(projects))` directly
   after the existing `register(CreateProjectTool(projects))` line,
   so the two template-related registrations sit adjacent in the
   tool list (makes drift easier to spot in future reviews).
2. `apps/ios/Talevia/Platform/AppContainer.swift` — add
   `registry.register(tool: CreateProjectFromTemplateTool(projects:
   self.projects))` directly after the `CreateProjectTool` line.
   `CreateProjectFromTemplateTool` was already exposed to Swift via
   SKIE (same package as `CreateProjectTool`, no per-type SKIE
   config was needed).

No code changes to the tool itself — the existing registrations in
CLI / Desktop / Server continue to work identically. Android and iOS
now pick up the full template catalog (narrative / vlog / ad /
musicmv / tutorial) AND the `template="auto"` intent-classifier path
from cycle 33.

**Alternatives considered.**

- *Leave the gap and document it as "mobile-intentional"*: rejected
  — CLAUDE.md's "Platform priority — 当前阶段" explicitly says the
  Android / iOS bottom line is "不退化", meaning when a Core
  capability lands in FFmpeg-backed paths it should match on mobile.
  `CreateProjectFromTemplateTool` has been Core-backed for months;
  the mobile omission was a registration oversight, not a design
  choice.
- *Bundle this with a broader "audit all five containers for
  consistency" pass*: rejected for this cycle — the Skill's
  "一个 PR 做一件事" discipline, and the debt bullet was the
  specific registration. A full audit would be its own backlog
  bullet if and when other gaps surface.
- *Extend iOS registration via a generic "all project tools" helper
  instead of per-tool lines*: rejected — the existing per-tool
  `registry.register(tool: ...)` pattern is what every other app
  container uses; breaking the convention for one new tool would
  be worse than the line of boilerplate it saves.

Industry consensus referenced: the five-end parity rule is the
Kotlin Multiplatform equivalent of what `Android / iOS` feature
flags should mirror in React Native / Flutter codebases — any shared
capability must be opted-in per-target, or it's just a Core API with
no consumers.

**Coverage.**

- No new tests. The tool's behavior is already fully covered by
  `CreateProjectFromTemplateToolTest` in `core:jvmTest` (15 tests
  including the new auto-mode suite from cycle 33). Registration on
  each target is verified by the gradle build graph — Android's
  `assembleDebug` fails if the import / call site is mistyped, and
  `linkDebugFrameworkIosSimulatorArm64` + Xcode's build phase fails
  if the Swift reference resolves wrong at SKIE bridge time.
- `./gradlew :apps:android:assembleDebug` + `ktlintCheck` green
  proves Android wiring; `:core:linkDebugFrameworkIosSimulatorArm64`
  green proves the Swift-side type is still exposed after cycle 33's
  tool-surface changes.

**Registration.** Two file touches —
`AndroidAppContainer.kt` (import + one call), `AppContainer.swift`
(one call). No Core changes; no tests added; no docs other than
this decision.

§3a checklist pass:
- #1 zero new tools — this is pure wiring. ✓
- #2 not a Define/Update pair. ✓
- #3 no Project blob changes. ✓
- #4 not introducing state flags. ✓
- #5 template names stay genre-neutral strings in the Tool's schema. ✓
- #6 no session-binding surface added. ✓
- #7 no serialisation changes. ✓
- #8 this cycle IS the §3a #8 fix — going from 3/5 registrations
  to 5/5. ✓
- #9 tool semantics already covered; registration correctness
  verified by compile + link. ✓
- #10 zero new LLM-visible tokens on the desktop / server / CLI side
  (tool was already in context). Android / iOS agent sessions gain
  the tool's spec (~200 tokens), which is the whole point. ✓
