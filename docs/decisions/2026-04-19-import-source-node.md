## 2026-04-19 — `import_source_node` (VISION §3.4 — closes "可组合")

**Context.** §3.4 names four codebase properties for Project / Timeline:
可读, 可 diff, 可版本化, 可组合. After snapshot / fork / diff landed, the
first three were covered. "可组合 (片段 / 模板 / 特效 / 角色可跨 project
复用)" was the only unfilled leg — the agent had no way to lift a `character_ref`
defined in a narrative project into a vlog project without retyping the body.
`fork_project` copies a whole project, which is the wrong tool for "share
one character".

**Decision.** New `core.tool.builtin.source.ImportSourceNodeTool` (id
`import_source_node`, permission `source.write`). Inputs: `(fromProjectId,
fromNodeId, toProjectId, newNodeId?)`. Walks the source node + its parent
chain in topological order, inserts each one into the target project, returns
`(originalId, importedId, kind, skippedDuplicate)` per node. Wired into all
four composition roots (server / desktop / Android / iOS).

**Why content-addressed dedup, not id-addressed.** `SourceNode.contentHash`
is a deterministic fingerprint over `(kind, body, parents)`. The AIGC
lockfile keys cache entries on bound nodes' content hashes (not their ids).
So when an imported node lands with the *same* contentHash as the source,
every previous AIGC generation that referenced that node is automatically a
cache hit on the target side too — without the agent doing anything special.
The alternative — keying on id — would force the user to use the same id on
both sides, and would still miss when ids legitimately differ ("Mei" vs
"character-mei-v2") even though the bodies are identical.

**Why reuse + remap parent refs when a parent is deduped to an existing
target node under a *different* id.** Real example: the source's `style-warm`
parent matches the target's pre-existing `style-vibe-1` by contentHash. We
reuse the existing node (no insertion) and remap the leaf's `SourceRef` to
point at `style-vibe-1`. The alternative — refusing to remap and inserting a
duplicate `style-warm` — would create two source nodes with identical content
but different ids in the same project, defeating the dedup discipline that
makes lockfile cache transfer work.

**Why fail loudly on same-id-different-content collision instead of auto-rename.**
If the target already has `character-mei` with different content, we throw
with a hint to pass `newNodeId` or `remove_source_node` first. The
alternative (silent suffix-rename to `character-mei-2`) would create
unobvious id divergence — a future binding referencing `character-mei` would
quietly resolve to the *original* version, not the just-imported one. Forcing
the agent to make the conflict explicit is worth one extra round-trip.

**Why `newNodeId` only renames the leaf, not parents.** The common case is
"I want to import the Mei character into the vlog project but the vlog
already has a `character-mei` for someone else". The leaf is what the user
named; parents are derived. Per-parent renaming would multiply the input
surface for a case the agent will rarely hit (today's consistency nodes are
leaves). When richer source schemas land and parent collisions become real,
the caller can break the import into two: `import_source_node(parent, ...,
newNodeId=...)` then `import_source_node(leaf, ...)` lets the parent-dedup
path remap the leaf's refs.

**Why reject self-import (`from == to`).** Nearly always a mistake. The
agent that wants a within-project copy already has `define_character_ref` /
`define_style_bible` / `define_brand_palette` with a fresh id. Failing
loudly costs one corrected round-trip and prevents the silent no-op that
content-addressed dedup would otherwise produce.

**Why permission `source.write`, not `project.write`.** This tool only
mutates `Project.source`; it does not touch the timeline, lockfile, render
cache, or asset catalog. Aligning with `define_*` / `remove_source_node`
keeps the permission ruleset clean — the user can grant blanket source
edits without authorising broader project mutations.

**Tests.** 9 cases in `ImportSourceNodeToolTest`: leaf import, idempotent
re-import, topological parent walk, parent-dedup remapping, same-id-
different-content failure, `newNodeId` rename, self-import rejection, missing
source/target project, missing source node.

---
