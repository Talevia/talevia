## 2026-04-21 — add_source_node for genre-agnostic DAG expansion (VISION §5.1 rubric)

Commit: `70cf169`

**Context.** VISION §5.1 asks whether new genres can be supported *without*
recompiling Core: "新 genre（例如从叙事片扩到 MV）要加 source schema，需要
改 Core 还是只需扩展?" The data-layer answer was already "just an extension"
— `SourceNode.kind` is an opaque dotted-namespace string and `addNode` /
`replaceNode` are kind-agnostic. But the *agent-visible* answer was "no":
after `create_project_from_template` seeded the starter DAG, the only ways
for the agent to create a new node were (a) the three `define_character_ref`
/ `define_style_bible` / `define_brand_palette` consistency shortcuts or
(b) `import_source_node{,_from_json}` from somewhere else. There was no
Tool<I,O> for "add this brand-new `narrative.scene` / `musicmv.performance_shot`
/ `ad.variant_request` I just conceived." That forced the agent to either
ship a fake bootstrap envelope through the import path (absurd) or stop
after the template's initial nodes (crippling for any multi-scene / multi-
variant / multi-shot project).

**Decision.** `AddSourceNodeTool` with kind-agnostic signature
`(projectId, nodeId, kind, body: JsonObject = {}, parentIds: List<String> = [])`.
Uses the existing `SourceNode.create` + `Source.addNode` primitives — zero
new data-layer machinery. Guardrails enforced at the tool boundary because
the underlying `addNode` is permissive:
  - Reject blank `kind` (a node with no kind is undispatchable).
  - Reject blank `nodeId` (same reason).
  - Reject duplicate `nodeId` (matches `Source.addNode` contract; use
    `update_source_node_body` to edit instead).
  - **Reject parent ids that don't exist in the project** — a dangling
    `SourceRef` silently breaks DAG propagation through `Source.stale` and
    `find_stale_clips`, and there's no staleness machinery that would
    surface the mistake after the fact.
Orthogonality preserved: edits are `update_source_node_body` (body),
`set_source_node_parents` (parents), `rename_source_node` (id). Matches the
`update_source_node_body` pattern of "opaque JSON body, trust the caller to
match kind + shape" — Core's genre-agnostic discipline is already the
industry-consensus answer for extensible schemas (protobuf's `Any`,
JSON Schema's `additionalProperties`, Postgres `jsonb`).

**Alternatives considered.**

1. *A per-kind `define_<kind>` for every genre node (narrative.scene,
   narrative.shot, musicmv.track, musicmv.visual_concept,
   musicmv.performance_shot, ad.brand_brief, ad.product_spec,
   ad.variant_request, tutorial.script, tutorial.broll_library,
   tutorial.brand_spec, vlog.raw_footage, vlog.edit_intent,
   vlog.style_preset — 14 new tools).* Rejected — each would duplicate the
   same JSON-schema / permission / commit shape with only the body
   serializer differing. The three consistency `define_*` tools earn their
   keep because the partial-patch + typed-body ergonomics (e.g.
   `loraPin: LoraPin? = null`) materially help. Genre bodies today are
   flat-ish POJOs the agent can assemble directly; a single kind-agnostic
   tool beats 14 near-identical wrappers.
2. *Rely on `import_source_node_from_json` for all new-node creation.*
   Rejected — it forces a JSON-envelope detour for what is conceptually a
   simple create: the agent has to marshal the envelope with the correct
   `formatVersion`, wrap the body in a single-node array, pick a rootNodeId
   equal to the id, then call import. Error-prone and opaque in the
   transcript.
3. *Auto-create dangling parents (lazy-init).* Rejected — a parent id typo
   would silently materialize a second kindless node. Fail loud.
4. *Defer parent validation to a follow-up `validate_project` call.*
   Rejected — the staleness lane assumes the DAG is well-formed; the cost
   of a dangling ref is silent and deferred, exactly the category `require`
   exists to prevent.

**Coverage.** `AddSourceNodeToolTest` — happy-path kind+body round-trip,
parent-wiring success, dangling-parent rejection (with project-unchanged
assertion), duplicate-id rejection, blank-kind and blank-nodeId rejection,
default (empty) body + parents, contentHash stability across reads.

**Registration.** Registered `AddSourceNodeTool` in `CliContainer.kt`,
`apps/desktop/AppContainer.kt`, `apps/server/ServerContainer.kt`,
`apps/android/AndroidAppContainer.kt`,
`apps/ios/Talevia/Platform/AppContainer.swift`.
