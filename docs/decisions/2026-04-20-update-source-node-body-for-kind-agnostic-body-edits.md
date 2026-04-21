## 2026-04-20 — `update_source_node_body` for kind-agnostic body edits (VISION §5.1 Source 层 refactoring)

Commit: `c63c4bc`

**Context.** The consistency lane has typed body editors
(`update_character_ref` / `update_style_bible` /
`update_brand_palette`) for its three kinds. Every **other** kind
— narrative.shot, vlog.raw_footage, the new musicmv / tutorial / ad
genre bodies, plus anything hand-authored via `import_source_node`
— had no body-edit path. The agent had to `remove_source_node` +
re-`import_source_node`, which:

1. Drops the id (every clip `sourceBinding`, every descendant
   `parents` ref, every lockfile `sourceBinding` + content-hash key
   pointing at the removed node goes dangling).
2. Forces the agent to re-specify the whole body.
3. Is one of the most error-prone multi-step flows the LLM runs.

VISION §5.1 asks "改一个 source 节点，下游哪些 clip / scene /
artifact 会被标为 stale？这个关系是显式的吗？" — but to even *test*
that relationship on a genre node, you first needed a working body
editor. Without it, the §3.2 DAG → stale-propagation story worked
for the three consistency kinds and nothing else.

**Decision.** Ship
`update_source_node_body(projectId, nodeId, body)` under
`source.write`:

1. **Whole-body replace.** The `body` argument is a full
   `JsonObject`; the tool runs `Source.replaceNode(nodeId) { node ->
   node.copy(body = input.body) }`. `SourceNode.bumpedForWrite` in
   `SourceMutations.kt` recomputes `contentHash` from the new
   `(kind, body, parents)` and bumps `revision`. Kind, parents, and
   id are all preserved — the tool is deliberately body-only.
2. **Scope carve-outs, enforced by omission rather than validation:**
   - Can't change `kind`: a kind flip is a type change, not an edit,
     and would break every reader that dispatches on it.
   - Can't change `parents`: that's `set_source_node_parents`.
   - Can't change `id`: that's `rename_source_node`.
   Three orthogonal verbs, one refactor intent each.
3. **Output** returns `{projectId, nodeId, kind, previousContentHash,
   newContentHash, boundClipCount}`. `boundClipCount` is the number
   of clips whose `sourceBinding` includes this nodeId directly —
   the immediate blast-radius hint so the agent can decide whether
   to follow with `find_stale_clips` + `regenerate_stale_clips`.
4. **No TimelineSnapshot.** Body edits touch zero `Clip` fields
   (`sourceBinding` is by id, not by hash), so `revert_timeline`
   would be a no-op. Following the pattern of `update_character_ref`
   / `set_source_node_parents`, which also skip timeline snapshots
   for pure-source mutations. Project-level undo for source edits
   lives in `save_project_snapshot` / `restore_project_snapshot`.

**Alternatives considered.**

- *Partial-patch (JSON merge) semantics.* Rejected — on free-form
  JSON a generic merge is ambiguous (null = clear or keep?
  arrays = replace or concat?), and every ambiguity is a way for
  the agent to do the wrong thing. Whole-replace has exactly one
  reading: "this is what the body will look like after the call".
  The typed `update_*` trio is still the partial-patch path for the
  three kinds where the ergonomics actually matter — they know
  which fields are optional, which lists clear on `[]`, which
  strings clear on `""`.
- *Restrict to non-consistency kinds.* Rejected — adding a kind
  gate would create a weird "this tool refuses three ids" carve-out
  the agent has to remember. The typed `update_*` trio is
  *preferred* for consistency kinds (partial patch > whole replace
  for iterative edits) but not *required*; keeping the generic tool
  permissive means one less rule to teach.
- *Also accept `parents` / `kind` in the same call.* Rejected —
  orthogonal verbs compose better than one mega-tool. Concretely,
  the agent's mental model already maps "change parents" →
  `set_source_node_parents` and "rename id" → `rename_source_node`;
  a body editor that also flips parents would invite partial writes
  ("I just wanted to change the body but this also cleared my
  parents").
- *Emit a TimelineSnapshot anyway (safety net).* Rejected — the
  undo stack only matters for operations that change the timeline.
  A body edit with no bound clips is a pure source-DAG mutation;
  emitting a snapshot would be a no-op clone that just pollutes the
  `revert_timeline` stack, same reasoning rename_source_node uses
  when skipping the snapshot on a zero-clip rename.
- *Return the full updated SourceNode in the output.* Rejected —
  the agent that just wrote the body already has the body in
  memory. `describe_source_node` is the tool for fetching a
  complete node; keeping `update_source_node_body`'s output to
  hash + bound-clip-count keeps the happy-path response compact.

**Coverage.** 8 JVM tests in `UpdateSourceNodeBodyToolTest`:
1. Replaces body on a `narrative.shot`; `contentHash` bumps;
   output echoes previous/new hashes.
2. Preserves `kind` and `parents` after body replace (shot with
   `parents=[mei]` keeps that parent list).
3. Rejects missing node (loud, "not found" in message).
4. Rejects missing project (loud).
5. Works on consistency kinds too (character_ref body replace)
   — regression lock for the "no kind gate" decision.
6. `boundClipCount` reports exactly the direct binders
   (two of three seeded clips in the fixture).
7. `boundClipCount == 0` when no clips bind the node.
8. `revision` on the node bumps monotonically.
9. Same-body replacement leaves `contentHash` unchanged
   (the hash is a function of `(kind, body, parents)`;
   identical inputs → identical hash, same semantics as
   `SourceNode.create`).

**Registration.** Added one `import` + one `register` call in each
of the five composition roots directly after `RenameSourceNodeTool`:
`apps/cli/CliContainer.kt`, `apps/desktop/AppContainer.kt`,
`apps/server/ServerContainer.kt`,
`apps/android/AndroidAppContainer.kt`, and
`apps/ios/Talevia/Platform/AppContainer.swift`.

**Prompt.** One paragraph in `TaleviaSystemPrompt.kt` immediately
after the `rename_source_node` blurb — keeps the source-refactor
verbs (define / update / set_parents / rename / body) clustered.
`TaleviaSystemPromptTest.keyPhrases` gains `update_source_node_body`
so silent prompt regressions trip the test.

---
