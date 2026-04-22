## 2026-04-22 — Transitive source-hash propagation for stale-clip detection (VISION §5.1)

Commit: see `feat(core)` pair below.

**Context.** `SourceNode.contentHash = contentHashOf(kind, body, parents)` is
intentionally shallow: the `parents` arg is the list of `SourceRef` **ids**,
not their content. So editing a `style_bible` *grandparent* node leaves every
descendant's `contentHash` byte-identical. `LockfileEntry.sourceContentHashes`
snapshots only the directly-bound node's shallow hash, and
`Project.staleClipsFromLockfile()` diffs that shallow hash only — so ancestor
edits are silently swallowed. VISION §5.1 "显式标 stale" was therefore only
**partially** satisfied: clips bound to a grandchild of an edited node never
fired stale, `regenerate_stale_clips` ignored them, and the user's "change the
global style bible" intent failed to propagate. This is the top P0 in
`docs/BACKLOG.md`.

**Decision.** Introduce `Source.deepContentHashOf(nodeId, cache = mutableMapOf())`
in `core/domain/source/DeepContentHash.kt`. Each call recursively folds the
node's own shallow `contentHash` with the sorted-by-parent-id deep hashes of
all parents via `fnv1a64Hex("shallow=<h>|parents=<id>=<h>|…")`. A missing
parent folds the sentinel `missing:<id>` so partial/editing DAGs still hash
deterministically. Walk is memoised per invocation (shared cache across all
clips in one `staleClipsFromLockfile()` call) → `O(nodes)` not
`O(nodes × depth)`, even with shared grandparents (one `style_bible` parenting
five `character_ref`s is typical).

Two call-sites updated to use the deep hash:

1. **`AigcPipeline.record()`** — snapshots `source.deepContentHashOf(id, cache)`
   for every bound id at generation time. A single `cache` is threaded across
   all bindings in one record so shared ancestors aren't re-walked.
2. **`Project.staleClipsFromLockfile()`** — replaces the shallow
   `currentHashByNode` map with a `deepCache` + per-entry
   `source.deepContentHashOf(nodeId, deepCache)` comparison. Missing node
   (deleted source) is still skipped, matching prior "non-comparable" semantics.

Tests: added `TransitiveSourceHashTest` (5 cases) covering deep-hash
invariants at the pure-function layer and the end-to-end stale-clip flow:
grandparent body edit → grandchild's deep hash changes → bound clip flags
stale with `changedSourceIds = {grandchild}`. Also updated the pre-existing
`GenerateImageToolTest` / `GenerateVideoToolTest` /
`FindStaleClipsToolTest` / `RegenerateStaleClipsToolTest` fabrications to
snapshot via `deepContentHashOf` so their "before edit" baseline matches
production behaviour (previously they hard-coded `.contentHash`, which would
still compare equal under the new comparison — but the fabrication now
expresses the real contract).

**Alternatives considered.**

- **Make `SourceNode.contentHash` itself transitive (fold parents' shallow
  hashes into the node's own hash at construction time).** Rejected: would
  require `SourceNode.create` to know about the sibling index, which breaks
  the current design where `SourceNode` is a pure value (`contentHashOf`
  takes only its own fields). Would also force re-hashing every descendant
  on any ancestor edit during `Source` construction — today's shallow hash
  stays stable per node and only the deep roll-up walks the DAG when
  staleness is *queried*. Deep-hash as a free function on `Source` keeps the
  node pure and pays the walk cost lazily.

- **Store the full ancestor contentHash set in `LockfileEntry.sourceContentHashes`
  (record every transitive ancestor at generation time, diff the whole set).**
  Rejected: grows lockfile entries non-linearly with DAG depth, and the deep
  hash already compresses the same information into a single string per
  binding id. "Set of ancestor shallow hashes" and "deep fold of those
  hashes" are equivalent as change detectors; the latter is 16 bytes per id.

- **Hash the entire `Source` once and fingerprint the clip against that.**
  Rejected: would flag every clip stale on any source edit, even unrelated
  branches of the DAG — a user tweaking `character_a` would re-run every
  clip bound to `character_b`. The whole point of source bindings is
  surgical invalidation; project-wide staleness is the anti-pattern we came
  from.

Industry consensus: deep/recursive content hashing with a memoised walk is
the Git-tree / Merkle-DAG / Bazel-action-cache pattern. `fnv1a64Hex` is
already the project's canonical content-hash primitive (used in `fnv1a64Hex`
/ `contentHashOf`); reusing it keeps the fingerprint comparable across
lanes.

**Coverage.**

- `core/src/jvmTest/.../domain/TransitiveSourceHashTest.kt` (new, 5 cases):
  - `grandparentEditChangesGrandchildDeepHash` — the core invariant.
  - `unchangedDagHasStableDeepHash` — determinism.
  - `danglingParentFoldsAsSentinelRatherThanThrowing` — `missing:<id>`.
  - `parentOrderInListDoesNotChangeDeepHash` — sort-by-id canonicalises.
  - `staleClipsFromLockfileFlagsClipWhenGrandparentEdited` — end-to-end
    lockfile lane: snapshot at gen-time, edit grandparent, assert
    grandchild-bound clip fires stale with the right `changedSourceIds`.
- `FindStaleClipsToolTest` / `RegenerateStaleClipsToolTest` /
  `GenerateImageToolTest` / `GenerateVideoToolTest` updated to fabricate
  snapshots via `deepContentHashOf` instead of the shallow `.contentHash`.
  Same assertions, real contract expressed.
- `./gradlew :core:jvmTest` passes; `./gradlew :core:ktlintCheck` passes.

**Registration.** No new tool; pure change to source-graph math + existing
lockfile/staleness lanes. No `AppContainer` wiring needed on any of the 5
platforms.
