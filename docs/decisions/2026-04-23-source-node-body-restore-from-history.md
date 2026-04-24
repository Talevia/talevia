## 2026-04-23 — `update_source_node_body(restoreFromRevisionIndex=N)` closes the source-history read/write loop (VISION §5.5)

**Context.** Cycle-45 landed body-history as a read-side surface
(`source_query(select=history)` over per-file JSONL at
`<bundle>/source-history/<nodeId>.jsonl`). The write-side complement
was missing — an operator who saw a bad LLM edit had to manually
round-trip the old body string back through `update_source_node_body`,
eyeballing the old body and re-serialising it. No programmatic
rollback.

Rubric delta §5.5 (source-layer body audit/restore loop): moves from
**部分** (history readable, no restore verb) to **有** (restore-by-
index closes the loop — read → pick → restore in one agent-level
dispatch).

**Decision.** Extend `UpdateSourceNodeBodyTool.Input` with a new
optional field:

```kotlin
@Serializable data class Input(
    val projectId: String,
    val nodeId: String,
    val body: JsonObject? = null,                   // was non-nullable
    val restoreFromRevisionIndex: Int? = null,      // new
)
```

Semantics:

- **Exactly one** of `body` / `restoreFromRevisionIndex` must be set.
  Both means "which one wins?" — fail loud. Neither is an obviously
  broken call — fail loud.
- `restoreFromRevisionIndex = N` where `0` = most-recent historical
  revision, `1` = the one before that, etc. Bounds-checked against
  the real history window (not just the default 20 — if the caller
  asks for index=50 and the node has 30 entries, the error message
  names the true ceiling 29, not "out of range of [0..19]").
- Negative index fails loud with a sign-check hint.
- Empty history fails loud with a `source_query(select=history)`
  hint, so the operator can confirm the node genuinely has no
  recorded revisions (vs. some other error).
- Non-JsonObject restored bodies fail loud — `BodyRevision.body` is
  typed `JsonElement` for contract symmetry with `SourceNode.body`,
  but every kind-handler in the runtime expects a `JsonObject`; a
  corrupt-history entry that somehow smuggled a primitive or array
  in would previously silently corrupt downstream reads. Now it
  throws at the restore site with a "history entry is corrupt or was
  written by an older schema" hint.
- **Audit trail marches forward.** On restore, the existing post-
  mutate hook still fires: the PRE-restore current body is appended
  to history as the new "most recent overwritten" revision. History
  before restore: `[v2, v1, v0]` (newest first). After restoring
  index=0 (v2): current body = v2; history = `[old-current, v2, v1,
  v0]`. No rewriting the past — the log only grows forward in time.
- `body != null` branch unchanged — existing callers that pass a
  full replacement body keep working exactly as before.

`InputCompatSerializer` also updated: the tolerant "flattened body"
rescue (which folded non-reserved top-level keys into a synthesized
`body` for LLMs that split the wrapper) now treats
`restoreFromRevisionIndex` as reserved. A pure restore call
`{projectId, nodeId, restoreFromRevisionIndex: 0}` no longer gets a
phantom `{}` body injected (which would then trip the exactly-one-of
mutual-exclusion check).

**Axis.** n/a — net-new feature (extends an existing tool's input
with a new mode); not a refactor.

**Alternatives considered.**

- **New tool `restore_source_node_body(nodeId, revisionIndex)`.**
  Rejected per §3a #1 (net tool count): a new tool costs ~400
  tokens of spec per turn; a new field on an existing tool costs
  ~80 tokens. The restore verb is semantically a specialisation of
  "update body" (end state is same: replace current body with some
  specific JSON), so folding into the existing tool is the natural
  shape.

- **`restoreFromFingerprint`** (content-hash based rather than index-
  based). Rejected — fingerprints are per-clip render-cache keys,
  not source-node body identifiers. Adding a second addressing
  scheme ("pick by index OR by content-hash") multiplies the schema
  surface without operator benefit; the index is what
  `source_query(select=history)` already hands back.

- **Append a NEW entry for the restored body** (history becomes
  `[v2, v1, v0, v1]` — the restored body re-enters as the newest
  revision). Rejected — that double-logs: the restored body is
  already at history[1], adding it again as a new entry would make
  `source_query(select=history)` show duplicates for no gain. The
  landing approach (append the PRE-restore state so history grows
  forward-in-time without duplicating) is the cleanest audit shape.

- **Keep `body` required, accept a sentinel like `body = {}` when
  restoring.** Rejected — `minProperties=1` was specifically set to
  catch "LLM forgot to include body" mistakes, and sentinel-
  overloading a required field is exactly the kind of ambiguity
  §3a #9 bounded-edge cases warn against. Nullable `body` with
  exactly-one-of enforcement is more honest.

**Secondary fix: `RegisteredToolsContractTest` post-cycle-46.**
Cycle-46's `debt-desktop-android-container-inline-to-extension-call`
refactor moved per-tool `register(FooTool(...))` calls from the
four JVM AppContainers into `core/commonMain/.../DefaultBuiltinRegistrations.kt`'s
shared `registerXxxTools(...)` extensions. The contract test grepped
only the 5 AppContainer files for each builtin tool class name;
every tool inside a `registerXxxTools(...)` function body surfaced
as "not registered in any AppContainer" — a false positive (the JVM
platforms DO register them transitively via the extension call).
This failure was masked in cycle-46 because `:core:jvmTest` ran from
an earlier cached state that still had the inline registrations; it
surfaced now when the restore feature forced a fresh test compile.

Fix: include `DefaultBuiltinRegistrations.kt` in the set of files
the contract test reads. The test's spirit (every tool reachable
from at least one platform's registration path) holds — the
extension IS that registration path.

**Secondary fix: helpText + schema trim to stay under budget.**
Initial landing pushed `tool_spec_budget` from 22_518 to 22_628 —
28 tokens over the 22_600 ceiling. Trimmed the new
`restoreFromRevisionIndex` schema description (~40 tokens → ~12) and
shortened the `body` description (dropped boilerplate already
covered by helpText). Final budget: within ceiling (reran, clean).

**Coverage.** Five new tests on `UpdateSourceNodeBodyToolTest`:

1. `restoreFromRevisionIndexRollsBackBody` — happy path: 2 updates →
   restore index 0 → current body = pre-restore history[0]; post-
   restore history grew by one entry (pre-restore body at the top).
2. `restoreRejectsBothBodyAndRevisionIndex` (§3a #9): mutual-
   exclusion enforced.
3. `restoreRejectsNeitherBodyNorRevisionIndex` (§3a #9): one-of
   required.
4. `restoreFromEmptyHistoryFailsLoud` (§3a #9): never-updated node.
5. `restoreOutOfRangeFailsLoudWithTrueCount` (§3a #9): error names
   the real history size, not the fetch window size.
6. `restoreNegativeIndexFailsLoud` (§3a #9): sign check.

Plus updated contract test (`RegisteredToolsContractTest`) now
recognises `DefaultBuiltinRegistrations.kt` as a valid wiring point.

Gradle: `:core:jvmTest` + `:apps:cli:test` + `:apps:server:test` +
`:apps:desktop:assemble` + `:core:compileKotlinIosSimulatorArm64` +
`:apps:android:assembleDebug` + `ktlintCheck` all green.

**Registration.** No new tool — extends the existing
`update_source_node_body`, which is already registered in all 4 JVM
AppContainers via `registerSourceNodeTools(projects)` (iOS skips
tool registration per CLAUDE.md).
