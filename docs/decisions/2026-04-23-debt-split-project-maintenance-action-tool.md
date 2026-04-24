## 2026-04-23 — Split `ProjectMaintenanceActionTool` 523 lines → 220 / 73 / 166 / 142 by handler (VISION §5.6 rubric axis)

**Context.** P1 bullet `debt-split-project-maintenance-action-tool`
from the cycle-31 repopulate. R.5 scan #4 flagged the file at 526
lines (actually 523 at measurement), just past the 500-line
long-file threshold. Shape: one `class` + three sibling
`private suspend fun execute*(pid, input): ToolResult<Output>`
handlers for the three actions (`prune-lockfile`, `gc-lockfile`,
`gc-render-cache`). The dispatcher's `when (input.action)` block
is 4 lines; everything else was per-action handler bodies
(~45 / ~135 / ~115 lines).

The file's growth axis is exactly "new maintenance action" — cycle 22
consolidated 3 separate tools into this one class, and each future
maintenance sweep (a proposed `gc-snapshots`, a hypothetical
`rebuild-lockfile-from-disk`) would land as another private suspend
fun + another Output sub-list field, pushing the file past 600 / 700
/ 800 lines. Splitting along that axis now — one file per handler —
means the fourth sweep adds one new file instead of extending the
existing one.

Rubric delta §5.6: long-file count at > 500 threshold drops from 1
(ProjectMaintenanceActionTool.kt 523) to 0. Next file in the top-10
is `core/domain/FileProjectStore.kt` at 479 lines — under the
threshold. No follow-up file-split bullet needed from this cycle.

**Decision.** Three new sibling files in
`core/src/commonMain/kotlin/io/talevia/core/tool/builtin/project/`:

- `PruneLockfileHandler.kt` (73 lines) — one top-level
  `internal suspend fun executePruneLockfile(projects, pid, input)`.
  No policy knobs; signature minimal.
- `GcLockfileHandler.kt` (166 lines) — one top-level
  `internal suspend fun executeGcLockfile(projects, pid, input, clock)`
  + the shared `internal const val MAINTENANCE_MILLIS_PER_DAY`
  (needed by both GC handlers; placed here as the first alphabetic
  consumer).
- `GcRenderCacheHandler.kt` (142 lines) — one top-level
  `internal suspend fun executeGcRenderCache(projects, engine, pid, input, clock)`.
  References `MAINTENANCE_MILLIS_PER_DAY` from the sibling file
  (same package, no import needed).

Main `ProjectMaintenanceActionTool.kt` drops from 523 to 220 lines.
What stays: `Input` + `Output` + three row types (`PrunedOrphanLockfileRow`,
`PrunedGcLockfileRow`, `PrunedRenderCacheRow`), helpText, inputSchema,
`execute(input, ctx)` dispatcher. What moves: the three handler
bodies + the `MILLIS_PER_DAY` constant.

`execute` now reads:

```kotlin
override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
    val pid = ProjectId(input.projectId)
    return when (input.action) {
        "prune-lockfile" -> executePruneLockfile(projects, pid, input)
        "gc-lockfile" -> executeGcLockfile(projects, pid, input, clock)
        "gc-render-cache" -> executeGcRenderCache(projects, engine, pid, input, clock)
        else -> error(
            "unknown action '${input.action}'; accepted: prune-lockfile, gc-lockfile, gc-render-cache",
        )
    }
}
```

Adding a fourth action is now: new `NewActionHandler.kt` file + new
`when` branch + new `Input` / `Output` fields (if the action needs
them) + new schema entry. No line-count pressure on the main file.

**Axis.** Number of distinct maintenance actions in the file. Before:
3 actions × all in one class = 523 lines. After: 3 actions × 3 handler
files + dispatcher class. Adding a 4th action pressures only the new
handler file it ships in; the main class takes minimal additions
(one enum branch + any new schema fields). The pressure source that
would re-trigger a split on the main class is growth in the unified
Output data class itself — if cross-action row types accumulate
enough that Output crosses 200+ lines, a separate
`ProjectMaintenanceActionOutput.kt` becomes the natural split (not
pursued this cycle: Output is 22 lines).

**Alternatives considered.**

- **Keep as-is; just add a trailing-split bullet for a future
  larger restructure.** Rejected: R.5 #4 long-file signal is
  specifically "> 500 lines → P1"; the threshold exists precisely
  to catch this moment before the file compounds. Kicking the can
  loses the R.5 signal's discipline.

- **Extract to an `internal object MaintenanceHandlers` with three
  member functions.** Similar shape, one file. Rejected: the
  "one file per action" pattern is load-bearing for the growth-
  axis argument. Object-with-members packs all three handlers
  into one file, and adding action #4 pressures that same object
  file — same growth axis as before, just moved.

- **Extract via an `interface MaintenanceHandler` + three
  implementing classes.** Type-system enforcement that every
  handler returns the same shape. Rejected: over-engineered for
  three handlers; the `when` dispatch in `execute` already
  provides the "every branch returns `ToolResult<Output>`" contract
  at compile time. Three free functions with uniform signatures
  are simpler and match what cycle 22's consolidation started
  from before the bodies accumulated.

- **Extract the three row types + Output into a sibling
  `ProjectMaintenanceActionShapes.kt`.** Would trim the main
  class further to ~150 lines (matches the bullet's hope).
  Rejected this cycle: Output lives with the tool by
  project-wide convention (every other `*Tool.kt` keeps its Output
  in the same file as the class). Breaking that convention for
  220-line files isn't cost-effective; if Output ever grows past
  200 lines itself, the shapes-file extraction is the natural
  follow-up split.

- **Put `MAINTENANCE_MILLIS_PER_DAY` in the main class's
  companion as `internal const val`.** Keeps the constant
  co-located with its conceptual owner (the tool). Rejected:
  Kotlin requires sibling handlers to fully-qualify
  `ProjectMaintenanceActionTool.MAINTENANCE_MILLIS_PER_DAY` from
  top-level functions, which is noisy. A sibling-file top-level
  `internal const val` is reachable unqualified across the
  package, which is the normal Kotlin idiom for package-local
  constants.

**Coverage.** Behaviour-preserving refactor — the three existing
test classes
(`core/src/jvmTest/kotlin/io/talevia/core/tool/builtin/project/ProjectMaintenanceActionToolPruneTest.kt`,
`*GcLockfileTest.kt`, `*GcRenderCacheTest.kt` per the cycle-22
decision's layout) continue to pass unchanged. They exercise
`ProjectMaintenanceActionTool.execute(input, ctx)` which now
delegates through the extracted top-level functions without any
observable behaviour difference. The `.apps:{cli,server}:test` +
`.core:jvmTest` + `.apps:desktop:assemble` +
`.core:compileKotlinIosSimulatorArm64` + `.apps:android:assembleDebug`
+ ktlintFormat + ktlintCheck all green.

**Registration.** None — no new tool id, no Input/Output shape
change, no permission-spec change, no AppContainer touch. The
registration call sites (`register(ProjectMaintenanceActionTool(projects, engine))`
across CLI / Desktop / Server / Android) continue to work
unchanged since the constructor signature is preserved.
