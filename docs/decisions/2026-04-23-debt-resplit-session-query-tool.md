## 2026-04-23 — Resplit SessionQueryTool: move row data classes to sibling files (VISION §5.6 / §3a-3 hygiene)

**Context.** `SessionQueryTool.kt` had grown to 586 lines, past the 500-line
long-file threshold. Exact same shape as the `ProjectQueryTool` drift the
previous cycle fixed (`b9d0da3`, decision
`2026-04-22-debt-resplit-project-query-tool.md`): every `run<Select>` handler
was already delegated to a sibling in `session/query/`, but the per-select
`@Serializable` row data classes all lived nested on `SessionQueryTool` and
pushed the file over the limit as selects accumulated (15 selects with rows,
vs 13 on project_query). Rubric delta §3a-3: long-file signal crossed
threshold for the second tool of this shape, confirming the "rows stay
nested" convention was the load-bearing cause, not a one-off.

**Decision.** Mirror the ProjectQueryTool refactor. Move every row data
class out of `SessionQueryTool` into the matching
`core/tool/builtin/session/query/<Select>Query.kt` sibling as a top-level
`@Serializable` type in the `io.talevia.core.tool.builtin.session.query`
package. The main file keeps Input, Output, dispatch, the `helpText`
string, and the companion — now 293 lines. Each sibling co-locates its
row schema + its handler + any private helpers.

Specifically moved:
- `SessionRow` → `SessionsQuery.kt`
- `MessageRow` → `MessagesQuery.kt`
- `PartRow` → `PartsQuery.kt`
- `ForkRow` → `ForksQuery.kt`
- `AncestorRow` → `AncestorsQuery.kt`
- `ToolCallRow` → `ToolCallsQuery.kt`
- `CompactionRow` → `CompactionsQuery.kt`
- `StatusRow` → `StatusQuery.kt`
- `SessionMetadataRow` → `SessionMetadataQuery.kt`
- `MessagePartSummary` + `MessageDetailRow` → `MessageDetailQuery.kt`
- `SpendSummaryRow` → `SpendQuery.kt`
- `CacheStatsRow` → `CacheStatsQuery.kt`
- `ContextPressureRow` → `ContextPressureQuery.kt`
- `RunStateTransitionRow` → `RunStateHistoryQuery.kt`
- `ToolSpecBudgetRow` + `ToolSpecBudgetEntry` → `ToolSpecBudgetQuery.kt`

Call sites (7 test files) updated to import each row directly from the
`query` package. No cross-package collision with `project.query`'s
`SpendSummaryRow` — separate packages, FQCN disambiguates; neither test
file imports both today.

Resulting file sizes (main + every sibling ≤ 190 lines; tightest
`ForksQuery.kt` 61):
- `SessionQueryTool.kt`: 586 → 293
- `MessageDetailQuery.kt`: 148 → 187 (gained `MessageDetailRow` + `MessagePartSummary`)
- `StatusQuery.kt`: 124 → 150
- `SessionMetadataQuery.kt`: 100 → 128
- `ContextPressureQuery.kt`: 100 → 125
- `SpendQuery.kt`: 104 → 125
- `RunStateHistoryQuery.kt`: 108 → 128
- `ToolSpecBudgetQuery.kt`: 94 → 122
- others ≤ 115

**Alternatives considered.**
- **`typealias SessionQueryTool.XxxRow = …`** — Kotlin disallows nested
  typealiases inside a class body; top-level `typealiases` would have
  required ~30 lines of aliases in the main file, defeating the goal and
  violating the "no-compat clean cuts" feedback memory (preserving an old
  API location for a relocated type is a backwards-compat shim). Rejected
  same as last cycle.
- **Keep `helpText` body next to the selects it documents** (one `HELP_*`
  const in each sibling + a concat in main) — would tighten the main
  file further (~210 lines) but risks `helpText` drifting out of sync
  between siblings because no single file holds the union anymore. The
  existing pattern (one canonical `helpText` block in the dispatcher)
  is what `ProjectQueryTool` shipped in the previous cycle
  (`PROJECT_QUERY_HELP_TEXT` in the schema file) and is the right
  symmetry; deferring.
- **Single `SessionQueryRows.kt` bag** — would centralise rows in one
  file but defeat the co-locality benefit (handler + row in the same
  file; edit a filter and its row in one place). Same reasoning as
  last cycle; rejected.

**Coverage.** `:core:jvmTest` green (covers `SessionQueryToolTest` +
`SessionQueryCacheStatsTest` + `SessionQueryContextPressureTest` +
`SessionQueryRunStateHistoryTest` + `SessionQuerySpendTest` +
`SessionQueryStatusTest` + `SessionQueryToolSpecBudgetTest` — all 22+
`<Row>.serializer()` call sites re-verify wire shape). Cross-platform:
`:core:compileKotlinIosSimulatorArm64` + `:apps:android:assembleDebug` +
`ktlintCheck` green.

**Wire format.** Unchanged. Rows aren't polymorphic on the wire
(no sealed discriminator, no `@Polymorphic`); kotlinx-serialization
encodes class name for polymorphic base types only. Moving a concrete
`@Serializable data class` across packages leaves the emitted JSON
identical.

**Registration.** No tool registration change — same `session_query`
tool id; every `AppContainer` still registers it by constructor.
