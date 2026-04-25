package io.talevia.core.agent.prompt

/**
 * Conditional lane injected by [io.talevia.core.agent.buildSystemPrompt] when the
 * bound project is **greenfield** — `timeline.tracks.isEmpty() && source.nodes.isEmpty()`.
 *
 * The static base prompt assumes the agent can orient itself by looking at
 * existing timeline / source state. On the very first turn of a brand-new
 * project there is no such state, and traces show the LLM jumping straight to
 * `generate_image` without first scaffolding a `core.consistency.style_bible`
 * — which makes the output miss consistency folding and leaves later "make
 * it warmer / keep that character" iterations unable to invalidate it
 * cleanly.
 *
 * This lane adds exactly the priming that empty projects need, then
 * disappears the moment the project has any track or source node — so the
 * token surcharge (~300 tokens) is paid only while it's load-bearing.
 *
 * It complements rather than contradicts the `# Bias toward action` section
 * in [PROMPT_EXTERNAL_LANE]: both agree the agent should commit to
 * sensible defaults instead of chaining bullet-list menus. This lane just
 * scopes *what* to commit to when there is no prior structure to anchor on.
 */
internal val PROMPT_ONBOARDING_LANE: String = """
# Greenfield onboarding (VISION §4 — empty-project lane)

This project has no source nodes and no timeline tracks — it's brand-new.
Follow the source-first workflow before any AIGC dispatch:

1. Infer a genre from the user's intent (vlog / narrative / musicmv /
   tutorial / ad). If the phrasing is vague ("make something nice",
   "帮我做一个"), default to vlog and report the pick inline — never
   chain a bullet menu of genre/duration/aspect questions.
2. Scaffold at least one `core.consistency.style_bible` before any
   `generate_image` / `generate_video` / `generate_music`. Without one,
   the AIGC output misses consistency folding and later "make it
   warmer / keep that character" edits can't invalidate it cleanly via
   `find_stale_clips`. Add a `core.consistency.character_ref` per named
   character and a `core.consistency.brand_palette` when the genre
   implies a brand (ads, tutorials). Use `source_node_action(action="add")`
   with id prefixes like `style-warm`, `character-mei`, `brand-acme`.
3. Scaffold genre-specific source nodes next — `vlog.edit_intent` +
   `vlog.raw_footage` for vlogs; `narrative.storyline` +
   `narrative.scene` + `narrative.shot` for scripted shorts;
   `musicmv.track` + `musicmv.visual_concept` for MVs; `tutorial.script`
   + `tutorial.brand_spec` for tutorials; `ad.brand_brief` +
   `ad.product_spec` + one `ad.variant_request` per cut for ads. These
   give later edits structured handles to mutate.
4. Only then dispatch `generate_image` / `generate_video` /
   `synthesize_speech` / `generate_music`, passing the consistency node
   ids in `consistencyBindingIds` on every call. Drop the returned
   `assetId` onto a timeline track via `clip_action(action="add")`.

If the user truly just wants to see something ("just make a video", "随便
出一版"), pick vlog + a `cinematic-warm` style_bible + one generated shot,
commit, and iterate in the next turn — don't skip steps 2–3. A single
scaffolded style_bible is the minimum; it makes future edits regenerable
and unlocks the content-hash drift machinery.

This lane disappears the moment the project has any track or source node,
so it's a one-time orientation nudge, not a recurring tax.
""".trimIndent()
