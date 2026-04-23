## 2026-04-23 — Consolidate Prune + GcLockfile + GcClipRenderCache into `ProjectMaintenanceActionTool` (VISION §5.7 rubric axis)

**Context.** `core/tool/builtin/project/` houses three "maintenance
sweep" tools: `prune_lockfile` (orphan sweep, no policy),
`gc_lockfile` (policy-based retention with pin + live-asset
guards), and `gc_clip_render_cache` (policy-based mezzanine-cache
GC + on-disk file deletion). All three share the "sweep project
state, optionally preview" shape; all three take `projectId` +
`dryRun`; all three run at `project.write`. Three separate LLM
tool-spec entries cost ~600 tokens of redundant scaffolding every
turn.

Rubric delta §5.7: tool-spec surface area shrinks by 2 entries
(102 → 100 tools). Fourth consolidation in the cycle-19 →
transition / cycle-20 filter / cycle-21 snapshot / **cycle-22
maintenance** pattern chain.

**Decision.** New
`core/src/commonMain/kotlin/io/talevia/core/tool/builtin/project/ProjectMaintenanceActionTool.kt`
exposes id `project_maintenance_action` with three actions:

- `action="prune-lockfile"` — unchanged orphan-sweep behaviour from
  the old `PruneLockfileTool`. Fields: `projectId`, `dryRun`.
  Output populates `prunedOrphanLockfileRows` only.
- `action="gc-lockfile"` — unchanged policy-based GC from
  `GcLockfileTool`, including pinGuard + liveAssetGuard logic.
  Fields: `projectId`, `dryRun`, `maxAgeDays?`,
  `keepLatestPerTool?`, `preserveLiveAssets`. Output populates
  `prunedGcLockfileRows` + `keptByLiveAssetGuardCount` +
  `keptByPinCount` + `policiesApplied`.
- `action="gc-render-cache"` — unchanged mezzanine-cache GC from
  `GcClipRenderCacheTool`, including `VideoEngine.deleteMezzanine`
  calls. Fields: `projectId`, `dryRun`, `maxAgeDays?`, `keepLastN?`.
  Output populates `prunedRenderCacheRows` + `policiesApplied`.

Unified `Output` carries shared headers (`totalEntries`,
`prunedCount`, `keptCount`, `dryRun`, `policiesApplied`) plus one
typed sub-list per action (`prunedOrphanLockfileRows` /
`prunedGcLockfileRows` / `prunedRenderCacheRows`). Unused sub-lists
stay empty, matching the cycle-19 / cycle-21 consolidation shape.
The two gc-lockfile-specific counters (`keptByLiveAssetGuardCount`,
`keptByPinCount`) default to zero on the other two actions.

Constructor signature: `ProjectMaintenanceActionTool(projects,
engine, clock)`. `engine` is only exercised on `gc-render-cache`
but must be passed always — this matches the existing
`GcClipRenderCacheTool` convention. Tests that only exercise
lockfile actions use a new
`core/src/jvmTest/.../NoopMaintenanceEngine.kt` shared object that
returns no-op for every `VideoEngine` method.

Permission stays uniformly `project.write` across all three
actions — no `PermissionSpec.permissionFrom` needed this cycle
because the original three tools all used that tier. (Cycle 21's
per-action permission extension continues to be available for
future consolidations that need it.)

Deleted:
- `core/src/commonMain/.../PruneLockfileTool.kt` (138 lines)
- `core/src/commonMain/.../GcLockfileTool.kt` (323 lines)
- `core/src/commonMain/.../GcClipRenderCacheTool.kt` (282 lines)

Test files renamed + rewritten in place via `sed` + targeted
follow-up edits (the three files stay separate, one per action,
since each has ~300-500 lines of its own carefully-crafted
fixtures):
- `PruneLockfileToolTest.kt` → `ProjectMaintenanceActionToolPruneTest.kt`
- `GcLockfileToolTest.kt` → `ProjectMaintenanceActionToolGcLockfileTest.kt`
- `GcClipRenderCacheToolTest.kt` → `ProjectMaintenanceActionToolGcRenderCacheTest.kt`

5 AppContainers re-registered (three `register(...)` lines
collapse to one per container), 4 doc-comment cross-references
updated (`Lockfile.kt`, `ClipRenderCache.kt`, `VideoEngine.kt`,
`SetLockfileEntryPinnedTool.kt`) so the kdoc hyperlinks continue
to resolve.

**Scope note: no UI call-site changes.** Unlike cycle-20 (filters)
and cycle-21 (snapshots), none of `prune_lockfile` / `gc_lockfile` /
`gc_clip_render_cache` were dispatched from Desktop UI buttons
(only the agent called them). That spared this cycle a 3–4 file
UI rewrite.

**Axis.** Number of maintenance-sweep tool classes in
`core/tool/builtin/project/`. Before: 3 separate classes. After:
1 action-dispatched class. Same pressure source as cycles 19–21:
a future refactor that splits a new `project_reindex` /
`project_vacuum` / etc. back into a standalone tool would re-trigger
this bullet. Adding a fourth action to the current file is a
5-line change (extra `when` branch + extra typed output sub-list +
extra helpText bullet) rather than a whole new `*Tool.kt` file.

**Alternatives considered.**

- **Only consolidate the two GC tools (gc-lockfile +
  gc-render-cache).** Both are "policy-based GC" with shared
  `maxAgeDays` + count axes. Net -1. Rejected: `prune_lockfile`
  is the same "project state maintenance" category and the bullet
  explicitly called out all three; splitting would require a
  separate follow-up for prune that would cost another 5-container
  pass. Doing all three at once is cheaper per tool.

- **Make `engine` optional (nullable) on the constructor and
  reject `gc-render-cache` at runtime when it's absent.** Would
  let lockfile-only callers skip the engine arg. Rejected:
  deferred-error-at-dispatch is worse than compile-time signature
  enforcement; the `NoopMaintenanceEngine` fixture for tests is 30
  lines and the 5 AppContainers always have an engine available.

- **Keep three output shapes via `sealed class Output` with three
  subtypes.** Cleanest type-theoretically. Rejected: kotlinx.serialization's
  `@JsonClassDiscriminator` + sealed-class Output would require
  every downstream consumer (the agent summariser, test
  assertions, JSON consumers) to decode-into-sealed and switch on
  the subtype — much heavier change vs. "one Output class with
  optional sub-lists populated per action", which matches the
  cycle-19 / cycle-21 shape. The optional-sub-lists shape is
  behaviour-preserving end-to-end.

- **Regex-discriminate permission tier via `permissionFrom`** (as
  cycle 21 did for the snapshot triple). Rejected: not needed
  here — all three original tools used `project.write`. The
  extension is ready for the next consolidation that does need it.

**Coverage.** `:core:jvmTest` green after fixing one sed-induced
test assertion (`"prune_lockfile" in out.outputForLlm` → `"prune-lockfile"`).
All three action test classes pass — 7 cases under prune, ~20 under
gc-lockfile, ~10 under gc-render-cache, all inherited from the
original test files.
`:apps:{cli,server}:test` + `:apps:desktop:assemble` + iOS
`:core:compileKotlinIosSimulatorArm64` + ktlintFormat + ktlintCheck
across all modules green.

**Registration.** 5 AppContainers updated (CLI / Desktop / Server
/ Android / iOS) — imports collapsed 3 → 1, registers collapsed
3 → 1. Four doc-comment refs updated across
`Lockfile.kt` / `ClipRenderCache.kt` / `VideoEngine.kt` /
`SetLockfileEntryPinnedTool.kt`. New `NoopMaintenanceEngine.kt`
test fixture.

Gotcha captured for the engineering notes: **BSD `sed` doesn't
honour `\b` word boundaries.** The first pass of my bulk test
rewrite tried `s/\.prunedEntries\b/…/g` and silently no-op'd on
every match. Only the compile errors surfaced the miss. Future
sed rewrites on this codebase should use `[^a-zA-Z_]` negative
classes instead of `\b`, or run through GNU sed when available.
