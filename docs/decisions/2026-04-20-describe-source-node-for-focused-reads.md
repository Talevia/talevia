## 2026-04-20 — `describe_source_node` for focused reads

**Context.** The Source DAG has three existing read tools:

- `describe_source_dag` — structural summary of the whole
  graph (roots, leaves, hotspots, per-kind counts).
- `list_source_nodes` — paginated rows with id / kind /
  contentHash / summary (+ optional body).
- `list_clips_for_source` — clips bound to a specific node
  (+ transitive descendants).

Nothing answers the very common follow-up "tell me everything
about *this one node* so I can decide how to edit it":

- Typed body (to decide which `update_*` fields to pass).
- Parent refs **with kinds resolved** ("style-warm
  (style_bible)" vs a bare id).
- Direct children in the DAG (which nodes inherit from me?).
- Bound clips, with direct vs transitive distinction + the
  intersecting ancestor set for transitive binds.
- A humanised one-line summary.

The agent was cobbling this together with three calls. One
focused read is cheaper for the token budget and easier to
reason about at the call site.

**Decision.** Ship `describe_source_node(projectId, nodeId)`:

1. Returns `node`: id, kind, revision, contentHash, body, and
   parent refs annotated with each parent's resolved `kind`
   (or `"(missing)"` for dangling refs that survived a
   malformed import).
2. `children`: direct children from `Source.childIndex`
   (kinds resolved, sorted by id for stable readback).
3. `boundClips`: reuses `Project.clipsBoundTo(nodeId)` (the
   transitive-closure walker that already powers
   `list_clips_for_source`). Each entry flags `directly`
   (this node appears in the clip's binding) vs transitive
   (bound via a descendant in the DAG, named in
   `boundViaNodeIds`).
4. `summary`: humanised one-liner (name + snippet for
   character_ref / style_bible / brand_palette; top-level
   keys + first string for opaque bodies) — same humaniser
   shape as `list_source_nodes`.
5. `outputForLlm` renders a compact 4-line block: kind/id
   header, parents line, children line, bound-clip count
   split by direct/transitive.
6. Read-only, `project.read`.

**Alternatives considered.**

- *Extend `list_source_nodes` with a `deep=true` flag.*
  Rejected — a `list` tool's contract is many nodes; a
  `describe` tool's is one node with more structure. Mashing
  deep details into the list output either bloats every call
  or gates the useful bits behind an awkward flag.
- *Auto-resolve parent kinds inside `list_source_nodes`.*
  Useful, but every row in a long list would pay the lookup
  even when the caller only wanted ids. Keep the list lean,
  let the describe tool do the resolving.
- *Return the full parent SourceNodes (recursive).* Would
  explode token budget on deep DAGs. Kind + id is enough to
  drive the next drill-in; the agent can follow with
  another `describe_source_node` call when it wants deep
  body.

**Coverage.** 8 JVM tests:
- Describes character_ref with parent style_bible (parent
  kind resolved).
- Describes style_bible with two direct children.
- Direct vs transitive clip bindings distinguished, with
  `boundViaNodeIds` listing the ancestor path.
- Rejects missing project.
- Rejects missing node.
- Lonely node → empty parents/children/boundClips.
- Dangling parent id → `kind="(missing)"`.
- Output contentHash/revision match stored node.
- Summary humanises character_ref name+description.

**Registration.** Registered in all 5 composition roots
directly after `DescribeSourceDagTool` — describe-family
verbs clustered together (dag-level, then node-level).

**Drive-by.** Dropped a shadowed `Duration.absoluteValue`
extension in `ListTransitionsTool` — kotlin.time already
provides the member. Non-behavioural clean-up.

**SHA.** b6a5b3a363f82803dfacc1d34fe13844a39675ae

---
