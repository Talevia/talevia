## 2026-04-20 — music-mv / tutorial / ad genre body schemas (VISION §2 genre coverage)

Commit: `d42b6ee`

**Context.** VISION §2 names five genres (叙事短片 / Vlog / Music MV /
Tutorial / Ad · Marketing), but `core/domain/source/genre/` only had
two sibling packages — `narrative/` and `vlog/`. The claim "新 genre
要能通过扩展 source schema 支持，而不是改 Core" (VISION §2 / §5.1)
stayed theoretical for three of the five named genres. An agent asked
to "make a 30-second ad" or "cut a tutorial" had to reach for generic
`import_source_node` with free-form JSON because there were no typed
writers / readers to lean on; the system prompt also didn't teach the
kind strings, so kind-dispatch drifted toward ad-hoc.

**Decision.** Add three sibling genre packages, each with the same
three-file layout as `vlog/` and `narrative/`:

- `musicmv/` — `musicmv.track` (source audio + BPM/key hints),
  `musicmv.visual_concept` (mood / motifs / optional `paletteRef` to
  a `brand_palette` node), `musicmv.performance_shot` (performer +
  imported takes).
- `tutorial/` — `tutorial.script` (spoken text + free-form
  `segments` list for chaptering), `tutorial.broll_library` (mirrors
  `VlogRawFootageBody` — tutorials are raw-footage-heavy),
  `tutorial.brand_spec` (product name + lower-third styling + optional
  logo asset).
- `ad/` — `ad.brand_brief` (strategy + audience + CTA),
  `ad.product_spec` (product + reference imagery),
  `ad.variant_request` (one node per shipping cut —
  duration × aspect × language).

All bodies are stringly-typed `data class`es: no enums on mood /
motif / tone / aspect ratio, because genre schemas have to absorb
edge cases without a Core recompile and enums the agent will violate
on day two are net-negative. Writers and readers round-trip through
the canonical `JsonConfig.default`. Writers accept optional
`parents: List<SourceRef>` so downstream nodes (e.g. a performance
shot on top of a track + visual concept, or a variant on top of a
brief + product spec) can wire the DAG stale-propagation edges at
construction time.

**Alternatives considered.**

- *Single `misc` genre with a bag of free-form fields.* Rejected —
  the whole point of the genre layer is typed bodies the agent (and
  UI) can reason about; collapsing three domains into one trades
  extensibility for laziness and reopens the "Core grew a new genre"
  failure mode because every new field lands in the shared type.
- *`define_*` tools for each new kind (as we have for
  `character_ref` / `style_bible` / `brand_palette`).* Rejected per
  CLAUDE.md's explicit rule — `define_*` is reserved for consistency
  nodes that need idempotent id-based upserts. Genre nodes are
  created via the existing generic `import_source_node` tool, which
  already accepts arbitrary kind strings + bodies.
- *Enums for `aspectRatio` / `language` / `mood`.* Rejected on the
  same grounds narrative rejected them: platforms keep inventing new
  aspect ratios ("2:3 for TikTok-shop", "4:5 for Instagram-carousel"),
  and hard-coding a subset forces a Core recompile when the first new
  one arrives. Stringly-typed with UI conventions is cheaper to
  evolve.
- *Single `ad.ad` node with nested variants.* Rejected —
  variant-heaviness is the distinguishing property of the ad genre,
  and each variant is a distinct deliverable that needs independent
  contentHash / DAG propagation. One variant per node lets an edit
  to the brief flow through the DAG to every variant cleanly.
- *Fold palette into a `musicmv.palette` kind.* Rejected — brand /
  palette consistency is §3.3 first-class and must work cross-genre.
  A per-genre palette would fragment the consistency lane without
  benefit; instead `musicmv.visual_concept.paletteRef` and
  `tutorial.brand_spec` carry local pointers only where the genre
  adds value on top of the shared `core.consistency.brand_palette`.

**Coverage.** Three `*BodiesTest` classes (one per genre), each with
four assertions:
1. Every kind round-trips through `Source.addXxx` + `SourceNode.asXxx`
   with the full body intact.
2. `asXxx()` returns `null` when called on a node of a different
   kind — the kind-dispatch `when`/`let` chain stays safe without
   try/catch.
3. `SourceNode.contentHash` differs between two distinct bodies of
   the same kind, so DAG stale propagation fires on body edits (not
   just structural edits).
4. Kinds coexist via `Source` without collision (implicit in the
   first assertion since multiple kinds land in the same graph).

`TaleviaSystemPromptTest` now asserts the new kind strings so the
regression guard catches silent prompt regressions that would leave
the agent guessing about which kind to import.

**Registration.** None needed. Genre bodies are pure schema — no
tool registration, no composition-root wiring. Agents create genre
nodes via `import_source_node` (already registered everywhere) and
discover them via `list_source_nodes` / `describe_source_node`. The
four app containers (CLI / Desktop / Server / Android / iOS) are
untouched — that's the invariant we wanted to prove.
