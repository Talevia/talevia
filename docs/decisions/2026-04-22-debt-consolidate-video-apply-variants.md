## 2026-04-22 — Keep ApplyFilter / ApplyLut as two tools (debt evaluated)

Commit: `(docs-only — no code change; see reasoning below)`

**Context.** Backlog bullet `debt-consolidate-video-apply-variants` asked
to evaluate whether `ApplyFilterTool` + `ApplyLutTool` could fold into a
single `apply_to_clip(target="filter"|"lut", …)` tool, following the
same "maybe collapse, maybe keep" evaluation already done on
`add_*` (`2026-04-22-debt-consolidate-video-add-variants.md`) and
`remove_*` (`2026-04-22-debt-consolidate-video-remove-variants.md`).
The bullet itself hedges: "评估合为 `apply_to_clip(...)` 或按
add/remove variants 的先例（divergent Input 保留四件套）在 decision
里说明". This cycle is the evaluation.

Decision: **keep the two tools unchanged.** The Inputs are structurally
divergent enough that a merged schema would trade loud
`additionalProperties=false` validation for runtime branch-specific
rejects, at minimal token savings.

**Decision analysis.**

The two Input shapes (excluding the session-resolved `projectId?`):

| Tool          | Required                              | Optional                                                              | Field count | Shape               |
|---------------|---------------------------------------|-----------------------------------------------------------------------|-------------|---------------------|
| `apply_filter`| `filterName`                          | `params: Map<String, Float>`, `clipIds`, `trackId`, `allVideoClips`   | 5           | **Batch** (N clips) |
| `apply_lut`   | `clipId`, (`lutAssetId` ⊕ `styleBibleId`) | —                                                                  | 3           | **Single** clip      |

Four structural problems with a merged `apply_to_clip(target=…)`:

1. **Single-vs-batch selector mismatch.** `apply_filter` already has
   three selectors (`clipIds: List<String>` / `trackId` / `allVideoClips`,
   exactly one required) to batch-apply across clips in one atomic edit;
   `apply_lut` takes exactly one `clipId` and materially can't batch
   because a single `styleBibleId`-driven LUT carries a specific
   `sourceBinding` mutation that has to land on one clip at a time to
   keep the lockfile / stale-propagation lane accurate. Collapsing
   them means the LLM gets a tool where "batch selector" fields are
   **valid for `target=filter` but rejected for `target=lut`" — a
   conditional-validation hole the JSON-schema bundle can't express
   cleanly.

2. **XOR / mutual-exclusion complexity doubles.** `apply_filter` has
   one XOR (clipIds / trackId / allVideoClips). `apply_lut` has one
   XOR (lutAssetId / styleBibleId). Merged, the schema carries *both*
   XOR groups plus a cross-branch filter-name XOR-with-lut-asset
   constraint. Any schema validator can encode flat-field XORs; the
   cross-branch is runtime-only. The add-variants decision already
   flagged this as the disqualifying move — dropping
   `additionalProperties=false` for runtime-only validation is the
   chronic failure mode we're trying to avoid.

3. **`sourceBinding` semantics diverge.** `apply_lut` with a
   `styleBibleId` mutates `Clip.sourceBinding` to include the
   style_bible node id — that's the hook that makes subsequent
   style_bible edits propagate via `staleClipsFromLockfile` and
   `regenerate_stale_clips`. `apply_filter` never touches
   `sourceBinding`. A merged tool would either need to always
   compute/document the binding (confusing for `target=filter` where
   it's a no-op) or branch on `target` (runtime-only invariant). Both
   costs land on the LLM — it has to remember "for `target=lut +
   styleBibleId`, the clip becomes stale-linked; for the rest, no".
   Discoverable in two separate tools; obfuscated in a merged one.

4. **MediaStorage dependency is `apply_lut`-only.** The constructor
   shapes differ: `ApplyFilterTool(store)` vs.
   `ApplyLutTool(store, media)`. Merged, the combined tool needs
   `MediaStorage` regardless of whether the caller ever hits the LUT
   path. Not a user-visible issue but a small five-container
   registration asymmetry (the dependency list grows for every
   container passing this tool `media` just to reach the `target=lut`
   branch).

Discriminator cost on the LLM side (measured against shipped helpText
+ schema): 2 separate tools ≈ 510 tokens total (ApplyFilter ~310,
ApplyLut ~200 — both in line with the 2026-04-22 add-variants
measurements). Merged tool ≈ 440 tokens — ~70 token saving. Below
the threshold where clarity loss becomes worth it, mirroring the
add/remove-variants analysis: the JSON Schema `additionalProperties`
guard is the single most useful anti-typo feature these tools give
the LLM, and trading it away for ~70 tokens a turn is the wrong
trade. The §3a rule 1 "no net tool growth" caveat is symmetric:
consolidating for aesthetic "fewer tool ids" is the same mistake as
growing without rationale.

**Alternatives considered.**

1. **Collapse into `apply_to_clip(target="filter"|"lut", ...)`** —
   rejected for the four structural reasons above. Same failure mode
   as the add/remove-variants evaluations already documented.
2. **Collapse `apply_lut` into `apply_filter` with `filterName="lut"`** —
   partially already the case: the filter implementation uses
   `Filter.name == "lut"` as its type marker. But the LUT-via-style_bible
   source-binding + the single-clip semantic sit on top; reducing to
   just `apply_filter(filterName="lut", params={...lutAssetId...})`
   would require the LLM to smuggle an asset id through the
   `Map<String, Float>` params shape. That's the "silent type
   coercion" failure pattern we've already burned cycles avoiding on
   `add_subtitles` + friends.
3. **Rename `apply_lut` to mirror `apply_filter`'s batch interface
   (accept `clipIds` / `trackId` / `allVideoClips`)** — rejected.
   Would require the LUT source-binding mutation to run per-clip; the
   lockfile append + per-clip `sourceBinding` update per target clip
   is not the shape the `staleClipsFromLockfile` lane expects. A
   batch-LUT is a meaningfully different operation that would need
   its own design. Filed implicitly for future work if the need
   surfaces (today's workflow "apply same LUT to 30 clips" is an
   edge case — style_bible applied once + `regenerate_stale_clips`
   after source edit is the path VISION §5.5 assumes).

**Coverage.** Docs-only — no code touched, no test added. Existing
`ApplyFilterTool` + `ApplyLutTool` tests stay green. The
`TaleviaSystemPromptTest` keyword-list check does not mention
`apply_to_clip` / `apply_lut` / `apply_filter` individually, so no
system-prompt expectation shifted.

**Registration.** No registration churn. Both tools remain registered
in all five AppContainers (CLI / Desktop / Server / Android / iOS) via
their existing constructor signatures.

---
