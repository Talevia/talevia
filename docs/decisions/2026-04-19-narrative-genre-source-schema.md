## 2026-04-19 — Narrative genre source schema (second concrete genre)

**Context.** VISION §5.1 asks "新 genre（例如从叙事片扩到 MV）要加 source schema,
需要改 Core 还是只需扩展?" Only one genre (vlog) existed, so the extensibility
claim was theoretical. The narrative genre is the VISION §6 flagship example and
the right second genre to pressure-test the boundary.

**Decision.**
- New package `core/domain/source/genre/narrative/` with three files mirroring
  the vlog exemplar:
  - `NarrativeNodeKinds.kt` — four dotted-namespace constants: `narrative.world`,
    `narrative.storyline`, `narrative.scene`, `narrative.shot`.
  - `NarrativeBodies.kt` — typed `@Serializable` bodies: `NarrativeWorldBody`
    (`name, description, era, referenceAssetIds`), `NarrativeStorylineBody`
    (`logline, synopsis, acts, targetDurationSeconds`), `NarrativeSceneBody`
    (`title, location, timeOfDay, action, characterIds`), `NarrativeShotBody`
    (`sceneId, framing, cameraMovement, action, dialogue, speakerId,
    targetDurationSeconds`).
  - `NarrativeSourceExt.kt` — `add…` builders (each accepting optional
    `parents: List<SourceRef>` so the caller wires the DAG at construction) and
    `as…` typed readers that return `null` on kind mismatch (same shape as the
    vlog readers).
- **Character nodes are reused, not minted.** Narrative deliberately does
  *not* define `narrative.character` — the genre-agnostic
  `core.consistency.character_ref` already serves that role, and all three
  AIGC tools fold it uniformly. A per-genre character kind would fork the
  §3.3 consistency lane.
- **Zero Core changes.** The narrative package only touches `Source.addNode`
  and `SourceRef` (both already public). Confirms the anti-requirement
  "在 Core 里硬编码某一个 genre 的 source schema" is unviolated.
- **No per-genre tools this round.** The generic `import_source_node` / the
  existing consistency `define_*` tools already let an agent populate a
  narrative graph. Purpose-built `define_narrative_world` / `..._scene` tools
  are a later call once we see whether the agent asks for them or just uses
  `import_source_node` + the body schema.
- Tests: `NarrativeSourceTest` covers round-trip for each kind, kind-dispatch
  null-on-mismatch, narrative + vlog coexistence in one Source, and a world
  → scene → shot stale-propagation walk through the genre-agnostic DAG
  (`Source.stale`) to prove parents wiring works.

**Alternatives considered.**
- **Define `narrative.character`.** Rejected — duplicates
  `core.consistency.character_ref` and fragments the cross-shot consistency
  lane that the AIGC tools already honour. The rule "character consistency
  is not genre-specific" is what keeps the fold logic DRY.
- **Fold scene/shot into one "beat" kind.** Rejected — scene and shot have
  different coarseness (scene = "what happens", shot = "how to film it") and
  the compiler targets shots one-to-one with clips. Collapsing them would
  force a flag on the body to distinguish, which is exactly the shape of a
  separate kind.
- **Promote `acts: List<String>` to `List<NarrativeActBody>`.** Rejected —
  ties the schema to a three-act assumption. Free-form strings let comedies,
  short films, and branching structures fit without pattern-matching on a
  typed shape.
- **Ship `define_narrative_*` tools in the same commit.** Rejected as scope
  creep — the schema-without-tools path is already usable via
  `import_source_node`, and adding tools is cheap once we know the agent
  actually reaches for them (YAGNI for tool sugar).

**Why.** VISION §5.1 rubric score goes from "有… 但只有一个 genre exemplar"
to "有… 两个独立 genre, 走的是同一条扩展路径" — the extensibility claim is
now backed by evidence. The narrative schema also unblocks the VISION §6
worked example ("修改主角发色 → 传导到 character reference → 引用该
reference 的所有镜头标记 stale → 只重编译这些镜头") as an end-to-end demo
path.

**How to apply.** Future genres (MV, tutorial, ad) follow the exact same
shape: a sibling package under `source/genre/<genre>/`, three files
(`*NodeKinds.kt`, `*Bodies.kt`, `*SourceExt.kt`). Do not import across genre
packages, and do not mint per-genre character / style / brand nodes — those
already live in `source.consistency`.

---
