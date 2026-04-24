## 2026-04-23 — Shared `registerXxxTools(...)` extensions in `core/commonMain/.../tool/builtin/DefaultBuiltinRegistrations.kt` (VISION §5.6 rubric axis)

**Context.** P1 bullet `debt-cross-container-tool-list-builder`.
`apps/cli/.../CliContainerTools.kt` (280 lines, 94 `register(...)`
calls) and `apps/server/.../ServerContainerTools.kt` (320 lines, 95
calls) held byte-identical copies of 7 `ToolRegistry` extension
functions grouping the shared builtin-tool registrations
(`registerSessionAndMetaTools` / `registerMediaTools` /
`registerClipAndTrackTools` / `registerProjectTools` /
`registerSourceNodeTools` / `registerBuiltinFileTools` /
`registerAigcTools`). Every new Core tool required
4 × {import-line insertion + `register(...)` insertion + ktlint-
sort pass} across the two files plus Desktop's + Android's
equivalent inline blocks — 4 drift-risk edit sites for a single
conceptual change. Past cycles had this pattern silently fire
when a new tool landed on 3 containers but not the 4th.

Rubric delta §5.6: cross-container tool-registration SSoT goes
from **无** (4 separate lists, synced by convention) to **部分**
(CLI + Server share one file; Desktop + Android still inline —
follow-up bullet `debt-desktop-android-container-inline-to-extension-call`
appended to P2 for the next cycle to port them).

**Decision.** Three-step refactor:

1. New file `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/DefaultBuiltinRegistrations.kt`
   — the 7 extensions, now `public` (was `internal` in CLI/Server
   files) so any composition root can call them. Module-path
   choice: `core.commonMain` because every dependency is already
   there (ktor `HttpClient`, SessionStore, ProjectStore,
   VideoEngine, etc. all live in core or are pure KMP libs). No
   platform code smuggled in.

2. `registerMediaTools` signature gains a new `proxyGenerator: ProxyGenerator`
   parameter (was hardcoded `FfmpegProxyGenerator()` at the call
   site). The JVM-specific `FfmpegProxyGenerator` could not move
   into `commonMain` without dragging `java.io.File` / ffmpeg
   shell-out logic with it. `ProxyGenerator` interface already
   lived in `core.platform`; threading it through as a parameter
   lets each container inject its concrete impl: CLI / Desktop /
   Server pass `FfmpegProxyGenerator()`; Android (future cycle)
   passes `Media3ProxyGenerator(...)`.

3. Delete both `apps/cli/.../CliContainerTools.kt` and
   `apps/server/.../ServerContainerTools.kt`. CliContainer +
   ServerContainer import from the shared file instead. Each adds:
   - `FfmpegProxyGenerator` import (already on the module's
     classpath via `platform-impls/video-ffmpeg-jvm`).
   - 7 extension-function imports from
     `io.talevia.core.tool.builtin.register*Tools`.
   - `FfmpegProxyGenerator()` passed into the `registerMediaTools(...)`
     call.

Desktop's `AppContainer` + Android's `AndroidAppContainer` still
inline their registrations in `ToolRegistry().apply { ... }`
blocks. Porting them to call the shared extensions requires
re-organising each container's `init` flow (dependency ordering:
both currently construct the proxy generator after the
`register(...)` block — would need a small re-order). Scope
control: this cycle lands the CLI+Server consolidation
(byte-identical duplication, highest drift risk); Desktop+Android
follow-up is queued as a P2 `顺手记 debt` bullet so the pattern
is visible.

**Axis.** Number of copies of the builtin-tool registration list.
Before: 2 (CLI + Server, byte-identical) + 2 inlined copies
(Desktop + Android, structurally-identical but inlined). After: 1
shared SSoT + 2 inlined (Desktop + Android). Pressure source for
re-triggering: any new Core tool that requires editing the
registration list. With this cycle, CLI + Server auto-pick up new
tools added to the shared file; Desktop + Android still need a
manual edit each time — the follow-up bullet closes that gap.

**Alternatives considered.**

- **Build an opinionated `DefaultToolRegistry(deps: Dependencies)`
  factory** that returns a fully-populated `ToolRegistry` in one
  call. Rejected: loses composability. Containers sometimes want
  to overlay platform-specific tools (e.g. Android skips fs/shell/web,
  Server adds `ProviderQueryTool` + `CompactSessionTool` in a
  second pass). An `apply { ... }` block with optional extension
  calls is the more Kotlin-idiomatic way to compose — matches the
  existing pattern the cycle-12 + cycle-20 split introduced, and
  matches `kotlinx.serialization` / `ktor-client` builder shapes.

- **Move only the 7 function bodies but keep the file locations
  (CliContainerTools.kt, ServerContainerTools.kt) as
  delegate-forwarders.** Would preserve the package-local shape.
  Rejected: delegate files that just forward is boilerplate for
  its own sake. Deleting the files is cleaner — the call sites
  import from `core.tool.builtin.*` directly.

- **Include `FfmpegProxyGenerator` as a default parameter on
  `registerMediaTools` so JVM callers don't need to pass it.**
  Rejected: `FfmpegProxyGenerator` lives in
  `platform-impls/video-ffmpeg-jvm`; `core.commonMain` can't
  reference it as a default. Parameter-passing is strictly the
  right layering — callers that know their platform inject the
  right impl.

- **Port Desktop + Android in the same cycle.** Rejected: Desktop's
  `AppContainer` runs 150+ lines of construction BEFORE the
  `ToolRegistry().apply { ... }` block; switching to extension
  calls requires moving the `FfmpegProxyGenerator()` construction
  up. Android's flow is similar. That's a legitimate scope, but
  mixing it with the CLI+Server consolidation would double the
  review surface. Follow-up bullet
  `debt-desktop-android-container-inline-to-extension-call`
  queues the port.

**Coverage.** No new tests — this is a pure structural refactor.
Regression surface is covered by existing integration tests:

- `:apps:cli:test` + `:apps:server:test` pass (the test suites
  construct `CliContainer` / `ServerContainer` and exercise the
  resulting tool registry). A missed tool in the new shared file
  would surface immediately via
  `ToolSpecBudgetGateTest.budgetIsNonTrivial` (> 50 registered
  tools asserted) and downstream integration tests that
  `registry["<tool-id>"]!!.dispatch(...)`.
- `ToolSpecBudgetGateTest.registeredToolSpecsFitWithinCeiling`
  (apps/server) passes — tool surface unchanged; budget unchanged
  post-refactor.
- Gradle: `:core:jvmTest` + `:apps:cli:test` + `:apps:server:test`
  + `:apps:desktop:assemble` + `:core:compileKotlinIosSimulatorArm64`
  + `:apps:android:assembleDebug` + ktlintFormat + ktlintCheck
  all green.

**Registration.** Container-surface refactor:
- 2 files deleted (~600 lines total):
  `apps/cli/.../CliContainerTools.kt`, `apps/server/.../ServerContainerTools.kt`.
- 1 file added (~280 lines):
  `core/src/commonMain/.../tool/builtin/DefaultBuiltinRegistrations.kt`.
- 2 files edited: `apps/cli/.../CliContainer.kt`,
  `apps/server/.../ServerContainer.kt` — imports updated,
  `FfmpegProxyGenerator()` threaded into the `registerMediaTools`
  call, file-level KDoc refresh pointing to the shared file.

Desktop + Android containers unchanged — their follow-up port
is queued as a P2 bullet.
