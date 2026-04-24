## 2026-04-23 — Port Desktop + Android AppContainers to shared `registerXxxTools` extensions (§5.6 / Axis: cross-container registration drift)

**Context.** Cycle-37 decision (`2026-04-23-debt-cross-container-tool-list-builder.md`)
introduced shared `ToolRegistry.registerXxxTools(...)` extensions in
`core/commonMain/.../tool/builtin/DefaultBuiltinRegistrations.kt` and
ported CLI + Server to call them. Desktop's `AppContainer` and
Android's `AndroidAppContainer` still inlined the same 70-odd
`register(ToolX(...))` calls apiece — meaning every new tool had to be
added to four AppContainers (3 inline + 1 extension), and the
inline containers drifted from the extension over time (e.g. Desktop
was missing the extension's stable registration order on a few tools,
Android had a different interleaving of meta / AIGC / session tools).

Rubric delta §5.6 (architecture-tax shrinkage): moves from **部分**
(extensions exist but only 2 of 4 JVM containers call them, so the
"add a tool" cost is still O(containers) instead of O(1)) to **有**
(all 4 JVM containers call the shared extensions; adding a tool now
touches exactly `DefaultBuiltinRegistrations.kt` + the tool's own
file, nothing else).

**Decision.** Desktop + Android `tools: ToolRegistry` blocks now call
the 6 / 5 shared extensions respectively:

- **Desktop** (full 7-extension set, matching CLI):
  ```kotlin
  registerSessionAndMetaTools(sessions, agentStates, projects, bus)
  registerMediaTools(engine, projects, bundleBlobWriter, FfmpegProxyGenerator())
  registerClipAndTrackTools(projects, sessions)
  registerProjectTools(projects, engine)
  registerSourceNodeTools(projects)
  registerBuiltinFileTools(fileSystem, processRunner, httpClient, search)
  registerAigcTools(imageGen, videoGen, musicGen, upscale, tts, asr, vision, bundleBlobWriter, projects)
  ```
- **Android** (skip `registerBuiltinFileTools`, pass all-null AIGC engines):
  ```kotlin
  registerSessionAndMetaTools(sessions, agentStates, projects, bus)
  registerMediaTools(engine, projects, bundleBlobWriter, proxyGenerator) // Media3ProxyGenerator
  registerClipAndTrackTools(projects, sessions)
  registerProjectTools(projects, engine)
  registerSourceNodeTools(projects)
  // No registerBuiltinFileTools — phone UI has no fs/shell/web surface.
  registerAigcTools(imageGen=null, videoGen=null, musicGen=null, upscale=null,
                   tts=null, asr=null, vision=null, bundleBlobWriter, projects)
  ```

Android's all-null AIGC call still runs the two always-on
`CompareAigcCandidatesTool` + `ReplayLockfileTool` registrations
inside `registerAigcTools` (they live outside the `?.let { register }`
conditional branches), so the LLM can inspect past AIGC runs even on
a mobile build with no live generators.

The `init` blocks still explicitly register `ProviderQueryTool` +
`CompactSessionTool` after `providers: ProviderRegistry` is
constructed — those two can't be inside `registerXxxTools` because
the shared helpers can't take an optional `ProviderRegistry` without
growing a parameter that CLI / Server / Desktop / Android all
populate anyway. Leaving them in `init` matches the existing CLI +
Server pattern unchanged.

**Axis.** `cross-container registration drift` — when the next new
tool (or the next refactor that moves tools between extensions)
lands, all 4 JVM `AppContainer` files stay in sync because they all
call the extensions. The drift-by-cherry-pick pathway is closed. The
line count drop is substantial but the real win is invariant:
_any_ next-tool cycle touches `DefaultBuiltinRegistrations.kt` + the
new tool's file, not five places.

**Alternatives considered.**

- **Keep Desktop / Android inline, port only when they next touch a
  tool.** Rejected: the bullet's own description documents that
  inlines drift — Desktop had accumulated register ordering different
  from CLI's. Waiting for the "next cycle" means next refactors
  duplicate work across 4 files again.

- **Make the extensions take `Context` on Android so they can do the
  `registerBuiltinFileTools` opt-out themselves.** Rejected: that
  couples the shared extension to a specific platform's capability
  layer. Cleaner for Android to express "no fs / shell / web on this
  platform" by not calling the extension — the skip is one obvious
  comment, not a hidden branch.

- **Add a `registerBuiltinMeta` extension that covers
  `ListToolsTool` / `EstimateTokensTool` / `TodoWriteTool` /
  `DraftPlanTool` / `ExecutePlanTool` separately from session tools.**
  Rejected for this cycle: those 5 already live inside
  `registerSessionAndMetaTools` and the grouping worked for CLI /
  Server. Re-splitting them now for one marginal clarity win would be
  a separate refactor cycle.

- **Inline Desktop + Android's `init {}` tools into the shared
  extension.** Rejected: `ProviderQueryTool` + `CompactSessionTool`
  need the `ProviderRegistry` which is built after the `tools`
  property initializer runs (property-initialiser ordering
  constraint). Pushing them into the extension would force the
  extension to take a nullable `ProviderRegistry?` with a post-hoc
  register step — net complexity increase.

**Coverage.** No new behaviour — pure refactor. The existing
`:apps:desktop:assemble` + `:apps:android:assembleDebug` builds
validate that the AppContainers compile; existing JVM tests (server
AgentLoopTest, CompactorTest, ToolRegistry tests) continue to green
because they don't touch Desktop / Android specifically but prove
that the shared extension contract is still correct end-to-end. The
line-level refactor itself doesn't need a dedicated "does
AppContainer still register the expected tools" test — the extension
is already exercised by CLI / Server tests.

**Registration.** `apps/desktop/src/main/kotlin/io/talevia/desktop/AppContainer.kt`
and `apps/android/src/main/kotlin/io/talevia/android/AndroidAppContainer.kt`
— their `tools: ToolRegistry` initializers replaced with 7 / 6
extension calls. iOS does not register tools (per CLAUDE.md). CLI +
Server already called the extensions from cycle 37. All builds
green: `:core:jvmTest`, `:apps:cli:test`, `:apps:server:test`,
`:apps:desktop:assemble`, `:apps:android:assembleDebug`,
`:core:compileKotlinIosSimulatorArm64`, `ktlintCheck`.

**Code-size delta.** ~150 lines of inline `register(ToolX(args))`
removed from Desktop; ~80 lines from Android. ktlintFormat then
trimmed ~45 unused per-tool imports across the two files. Net: ~270
fewer lines with identical tool registration behaviour.

**§3a arch-tax check (#12).** `debt-register-tool-script`'s trigger
condition is `debt-cross-container-tool-list-builder` infeasible AND
≥10 consecutive new-tool cycles paying the tax. That bullet landed
cycle-37 (feasible); this cycle consolidates the remaining two
inline containers onto it. Trigger now fully unmet — future
new-tool cycles touch 1 file (the extension) instead of 4. Leave
`debt-register-tool-script` as P2.
