## 2026-04-23 — Consolidate session lifecycle verbs into `SessionActionTool` (VISION §5.7 rubric axis)

**Context.** `core/tool/builtin/session/` housed 13 tools before
this cycle. Four of them — `archive_session`, `unarchive_session`,
`rename_session`, `delete_session` — are lifecycle verbs that
operate on `Session.title` / `Session.archived` / row-delete.
Identical `sessionId` plumbing, three reuse `ToolContext.resolveSessionId`
for the optional default, delete requires an explicit id (can't
self-destruct mid-dispatch). Three tools use `session.write`
permission; `delete_session` uses `session.destructive`. Four
separate LLM tool-spec entries for one conceptual lifecycle cluster
cost ≈ 900 tokens of redundant scaffolding every turn.

Rubric delta §5.7: tool-spec surface area shrinks by 3 entries
(100 → 97 tools). Fifth consolidation in the cycle-19 → 20 → 21 →
22 → **23** pattern chain; the first to use all four action
branches on a single mixed-tier tool, exercising cycle 21's
`PermissionSpec.permissionFrom` extension in earnest.

**Decision.** New
`core/src/commonMain/kotlin/io/talevia/core/tool/builtin/session/SessionActionTool.kt`
exposes id `session_action` with four actions:

- `action="archive"` — unchanged behaviour from the old
  `ArchiveSessionTool`. `sessionId` optional (defaults to
  `ToolContext.sessionId`). Idempotent. Permission `session.write`.
- `action="unarchive"` — unchanged from `UnarchiveSessionTool`.
  Permission `session.write`.
- `action="rename"` — unchanged from `RenameSessionTool`. Required
  non-blank `newTitle`. Same-title no-op. Permission `session.write`.
- `action="delete"` — unchanged from `DeleteSessionTool`. Required
  explicit `sessionId` (cannot default to the owning session — see
  safety note below). Permission `session.destructive`.

Unified `Output` carries shared headers (`sessionId`, `action`,
`title`) plus optional fields populated only on their branch:
`wasAlreadyInTargetState` (archive/unarchive idempotency marker,
replacing the previous per-tool `wasArchived` / `wasUnarchived`),
`previousTitle` + `newTitle` (rename), `archived` (delete snapshot).

Permission tier mismatch handled via cycle-21's extension:

```kotlin
override val permission = PermissionSpec(
    permission = "session.write",
    permissionFrom = { inputJson ->
        if (isDeleteAction(inputJson)) "session.destructive" else "session.write"
    },
)
```

The base tier is `session.write` (three of four actions); regex-
match on `"action":"delete"` in the raw input JSON upgrades to
`session.destructive` for delete. Malformed input defaults to the
LOWER (`session.write`) tier — safe here because `execute()`'s
validation rejects malformed input before touching the store, and
the lower tier doesn't bypass any destructive check (delete's
self-id-required guard is separate from permission).

Symmetry with cycle-21's snapshot tool: that tool defaults to the
HIGHER tier on unknown actions (three of four actions are
destructive there). Here it defaults to the lower because the
split is opposite (one destructive, three non-destructive). The
general rule: `permissionFrom` should bias toward the safer
outcome for the specific tool's action distribution; "safer" isn't
monotone in "higher tier".

Deleted:
- `core/src/commonMain/.../ArchiveSessionTool.kt` (182 lines — held
  both `ArchiveSessionTool` and `UnarchiveSessionTool` in one file)
- `core/src/commonMain/.../DeleteSessionTool.kt` (101 lines)
- `core/src/commonMain/.../RenameSessionTool.kt` (126 lines)

Test files renamed + rewritten in place via `sed`:
- `ArchiveSessionToolTest.kt` → `SessionActionToolArchiveTest.kt`
  (class name updated; handles both archive and unarchive paths
  since the old file did).
- `DeleteSessionToolTest.kt` → `SessionActionToolDeleteTest.kt`.
- `RenameSessionToolTest.kt` → `SessionActionToolRenameTest.kt`.

The 5-test-file/one-tool shape is unchanged from cycle 22's
maintenance consolidation — each test file keeps its existing
rig + assertions, only the class references + Input/Output
constructors change.

5 AppContainers re-registered (four `register(...)` lines collapse
to one per container). One doc-comment in
`SwitchProjectTool.kt` had a `[ArchiveSessionTool]` link; updated
to `[SessionActionTool]`.

**Safety note: delete requires explicit sessionId.** The old
`DeleteSessionTool` required `sessionId` because `ctx.resolveSessionId(null)`
would resolve to the currently-dispatching session — self-delete
mid-dispatch is a race against the store's message-persistence
writes (matching `RevertSessionTool`'s caveat). The consolidated
tool preserves this: `executeDelete` fails loud on null
`sessionId` rather than delegating to the context. The helpText +
input-schema description make this explicit.

**Axis.** Number of session-lifecycle tool classes in
`core/tool/builtin/session/`. Before: 4 separate classes. After:
1 action-dispatched class. Same pressure source as cycles 19–22:
a future refactor that adds a new "session pause" / "session
fork-and-delete" verb should extend this file's `when` block, not
create a new `*Tool.kt`. Adding a fifth action is a 5-line change
(extra `when` branch + optional Output field + helpText bullet +
schema `enum` entry).

**Alternatives considered.**

- **Uniform `session.write` for all four actions.** Simplifies the
  tool (no `permissionFrom` plumbing). Rejected: demotes delete
  from `session.destructive` → `session.write`, flipping the
  default permission ruleset behaviour from ASK → ALLOW for
  irreversible session deletion. Security / UX regression.

- **Uniform `session.destructive` for all four actions.** No tier
  split. Rejected: elevates archive / unarchive / rename from
  `session.write` → `session.destructive`, flipping ALLOW → ASK
  for three everyday operations. Friction regression.

- **Keep four classes; share an internal helper that takes an
  `action` enum.** Dedupe Kotlin LOC while preserving LLM tool
  surface. Rejected: the bullet's primary goal is LLM tool-spec
  reduction. Shared-helper approach misses the goal.

- **Use an enum for `action` instead of a string.** Kotlin-idiomatic
  but `kotlinx.serialization` surfaces enum values in JSON Schema
  regardless, and existing tools (`transition_action`,
  `filter_action`, `project_snapshot_action`, `project_maintenance_action`)
  all use string-with-enum-in-schema for the `action` field. Kept
  the convention for cross-tool consistency.

**Coverage.** `:core:jvmTest` green — `SessionActionToolArchiveTest`
(handles both archive + unarchive paths), `SessionActionToolDeleteTest`,
`SessionActionToolRenameTest` all pass after the sed rewrite (no
manual fixups needed this cycle — the identifiers renamed cleanly
because `permissionFrom` compile-errors would have caught any
stale type references, and the outputs' field renames
(`wasArchived`/`wasUnarchived` → `wasAlreadyInTargetState`) were
handled by the bulk sed rules).
`:apps:{cli,server}:test` + `:apps:desktop:assemble` + iOS
`:core:compileKotlinIosSimulatorArm64` + ktlintFormat + ktlintCheck
across all modules green.

**Registration.** 5 AppContainers updated (CLI / Desktop / Server
/ Android / iOS). Desktop import ordering needed one format pass
(same pattern as previous consolidations). One doc-comment ref
updated in `SwitchProjectTool.kt`. No UI call-site changes (none
of the four tools were dispatched from Desktop UI buttons —
session lifecycle in the Desktop session sidebar goes through
`ForkSessionTool` + `SessionTitler`, which weren't touched).
