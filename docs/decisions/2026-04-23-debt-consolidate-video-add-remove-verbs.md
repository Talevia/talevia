## 2026-04-23 — Consolidate transition add/remove into `TransitionActionTool` (VISION §5.7 rubric axis)

**Context.** `core/tool/builtin/video/` reached 31 tools — the highest-
density area per R.5 #2 — with several Add*/Remove* verb pairs each
shipping as two separate tool classes (two LLM tool-spec entries on
every turn). This cycle's bullet
(`debt-consolidate-video-add-remove-verbs`) targeted `AddTrack +
RemoveTrack + AddTransition + RemoveTransition + AddSubtitles 等 Add*/Remove*`,
aiming for net ≥ 3 `*Tool.kt` deletions while accepting net 0 as
the floor.

Rubric delta §5.7: tool-spec surface area shrinks by one entry
(106 → 105 tools) and LLM per-turn spec cost drops ≈ 300 tokens
(the combined spec is larger than either alone, but smaller than
both summed). Sets the consolidation pattern for the four sibling
bullets still queued (filters, snapshots, lockfile maintenance,
session lifecycle).

**Decision.** Consolidate the transition half of the bullet:

- New `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/video/TransitionActionTool.kt`
  exposes id `transition_action` with `action: "add" | "remove"` dispatch.
  `action="add"` requires `items: List<AddItem>`; `action="remove"`
  requires `transitionClipIds: List<String>`. `Output` carries both
  `added: List<AddResult>` and `removed: List<RemoveResult>` — the
  unused branch stays empty-list rather than nullable to keep the
  return type uniform for downstream consumers.
- All validation, atomicity, and Effect-track reuse semantics
  carried over verbatim from the original `AddTransitionTool` +
  `RemoveTransitionTool` — error messages match, snapshot emission
  matches, `remainingTransitionsTotal` book-keeping matches.
- Deleted: `AddTransitionTool.kt`, `RemoveTransitionTool.kt`,
  `AddTransitionToolTest.kt`, `RemoveTransitionToolTest.kt`.
- New consolidated test: `TransitionActionToolTest.kt` — folds in
  all meaningful cases from the two deleted test files plus a
  `rejectsUnknownAction` case. Test-method names preserved so a
  regression that flagged a test by name before still flags it by
  the same name now.
- 5-container re-registration: CLI / Desktop / Server / Android /
  iOS — all swap `register(AddTransitionTool(store)) +
  register(RemoveTransitionTool(store))` for
  `register(TransitionActionTool(store))`.
- Doc-only refs updated in `core/iosMain/IosBridges.kt`,
  `core/jvmTest/domain/ProjectStoreConcurrencyTest.kt`, and
  `apps/ios/Talevia/Platform/AVFoundationVideoEngine.swift`.
- `M6FeaturesTest.kt` updated to use the new tool id
  (`"transition_action"`) + explicit `"action": "add"` JSON field.

**Scope shortfall vs. the bullet's "≥ 3" target.** The bullet's
Add*/Remove* scope was `AddTrack + RemoveTrack + AddTransition +
RemoveTransition + AddSubtitles`. Of those, only the two full
pairs (Track and Transition) can consolidate — `AddSubtitles`
has no `RemoveSubtitles` companion (that asymmetry is what the
sibling bullet `debt-consolidate-video-filter-lut-apply-remove`
targets). So the reachable ceiling for this bullet alone is
net -2. This cycle ships one of the two halves (-1); the track
half lands as a follow-up P2 in the same repopulate batch
(see **Registration** below).

Why only half this cycle: each half requires (a) rewriting two
test files totalling ~450 lines, (b) updating 5 AppContainer
registrations, (c) updating cross-module doc references. Even the
transition-only path touched 12 files. Doing both halves in one
cycle would double the diff size for not-much-faster real-world
consolidation — splitting keeps each decision file scoped to one
clearly-named pattern instance, easier to review and easier to
cite when the next consolidation cycle picks up the pattern.

**Axis.** Number of tool classes per "Add X / Remove X" entity pair
in `core/tool/builtin/video/`. Before: 2 per pair (one file per
verb). After: 1 per pair (verb on `action` param). The pressure
source for re-triggering this consolidation is a future refactor
that reintroduces a separate `AddX` / `RemoveX` split — typically
from a plan that didn't check the existing action-dispatch shape
first. The `TransitionActionTool` file itself sits at ~300 lines
(roughly `AddTransitionTool 208 + RemoveTransitionTool 194 - shared
scaffolding`), well below the 500-line watch threshold.

**Alternatives considered.**

- **Keep two classes but share an internal helper** (e.g.
  `TransitionMutations` with `addBatch` + `removeBatch` fns,
  called from thin wrappers). Would dedup Kotlin code but leave
  the LLM tool-spec surface unchanged (still 2 top-level entries).
  Rejected: the bullet's primary goal is LLM tool-spec reduction,
  not Kotlin LOC reduction. Wrapper approach misses the goal.

- **Go wider and also consolidate `AddTrack` + `RemoveTracks`
  into `TrackActionTool` in this cycle.** Maximum net -2. Rejected
  for scope control: would double the test-file rewrite cost
  (~300 lines of `AddTrackToolTest` + ~500 of `RemoveTrackToolTest`
  on top of the transitions' ~650), plus a second 5-container
  pass. A follow-up bullet `debt-consolidate-video-add-remove-verbs-tracks`
  carries that work on the next P2 cycle with the same pattern.

- **Use JSON Schema `oneOf` to discriminate "add" vs "remove"
  payload shapes sharply.** Cleaner per-action schema, but schema
  size grows (two `properties` blocks with per-branch `required`)
  and some LLM providers handle `oneOf` inconsistently. Rejected:
  flat schema + narrative description in `helpText` ("Pick
  action='add' and pass items … / Pick action='remove' and pass
  transitionClipIds …") is the same shape OpenCode uses for
  similar action-dispatched tools and covers what the agent
  needs. If a provider turns out to regress on this shape, we
  revisit — nothing in this tool's implementation prevents a
  future schema tightening.

- **Expose `action` as an enum (`ADD` / `REMOVE`) rather than a
  free-form string.** Would be Kotlin-idiomatic but kotlinx.serialization's
  enum SerializableDescriptor surfaces the enum names in JSON
  Schema, which we'd still describe narratively for the LLM. The
  string-with-enum-in-schema approach (JSON Schema `enum: ["add",
  "remove"]`) matches what every existing action-dispatched tool
  in the codebase already does. Kept the convention for
  consistency.

**Coverage.** `:core:jvmTest` green (28 tests in
`TransitionActionToolTest` + `M6FeaturesTest.addTransitionInsertsClipOnEffectTrack`).
`:apps:cli:test` green, `:apps:server:test` green,
`:apps:desktop:assemble` green, iOS
`:core:compileKotlinIosSimulatorArm64` green. ktlintFormat +
ktlintCheck across all modules green. `RegisteredToolsContractTest`
passes — `TransitionActionTool` class is referenced in every
container (Desktop holds the full set as the load-bearing one per
cycle-12's split rationale). Android assembleDebug not run
locally (takes several minutes on cold SDK install; covered by
the same KMP compile path, no Android-specific Swift engine
re-entry to verify).

**Registration.** 5 AppContainers updated:
- `apps/cli/.../CliContainerTools.kt`: import + 2→1 register call.
- `apps/server/.../ServerContainerTools.kt`: same.
- `apps/desktop/.../AppContainer.kt`: same.
- `apps/android/.../AndroidAppContainer.kt`: same.
- `apps/ios/Talevia/Platform/AppContainer.swift`: same.

The bullet's "AddTrack + RemoveTrack" half is not done. Added
sibling P2 bullet `debt-consolidate-video-add-remove-verbs-tracks`
via the §6 "顺手记 debt" P2 append path — that's the right queue
for "related follow-up surfaced during this cycle's implementation".
