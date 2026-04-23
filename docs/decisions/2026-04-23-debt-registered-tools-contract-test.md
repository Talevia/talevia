## 2026-04-23 — RegisteredToolsContractTest: static guard against forgotten AppContainer registrations (VISION §3a-8)

**Context.** Adding a new `Tool.kt` under `core/tool/builtin/` without
wiring it into the five `AppContainer`s (CLI / Desktop / Server /
Android / iOS) silently drops the tool — it exists in source, passes
tests in isolation, but is never reachable from any platform's
`ToolRegistry`. The invariant was broken at least once (`cb551be`
fixed a tool that had been added but never wired). Until this cycle
there was no CI-enforced guard. Rubric delta §3a-8: "五端装配不能漏"
is now statically enforced by a test, not just a checklist item in
the /iterate-gap plan step.

The bullet's premise is also what exposed a live bug: during the
dry-run scan that informed the test design, `OpenProjectTool` surfaced
as unregistered in every container, with a `TODO(file-bundle-migration):
register OpenProjectTool(projects) in each AppContainer's tool registry`
comment sitting in the source file. Separate from the contract test,
this cycle wires `OpenProjectTool` into all five containers and deletes
the TODO — without it the new test would fail on first run.

**Decision.**

1. New test at
   `core/src/jvmTest/kotlin/io/talevia/core/tool/RegisteredToolsContractTest.kt`.
   It walks the filesystem for every `*Tool.kt` file under
   `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/` (104
   classes today), assembles a word-boundary regex per class name, and
   greps the concatenated texts of the five `AppContainer` files for at
   least one match. Pure string match — no Kotlin reflection, no
   container instantiation — so the check runs in milliseconds and has
   zero runtime dependency on the composition root succeeding.

   Test robustness: `findRepoRoot()` walks up from the working directory
   looking for `settings.gradle.kts`, so the test works both when run
   from the repo root (`./gradlew :core:jvmTest`) and from an IDE's
   per-module launcher.

   Small allowlist for test-only fixtures: `EchoTool` is documented as
   "trivial smoke / Agent-loop test fixture; intentionally not wired".
   Each allowlist entry must carry a one-line rationale so the map
   can't grow into a hiding spot for real bugs.

2. `OpenProjectTool` registration added to all 5 containers:
   - `CliContainer.kt`, `AppContainer.kt` (desktop), `ServerContainer.kt`,
     `AndroidAppContainer.kt`: `register(OpenProjectTool(projects))`
     next to the existing `CreateProjectTool(projects)` line.
   - `AppContainer.swift` (iOS): `registry.register(tool:
     OpenProjectTool(projects: self.projects))` in the same spot.
   - Deleted the 3-line `TODO(file-bundle-migration):` comment on the
     class declaration.

**Alternatives considered.**
- **Reflection-based test that instantiates each container and reads
  its ToolRegistry.** Would tighten the check (catches typos where a
  tool name is mentioned in a comment but not actually registered).
  Rejected: container instantiation pulls in SQLDelight drivers +
  env-var wiring (the `ServerContainer.kt:215` NPE path already in the
  backlog — `debt-server-container-env-defaults`), ballooning the
  test's surface area and failure modes. The string-grep approach is
  a strictly weaker check but zero runtime dependency makes it
  reliable. If a regression ever slips through (e.g. a tool name
  mentioned in a KDoc but not in a register call), a follow-up can
  tighten the regex to require `register\(FooTool\(` specifically
  rather than bare word boundary.
- **Annotation-driven registry** (tools self-declare via `@AutoRegister`
  or similar, picked up at compile time by kapt/KSP). Rejected:
  introduces a code generation step for a problem this test already
  solves, and adds a new "magic" registration path that contradicts
  the explicit `register(FooTool(...))` convention every AppContainer
  uses today. Better to keep registration explicit and just enforce
  completeness.
- **Skip the `OpenProjectTool` fix** and add it to the allowlist as
  "known unregistered". Rejected: that defeats the test's purpose on
  day one. The bug the test is designed to catch is exactly what
  `OpenProjectTool` represents — a tool that exists in source but was
  forgotten. Fixing it in the same commit demonstrates the test works
  end-to-end (the test was failing before the fix, passes after).

**Coverage.** `:core:jvmTest` green (new test + all 1000+ existing
tests). `:core:compileKotlinIosSimulatorArm64` + `:apps:android:assembleDebug`
+ `:apps:desktop:assemble` + `./gradlew ktlintCheck` green — confirms
the new registrations compile on all three mobile/desktop platforms.

`:apps:server:test` remains pre-existing red — the backlog's
`debt-server-container-env-defaults` bullet already documents this
(ServerContainer NPEs on `env["TALEVIA_RECENTS_PATH"]!!` when tests
pass `env = emptyMap()`; 15 server tests fail on clean main).
Verified not introduced by this cycle via `git stash && :apps:server:test`
run before committing — same failure mode without my changes applied.
This cycle doesn't fix that separately-tracked regression.

**Registration.** `OpenProjectTool` now appears in all 5 `AppContainer`
files. `RegisteredToolsContractTest` needs no registration — it's a
test class picked up by the JUnit runner.
