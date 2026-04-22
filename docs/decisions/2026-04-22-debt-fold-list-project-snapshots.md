## 2026-04-22 — Fold list_project_snapshots into project_query(select=snapshots) (debt)

Commit: `0f0e75a`

**Context.** `ListProjectSnapshotsTool` was the last surviving `list_*`
tool after the `project_query` / `session_query` / `provider_query`
consolidations. It enumerated saved snapshots on a project with the same
shape the consolidated primitives already handle: filter + sort + limit
over a per-project list, compact row summary, read-only permission. The
backlog flagged it as a direct `debt-fold-list-project-snapshots`
cleanup — merging it into `project_query(select=snapshots)` removes one
tool spec from every LLM turn and finishes the "one tool per query
domain" cleanup.

Eleventh skip on `per-clip-incremental-render` (stays deferred per the
2026-04-19 multi-day-refactor rationale).

**Decision.** Add `SELECT_SNAPSHOTS = "snapshots"` to `ProjectQueryTool`
with the same filter surface the old tool had:

- `maxAgeDays: Int?` — drop snapshots captured strictly earlier than
  `now - maxAgeDays` (inclusive cutoff). Hard-rejected on other selects
  via `rejectIncompatibleFilters`.
- `limit: Int?` — post-filter cap, honouring the shared `[1, 500]`
  clamp from the outer primitive (changes fail-loud-on-0-or-501 to
  silent-clamp; tests updated accordingly).
- `offset: Int?` — same pagination the other selects use. The old
  tool didn't expose offset; the unified primitive does so paging
  generalises.

Row shape `ProjectQueryTool.SnapshotRow(snapshotId, label,
capturedAtEpochMs, clipCount, trackCount, assetCount)` is a 1:1 copy
of the old `ListProjectSnapshotsTool.Summary`; consumers decode via
`ListSerializer(ProjectQueryTool.SnapshotRow.serializer())` through the
uniform `Output.rows` JsonArray.

`ProjectQueryTool` gets a new optional `clock: Clock = Clock.System`
constructor param so the `maxAgeDays` cutoff is test-injectable with
`FixedClock`, same pattern the old tool used. Apps with a standard
wiring don't need to touch anything — default Clock.System.

Net tool count: **−1**. Net LLM-context cost: ≈ −200 tokens per turn
(old tool's helpText + schema gone; the new select adds ~3 lines in
`project_query`'s help).

**Migration.**

- `ListProjectSnapshotsTool.kt` deleted.
- All five AppContainer registrations dropped (CLI / desktop / server /
  Android / iOS).
- `PromptProject.kt` rewired `list_project_snapshots` → `project_query(select=snapshots)` in the agent's running guidance.
- `TaleviaSystemPromptTest` key-phrase list updated so the canonical
  `project_query(select=snapshots)` is what the prompt must contain.
- `SnapshotPanel.kt` (desktop Compose UI) migrated to dispatch
  `project_query` and decode `SnapshotRow` rows.
- `ProjectSnapshotToolsTest.kt` migrated in-place via a `listSnapshots`
  helper that runs the new query + decodes the rows, preserving every
  assertion shape. The old `listRejectsLimitOutsideRange` test
  converted to `listLimitOutsideRangeIsClampedSilently` — the unified
  primitive clamps rather than throws.

**Alternatives considered.**

1. **Keep the tool as a thin shim that delegates to `project_query(select=snapshots)`**
   — rejected. Leaves two tool specs in the LLM context for the same
   query with no semantic gain; the net +0 tool-count dodges the
   consolidation benefit.
2. **Add `snapshots` as a `select=project_metadata` sub-row** —
   rejected. `project_metadata` is an aggregate single-row drill-down;
   snapshots are a variable-length list that deserves the row-per-entry
   shape used by every other list-style select.
3. **Expose `offset` only when the caller explicitly asks** (preserve
   the old tool's offset-less surface) — rejected. The uniform query
   shape's whole point is the `limit + offset` paging idiom; adding a
   per-select quirk that strips offset would just grow special-case
   documentation without reducing surface.

**Coverage.** Existing `ProjectSnapshotToolsTest` cases migrated
in-place (save / save-accumulate / list-newest-first / empty-project /
maxAgeDays-5 / maxAgeDays-0 / limit=2 / default-sort / negative-age
rejected / limit-clamped / save-list-restore round-trip /
restore-preserves-snapshots-list). All 9 snapshot-listing tests + the
round-trip test pass against the new surface; system-prompt + ktlint +
:core:jvmTest + :apps:server:test + :core:compileKotlinIosSimulatorArm64
+ :apps:android:assembleDebug + :apps:desktop:assemble all green.

**Registration.** Deleted `ListProjectSnapshotsTool` registrations in
CliContainer / AppContainer (desktop) / ServerContainer /
AndroidAppContainer / iOS `AppContainer.swift`. Added `clock` arg on
every `ProjectQueryTool(...)` call site — passes via default
`Clock.System` so apps don't need explicit wiring changes.

---
