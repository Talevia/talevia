## 2026-04-22 — Resplit ProjectQueryTool: move row data classes to sibling files (VISION §5.6 / §3a-3 hygiene)

**Context.** `ProjectQueryTool.kt` was 547 lines, past the 500-line long-file
threshold. The previous split cycle (`6e7bd8f`, decision
`2026-04-21-debt-split-projectquerytool.md`) trimmed it 638 → 540 lines by
carving the per-select `run*Query` handlers and the JSON schema into siblings.
Since then new selects (`consistency_propagation`, `spend`, `snapshots`, the
three `describe_*` drill-downs) each brought a row data class back into the
main file, re-inflating it to 547 lines.

Backlog bullet `debt-resplit-project-query-tool` suggested re-scanning the
`handle<Select>` branches, but all 13 selects were already delegated. The
residual bulk (≈297 lines from L173-470) was entirely nested `@Serializable
data class` row types. Rubric delta §3a-3: long-file signal re-crossed
threshold without a new select branch — the previous "rows stay nested for
API stability" convention was the load-bearing cause.

**Decision.** Move every row data class out of `ProjectQueryTool` into the
matching `core/tool/builtin/project/query/<Select>Query.kt` sibling as a
top-level `@Serializable` type in the `io.talevia.core.tool.builtin.project
.query` package. The main file keeps only `Input`, `Output`, dispatch, and
the companion — now 233 lines. Each sibling now co-locates: its row schema
+ its handler + any handler-private helpers, so a new filter field is
usually a one-file edit.

Specifically moved:
- `TrackRow` → `TracksQuery.kt`
- `ClipRow` → `TimelineClipsQuery.kt`
- `AssetRow` → `AssetsQuery.kt`
- `TransitionRow` → `TransitionsQuery.kt`
- `LockfileEntryRow` → `LockfileEntriesQuery.kt`
- `ClipForAssetRow` → `ClipsForAssetQuery.kt`
- `ClipForSourceRow` → `ClipsForSourceQuery.kt`
- `ConsistencyPropagationRow` → `ConsistencyPropagationQuery.kt`
- `ClipDetailTimeRange` + `ClipDetailLockfileRef` + `ClipDetailRow` → `ClipDetailQuery.kt`
- `LockfileEntryProvenance` + `LockfileEntryDriftedNode` + `LockfileEntryClipRef` + `LockfileEntryDetailRow` → `LockfileEntryDetailQuery.kt`
- `ProjectMetadataProfile` + `ProjectMetadataSnapshotSummary` + `ProjectMetadataRow` → `ProjectMetadataQuery.kt`
- `SnapshotRow` → `SnapshotsQuery.kt`
- `SpendSummaryRow` → `SpendQuery.kt`

Call sites (4 test files + `apps/desktop/src/.../SnapshotPanel.kt`) updated to
import each row directly from the `query` package and drop the
`ProjectQueryTool.` prefix on `.serializer()` calls.

Resulting file sizes (main + every sibling ≤ 250 lines; tightest:
`ClipsForSourceQuery.kt` 71):
- `ProjectQueryTool.kt`: 547 → 233
- `ProjectMetadataQuery.kt`: 210 → 243 (gained the 3 metadata data classes + their KDoc)
- `TimelineClipsQuery.kt`: 164 → 181
- `LockfileEntryDetailQuery.kt`: 116 → 164
- others <160 lines each

**Alternatives considered.**
- **`typealias ProjectQueryTool.TrackRow = …`** — Kotlin disallows nested
  typealiases inside a class body. Top-level `typealias` in the dispatcher
  file would have preserved the FQCN for callers but (a) would add back
  ~20 lines of aliases in the main file, defeating the goal; (b) is an
  anti-pattern — keeping a symbol at an old location after its type moves
  is the kind of "backwards-compat shim" the "no-compat clean cuts"
  feedback memory explicitly rules out. One round of call-site churn is
  the correct tradeoff.
- **Move only the longest rows** (e.g. `ClipRow`, `ClipDetailRow`,
  `LockfileEntryDetailRow`) and keep small ones nested. Would cross the
  threshold today but leaves main-file bulk positively correlated with
  "number of selects" — the same drift that re-triggered this debt bullet.
  Rejected: the structural fix is "every row ships in its handler's
  file", not "every row but the small ones".
- **Extract all rows into one shared `ProjectQueryRows.kt`** (the style
  Kotlin stdlib uses for `ReadOnly*` / `Ext*` grouping files). Would
  solve the main-file problem but replace it with a single ~300-line
  "bag of dumb types" sibling — no co-locality with the handler that
  produces each row, which is the whole reason the extraction helps
  future edits. Rejected.

**Coverage.** `:core:jvmTest` green (includes `ProjectQueryToolTest`,
`ProjectQueryLockfileFiltersTest`, `ProjectQuerySpendTest`,
`ProjectSnapshotToolsTest` — all 22 `<Row>.serializer()` call sites
re-verify the wire shape). `:apps:desktop:assemble` green (confirms
`SnapshotPanel` still decodes via the moved `SnapshotRow`). Cross-platform:
`:core:compileKotlinIosSimulatorArm64` + `:apps:android:assembleDebug`
green, confirming the commonMain shape still compiles on iOS native +
Android. `./gradlew ktlintCheck` clean.

**Wire format.** No change. None of the moved rows were polymorphic on
the wire (no sealed discriminator, no `@Polymorphic`); kotlinx-
serialization encodes class name for polymorphic base types only, so
moving a concrete `@Serializable data class` across packages leaves
the emitted JSON identical. JSON decoded by an older consumer that
grabs the root `Output.rows: JsonArray` and decodes each element with
the matching serializer still works — what moved is the Kotlin import,
not the JSON schema.

**Registration.** No tool registration change — same `project_query`
tool id; every `AppContainer` still registers it by constructor.
