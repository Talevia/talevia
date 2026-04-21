## 2026-04-20 — `set_source_node_parents` for DAG parent edits

**Context.** The `update_character_ref` / `update_style_bible`
/ `update_brand_palette` trio each accepts an optional
`parentIds` replacement list, but that lane only covers the
three `core.consistency.*` kinds. Every other SourceNode kind
— narrative.shot, vlog.raw_footage, anything the agent built
via `import_source_node`, any future genre kind — has no way
to edit its `parents` list after creation.

The workaround forced `remove_source_node` + re-add, which
loses the body and forces re-bindings elsewhere. VISION §5.5
("跨镜头一致性必须在 source 层表达") depends on the DAG
being editable: retroactively binding a scene to a freshly-
defined character_ref is a very common refactor move.

**Decision.** Ship
`set_source_node_parents(projectId, nodeId, parentIds[])`:

1. Genre-agnostic: works on **any** kind.
2. `parentIds` is a **full replacement**. Empty list clears
   all parents. Partial "add-one" / "remove-one" editing is
   left to the caller (read with `list_source_nodes`, mutate
   client-side, write back).
3. Reuses the `resolveParentRefs` helper the `define_*` tools
   use: trims blanks, rejects self-reference, every id must
   resolve, dedups while preserving insertion order.
4. **Cycle check** walks transitively: if any proposed parent
   has the node being edited in its ancestor set, fail loud.
   Pre-existing cycles (shouldn't exist, but belt-and-braces)
   can't produce infinite loops via the visited-set.
5. Bumps `contentHash` via `replaceNode` — downstream clips go
   stale, `find_stale_clips` surfaces them exactly like any
   other DAG edit.
6. Output returns `previousParentIds` + `newParentIds` so the
   agent can read what changed without a follow-up query.
7. Permission: `source.write` (same tier as `update_*`).

**Alternatives considered.**

- *Add `set_source_node_parents` functionality into a
  generic `update_source_node_body` tool.* Rejected —
  editing parents and editing body are distinct intents.
  Conflating them means every generic-update call has to
  opt-out of whichever it doesn't want to touch.
- *Differential API (`add_parent` + `remove_parent`).*
  Rejected — the caller almost always has the full list in
  mind (from `list_source_nodes`), and differential edits
  over an ordered list invite order-dependence bugs
  ("did add-then-remove leave the list as I expected?").
  Wholesale replacement matches the existing `update_*`
  parentIds contract.
- *Accept a delta + base revision for optimistic
  concurrency.* Overkill for now — the mutex on
  `ProjectStore` is the ordering guarantee and there's no
  evidence of concurrent edits stomping each other.

**Coverage.** 11 JVM tests:
- Replaces parents wholesale; output echoes previous/new.
- Empty list clears.
- Bumps contentHash (stale-propagation signal).
- Dedups + preserves insertion order.
- Rejects self-reference.
- Rejects unknown parent id.
- Rejects transitive cycle (a → b → c; set c.parents=[a]).
- Rejects unknown target node.
- Works on consistency kinds (character_ref accepting a
  style_bible parent — unusual but not disallowed by DAG).
- Bumps `source.revision`.
- Output echoes previous/new parent lists.

**Registration.** Registered in all 5 composition roots
directly after `ForkSourceNodeTool` — source-edit verbs
clustered together (define, update, remove, import, fork,
set_parents).

**SHA.** 87c821015ff7b1803d80287a72980decef475671

---
