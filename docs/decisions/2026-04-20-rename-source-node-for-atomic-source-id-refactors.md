## 2026-04-20 — `rename_source_node` for atomic source-id refactors

**Context.** A `SourceNodeId` is immutable today — `Source.replaceNode`
asserts `next.id == id` on the updater result. To "rename" a node the
agent had to `remove_source_node` + `define_*` under a new id, which
silently dropped every downstream reference: sibling nodes'
`SourceNode.parents`, `Clip.sourceBinding` entries across every track,
`LockfileEntry.sourceBinding` sets, and `LockfileEntry.
sourceContentHashes` keys. That turns a pure rename into a bespoke
multi-step refactor that the LLM rarely gets right end-to-end.

VISION §3.4 ("可读") and §5.1 ("能不能序列化、版本化、跨 project
复用") both assume you can *refactor names without losing history*.
Giving the agent one atomic primitive that rewires the DAG + timeline
+ lockfile in a single mutation closes that gap.

**Decision.** Ship `rename_source_node(projectId, oldId, newId)` under
`source.write`:

1. One `ProjectStore.mutate` block updates all five affected surfaces
   in lockstep: the target node's `id`, every descendant's
   `SourceRef(oldId)` → `SourceRef(newId)`, every clip's
   `sourceBinding` set across every track / clip kind, every
   `LockfileEntry.sourceBinding` set, and every
   `LockfileEntry.sourceContentHashes` key.
2. `SourceNode.contentHash` is computed over `(kind, body, parents)`
   — not `id` — so the *renamed node's* hash survives unchanged.
   Descendant nodes whose `parents` list was rewritten do get a new
   hash: the serialised parent-ref value changed, so the hash must
   change. That cascade is the correct stale-propagation behaviour
   — renaming a node is a refactor that invalidates any AIGC render
   bound to the old parent-ref hash.
3. Validation: `newId` must pass `isValidSourceNodeIdSlug` (a new
   helper in `SourceIdSlug.kt`: non-empty, lowercase ASCII letters /
   digits / `-`, no leading or trailing `-`). Unknown `oldId` or
   collision with an existing node → loud `IllegalStateException`.
   Malformed `newId` → `IllegalArgumentException`. `oldId == newId`
   is a deliberate no-op (no revision bump, no snapshot emission,
   no output churn).
4. Emits one `Part.TimelineSnapshot` *only when* a clip binding was
   actually rewritten. Pure source-DAG renames (no clips bound to
   the node) skip the snapshot — otherwise every DAG refactor would
   pollute `revert_timeline`'s stack with identical snapshots.
5. Output: `{projectId, oldId, newId, parentsRewrittenCount,
   clipsRewrittenCount, lockfileEntriesRewrittenCount}` so the agent
   can report exactly how wide the refactor rippled.

**Alternatives considered.**

- *Two-step remove + re-add.* This was the pre-tool workaround and
  is what `rename_source_node` replaces. It drops every `SourceRef`
  pointing at the removed node, every `Clip.sourceBinding` entry,
  and every `LockfileEntry` binding — and requires the agent to
  re-thread all of them manually. Across a non-trivial project the
  LLM reliably misses one or two references, leaving the project in
  an inconsistent state. Atomic rewrite beats best-effort rethread.
- *Kind-specific body-aware rename.* A real `narrative.shot` can
  reference other source nodes by id inside its typed body
  (e.g. `narrative.shot.body.sceneId`). We could snoop genre bodies
  on rename and rewrite matching string ids. Rejected — that would
  require Core to know every genre's body schema, which is exactly
  the Core → genre boundary CLAUDE.md "anti-requirements" forbids.
  The carve-out is documented in the tool's `helpText` and in the
  system prompt so the agent knows to update string-based
  intra-body refs via the kind-specific `update_*` tool.
- *Rebuild `contentHash` from id too.* Tempting for a "clean slate"
  on rename, but would break content-addressed cache dedup across
  projects (the whole point of `import_source_node` being a cache
  hit when contentHash matches). The hash is a fingerprint over
  *semantic content*, not identity; keeping it stable on a
  name-only refactor is correct.
- *Always emit a TimelineSnapshot.* Would let `revert_timeline`
  undo any rename, but a rename that touched zero clips doesn't
  change `Timeline` at all — the snapshot would be identical to
  the previous one, just noise in the undo stack. Emit only when a
  clip binding was actually rewritten.

**Coverage.** 9 JVM tests in `RenameSourceNodeToolTest`:

1. Happy path: standalone node rename preserves `contentHash`.
2. Descendant parent-ref rewrite, with correct cascade of
   descendant `contentHash` bump and unrelated-sibling hash
   stability.
3. `Clip.sourceBinding` rewrite across Video / Audio / Text clip
   kinds on three different tracks; unbound clip untouched;
   TimelineSnapshot emitted.
4. Lockfile entry rewrite — `sourceBinding` membership swap and
   `sourceContentHashes` key rewrite (value preserved); unrelated
   entry untouched.
5. Same-id no-op: whole-project snapshot equal before/after; no
   TimelineSnapshot emitted.
6. Rejects on unknown `oldId`; project state unchanged.
7. Rejects on `newId` collision with an existing node; state
   unchanged.
8. Rejects on malformed `newId` across six shapes (empty, blank,
   spaces, slash, uppercase, leading `-`, trailing `-`); state
   unchanged.
9. Conditional snapshot emission: clip-binding rename → 1
   snapshot, no-clip rename → 0 snapshots.

Plus the regression guard on the system prompt: `rename_source_node`
is now in `TaleviaSystemPromptTest.keyPhrases`.

**Registration.** Added one `import` + one `register` in each of the
five composition roots directly after `SetSourceNodeParentsTool`:
`apps/cli/CliContainer.kt`, `apps/desktop/AppContainer.kt`,
`apps/server/ServerContainer.kt`,
`apps/android/AndroidAppContainer.kt`, and
`apps/ios/Talevia/Platform/AppContainer.swift`.

**SHA.** f6a97418d88b2fced6421b869fe95f3c39333640
