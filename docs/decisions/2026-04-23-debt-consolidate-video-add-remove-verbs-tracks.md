## 2026-04-23 — Consolidate `AddTrackTool` + `RemoveTrackTool` into `TrackActionTool(action=add|remove)` (VISION §5.7 rubric axis — tool-spec surface)

**Context.** P2 backlog bullet `debt-consolidate-video-add-remove-verbs-tracks`
— the unfinished track-half of cycle-19's transition-consolidation
decision (`2026-04-23-debt-consolidate-video-add-remove-verbs.md`).
`core/tool/builtin/video/` had two separately-registered tools —
`AddTrackTool` (151 lines, `timeline.write` tier) and
`RemoveTrackTool` (156 lines, `project.destructive` tier) — that
operate on the same entity (timeline tracks, by id) with identical
`ctx.resolveProjectId(input.projectId)` plumbing and the same
timeline-snapshot emission pattern. Two LLM tool-spec entries for
one conceptual lifecycle verb pair cost ~400 tokens of redundant
scaffolding every turn.

Rubric delta §5.7: tool-spec surface shrinks by 1 entry (88 → 87
tools after this cycle; cycle-24 budget-ratchet baseline was 88 /
24_384 tokens). Sixth consolidation in the cycle-19 → 20 → 21 →
22 → 23 → **this cycle** pattern chain. Same mixed-tier shape as
cycle-23's `SessionActionTool` (one destructive + one non-
destructive action) — both tools land on `PermissionSpec.permissionFrom`
with the lower tier as base and the destructive action as the
upgrade branch.

**Decision.** New
`core/src/commonMain/kotlin/io/talevia/core/tool/builtin/video/TrackActionTool.kt`
exposes id `track_action` with two actions:

- `action="add"` — unchanged behaviour from the old `AddTrackTool`.
  Required `trackKind` (`video` / `audio` / `subtitle` / `effect`,
  case-insensitive); optional `trackId` (defaults to a fresh UUID,
  fails if the id already exists). Emits a timeline snapshot.
  Permission `timeline.write`.
- `action="remove"` — unchanged behaviour from the old
  `RemoveTrackTool`. Required `trackIds: List<String>`; optional
  `force: Boolean = false`. Non-empty tracks require `force=true`;
  otherwise the whole batch aborts with the offending clip count.
  Atomic; one timeline snapshot per call. Permission
  `project.destructive`.

Unified `Output` carries shared headers (`projectId`, `action`,
`snapshotId`) plus optional fields populated only on their branch:
`trackId` + `trackKind` + `totalTrackCount` (add); `results` +
`forced` (remove). Each optional field defaults to an empty /
zero-valued sentinel so the JSON shape is forward-compatible if
either action later grows a new field.

Permission tier split via cycle-21's extension:

```kotlin
override val permission = PermissionSpec(
    permission = "timeline.write",
    permissionFrom = { inputJson ->
        if (isRemoveAction(inputJson)) "project.destructive" else "timeline.write"
    },
)
```

Base tier `timeline.write` (the safer, more-common branch); regex-
match on `"action":"remove"` in the raw input JSON upgrades to
`project.destructive` for remove. Same safer-default discipline as
`SessionActionTool` — malformed / unknown input falls through to
`timeline.write`, which is never actually exercised because
`execute()`'s `when { … else -> error(…) }` rejects unknown actions
before touching the store. Biasing the safer tier keeps the high-
frequency add path free of ASK friction.

`applicability = RequiresProjectBinding` preserved from
`AddTrackTool` — both actions resolve the project via
`ctx.resolveProjectId` and fail loud on an unbound session.
`RemoveTrackTool` formerly used the default applicability; the
default's behaviour-at-unbound-session is "dispatch anyway", which
is strictly weaker than `RequiresProjectBinding` ("fail loud at
dispatch"). The consolidation upgrades remove to the stricter
applicability — defensible since the downstream `resolveProjectId`
call would fail the same way, just one step later with a less
direct error.

Deleted:
- `core/src/commonMain/.../AddTrackTool.kt` (151 lines).
- `core/src/commonMain/.../RemoveTrackTool.kt` (156 lines).

Test files renamed + rewritten in place (mirroring cycle-22 /
cycle-23's "2 test files per consolidation" shape):
- `AddTrackToolTest.kt` → `TrackActionToolAddTest.kt` (all 9 original
  tests + 2 new guards: `rejects_add_without_trackKind` covers the
  §3a #9 counter-intuitive edge where the consolidated Input has
  `trackKind: String? = null` but `executeAdd` still requires it;
  `rejects_unknown_action` covers the shared dispatch-path error
  for typos).
- `RemoveTrackToolTest.kt` → `TrackActionToolRemoveTest.kt` (all 10
  original tests; no new ones — the remove semantics are already
  well-covered).

4 AppContainers re-registered (CLI / Desktop / Server / Android) —
two `register(...)` lines collapse to one per container, two import
lines collapse to one. iOS doesn't register tools directly (the
`core/src/iosMain/IosBridges.kt` builds provider registry only; the
SwiftUI app consumes the KMP framework and registers providers, not
tools), consistent with every prior consolidation's 4-container
scope.

**Axis.** Number of track-lifecycle tool classes in
`core/tool/builtin/video/`. Before: 2 separate classes + 2 AppContainer
registrations × 4 containers. After: 1 action-dispatched class + 1
registration × 4 containers. Same pressure source as cycles 19-23: a
future refactor that adds a new track lifecycle verb (rename, reorder
— already separate tools today — merge, split) should extend this
file's `when` block, not create a new `*Tool.kt`. Adding a third
action is a 5-line change (extra `when` branch + optional Output
fields + helpText bullet + schema `enum` entry).

**Alternatives considered.**

- **Uniform `project.destructive` for both actions.** Simpler (no
  `permissionFrom` plumbing). Rejected: elevates add from
  `timeline.write` → `project.destructive`, flipping ALLOW → ASK
  for an everyday track-layout declaration. Friction regression;
  exactly the scenario cycle-23 explicitly rejected for
  `SessionActionTool`.

- **Uniform `timeline.write` for both actions.** Also simpler.
  Rejected: demotes remove from `project.destructive` →
  `timeline.write`, flipping ASK → ALLOW for irreversible track
  deletion (especially with `force=true` which silently discards
  every clip on the track). Security / UX regression.

- **Fold `DuplicateTrackTool` + `ReorderTracksTool` into the same
  `TrackActionTool` this cycle.** Would drop two more tools (88 →
  85). Rejected this cycle: `DuplicateTrackTool` has additional
  semantics (new-track-id generation rules, clip copy vs reference
  decision, source-binding cascade) not uniform with add/remove;
  `ReorderTracksTool` takes a permutation rather than a single id.
  Both are candidates for a future consolidation cycle (with their
  own decision — potentially a `track_query` + `track_action(add
  | remove | reorder | duplicate)` pair), but mixing them into
  add/remove now muddles the shape for diminishing returns. The
  bullet specifically scoped add/remove and that's what this
  cycle delivers.

- **Use an enum for `action` instead of a string.** Kotlin-idiomatic
  but existing tools (`transition_action`, `filter_action`,
  `project_snapshot_action`, `project_maintenance_action`,
  `session_action`) all use string-with-enum-in-schema for the
  `action` field. Kept the convention for cross-tool consistency
  (cycle-23 made the same call).

- **Leave the two tools separate.** Saves the refactor churn.
  Rejected: the bullet is explicitly queued from cycle 19's
  decision as "not done yet"; consolidation is the pattern this
  corner of the codebase has been converging on for 5 consecutive
  cycles.

**Coverage.** `:core:jvmTest` green (both new test classes), plus
`:apps:cli:test` + `:apps:server:test` + `:apps:desktop:assemble` +
`:core:compileKotlinIosSimulatorArm64` +
`:apps:android:assembleDebug` all pass. ktlintFormat +
ktlintCheck clean across all modules. Behaviour preservation:
every assertion from the original two test files survives verbatim
into the new files, with the outputs rebuilt to match the
consolidated `Output` shape (`results` field name identical to
pre-consolidation; `forced` field preserved; `trackId` / `trackKind`
/ `totalTrackCount` fields preserved from add path). Two new tests
cover consolidation-specific shape concerns (add requires
`trackKind`; unknown action dispatch fails loud).

**Registration.** 4 AppContainers updated (CLI / Desktop / Server /
Android). Each container's two `import … AddTrackTool / RemoveTrackTool`
lines collapse to one `import … TrackActionTool`; two
`register(…)` lines collapse to one. No UI call-site changes
(neither tool was dispatched directly from Desktop UI — the Desktop
track controls go through `add_clip` / `remove_clips` broadcast,
and explicit track-level buttons don't exist). No SQLDelight schema
change, no BusEvent change, no Provider change. Pure tool-surface
consolidation.
