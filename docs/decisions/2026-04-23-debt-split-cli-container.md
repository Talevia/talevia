## 2026-04-23 — Split apps/cli/CliContainer.kt (524 → 323 lines) — extract CliContainerTools (VISION §5.6)

**Context.** `apps/cli/src/main/kotlin/io/talevia/cli/CliContainer.kt`
was 524 lines (R.5 #4 long-file — 500–800 → default P1). The dominant
growth axis was the 110-line `val tools: ToolRegistry = ToolRegistry
().apply { … }` block plus its ~60 lines of per-tool imports —
structurally identical to what cycle 12 did for
`apps/server/ServerContainer.kt`. Every new Core tool extended this
block and pushed the file closer to the forced-P0 800 threshold.

Rubric delta §5.6: long-file 524 → 323 for `CliContainer.kt`; the
extracted sibling is 300 lines. Both below the 500 watch threshold
with headroom.

**Decision.** Extract tool registration into 7 category-grouped
extension functions on `ToolRegistry`, living in the sibling
`apps/cli/src/main/kotlin/io/talevia/cli/CliContainerTools.kt`.
Signatures mirror `apps/server/ServerContainerTools.kt` from cycle 12
one-for-one:

- `registerSessionAndMetaTools(sessions, agentStates, projects, bus)`
- `registerMediaTools(engine, projects, bundleBlobWriter)`
- `registerClipAndTrackTools(projects, sessions)`
- `registerProjectTools(projects, engine)` — includes `ExportTool` +
  `ExportDryRunTool` (which were inlined with the clip/track block
  in the pre-split CLI, but live with project tools in server;
  aligned to server's grouping here for a consistent cross-container
  pattern).
- `registerSourceNodeTools(projects)`
- `registerBuiltinFileTools(fileSystem, processRunner, httpClient, search)`
- `registerAigcTools(imageGen, videoGen, musicGen, upscale, tts, asr,
  vision, bundleBlobWriter, projects)`

`CliContainer.apply { … }` collapses from 110 lines of per-tool
`register(...)` to 7 grouped calls:

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
`CliContainerTools.kt`; `CliContainer.kt` retains only
framework-level imports (its own fields' types) plus `ToolRegistry`
itself. `ProviderQueryTool` + `CompactSessionTool` stay in the
container's `init {}` block because they depend on `providers:
ProviderRegistry`, which is built from the same container in a
second pass — same shape as server.

**Axis.** "Every new Core tool." Before: new tool = one line in
`ToolRegistry().apply {}` + one line in `CliContainer` imports, both
in `CliContainer.kt`. After: new tool = one line in the category's
helper in `CliContainerTools.kt` + one line in that file's imports.
`CliContainer.kt` itself is stable; its only pressure source is
framework-level container-field additions (new engines, new
providers, new permission plumbing).

**Alternatives considered.**

- **Share `ServerContainerTools.kt`'s extensions across both
  containers** (move them to a cross-cutting
  `apps/common/ContainerTools.kt` or to `core/tool/builtin/`).
  Would dedupe the ~300 lines now duplicated between server and CLI.
  Rejected for this cycle: the P2 bullet
  `debt-register-tool-script` already tracks the 5-container
  dedup design problem, which is broader (Desktop / Android / iOS
  also carry their own register blocks; Android + iOS have
  platform-specific engine bindings — e.g. CLI uses
  `FfmpegProxyGenerator`, Android uses `Media3VideoEngine`). A
  cross-container helper needs a parameter bag that varies per
  platform, which is its own design cycle. This cycle mechanically
  mirrors the server split so the two siblings stay structurally
  aligned for whenever the dedup lands — the extension-function
  shape is what the dedup helper will call into.

- **Inline the `registerXxxTools()` functions as private helpers on
  `CliContainer`** (still file-local, just nested inside
  `CliContainer`). Would leave `CliContainer.kt` at ~510 lines —
  below the forced-P0 800 threshold but still above the 500 watch
  line. Rejected: same argument as cycle 12; the extension-function
  approach cuts the file below 500 and keeps the pattern visible
  + consistent with the server sibling.

- **Move `ExportTool` + `ExportDryRunTool` into
  `registerClipAndTrackTools` to match the pre-split CLI order.**
  Rejected: the server split put them in `registerProjectTools`
  (they take `engine` + operate on whole-project state), and
  mirroring that grouping keeps the two container-tools files
  step-for-step identical in structure. Diff between
  `ServerContainerTools.kt` and `CliContainerTools.kt` is now
  purely the surrounding text (KDoc header naming the slug); the
  tool-registration bodies are identical, which is the property
  `debt-register-tool-script` will later exploit to dedup them.

**Coverage.** `:apps:cli:test` green (the pre-existing CLI test suite
exercises the fully-wired container indirectly — smoke + slash
commands + REPL rely on `CliContainer` constructing successfully, any
missed `register(...)` call during the move would manifest as a
"unknown tool" failure or a tool spec budget drift). `:core:jvmTest`
green — `RegisteredToolsContractTest` continues to pass because
Desktop `AppContainer.kt` (the one AppContainer not yet split) still
contains every tool-class reference, and the contract test uses the
union of all 5 container files. When Desktop eventually gets split
via the same pattern, the contract test will need to either: (a)
also scan the sibling `*ContainerTools.kt` files, or (b) land a
5-container dedup (`debt-register-tool-script`). Noted here so a
future cycle doesn't trip on it. `:apps:cli:ktlintCheck` green after
`ktlintFormat`.

**Registration.** No tool / AppContainer change. Every previously-
registered tool is registered in exactly one of the 7 category
functions — behavioural diff is zero. `RegisteredToolsContractTest`
(greps the 5 AppContainer files for tool class references)
continues to pass via Desktop's `AppContainer.kt`; the class-name
text no longer appears in `CliContainer.kt` itself (it appears in
the sibling), but that does not break the contract test because
the test requires each tool to be named in *at least one* of the
5 files, not specifically each. The Desktop / Android / iOS
containers are unmodified.
