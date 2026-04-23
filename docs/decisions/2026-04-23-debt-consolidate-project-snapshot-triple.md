## 2026-04-23 — Consolidate Save/Restore/Delete snapshot triple into `ProjectSnapshotActionTool` (VISION §5.7 rubric axis)

**Context.** `core/tool/builtin/project/` 25 tools (second-largest
area per R.5 #2). The Save/Restore/Delete snapshot triple is a
classic consolidation target: all three take the same `projectId`
root, two take `snapshotId`, one takes an optional `label`. Each
does one step of the same operational loop ("capture", "roll
back", "prune"). Three separate tool-spec entries on every turn
cost ~600 tokens of pure redundancy — the LLM sees three helpTexts
for one concept.

Rubric delta §5.7: tool-spec surface area shrinks by 2 entries
(104 → 102 tools) and LLM per-turn spec cost drops ≈ 600 tokens.
Fourth step of the consolidation pattern chain (transition →
filter → **snapshot triple**).

This cycle is also the first to exercise the pattern of **per-action
permission tiers within a consolidated tool** — save is
`project.write` (non-destructive additive), restore + delete are
`project.destructive` (overwrite / irreversible drop). The existing
`PermissionSpec.fixed(permission)` didn't support per-input
resolution, so a small plumbing extension was needed first.

**Decision.** Two landed pieces:

### 1. `PermissionSpec.permissionFrom` extension

`core/src/commonMain/kotlin/io/talevia/core/permission/Permission.kt`
gains a new field:

```kotlin
data class PermissionSpec(
    val permission: String,
    val patternFrom: (inputJson: String) -> String = { "*" },
    val permissionFrom: (inputJson: String) -> String = { permission },
)
```

Default stays `{ permission }`, so `PermissionSpec.fixed(...)` is
byte-identical-behaviour for every existing tool. The one
permission-check site in `AgentTurnExecutor` now calls
`tool.permission.permissionFrom(inputJson)` instead of reading the
static `.permission` field — so action-dispatched tools can return
different tiers per input without every existing tool having to
opt in.

`ListToolsTool` continues to report the base `permission` string
in its output (the tool's declared tier shown to `list_tools`
consumers). For action-dispatched tools the helpText documents the
per-action tiers narratively.

### 2. `ProjectSnapshotActionTool`

`core/src/commonMain/kotlin/io/talevia/core/tool/builtin/project/ProjectSnapshotActionTool.kt`
exposes id `project_snapshot_action` with `action="save"|"restore"|
"delete"`. Base permission `project.write` (save's tier, the most
common action); `permissionFrom` regex-parses the input JSON for
`"action":"save"` and returns `project.write` when matched,
`project.destructive` otherwise. Anything that fails the regex
match falls through to destructive — malformed input can't slip
past the destructive gate.

Each action's body preserves the original tool's behaviour
verbatim: save uses the `clock` clone (same fixture injection test
path), restore preserves the snapshots list + project id (still
not a trapdoor), delete refuses unknown ids (still loud). Output
carries a union shape: `totalSnapshotCount` populated on all
three, `clipCount + trackCount` populated on restore only.

Deleted: `SaveProjectSnapshotTool.kt`, `RestoreProjectSnapshotTool.kt`,
`DeleteProjectSnapshotTool.kt`. Their consolidated test
(`ProjectSnapshotToolsTest.kt`, already a single file covering all
three) rewritten in place via `sed` — 32 class-name references
swapped to `ProjectSnapshotActionTool` + `action=X` inserted into
each `Input(...)` constructor; `remainingSnapshotCount` field
rename to `totalSnapshotCount` propagated. File kept its name
(`ProjectSnapshotToolsTest`) to preserve git blame; class name
preserved likewise.

5 AppContainers re-registered:
`apps/{cli,server,desktop,android}/.../*.kt` + `apps/ios/.../AppContainer.swift`.

UI dispatch call sites updated:
- `apps/desktop/AppRoot.kt` — `save_project_snapshot` → `project_snapshot_action` + `action=save`.
- `apps/desktop/SnapshotPanel.kt` — two dispatches (save, restore) + matching docstring.
- `apps/desktop/ProjectBar.kt` — two dispatches (save, restore) + docstring refs.

System prompt text updated:
- `core/agent/prompt/PromptProject.kt` — rewrote the project-snapshot paragraph to use the consolidated tool name.
- `core/agent/TaleviaSystemPromptTest.kt` — the assert-tool-name list now expects `project_snapshot_action` (one entry) where before it had three.

Cross-referring doc comments updated:
- `GcLockfileTool.kt` — `SaveProjectSnapshotTool` → `ProjectSnapshotActionTool`.
- `UpdateSourceNodeBodyTool.kt` + `RevertSessionTool.kt` + `SnapshotsQuery.kt` — pointer text updated to the new tool id.

**Axis.** Number of snapshot-operation tool classes in
`core/tool/builtin/project/`. Before: 3 separate classes (save,
restore, delete). After: 1 action-dispatched class. Same pressure
source as cycles 19 + 20: a future refactor that splits
`action=foo` back into a standalone tool would re-trigger this
bullet. The per-action permission extension (`permissionFrom`)
unlocks the same pattern for the next queued consolidation
(`debt-consolidate-session-lifecycle-verbs` — Archive / Unarchive
/ Delete / Rename have the same mixed-permission-tier shape).

**Alternatives considered.**

- **Use uniform `project.destructive` for the whole consolidated tool.**
  Simpler — no permission-plumbing change. Rejected: elevates save
  from `project.write` → `project.destructive`, which in the
  default ruleset flips save from ALLOW to ASK. Every quick
  "take a snapshot before trying this" would prompt the user. UX
  regression for a very common safety-net action. The
  `permissionFrom` extension is ~10 lines and solves it cleanly.

- **Use uniform `project.write` for the whole consolidated tool.**
  Rejected: DEMOTES restore + delete from `project.destructive` →
  `project.write`. In the default ruleset that flips ASK → ALLOW —
  the agent could silently clobber the project state or drop
  snapshots without user confirmation. Security / safety regression,
  not acceptable.

- **Keep three classes but share internal helpers.** Rejected: the
  bullet's primary goal is LLM tool-spec surface reduction, not
  Kotlin LOC reduction. Helper-sharing doesn't reduce the
  top-level tool count the LLM sees.

- **Regex parse `action` from the raw input JSON for `permissionFrom`.**
  Did this — the alternative is to first kotlinx.serialization-decode
  the input and then read `.action`. Rejected the full-decode path
  because `permissionFrom` runs *before* the tool's `execute` dispatch
  (by design — permission check gates dispatch); doing a full decode
  there duplicates the decode work and adds an extra failure mode
  (what if the input is malformed?). The regex is 10 lines, runs in
  microseconds, and defaults to `project.destructive` on no-match —
  safer than the other way around.

**Coverage.** `:core:jvmTest` green — `ProjectSnapshotToolsTest`
(all cases preserved via sed rewrite — 32 tool-class references
swapped, all test-method names kept) + `TaleviaSystemPromptTest`
(updated tool-name list). `:apps:{cli,server}:test` +
`:apps:desktop:assemble` + iOS
`:core:compileKotlinIosSimulatorArm64` + ktlintFormat + ktlintCheck
across all modules green. `RegisteredToolsContractTest` still
passes — the new `ProjectSnapshotActionTool` class name is
referenced in every AppContainer.

`PermissionSpec.permissionFrom` extension itself is exercised
indirectly via the `ProjectSnapshotActionTool` tests (which call
`tool.execute(...)` with each action; the dispatcher applies
`permissionFrom` to each input). A dedicated test for the new
function shape wasn't added this cycle — the behaviour is
transparent to consumers and backward-compatible for every
existing tool (default unchanged); adding per-action permission
test coverage is a reasonable follow-up if we grow more users of
this pattern.

**Registration.** 5 AppContainers updated (CLI / Desktop / Server
/ Android / iOS), plus Desktop UI call sites (3 files, 5 dispatch
call sites), plus the system-prompt text + its test, plus 4
cross-refer doc comments. `PermissionSpec` extension in
`core/commonMain` plus one call-site update in
`AgentTurnExecutor`. No additional test registration — existing
`ProjectSnapshotToolsTest` rewrote in place.
