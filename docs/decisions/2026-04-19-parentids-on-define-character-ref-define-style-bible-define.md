## 2026-04-19 — `parentIds` on `define_character_ref` / `define_style_bible` / `define_brand_palette`

**Context.** The Source DAG already supported cross-references via
`SourceNode.parents: List<SourceRef>`, and `Source.stale(ancestorId)` walked
the ancestry to report every descendant that needs recomputation. But the
**definer tools the agent actually calls** (`define_character_ref`,
`define_style_bible`, `define_brand_palette`) didn't expose `parentIds` on
their inputs. In practice that meant every consistency node the agent created
sat as a disconnected root — e.g. a `character_ref` whose wardrobe palette
derives from a `brand_palette` had no way to record that derivation, so an
edit to the brand palette *wouldn't* cascade staleness onto the character.

This is the §5.1 Source layer question #2 gap: "改一个 source 节点（比如角色
设定），下游哪些 clip / scene / artifact 会被标为 stale？这个关系是显式的吗？"
The DAG machinery existed; the tool-surface bridge didn't.

**Decision.**

1. **Extend all three definer tool Inputs with `parentIds: List<String>`.**
   Optional, defaults to empty, so every existing caller is untouched. JSON
   schema documents the use-case: "Optional source-node ids this {kind}
   depends on … editing any parent cascades contentHash changes." The agent
   reads that directly in its tool catalog.

2. **Validate ids at the tool boundary** (`ResolveParentRefs.kt`):
   - Blank ids are dropped (LLMs sometimes emit `""`).
   - Self-references (`parentIds = [self]`) fail loudly — cycle prevention at
     the entry point, so lower layers don't need to guard.
   - Unknown ids fail loudly with the hint "define the parent first, or use
     `import_source_node` to bring it in." Dangling `SourceRef`s would show up
     as ghost edges in `list_source_nodes` and corrupt stale-propagation.
   - Duplicates are deduped while preserving caller order.

3. **Extend `addCharacterRef` / `addStyleBible` / `addBrandPalette` helpers
   with a `parents: List<SourceRef> = emptyList()` parameter.** Keeps helpers
   symmetric with the tool surface and avoids forcing tools to open-code
   `addNode(SourceNode(id, kind, body, parents))`. Default is empty, so
   existing tests and callers are unaffected.

4. **On `replace` path, update the node's `parents` field too, not just the
   body.** A user re-defining "Mei" with `parentIds = [style-warm]` *expects*
   the stored node to have that parent afterward — dropping the new parent
   list silently would make re-define semantically asymmetric with first
   define. `replaceNode`'s `bumpedForWrite` re-computes contentHash from the
   updated parents list, so ancestry-driven staleness lands correctly.

**Why validate at the tool boundary and not in `addNode`.** `addNode` is
genre-agnostic and lives in the Source lane alongside the raw data model.
Adding "all `SourceRef`s must resolve" as an invariant there would force
every low-level mutation (migration, import, test fixture) to carry the full
node index — a foot-gun in places where dangling refs are a transient state
that gets fixed up before commit. The definer tools are the one authoritative
user-facing entry where "the graph must be well-formed right now" is a valid
contract to enforce.

**Why fail loudly on unknown parents instead of silently dropping them.** The
LLM typing `parentIds = ["style-warm"]` when `style-warm` doesn't exist is
nearly always a sequencing mistake (it forgot to call `define_style_bible`
first, or misremembered the id). A silent drop would create a character_ref
with no parents but an intent the caller thought it had expressed —
failure-to-propagate-later is a much worse symptom than failure-at-define-now.

**Why keep `parentIds` separate from `consistencyBindingIds`.** The two serve
distinct roles:
- `consistencyBindingIds` on AIGC tools = "fold these nodes into **this one**
  prompt." Ephemeral, per-call.
- `parentIds` on define_* tools = "this node derives from those nodes
  structurally." Persistent in the Source DAG; drives staleness propagation.
A character_ref that *derives from* a brand_palette usually wants both: the
parent ref keeps the derivation visible + cascades staleness, and AIGC tools
still bind just the character (the brand palette folds implicitly through
the DAG-flattening pass when that lands, or explicitly via a second binding
today).

**Coverage.** Added 5 tests to `SourceToolsTest`:
- `defineCharacterRefThreadsParentIdsIntoNode` — parent makes it onto the
  stored node.
- `parentEditCascadesContentHashDownstream` — editing the brand palette
  makes `Source.stale(brand-acme)` include the style_bible that parents it.
- `parentIdsThatDontExistFailLoudly` — unknown id errors out.
- `selfParentIsRejectedAtTheToolBoundary` — cycle protection.
- `replacingCharacterRefUpdatesParentsToo` — re-define rewrites parents, not
  just body.

System prompt gained a paragraph teaching the agent when to use `parentIds`
(derivation, not documentation) and when to rely on flat
`consistencyBindingIds` instead.

---
