## 2026-04-23 — Consolidate filter apply/remove into `FilterActionTool` (VISION §5.7 rubric axis)

**Context.** Continuing the `core/tool/builtin/video/` consolidation
pattern established by cycle 19's transition consolidation. This
bullet targeted three asymmetric apply/remove clusters —
`ApplyFilter + RemoveFilter` (a complete pair), `ApplyLut` (no
`RemoveLut`), and `AddSubtitles` (no `RemoveSubtitles`). The bullet
offered two directions: (a) pad missing Remove companions then
consolidate, or (b) unify into `clip_effect(op=apply|remove,
kind=filter|lut|subtitle, …)`.

Rubric delta §5.7: tool-spec surface area shrinks by one entry
(105 → 104 tools) and LLM per-turn spec cost drops ≈ 300 tokens.
Second step of the consolidation pattern for the queued
`debt-consolidate-*` bullets.

**Decision.** Scope-cut to the filter half only — consolidate
`ApplyFilter` + `RemoveFilter` into a single
`FilterActionTool(action="apply"|"remove")`. LUT and subtitle
handling intentionally out of scope (rationale in Alternatives
below).

- New `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/video/FilterActionTool.kt`
  (id `filter_action`). `action="apply"` path preserves the
  original `ApplyFilterTool` semantics verbatim: exactly one of
  `clipIds` / `trackId` / `allVideoClips` selector, `params` map,
  non-video clips skipped silently with structured `skipped`
  entries. `action="remove"` preserves `RemoveFilterTool` exactly:
  required `clipIds` (no `trackId`/`allVideoClips` shortcut),
  idempotent per clip, unresolvable / non-video clipIds abort the
  whole batch. Both emit exactly one `Part.TimelineSnapshot`.
- Unified `Output` carries both branches' shapes: `appliedClipIds
  + skipped` populated on apply, `removed + totalRemoved`
  populated on remove. Unused branch is empty-list rather than
  nullable, matching the cycle-19 `TransitionActionTool` shape.
- Deleted: `ApplyFilterTool.kt`, `RemoveFilterTool.kt`,
  `ApplyFilterToolTest.kt`, `RemoveFilterToolTest.kt`.
- New consolidated test `FilterActionToolTest.kt` folds in all
  meaningful cases from both deleted test files (6 apply cases +
  10 remove cases + 1 unknown-action). Test-method names
  preserved where possible so regressions still flag by the same
  name.
- 5-container re-registration: CLI / Desktop / Server / Android /
  iOS — all swap `register(ApplyFilterTool(store)) +
  register(RemoveFilterTool(store))` for
  `register(FilterActionTool(store))`.
- `apps/desktop/.../TimelinePanel.kt` has two UI call sites that
  dispatch `"apply_filter"` directly (Compose button handlers) —
  updated to `"filter_action"` with `"action": "apply"` and, in
  one case, also fixed a **latent bug**: the single-clip dispatch
  passed `"clipId"` (singular string) which was not in the tool's
  schema, so kotlinx.serialization's `ignoreUnknownKeys=true`
  silently dropped the field and the tool rejected the call with
  "exactly one of clipIds / trackId / allVideoClips". Now passes
  `"clipIds"` as a 1-element array. This is a drive-by fix —
  small enough to include without muddying the consolidation
  commit, and within the same file the consolidation already
  touches.
- Test-side callers updated: `M6FeaturesTest` (3 refs),
  `RevertTimelineTest` (1 ref), `SessionRevertTest` (2 refs +
  doc comment + inline comment).

**Scope-cut rationale.** The bullet's "or":
- Padding `RemoveLut` / `RemoveSubtitles` as new standalone tools
  to enable later consolidation would net +2 tools *now* for -3
  tools *later*. That violates §3a #1 (tool count net negative)
  unless the follow-up lands immediately. Two-cycle coupling is
  possible but higher-risk than landing the clearly-scoped
  filter consolidation first.
- Unifying into one `clip_effect(kind=filter|lut|subtitle)`
  muddles three genuinely different operations:
  `ApplyFilter` attaches a `Filter` to `clip.filters`;
  `ApplyLut` resolves a `styleBibleId` via source-DAG, stamps
  `clip.sourceBinding` for stale propagation, and only then
  attaches a `Filter(name="lut", assetId=…)`; `AddSubtitles`
  creates new `Clip.Text` clips on a subtitle track (no existing
  clip required). Collapsing them into one tool would require a
  union input schema with 8+ optional fields and action-and-kind
  gated requirements — a worse LLM tool-spec cost than what the
  three current tools combined incur.

The `ApplyLut` + `AddSubtitles` halves are added as P2 follow-up
bullets (`debt-apply-lut-remove-pad` + `debt-subtitle-add-remove-pad`)
in the same repopulate batch — each addresses one asymmetric pair
under the "pad then consolidate, or leave asymmetric if Remove
has no real use case" design question, which is its own design
cycle.

**Axis.** Count of apply/remove tool-class pairs in
`core/tool/builtin/video/`. Before this cycle: 2 (transition
pair + filter pair + asymmetric LUT/subtitle). After cycle 19 +
this cycle: 0 complete pairs remain as separate classes. The
pressure source for re-triggering this consolidation axis is
"someone adds a new `AddX` tool and a matching `RemoveX` tool
instead of `XActionTool`" — the `TransitionActionTool` and
`FilterActionTool` precedents now serve as the naming template.

**Alternatives considered.**

- **Broaden `FilterActionTool` to also include LUT (kind=filter|lut).**
  Rejected as detailed above — `ApplyLutTool` has
  `styleBibleId` → source-DAG resolution + `sourceBinding` side
  effects that don't fit a generic filter path. Landing it as a
  separate cycle keeps the action-dispatch pattern clean.

- **Broaden the `action="remove"` path to accept `trackId` /
  `allVideoClips` selectors like apply does.** Would make the
  two branches symmetric and arguably more powerful (agent could
  "remove blur from every clip on track X"). Rejected for this
  cycle: the bullet direction was "consolidate apply/remove",
  behaviour-preserving. Extending remove's selector semantics is
  a separate design question — does `removeMatchingFiltersOnEveryClipOfTrack`
  abort if any clip on the track is non-video? Does it skip them
  silently like apply? Worth doing but worth its own decision.

- **Keep two classes and share an internal helper.** Same trade-off
  as cycle 19's `TransitionActionTool` — saves Kotlin LOC but
  doesn't reduce LLM tool-spec surface. The bullet is about
  tool-spec surface reduction specifically.

**Coverage.** `:core:jvmTest` green — 17 cases in
`FilterActionToolTest` (6 apply + 10 remove + 1 unknown-action),
plus `M6FeaturesTest.applyFilterAddsFilterToVideoClip` /
`RevertTimelineTest` / `SessionRevertTest` all updated and green.
`:apps:cli:test` + `:apps:server:test` + `:apps:desktop:assemble`
+ iOS `:core:compileKotlinIosSimulatorArm64` all green.
ktlintFormat + ktlintCheck across all modules green after one
format pass picked up an import re-ordering in
`apps/desktop/AppContainer.kt`.

**Registration.** 5 AppContainers updated (CLI / Server / Desktop
/ Android / iOS), plus UI-layer `TimelinePanel.kt` (2 dispatch
sites: batch-apply + single-clip apply; single-clip path picked
up the latent `"clipId"` → `"clipIds"` fix as a drive-by). Two P2
follow-up bullets appended to BACKLOG for the LUT / subtitle
asymmetric halves the bullet gestured at.
