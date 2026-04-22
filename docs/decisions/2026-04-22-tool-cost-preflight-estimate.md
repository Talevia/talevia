## 2026-04-22 — list_tools surfaces `priceBasis` for preflight cost reasoning (VISION §5.2)

Commit: `8277aa5`

**Context.** `AigcBudgetGuard` (cycle 16) wraps every AIGC tool and
raises a permission ASK when cumulative session cost crosses a cap.
`ListToolsTool.Summary.avgCostCents` (cycle 20) reports the
retrospective running average per tool. Both fire **after** a call —
the LLM still has no surface that says "if you call `generate_video`
on a 10-second clip with sora-turbo, it'll be roughly $3 per
invocation" at planning time.

Result: the agent plans blind. It picks `generate_video` vs
`generate_image` based on creative intent, then the budget guard
fires mid-plan because video is 100× more expensive than a still.
For a brand-new session `avgCostCents` is null (no priced calls
yet), so the retrospective signal is useless on the first call of
the turn.

The backlog bullet `tool-cost-preflight-estimate` hedged between
two shapes:
1. New `describe_tool_cost(toolId, input) -> CostEstimate(cents, currency, basis)`
2. `list_tools` extension with `estimateCentsPer` / basis field.

**Decision.** Going with shape (2). Zero new tools (§3a rule 1).

1. **New `AigcPricing.priceBasisFor(toolId: String): String?`** —
   returns a single-line textual description of the tool's pricing
   shape for AIGC tool ids, null for non-priced tools:
   - `generate_image` → `"OpenAI: gpt-image-1 ~$0.04/square, ~$0.06/non-square; dall-e-3 ~$0.04/square, ~$0.08/non-square; dall-e-2 ~$0.02 flat."`
   - `synthesize_speech` → `"OpenAI: tts-1 ~$0.015/1k chars; tts-1-hd ~$0.030/1k chars; gpt-4o-mini-tts billed as tts-1."`
   - `generate_video` → `"OpenAI Sora: ~$0.30/sec (sora/sora-turbo); ~$0.50/sec (sora-hd/sora-1080p)."`
   - `generate_music` → `"Replicate meta/musicgen: ~$0.02/sec of requested output."`
   - `upscale_asset` → `"Replicate nightmareai/real-esrgan: ~$0.05 per call (flat)."`
   - everything else → `null`.

   Text format preserves pricing SHAPE (`$/sq vs $/rect`, `$/sec`,
   `$/1k chars`) so the LLM can do input-dependent scaling on its
   own — it knows a square image costs less than a rectangular one,
   a 30s video costs 3× a 10s video, etc.

2. **`ListToolsTool.Summary.priceBasis: String? = null`** — new
   field. Populated via `AigcPricing.priceBasisFor(rt.id)` in the
   `rawTools.map { ... }` block. Default null preserves forward-compat
   (§3a rule 7).

Tool-count delta: **0 new tools** (extension). New field per row:
~60 tokens for priced tools, ~0 for everything else. Turn-steady
LLM context cost: unchanged — `list_tools` is only dispatched on
demand by the agent.

Why text not number:
- A numeric `estimateCentsPer` field needs the **full input**
  (width, height, duration, text length, …) to compute; the agent
  has that at dispatch time, but `list_tools` is a read-only
  enumerator that doesn't take per-tool inputs.
- A numeric basis per tool (e.g. "8 cents") throws away the shape:
  the LLM can't scale "1080p vs 720p" or "10s vs 30s" from a
  single scalar.
- Text forwards the shape verbatim. The agent reads "sora ~$0.30/sec"
  and computes `0.30 × 10s = $3` for a 10-second generation. That's
  the right division of labour: the pricing module describes the
  curve; the LLM does the arithmetic.

**Alternatives considered.**

1. **New `describe_tool_cost(toolId, input)` dedicated tool.**
   Rejected on §3a rule 1 — extending `list_tools` is zero-cost on
   tool count. Adding a tool would also require full input schema
   forwarding (each AIGC tool has different required fields) which
   duplicates surface the LLM already sees on the target tool's own
   spec.

2. **Numeric `estimateCentsPer` field keyed on a synthesised
   input shape.** Rejected. Would need `ListToolsTool` to guess
   representative inputs per tool (e.g. "1024×1024 image", "10
   second video"). The synth inputs leak bias into the reported
   number — a user who always asks for 2048×2048 images would see
   numbers half their actual cost. Text preserves the full
   pricing table so the LLM can do the local calculation on the
   actual call's input.

3. **Include the `priceBasis` text in the tool's `helpText`
   directly, not in `Summary.priceBasis`.** Rejected. `helpText` is
   part of the baseline tool spec shipped every turn — tagging
   every AIGC tool's helpText with pricing moves ~300 tokens from
   on-demand into the turn-steady context budget. `Summary.priceBasis`
   is only materialised when the agent actively queries
   `list_tools`. Same info, different footprint.

4. **Populate `priceBasis` lazily from provenance / model config
   rather than hardcoded in `AigcPricing`.** Rejected for this
   cycle. Providers publish list prices that drift slowly
   (quarters, not weeks); a lookup table with a clear single-file
   blast radius is the same pattern `AigcPricing.estimateCents`
   already uses for the retrospective numbers. When providers
   reprice, both the numeric table and the basis string update in
   one PR.

**Coverage.** `ListToolsToolTest` gains 3 cases covering §3a rule 9
semantic boundaries:

- `priceBasisNullForNonAigcTools` — null for `list_tools` /
  `todowrite` / `echo` (the baseline test fixture). Guards against
  the `priceBasisFor` fallback wrongly returning text for unknown
  ids.
- `priceBasisPopulatedForAigcToolIds` — register fake tools under
  each priced id (`generate_image`, `synthesize_speech`,
  `generate_video`, `generate_music`, `upscale_asset`); assert each
  surfaces a non-null, non-blank `priceBasis` AND that `list_tools`
  itself stays null.
- `priceBasisForwardsExactAigcPricingText` — the forward is
  verbatim, not trimmed / summarised. Guards against a future
  regression that adds "helpful" post-processing to the basis
  string (which would break the LLM's "$/sq vs $/rect" arithmetic
  reading).

Pre-existing `ListToolsToolTest` cases (enumerate / prefix / limit /
helpText / emptyResult / cost hints × 5) pass unchanged.

Full cross-platform: `:core:jvmTest`,
`:platform-impls:video-ffmpeg-jvm:test`, `:apps:server:test`,
`:apps:desktop:assemble`, `:apps:android:assembleDebug`,
`:core:compileKotlinIosSimulatorArm64`, `ktlintCheck` — all green.

**Registration.** No `AppContainer` change. `ListToolsTool` is
already wired in all 5 containers (CLI / Desktop / Server / Android
/ iOS); the `priceBasis` field flows automatically. New tool ids
that want preflight estimates register themselves in
`AigcPricing.priceBasisFor` alongside the numeric rule — one
file, clear blast radius.

§3a rundown:
- Rule 1 (tool growth): 0 new tools. ✓
- Rule 5 (genre-agnostic): pricing keyed on AIGC tool id, not
  genre. ✓
- Rule 7 (serialization): new field has `= null` default. ✓
- Rule 8 (5-platform): no new tool → no wiring change. ✓
- Rule 10 (LLM context): `priceBasis` only populates on `list_tools`
  dispatch (on-demand); zero turn-steady cost. ✓

---
