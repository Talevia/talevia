## 2026-04-23 — project_query(select=timeline_diff) — snapshot-vs-snapshot timeline delta (VISION §5.1 / §5.4)

**Context.** `DiffProjectsTool` answers "what changed between these two
project payloads" but returns the full 3-section payload
(timeline + source + lockfile). The common UI path — "what edits did I
make to my timeline since the last save?" — only needs the timeline
section, and paying for source + lockfile on every call is wasted
tokens + an unused LLM affordance that competes with the one the caller
actually wants. The bullet explicitly asked for
`project_query(select=timeline_diff, fromSnapshot=..., toSnapshot=...)`
returning clips/tracks added/removed/changed lists. Rubric delta
§5.1 timeline-edit delta `部分 → 有` (the delta was reachable via
`diff_projects`'s `.timeline` field but not via the unified
`project_query` primitive).

**Decision.** Added `SELECT_TIMELINE_DIFF` to `ProjectQueryTool` with
two new `Input` fields: `fromSnapshotId: String?` and
`toSnapshotId: String?`. Null on either side = "current live state of
the project". At least one must reference a snapshot — diffing
current-vs-current is always identical and almost always a usage
error, so we fail loud rather than returning a trivially-empty diff.
The same-project constraint stays; cross-project (fork vs parent)
diffs remain on `diff_projects`.

Implementation lives in new
`core/tool/builtin/project/query/TimelineDiffQuery.kt` with:
- `TimelineDiffRow` — single compound row returned in `Output.rows`
  (fromLabel / toLabel / tracks[Added|Removed] / clips[Added|Removed
  |Changed] / identical / totalChanges). `identical` + `totalChanges`
  are exact; detail lists cap at 50 items so a wholesale rewrite can't
  blow the response token budget.
- Supporting types: `TimelineDiffTrackRef`, `TimelineDiffClipRef`,
  `TimelineDiffClipChange` (all top-level `@Serializable`, following
  the cycle-1/2 convention of rows-next-to-handler).
- Diff math mirroring `DiffProjectsTool.diffTimeline` / `.changedClipFields`.

Schema and `helpText` extended with a paragraph describing the new
select + explicit "diff_projects for cross-project / source /
lockfile" pointer so the LLM doesn't spuriously invoke both.
`ProjectQueryFilterGuard.kt` rejects `fromSnapshotId` /
`toSnapshotId` outside this select (mirroring every other
select-scoped filter field).

**Alternatives considered.**
- **Refactor `DiffProjectsTool` to share row types with the query
  select.** Would consolidate the diff math in one place. Rejected
  this cycle: `DiffProjectsTool.TimelineDiff` + its nested types are
  part of the `diff_projects` tool's public output surface — callers
  (including `DiffProjectsToolTest`) decode via
  `DiffProjectsTool.TimelineDiff.serializer()`. Moving them to
  top-level would have been the ProjectQueryTool-resplit
  (cycles 1-2) pattern all over again — clean but scope-doubling.
  Logged as a P2 debt bullet (`debt-unify-project-diff-math`) so the
  next cycle that already touches `DiffProjectsTool` can fold
  `TimelineDiffQuery`'s compute into the shared helper.
- **Fold the whole `diff_projects` into `project_query(select=diff)`
  with a `sections: List<String>` filter.** Would remove the
  `diff_projects` tool entirely. Rejected: would break every
  caller that decodes `DiffProjectsTool.Output.source` /
  `.lockfile` today, and § 3a-1 discourages tool-level churn without a
  clear driver. The current two-tool surface is acceptable — just
  specialize the common "timeline-only" case into a query select.
- **Accept cross-project diffs on this select** (via a `toProjectId`
  field). Rejected: same-project scoping matches the UI flow the
  bullet describes ("two snapshots of this project"); cross-project
  flows belong on the full `diff_projects` whose semantics already
  handle the fork-vs-parent case. Widening here would leak one
  tool's scope into the other and create a confusing "which of the
  two do I call" decision.
- **Return multiple rows** (one per changed clip / track) rather
  than one compound row. Rejected: same reason `dag_summary` returns
  a single row — the diff has a fixed aggregate shape, and `rows`
  is the only output surface on `Output`. One row with nested lists
  matches the existing `select=project_metadata` /
  `select=consistency_propagation` single-compound-row precedent.

**Coverage.** New `ProjectQueryTimelineDiffTest` covers 8 cases:
clip added (snapshot vs current), clip removed + asset-swap detected
as changedFields, track added, snapshot-vs-snapshot (both named),
both-nulls fails loud, unknown snapshot id fails loud, filter
rejected outside this select, identical snapshot-vs-same-snapshot
reports zero changes. `:core:jvmTest`, `:core:compileKotlinIosSimulatorArm64`,
`:apps:android:assembleDebug`, `:apps:desktop:assemble`, `ktlintCheck`
all green. `diff_projects` unchanged — its existing test suite
continues to pass.

**Registration.** None — new select on the existing `project_query`
tool, which is already registered in all 5 `AppContainer`s.
