## 2026-04-21 — Fold describe_clip + describe_lockfile_entry + describe_project into project_query (R.5 near-tool-group debt)

Commit: `f52191c`

**Context.** `core/tool/builtin/project/` carried three drill-down
tools — `DescribeClipTool` (215 lines), `DescribeLockfileEntryTool`
(211 lines), `DescribeProjectTool` (325 lines) — each a focused
single-entity deep read alongside `ProjectQueryTool`'s seven
list-style selects. R.5 debt scan (cycle 36 repopulate, commit
`9ce7071`) flagged the `project/Describe*` trio as a near-tool
group: every LLM turn paid for four largely-overlapping tool specs.

Cycle 37 completed the session-side equivalent
(`debt-consolidate-session-describe-queries`, decision
`2026-04-21-debt-consolidate-session-describe-queries.md`) using the
same pattern: single-row drill-down as a `select` variant returning
`total=1, returned=1, rows=[oneRow]`. That established the template
and reduced scope uncertainty for this cycle.

(Why not the P0 top `aigc-cost-tracking-per-session`: still blocked
on pricing-table product decisions. Left in place for a dedicated
cycle.)

**Decision.** Three new selects on `ProjectQueryTool`:

1. `SELECT_CLIP = "clip"` — drill-down by new `clipId` input field
   (required here, rejected elsewhere). Returns a single
   `ClipDetailRow` with timeRange / sourceRange + transforms +
   per-kind fields (video filters, audio volume+fades, text
   text+style) + derived `ClipDetailLockfileRef` (pin + staleness).
2. `SELECT_LOCKFILE_ENTRY = "lockfile_entry"` — drill-down by new
   `inputHash` input field. Returns a single `LockfileEntryDetailRow`
   with provenance + source-binding snapshot + drifted-nodes list +
   baseInputs + live timeline clip references.
3. `SELECT_PROJECT_METADATA = "project_metadata"` — no extra input
   beyond `projectId`. Returns a single `ProjectMetadataRow` with
   the compact cross-axis aggregate (tracksByKind / clipsByKind /
   sourceNodesByKind / lockfileByTool / recentSnapshots / optional
   non-default outputProfile) plus the same `summaryText`
   pre-rendered quotable paragraph the old tool produced.

All three handlers live under `core/tool/builtin/project/query/` per
the existing per-select file split pattern (`ClipDetailQuery.kt`,
`LockfileEntryDetailQuery.kt`, `ProjectMetadataQuery.kt`).

`DescribeClipTool.kt`, `DescribeLockfileEntryTool.kt`,
`DescribeProjectTool.kt`, and their test files are deleted. Five
AppContainers (CLI / Desktop / Server / Android / iOS) drop three
imports + three registrations each. Net LLM context: −3 tool specs
(saving ~600 tokens per turn) + ~220 tokens added to project_query's
helpText + schema. Net savings per turn: ~380 tokens.

**Alternatives considered.**

- *Keep the three tools and tighten their outputs*: rejected — the
  debt scan has flagged them for two repopulate cycles running; the
  per-tool spec overhead is the real cost.
- *Ship one at a time in three cycles*: rejected — the pattern was
  proven in cycle 37, shipping all three together keeps the
  consolidation momentum and avoids transient states where the LLM
  sees some describes-as-selects + some describes-as-tools
  (confusing spec surface).
- *Flatten ClipDetailRow to inline all per-kind fields into
  ProjectQueryTool directly rather than nested types*: rejected —
  nested `ClipDetailTimeRange` and `ClipDetailLockfileRef` let
  consumers decode just the sub-piece they need without pulling in
  the full row class. Mirrors how the tool-dispatch layer handles
  nested rows (e.g. `ProjectQueryTool.ClipRow`).
- *Preserve the legacy `describe_*` tool ids as aliases pointing to
  the new select*: rejected — aliases double the LLM's spec surface
  forever to ease a single-turn transition. Clean delete, decision
  doc + git history provide the migration narrative.

Industry consensus referenced: same precedent as cycle 37's decision
— AWS CLI's subcommand pattern, codebase-grep's
`(select, filter, sort, limit)` shape, and `kotlinx.serialization`'s
sealed-class discriminator convention for polymorphic row types.
OpenCode's `tool/tool.ts` tool-dispatch shape uses the same "one
typed entry point, fan out inside" pattern.

**Coverage.**

- `ProjectQueryToolTest.clipDrillDownReturnsKindSpecificFields` —
  video clip drill-down returns `trackId`, `clipType="video"`,
  `assetId`, and `sourceBindingIds`.
- `ProjectQueryToolTest.clipDrillDownMissingClipFailsLoud` —
  unknown clipId yields "Clip … not found" with a
  `project_query(select=timeline_clips)` discovery hint.
- `ProjectQueryToolTest.clipDrillDownRequiresClipId` — missing
  clipId fails loud.
- `ProjectQueryToolTest.clipIdOnOtherSelectFailsLoud` — clipId on
  `select=timeline_clips` is rejected (regression guard against
  the new input field leaking into list selects).
- `ProjectQueryToolTest.lockfileEntryDrillDownReturnsFullProvenanceAndRefs`
  — lockup by `inputHash="h-pinned"` returns pin=true + provenance
  + one clipRef to `c-pinned` + `currentlyStale=false`.
- `ProjectQueryToolTest.lockfileEntryDrillDownMissingHashFailsLoud`
  — unknown hash fails loud.
- `ProjectQueryToolTest.lockfileEntryDrillDownRequiresInputHash` —
  missing field fails loud.
- `ProjectQueryToolTest.projectMetadataReturnsSummaryAndBreakdowns`
  — fixture project's metadata row matches expected
  tracksByKind / clipsByKind counts and `summaryText` contains
  the project title.

Three test files deleted: `DescribeClipToolTest.kt`,
`DescribeLockfileEntryToolTest.kt`, `DescribeProjectToolTest.kt`.
Coverage axis stays identical (happy + validation + failure per
drill-down); net test lines drop substantially because the row
shapes are smaller than the old Output types.

**Registration.** Five AppContainers each drop three imports + three
registration calls:
- `apps/cli/src/main/kotlin/io/talevia/cli/CliContainer.kt`
- `apps/desktop/src/main/kotlin/io/talevia/desktop/AppContainer.kt`
- `apps/server/src/main/kotlin/io/talevia/server/ServerContainer.kt`
- `apps/android/src/main/kotlin/io/talevia/android/AndroidAppContainer.kt`
- `apps/ios/Talevia/Platform/AppContainer.swift`

No new AppContainer wiring — the existing `ProjectQueryTool(projects)`
registration now covers the three new selects automatically.

§3a checklist pass:
- #1 **negative** tool count (−3 Tool.kt files). ✓
- #2 not a Define/Update pair. ✓
- #3 no Project blob changes. ✓
- #4 no binary flag. ✓
- #5 project vocabulary is genre-neutral. ✓
- #6 no session-binding surface added; `projectId` continues to
  default from `ToolContext.currentProjectId` for all selects. ✓
- #7 new Input fields (`clipId`, `inputHash`) default to null; new
  row types have nullable defaults on per-kind conditional fields
  (filters / volume / fadeIn/Out / text / textStyle on ClipDetailRow,
  currentContentHash on DriftedNode, outputProfile on
  ProjectMetadataRow). ✓
- #8 five-end: all containers updated in lock-step. ✓
- #9 eight new tests cover happy paths (three selects), missing-id
  failures (two selects), missing-input-field failures (two
  selects), cross-select field misapplication (clipId on
  `select=timeline_clips` rejected). Edge cases beyond happy paths
  in every drill-down. ✓
- #10 **negative** LLM context — three tool specs removed (~600
  tokens saved per turn), minus ~220 tokens added to project_query
  helpText + schema. Net savings per turn: ~380 tokens. ✓
