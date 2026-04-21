## 2026-04-21 — create_project_from_template gains ad / musicmv / tutorial (VISION §5.4 小白路径)

Commit: `4a6745d`

**Context.** VISION §5.4 novice-path rubric asks: "给 agent 一句高层意图
（'做个毕业 vlog'），它能不能自主推断 source、调度工具、产出可看的初稿?"
`create_project_from_template` was the bootstrap that lets the agent skip the
"define N source nodes from scratch" chore and jump straight to "fill in the
TODO placeholders." But only `narrative` and `vlog` templates shipped, even
though `core/domain/source/genre/` holds five typed schemas (narrative, vlog,
ad, musicmv, tutorial) with matching builder helpers in each genre's
`*SourceExt.kt`. For an `ad`, `musicmv`, or `tutorial` project the novice path
still required the agent to know all the genre-specific node kinds, wire
parents by hand, and populate every required field — exactly the friction
this tool was built to eliminate.

**Decision.** Add three new template branches that compose the existing genre
builders atomically in one `ProjectStore.upsert`, matching the shape of
`seedNarrative` / `seedVlog`:

- `ad` → brand_palette + brand_brief + product_spec + variant_request. The
  variant is parented on both brief and product so a brief edit flows down
  to every variant via DAG propagation (the distinguishing property of the
  genre per `AdBodies.kt`).
- `musicmv` → brand_palette + character_ref (performer) + visual_concept
  (parent = palette) + performance_shot (parents = concept + performer).
  **`musicmv.track` is intentionally not seeded** — `MusicMvTrackBody`
  requires a non-nullable `AssetId`, and there's no imported asset at
  bootstrap time. The help text + decision doc call this out so a future
  "define_musicmv_track" tool can close the gap cleanly.
- `tutorial` → style_bible + tutorial_brand_spec + tutorial_script (parents
  = style + brand-spec) + tutorial_broll_library (empty asset list).

All new templates use the same `TODO: …` placeholder convention as the
existing two so the agent can distinguish "seeded, needs filling" from
"user-authored" via `describe_source_node`.

**Alternatives considered.**

1. *Pre-seed `musicmv.track` with a placeholder `AssetId("TODO-music")`.*
   Rejected — a dangling asset id would silently corrupt downstream tools
   that follow references (cache-hit lookups, `list_clips_bound_to_asset`).
   The stale-asset invariant is load-bearing; better to leave the node out
   and document the follow-up than to plant a landmine.
2. *Make `MusicMvTrackBody.assetId` nullable.* Rejected — would cascade
   through every consumer (AIGC prompt folding, cut alignment, export),
   each having to defensive-check a field that's semantically mandatory.
   The right contract is "no track without an asset."
3. *Introduce a sixth "blank" template that seeds nothing but consistency
   nodes.* Rejected as scope creep — `create_project` + the `define_*`
   tools already cover that path. Templates only earn their keep when they
   encode a *genre*'s characteristic skeleton.

**Coverage.** `CreateProjectFromTemplateToolTest` — three new tests: `ad`
seeds four nodes and wires variant→{brief,product} parents; `musicmv` seeds
four nodes and the asserts-track-kind-absent check; `tutorial` seeds four
nodes with script→{style,brand-spec} parents.

**Registration.** No AppContainer changes — the tool was already registered
in all five containers (CLI / Desktop / Server / Android / iOS).
