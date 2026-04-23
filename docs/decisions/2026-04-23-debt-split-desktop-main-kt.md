## 2026-04-23 — Split apps/desktop/Main.kt (1011 lines) into sibling files (VISION §5.6)

**Context.** `apps/desktop/src/main/kotlin/io/talevia/desktop/Main.kt`
was 1011 lines — over R.5's 800-line forced-P0 threshold. The file had
fused six unrelated concerns:

1. The `fun main()` entry point (~30 lines).
2. `DesktopShortcutHolder` — window-level cmd+E/S/R key dispatch (~40 lines).
3. `AppRoot` — the three-column composition root that owns the asset /
   timeline+render / tabbed-workbench layout plus bootstrap
   `LaunchedEffect`s (~395 lines).
4. `ChatPanel` — the agent-driven chat surface with session switcher and
   replay of persisted Parts (~320 lines).
5. Export preset UI — `ResolutionPreset` / `FpsPreset` enums +
   dropdowns + `defaultModelFor` (~75 lines).
6. Helpers — `RightTab`, `SectionTitle`, `openExternallyIfExists`,
   `resolveOpenablePath`, `desktopEnvWithDefaults`, `JsonPrimitive
   .contentOrNull` (~95 lines).

Each concern already had its own natural name; they only lived in
Main.kt because the file was seeded as a wiring demo and accumulated. A
reader who needed "what does cmd+E do" had to scroll past 800 lines of
unrelated Composable code.

Rubric delta §5.6: long-file threshold 1011 → 56 for Main.kt; no
sibling crosses the 500 watch line.

**Decision.** Split into six sibling files in the same
`io.talevia.desktop` package:

| File | Lines | Contents |
|---|---|---|
| `Main.kt` | 56 | `fun main() = application { … AppRoot(…) }` — nothing else |
| `AppRoot.kt` | 454 | `@Composable internal fun AppRoot` + its enclosed layout |
| `ChatPanel.kt` | 375 | `@Composable internal fun ChatPanel` + `ChatLine` + `replayMessageParts` |
| `DesktopShortcutHolder.kt` | 47 | `internal class DesktopShortcutHolder` |
| `ExportPresets.kt` | 78 | `ResolutionPreset` / `FpsPreset` enums + dropdown Composables + `defaultModelFor` |
| `MainHelpers.kt` | 117 | `RightTab` / `SectionTitle` / `openExternallyIfExists` / `resolveOpenablePath` / `JsonPrimitive.contentOrNull` / `desktopEnvWithDefaults` |

Visibility audit:

- Anything crossing a file boundary flipped `private` → `internal`.
  `DesktopShortcutHolder`, `AppRoot`, `ChatPanel`, the four preset
  dropdowns + enums, `defaultModelFor`, `RightTab`, `SectionTitle`,
  `openExternallyIfExists`, `resolveOpenablePath`,
  `JsonPrimitive.contentOrNull` (used by `resolveOpenablePath`),
  `desktopEnvWithDefaults`.
- Stayed `private`: `ChatLine` data class and `replayMessageParts` —
  only used by `ChatPanel`.

Imports are not deduped across files — each sibling imports exactly
what it uses, which is the standard post-split pattern. The +116-line
gross-line-count growth (1011 → 1127 total) is entirely duplicated
import declarations.

No behaviour change: the full `fun main()` flow is byte-identical to
before for every observable state (window state, bootstrap
LaunchedEffect order, shortcut dispatch, AppRoot composition).

**Axis.** "New top-level Desktop panels / new cmd-key shortcuts."
If Desktop grows a new tab (e.g. a budget panel) or a new shortcut
(cmd+T to toggle theme), they land in their most natural sibling file
— not Main.kt. The test suite's Info.plist guard + `:apps:desktop:test`
continuing to pass certifies the split preserved semantics.

**Alternatives considered.**

- **Split by "one file per Composable"** (AssetsPanel.kt /
  RenderPanel.kt / RightWorkbench.kt). Rejected: those composables
  share per-turn state (assets list, render progress, projectId,
  chatLines) that's hoisted into `AppRoot`. Pulling them into separate
  files would force passing 6+ state holders through each signature or
  moving state up into a singleton — a worse cut than the present
  AppRoot-keeps-layout split. Will revisit if AppRoot itself grows
  past 500 lines (currently 454, within watch range).

- **Split by logical domain** (chat module / export module / asset
  module). Rejected: Compose state hoisting means AppRoot is the
  natural composition root, and the useful boundary is
  "composition root vs. its children", not "feature A vs. feature B".
  A domain split would duplicate mutable state everywhere.

- **Extract the inline `runCatching { container.bus.subscribe … }`
  LaunchedEffects to named functions** (`rememberRenderProgressState()`
  etc.). Would have shrunk AppRoot further but materially changes
  behaviour — the `rememberCoroutineScope` and `remember` cache keys
  stay bound to the `LaunchedEffect` block's source position. Deferred
  until a driver (e.g. a regression that points at subscription
  lifecycle) motivates the reshape.

**Coverage.** `:apps:desktop:test` (pre-existing
`MacOsInfoPlistExtraXmlTest` — 5 cases), `:apps:desktop:assemble`,
`:apps:desktop:compileKotlin`, `ktlintCheck` all green. The Kotlin
compiler checks every cross-file reference, so a missed visibility
flip would have failed compilation.

**Registration.** No tool / no AppContainer change. Package is
unchanged, file layout only. Desktop's own build.gradle.kts needs no
edit (Kotlin source set globs `src/main/kotlin/**`).
