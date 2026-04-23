## 2026-04-23 — Split apps/desktop/SourcePanel.kt (635 lines) into 6 sibling Composables + helpers (VISION §5.6)

**Context.** `apps/desktop/src/main/kotlin/io/talevia/desktop/SourcePanel.kt`
was 635 lines (R.5 #4 long-file — 500–800 → default P1). The file had
fused five responsibilities:

1. The `@Composable fun SourcePanel(...)` surface itself — layout, state
   hoist, LaunchedEffect bus subscription, per-dispatch closure (~250
   lines).
2. `SourceNodeRow` — per-node inspector Composable with inline edit
   form, downstream-clip list, pretty-printed JSON body (~135 lines).
3. `AddSourceControls` — "+ character / + style / + palette" form
   Composable (~55 lines).
4. `dispatchBodyUpdate` + `displayName` + `nodeSecondaryField` /
   `nodeSecondaryLabel` / `nodeDescription` + `PrettyJson` (~65 lines).
5. `AppContainer.uiToolContext(projectId)` extension — already used by
   other panels (AppRoot, SnapshotPanel, LockfilePanel dispatch through
   it) but was coupled to SourcePanel.kt (~15 lines).

Rubric delta §5.6: long-file 635 → 306 for SourcePanel.kt; every sibling
under 200 lines.

**Decision.** Split into 6 sibling files in the same `io.talevia.desktop`
package. Everything crossing a file boundary flipped `private` →
`internal`.

| File | Lines | Contents |
|---|---|---|
| `SourcePanel.kt` | 306 | Top-level `@Composable fun SourcePanel(...)` — layout, bus subscription, per-dispatch closure, calls into `groupSourceNodes` + `SourceGroupHeader` + `SourceNodeRow` + `AddSourceControls`. |
| `SourceNodeRow.kt` | 177 | `@Composable internal fun SourceNodeRow(...)` — inspector with inline edit form + downstream clip list + JSON body view. |
| `SourceNodeList.kt` | 55 | `internal fun groupSourceNodes(...)` + `SourceGroupHeader` Composable + the private `SourceGroup` enum. |
| `AddSourceControls.kt` | 82 | `@Composable internal fun AddSourceControls(...)` — "Define new" form. |
| `SourceNodeHelpers.kt` | 97 | `dispatchBodyUpdate` + `displayName` + `nodeSecondaryField` + `nodeSecondaryLabel` + `nodeDescription` + `SourcePrettyJson` (renamed from `PrettyJson` to avoid collision with the homonymous `private val PrettyJson` in `LockfilePanel.kt` / `TimelinePanel.kt`). |
| `UiToolContext.kt` | 34 | `AppContainer.uiToolContext(projectId): ToolContext` — extracted because it's already used by AppRoot / SnapshotPanel / LockfilePanel, not SourcePanel-specific. |

Gross line growth: 635 → 751 (+116) from per-file imports, as expected
for a split of this shape.

**Axis.** "New source-DAG view widget." A future `SourceDagGraph`
Composable (the bullet mentioned this as a speculative third view, not
currently implemented) lands in its own sibling file, not back into
`SourcePanel.kt`. Per-kind editor Composables (`CharacterEditor`,
`StyleEditor`) would similarly extend `SourceNodeRow.kt`'s pattern
rather than bloat the main panel.

**Alternatives considered.**

- **Keep everything in SourcePanel.kt, just break out helpers.** Would
  drop line count ~80 (to ~555) but leave the main file still past the
  watch threshold + keep SourceNodeRow / AddSourceControls entangled
  with the panel's layout logic. Rejected.

- **Centralise `PrettyJson` into a single `JsonPrettyPrint.kt` shared
  across LockfilePanel / TimelinePanel / SourcePanel.** Would unify
  three near-identical `private val PrettyJson` declarations. Rejected
  for this cycle: the configs differ (Lockfile / Timeline use
  `Json(JsonConfig.default) { prettyPrint = true; prettyPrintIndent =
  "  " }`, this one was `Json { prettyPrint = true; prettyPrintIndent =
  "  " }` without a base config). Centralising would either require
  picking one and changing the other two's behaviour, or introducing
  parameters — either change is out of scope. Renamed to
  `SourcePrettyJson` to resolve the name collision the move created.
  Follow-up captured in a `debt-` append.

- **Flatten `SourceNodeList.kt` into `SourceNodeRow.kt`.** Row + header
  + grouping are cohesive. Kept separate for now because grouping is
  pure logic (testable on its own) and the header is a 5-line
  Composable — mixing them into SourceNodeRow would muddy the 177-line
  row file for little benefit.

**Coverage.** `:apps:desktop:test` green (pre-existing
`MacOsInfoPlistExtraXmlTest` unaffected; there's no UI test for
SourcePanel specifically — Compose Desktop doesn't have an automated UI
test harness wired in this repo). `:apps:desktop:assemble` +
`:apps:desktop:compileKotlin` + `ktlintCheck` green — the compiler
checks every cross-file reference, so a missed visibility flip would
have failed compilation. `:core:jvmTest` unaffected.

**Registration.** No tool / AppContainer / platform change.
Composables are file-layout only; the `SourcePanel(...)` call site in
`AppRoot.kt` is unchanged (sees the same public `fun SourcePanel` with
the same signature).
