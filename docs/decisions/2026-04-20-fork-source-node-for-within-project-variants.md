## 2026-04-20 — `fork_source_node` for within-project variants

**Context.** [ImportSourceNodeTool] covers cross-project
source reuse ("my 'Mei' character_ref lives in project A, copy
her into project B"). There is no within-project analogue for
the very common **variant-iteration** case that VISION §5.5
calls out directly:

- "Try the same scene with a different take of the
  protagonist."
- "Clone the style bible so I can tweak mood on the copy
  without touching the original."
- "I want three alternate character designs sharing the same
  upstream world node."

Workaround today: `define_character_ref` / `define_style_bible`
/ `define_brand_palette` with a fresh id + retype the entire
body. For a 200-word character description with a LoRA pin and
5 reference asset ids, that's both expensive (agent tokens) and
error-prone (copy-paste drift).

**Decision.** Ship
`fork_source_node(projectId, sourceNodeId, newNodeId?)`:

1. Looks up the original by id; fails loud if not found.
2. Copies `kind`, `body`, and `parents` verbatim to a new node.
   Parents are **referenced**, not cloned — the fork shares
   upstream ancestors. If the user wants a fully independent
   subtree, they `fork_source_node` each ancestor too (the
   composable contract: going deeper is opt-in, not the
   default).
3. `newNodeId` blank or absent → generate a UUID.
4. `newNodeId == sourceNodeId` is rejected (typo guard; failing
   loud beats silently no-op-ing).
5. `newNodeId` colliding with an existing node id fails loud
   (same rule as `addNode`) — the caller resolves by picking a
   different id or `remove_source_node` first.
6. Because body + parent refs are identical, the forked
   node's [contentHash] equals the original's. The AIGC
   lockfile keys on contentHash, so any pre-existing cache hit
   that consumed the original also lights up for the fork
   immediately — exactly the behaviour we want for "same
   character in a different context." The moment the caller
   tweaks the fork via `update_*`, the hash changes and the
   cache invalidates in the usual way.
7. Permission: `source.write` (same tier as `define_*`).

**Alternatives considered.**

- *Extend `import_source_node` to accept `fromProjectId ==
  toProjectId`.* That tool's own guard explicitly rejects
  self-import with a pointer at this use-case — overloading it
  to do two jobs would muddy an otherwise-clear contract and
  force the caller to pass the same project id twice.
- *A "deep fork" option that clones ancestors too.* Rejected
  for now — deep cloning changes the DAG shape (parents were
  shared, now they're not), and the composable "fork each
  ancestor you want cloned" pattern keeps the default tight.
  Add a flag only if a concrete driver appears.
- *Auto-prefix the new id with `<original>-fork-<n>`.*
  Tempting, but bakes naming ontology in. Generating a UUID
  when blank gives the agent freedom to rename afterwards if
  it wants human-readable slugs.

**Coverage.** 10 JVM tests:
- Fork character_ref under explicit new id; hash equals
  original.
- Auto-generated id when newNodeId null.
- Blank/whitespace newNodeId also auto-generates.
- Parent refs preserved (ancestors shared, not cloned).
- Rejects newNodeId == sourceNodeId.
- Rejects colliding newNodeId.
- Rejects unknown source.
- Rejects missing project.
- Forked node starts at revision=1 (bumped by addNode).
- `source.revision` bumps by exactly 1 on fork.

**Registration.** Registered in all 5 composition roots
directly after `ImportSourceNodeTool` — cross-project and
within-project reuse verbs clustered together.

**SHA.** f539da5bdc5855484d5121b3d02edcb7b6bf443c

---
