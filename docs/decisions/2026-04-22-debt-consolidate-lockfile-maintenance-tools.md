## 2026-04-22 — Keep PruneLockfileTool / GcLockfileTool as two tools (debt evaluated)

Commit: `(docs-only — no code change; see reasoning below)`

**Context.** Backlog bullet `debt-consolidate-lockfile-maintenance-tools`
asked whether `PruneLockfileTool` + `GcLockfileTool` could fold into a
single `maintain_lockfile(action="prune"|"gc"|"all", ...)` tool, or
whether the divergent Input precedent (already used for
`apply_*`, `add_*`, `remove_*` variants — see
`2026-04-22-debt-consolidate-video-apply-variants.md`) applied here
too. This cycle is the evaluation.

Decision: **keep the two tools unchanged.** The four structural checks
surface the same disqualifier pattern as the apply-variants decision:
default behaviors conflict and the single-vs-policy semantic gap is
easier to teach the LLM via two focused tools than one branching tool.

**Decision analysis (the 4 structural checks).**

Input shapes (excluding the common `projectId`):

| Tool | Required | Optional | Field count | Semantic |
|---|---|---|---|---|
| `prune_lockfile` | — | `dryRun` | 1 | Drop rows with dead `assetId` (pure referential sweep) |
| `gc_lockfile` | — | `maxAgeDays, keepLatestPerTool, preserveLiveAssets, dryRun` | 4 | Drop rows failing age/count policy (with live-asset + pin rescues) |

1. **Default-behavior divergence.** `prune_lockfile(projectId)` with no
   other args = sweep orphans (the common case). `gc_lockfile(projectId)`
   with no other args = **no-op**, emits a helpText pointer to
   `prune_lockfile`. A merged `maintain_lockfile(projectId)` with no
   other args has to pick one meaning. Either it sweeps orphans (GC
   users expect "nothing happens without a policy") or it no-ops (prune
   users lose the zero-arg convenience). Both options regress one of
   the existing call-sites the tools were built around. This is the
   same "default regression" trap the apply-variants decision flagged.

2. **`preserveLiveAssets` semantic relationship to the policy.**
   - Prune: the **sole criterion** is "asset live-ness". No policy.
   - GC: `preserveLiveAssets=true` is a **rescue that runs AFTER
     policy selection**. If no policy is active, it's dead code.
   In a merged `maintain_lockfile`, `preserveLiveAssets` would have to
   mean different things depending on whether `action="prune"` or
   `action="gc"` — or the LLM has to remember "for orphan-sweeps,
   `preserveLiveAssets=false` is meaningless" (which would become a
   runtime-only validation, the failure mode §3a Rule 1 cautions
   against). Two tools = the semantic lives in helpText next to the
   one action it applies to.

3. **Output shape divergence.** `prune.Output.prunedEntries:
   List<PrunedSummary>` holds `(inputHash, toolId, assetId)`. `gc.Output.prunedEntries`
   holds `(inputHash, toolId, assetId, createdAtEpochMs, reason)`
   plus top-level `keptByLiveAssetGuardCount`, `keptByPinCount`, and
   `policiesApplied: List<String>`. The `reason` field ("age",
   "count", "age+count") is only meaningful when a policy ran;
   merging forces prune paths to fill it with a synthetic `"orphan"`
   marker (introducing a sentinel the LLM has to remember) or we lose
   the field on prune runs (breaking consumers that decode Output). The
   apply-variants decision flagged output-shape merging as the same
   trap on a different axis.

4. **Dependency asymmetry.** `PruneLockfileTool(projects)` — no clock
   needed. `GcLockfileTool(projects, clock)` — clock is the age-policy
   cutoff. Merged, the combined tool always needs `Clock`; every
   `AppContainer` (5 platforms) would inject it even for callers that
   only ever want orphan sweeps. Small overhead, but symmetric with
   the `MediaStorage` asymmetry the apply-variants decision also
   flagged — "the dependency list grows for every container passing
   this tool `clock` just to reach the policy branch".

**Token cost estimate.** Measured against shipped helpText + schema:
- `prune_lockfile` ≈ 210 tokens (shorter schema, 1 optional flag).
- `gc_lockfile` ≈ 410 tokens (4 optional params, long prose on pin/
  live-asset guard interactions).
- **Total: ≈ 620 tokens.**
- Merged `maintain_lockfile(action, ...)` estimated ≈ 540 tokens
  (still has to cover both policy branches plus the `action`
  discriminator and its conditional-field rules). Saving ≈ 80
  tokens per turn. Below the clarity-worth-it threshold that killed
  the apply-variants and add-variants consolidations.

**Alternatives considered.**

1. **Collapse into `maintain_lockfile(action="prune"|"gc"|"all", ...)`**
   — rejected for the four structural reasons above. Same failure
   mode as the apply-variants decision. The `action="all"` branch
   would also need to define interaction order (prune first, then
   gc? or interleaved? — introduces behavior the LLM can't
   deterministically predict).

2. **Collapse `prune_lockfile` into `gc_lockfile` with a synthetic
   `maxAgeDays=0, preserveLiveAssets=true` convenience default** —
   technically `preserveLiveAssets=true` + `maxAgeDays=0` drops
   every dead-asset row and keeps every live-asset row, which IS
   prune semantics. But this hides the "orphan sweep" operation
   behind a non-obvious parameter combination. The LLM would have
   to learn "to prune, pass `maxAgeDays=0` even though time has
   nothing to do with orphan sweeps" — the "silent type coercion"
   pattern the add-variants decision flagged.

3. **Keep both but rename for parallelism (`gc_lockfile_orphans` +
   `gc_lockfile_policy`)** — cosmetic. Current names
   (`prune_lockfile` / `gc_lockfile`) already map onto the
   established semantics; renaming would churn 5 `AppContainer`
   registrations plus every existing test for no functional gain.

**Reference precedents (cite-worthy in future §3a sweeps).**
- `2026-04-22-debt-consolidate-video-apply-variants.md` — 4-check
  format.
- `2026-04-22-debt-consolidate-video-remove-variants.md`,
  `2026-04-22-debt-consolidate-video-add-variants.md`,
  `2026-04-22-debt-consolidate-video-duplicate-variants.md` — same
  outcome for sibling tool families.

**Impact.**
- No code change. No tests modified.
- Backlog bullet `debt-consolidate-lockfile-maintenance-tools` removed.
- Decision doc preserves the "evaluated → kept" outcome so future
  sweeps don't re-litigate.
