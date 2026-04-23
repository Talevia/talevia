## 2026-04-23 — Split apps/server/ServerContainer.kt (555 → 363 lines) — extract ServerContainerTools (VISION §5.6)

**Context.** `apps/server/src/main/kotlin/io/talevia/server/ServerContainer.kt`
was 555 lines (R.5 #4 long-file — 500–800 → default P1). The dominant
growth axis was the 110-line `val tools: ToolRegistry = ToolRegistry
().apply { … }` block — every new `register(FooTool(...))` call added
one line, and every cycle that shipped a new Core tool extended this
block plus the ~60 lines of per-tool imports at the top.

Rubric delta §5.6: long-file 555 → 363 for ServerContainer.kt; the
extracted sibling is 340 lines. Both below the 500 watch threshold
with headroom.

**Decision.** Extract tool registration into 7 category-grouped
extension functions on `ToolRegistry`, living in the sibling
`apps/server/src/main/kotlin/io/talevia/server/ServerContainerTools.kt`:

- `registerSessionAndMetaTools(sessions, agentStates, projects, bus)` —
  ListTools / EstimateTokens / TodoWrite / DraftPlan / ExecutePlan /
  SessionQuery / session lifecycle verbs (Export / Fork / Rename /
  Archive / Unarchive / Delete / Revert / SwitchProject / SetSpendCap
  / SetToolEnabled / ReadPart / EstimateSessionTokens).
- `registerMediaTools(engine, projects, bundleBlobWriter)` —
  ImportMedia (with `FfmpegProxyGenerator`) / ExtractFrame /
  ConsolidateMediaIntoBundle / RelinkAsset.
- `registerClipAndTrackTools(projects, sessions)` — 21 clip / track /
  filter / LUT / subtitle / transition tools + `RevertTimeline` +
  `ClearTimeline`.
- `registerProjectTools(projects, engine)` — Project CRUD +
  Export(+DryRun) + ValidateProject + RegenerateStaleClips +
  Prune/GcLockfile + GcClipRenderCache + snapshot verbs +
  ForkProject + DiffProjects + Export/ImportProject +
  SetClipAssetPinned + SetOutputProfile + ProjectQuery.
- `registerSourceNodeTools(projects)` — SourceQuery +
  Describe/Diff/Remove/Import/Export/Add/Fork/SetParents/Rename +
  UpdateSourceNodeBody.
- `registerBuiltinFileTools(fileSystem, processRunner, httpClient,
  search)` — Read/Write/Edit/MultiEdit/ListDirectory/Glob/Grep +
  Bash + WebFetch + conditional WebSearch.
- `registerAigcTools(imageGen, videoGen, musicGen, upscale, tts, asr,
  vision, bundleBlobWriter, projects)` — all conditional AIGC
  registrations + CompareAigcCandidates + ReplayLockfile, including
  the ASR-gated auto-subtitle pair.

`ServerContainer.apply { … }` collapses from 110 lines of per-tool
`register(...)` to 8 grouped calls:

```kotlin
val tools = ToolRegistry().apply {
    registerSessionAndMetaTools(sessions, agentStates, projects, bus)
    registerMediaTools(engine, projects, bundleBlobWriter)
    registerClipAndTrackTools(projects, sessions)
    registerProjectTools(projects, engine)
    registerSourceNodeTools(projects)
    registerBuiltinFileTools(fileSystem, processRunner, httpClient, search)
    registerAigcTools(imageGen, …, projects)
}
```

All per-tool imports moved with their registrations to
`ServerContainerTools.kt`; `ServerContainer.kt` retains only the
framework-level imports (container fields' own classes) + `ExportTool`
(unchanged, still registered via `registerProjectTools`, and removed
from `ServerContainer`'s imports by ktlintFormat). The two registrations
that happen in the `init {}` block (`ProviderQueryTool`,
`CompactSessionTool`) stay there because they depend on
`providers: ProviderRegistry`, which is built from the same container
in a second pass.

**Axis.** "Every new Core tool." Before: new tool = one line in
`ToolRegistry().apply {}` + one line in ServerContainer imports, both
in ServerContainer.kt. After: new tool = one line in the category's
helper in ServerContainerTools.kt + one line in that file's imports.
`ServerContainer.kt` itself is stable; its only pressure source is
framework-level container-field additions (engines, stores, auth).

**Alternatives considered.**

- **Move register calls to a top-level `fun registerAllTools(registry,
  sessions, projects, …)` with one signature taking every
  dependency.** Simpler to invoke (single call) but recreates the
  "too many dependencies, everything in one place" problem — every
  new AIGC engine would widen the signature. Rejected: extension-
  function-per-category is the pattern the bullet asked for and
  scales better.

- **One sibling file per category** (`ServerSessionTools.kt`,
  `ServerMediaTools.kt`, etc.) — 7 new files. Rejected: the per-
  category functions are small (~20 lines each), and splitting them
  across 7 files would multiply the import duplication + the
  directory chatter with little readability gain. One
  `ServerContainerTools.kt` sibling keeps the pattern visible.

- **Centralise into a `core/tool/builtin/RegisterAllBuiltinTools.kt`
  shared across CLI / Desktop / Server / Android / iOS AppContainers.**
  Would dedupe the register blocks across 5 containers
  (`debt-register-tool-script` P2 bullet tracks the pain).
  Rejected for this cycle: each container wires engines / clients /
  pickers differently (e.g. CLI uses `JvmProcessRunner`, Android uses
  Media3 engine, iOS has its own `Swift-side` registration). A
  shared registration helper would need a parameter bag that varies
  per platform — that's a separate design problem. This cycle
  removes the *server's* bloat; the cross-container dedup is
  follow-up via `debt-register-tool-script`.

- **Inline the `registerXxxTools()` functions as private helpers on
  `ServerContainer`** (still file-local, just nested). Would leave
  `ServerContainer.kt` at ~540 lines — below the forced-P0 800
  threshold but still above the 500 watch line. Extension-function
  approach cuts the file below 500 and keeps the pattern visible.

**Coverage.** `:apps:server:test` green
(ServerContainerSmokeTest / InputValidationTest / MetricsEndpointTest /
ToolSpecBudgetGateTest all exercise the fully-wired registry — any
missed `register(...)` call during the move would have shown up as a
"tool not registered" failure or a 500-tool-count regression on the
budget gate). `:apps:server:compileKotlin` + `ktlintCheck` green.
`:core:jvmTest` + `:apps:cli:test` + `:apps:desktop:test` unaffected.

**Registration.** No behavioural change. Every previously-registered
tool is registered in exactly one of the 7 category functions — the
`RegisteredToolsContractTest` (greps the 5 AppContainer files for
tool class references) continues to match `ServerContainer.kt` because
we keep using the same tool class names in the new sibling's function
bodies. No change to the Desktop / CLI / Android / iOS containers.
