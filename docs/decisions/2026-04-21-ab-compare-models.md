## 2026-04-21 — compare_aigc_candidates for parallel A/B on AIGC tools (VISION §5.2 rubric)

Commit: `dbdb8ce`

**Context.** VISION §5.2's "新效果接入成本" rubric: adding a new AIGC
effect should be cheap, *and* picking a model for an existing effect
shouldn't force the user to run the same prompt by hand across five
models and eyeball the outputs. Today every AIGC tool
(`generate_image`, `generate_music`, `generate_video`,
`synthesize_speech`, `upscale_asset`) accepts a single `model` per
invocation. To A/B across `sdxl` vs `flux-dev` on the same prompt,
the user / agent dispatches twice, sequentially, and has to
correlate the two Outputs manually. Backlog bullet
(`ab-compare-models`, P1 #2) asked for a primitive that fans out in
one call and returns a `{modelId → assetId}` map.

(Why not the P0s: `aigc-cost-tracking-per-session` is still blocked
on pricing-table product decisions; `per-clip-incremental-render`
(P1 top) is deferred per
`2026-04-19-per-clip-incremental-render-deferred-rationale-recorded.md`.
Both preserved in backlog.)

**Decision.** New `CompareAigcCandidatesTool` (tool id
`compare_aigc_candidates`) — a meta-tool that dispatches an existing
AIGC tool in parallel against a list of model ids. Input:

```kotlin
Input(
    toolId: String,        // one of generate_image / generate_music / generate_video
                           //        / synthesize_speech / upscale_asset
    baseInput: JsonObject, // underlying tool's normal Input minus `model`
    models: List<String>,  // non-empty + pairwise distinct
)
```

Execution:
1. Validate `toolId` against the `ALLOWED_TOOL_IDS` whitelist.
2. Validate `models` non-empty + distinct.
3. Validate `baseInput` does NOT include a `model` field (fail-loud
   guard — injecting one per candidate makes a stale base field
   ambiguous).
4. Resolve the underlying tool via `ToolRegistry[toolId]`.
5. `coroutineScope { async { … } }.awaitAll()` to fan out one
   dispatch per model, each with `baseInput + ("model" → modelId)`.
6. Per-candidate failures captured into `Candidate.error` so
   sibling candidates are not cancelled.

Output: one `Candidate(modelId, assetId?, output?, error?)` per
model, plus aggregate `successCount` / `errorCount`. Consumers
decode the typed Output from `output: JsonObject?` using the
underlying tool's `outputSerializer`. `assetId` is extracted
opportunistically from the `assetId: String` field every AIGC tool
ships on its Output — saves the common caller a second JSON decode.

Each successful dispatch runs through the underlying AIGC tool's
normal lockfile-write path, so every candidate produces an **unpinned**
lockfile entry. The user / agent picks a winner with
`set_lockfile_entry_pinned`. No special pinning logic in compare
itself — the pinning concern stays with the existing tool.

§3a checklist details below pre-empt the usual objections to
net-tool-count growth and parallel dispatch hazards.

**Alternatives considered.**

- *Add a `variants: List<String>` parameter to each of the 5 AIGC
  tools*: rejected — quintuples surface area, couples every AIGC
  tool to the A/B concern, and reintroduces the problem each time a
  new AIGC tool ships. The meta-tool is the abstraction.
- *Make the compare tool parameterised over a `CompareStrategy`
  interface* (e.g. strategies for "compare seeds", "compare
  prompts", not just "compare models"): rejected — YAGNI. The
  bullet explicitly asks about models; other parallel-A/B shapes
  can be separate tools when a real driver appears. Matches
  OpenCode's "extract behavior, not structure" discipline.
- *Dispatch sequentially rather than in parallel*: rejected —
  defeats half the feature's value. 5 models × 8-second image gen
  serialised = 40s wall-clock for a user waiting; parallel is ~8s.
  Coroutines make fan-out cheap.
- *Pin all candidates automatically and let the user unpin*:
  rejected — pinning broadcasts "this is the winner" semantics;
  pinning every candidate would pollute the pin set. The
  bullet explicitly said "不 pin" for this exact reason.
- *Cancel sibling candidates on the first failure*: rejected —
  the whole point of A/B is seeing ALL the outcomes. A single
  model 503 would waste 4 otherwise-valid comparisons.

Industry consensus referenced: `kubernetes` deployment rollout's
parallel-pod-start + per-pod-failure-isolation pattern; OpenCode's
`tool/tool.ts` treats tools as composable dispatch targets — a
meta-tool that composes existing tools is the Kotlin equivalent.
`coroutineScope { async { … } }` is the canonical Kotlin
structured-concurrency shape for bounded fan-out.

**Coverage.**

- `CompareAigcCandidatesToolTest.twoModelsFanOutAndBothSucceed` —
  two-model happy path; verifies distinct `assetId`s, both
  candidates received their intended model, aggregates match.
- `CompareAigcCandidatesToolTest.oneModelFailsOthersStillReturn`
  — three models with one throwing; error captured in the broken
  candidate's `error` field (assetId/output null), siblings
  succeed, `errorCount=1`, `successCount=2`.
- `CompareAigcCandidatesToolTest.unknownToolIdRejected` — non-AIGC
  toolId (`fs_read`) fails loud with accepted-list hint.
- `CompareAigcCandidatesToolTest.emptyModelsListRejected` —
  empty `models` fails loud.
- `CompareAigcCandidatesToolTest.duplicateModelsRejected` —
  `["sdxl", "sdxl"]` fails loud.
- `CompareAigcCandidatesToolTest.baseInputContainingModelRejected`
  — baseInput with a stale `model` field fails loud.
- `CompareAigcCandidatesToolTest.allowedToolIdButNotRegisteredFailsLoud`
  — `generate_music` in whitelist but not registered (Replicate
  key missing) → fail-loud with a `list_tools` discovery hint.
- `CompareAigcCandidatesToolTest.singleModelIsDegenerateButShipsFine`
  — the degenerate 1-model case still runs (doesn't require ≥2).

FakeAigcTool double registered under the real `generate_image` id
so the whitelist accepts it; simulated failures via `failOnModel`.

**Registration.** Five AppContainers each gain one import + one
register call:
- `apps/cli/src/main/kotlin/io/talevia/cli/CliContainer.kt`
- `apps/desktop/src/main/kotlin/io/talevia/desktop/AppContainer.kt`
- `apps/server/src/main/kotlin/io/talevia/server/ServerContainer.kt`
- `apps/android/src/main/kotlin/io/talevia/android/AndroidAppContainer.kt`
- `apps/ios/Talevia/Platform/AppContainer.swift`

On Android and iOS the underlying AIGC tools are not yet wired (no
Replicate / Anthropic image client in those containers today), so
the compare tool is effectively a no-op there until mobile picks up
AIGC providers. Wiring it now keeps §3a #8's five-end parity and
means a mobile-side AIGC rollout doesn't need a follow-up
registration pass.

§3a checklist pass:
- #1 +1 tool net. Justified: the A/B concept is a meta-operation
  that doesn't fit any single AIGC tool's surface; centralising it
  avoids replicating the parameter across 5 tools. No existing tool
  deleted; none has a natural home for this parameter. ✓
- #2 not a Define/Update pair. ✓
- #3 no Project blob growth. ✓
- #4 no binary flag. ✓
- #5 genre-neutral — `toolId` / `model` are generic; whitelist
  contains the existing AIGC tool ids (all genre-neutral). ✓
- #6 no session-binding surface added; `ctx` passthrough preserves
  existing `currentProjectId` defaulting on the underlying tool. ✓
- #7 new Input / Output fields all have explicit defaults (Candidate
  fields default null). ✓
- #8 five-end: all containers updated. ✓
- #9 eight new tests cover happy / single-model / partial failure /
  all-rejected paths (4 rejections: unknown toolId, empty models,
  duplicate models, stale `model` in baseInput) + unregistered-tool
  edge. More edges than happy paths. ✓
- #10 +~250 tokens for the new tool spec + helpText + schema. Under
  500 cap. Breaks even over multi-turn sessions where the user
  would otherwise issue multiple separate generate_image calls. ✓
