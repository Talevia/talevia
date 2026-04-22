## 2026-04-22 — Keep SetClipAssetPinned / SetLockfileEntryPinned as two tools (debt evaluated)

Commit: `(docs-only — no code change; see reasoning below)`

**Context.** Backlog bullet `debt-consolidate-pinning-tools` asked
whether `SetClipAssetPinnedTool` + `SetLockfileEntryPinnedTool`
could fold into a single
`set_pinned(target="clip"|"lockfile_entry", id, pinned)` tool,
following the evaluation pattern already run on:
- `add_*` (`2026-04-22-debt-consolidate-video-add-variants.md`)
- `remove_*` (`2026-04-22-debt-consolidate-video-remove-variants.md`)
- `apply_*` (`2026-04-22-debt-consolidate-video-apply-variants.md`)
- `snapshot_*` (`2026-04-22-debt-consolidate-project-snapshot-ops.md`)
- `duplicate_*` (`2026-04-22-debt-consolidate-video-duplicate-variants.md`)
- `maintain lockfile` (`2026-04-22-debt-consolidate-lockfile-maintenance-tools.md`)

This cycle is the evaluation. Decision: **keep the two tools
unchanged.** Same structural blockers as the six prior evaluations;
the discriminator saving (~60 tokens/turn) is again dwarfed by the
identifier-type + output-shape divergence.

**Decision analysis.**

The two Input shapes (excluding `projectId`):

| Tool                        | Identifier                | Resolution hops                   | Output anchor          |
|-----------------------------|---------------------------|-----------------------------------|------------------------|
| `set_clip_asset_pinned`     | `clipId: String`          | clip → asset → lockfile entry (2) | `clipId` + `assetId`   |
| `set_lockfile_entry_pinned` | `inputHash: String`       | lockfile entry (1 direct)         | `assetId` only         |

Four structural blockers — the canonical "union-schema antipattern"
pattern that disqualified the six prior consolidations:

1. **Different identifier types.** `clipId` resolves through the
   timeline; `inputHash` is the stable lockfile handle. Merged
   tool would need a discriminator-sensitive field name per branch
   (or one polymorphic `id: String` that the runtime re-interprets
   per `target`). The latter route is the "pass a raw string, we'll
   figure it out" shape that silently accepts wrong-type inputs —
   passing an inputHash where clipId was expected just runs the
   lockfile entry through the clip→asset→lockfile traversal and
   fails with a confusing "clip <hash> not found" error instead of
   "wrong target type".

2. **Different resolution paths + failure modes.**
   `set_clip_asset_pinned` fails loudly for text clips (no asset)
   and for clips whose asset has no lockfile entry (imported media
   → routes user to `set_lockfile_entry_pinned`). The two tools'
   helpText cross-references the split: "use
   set_lockfile_entry_pinned with an explicit inputHash if that's
   what you mean". A merged tool would collapse both failure modes
   into the same dispatch path, eliminating the explicit routing
   signal the agent uses today.

3. **Different Output shapes.** `set_clip_asset_pinned.Output`
   carries `(projectId, clipId, assetId, inputHash, toolId,
   pinnedBefore, pinnedAfter, changed)`. `set_lockfile_entry_pinned.Output`
   carries `(projectId, inputHash, toolId, assetId, pinnedBefore,
   pinnedAfter, changed)` — same **minus clipId**. Merged Output
   would have `clipId: String? = null` populated only on
   `target="clip"` branch — classic "union schema" the prior six
   evaluations rejected. LLMs have to remember which fields
   populate per target; three slimmer Outputs beat one bloated
   seven-field Output with per-target nullability.

4. **Semantic intent is distinct at the call site.**
   `set_clip_asset_pinned` answers "pin THIS clip I'm pointing
   at on the timeline" (UI-anchored intent). `set_lockfile_entry_pinned`
   answers "pin THIS generation in the lockfile history"
   (audit-anchored intent). Both mutate the same underlying
   `LockfileEntry.pinned` field, but the user journeys that reach
   them differ. A single `set_pinned(target=…)` tool elides the
   semantic anchor — the LLM would need to re-derive "which user
   intent am I responding to?" from context instead of from the
   tool choice itself. This is the same argument the
   snapshot / duplicate evaluations used to preserve their
   splits.

Discriminator cost on the LLM side (measured against shipped
helpText + schema): 2 separate tools ≈ 380 tokens total
(set_clip_asset_pinned ~210, set_lockfile_entry_pinned ~170).
Merged tool ≈ 320 tokens — ~60 token saving. Same trade-off bound
as the prior evaluations hit; clarity + failure-mode separation is
worth far more than 60 tokens/turn.

**Alternatives considered.**

1. **Collapse into `set_pinned(target=…)` with per-target runtime
   validation.** Rejected on the four structural axes above. Same
   failure mode the add / remove / apply / snapshot / duplicate /
   maintain-lockfile evaluations documented.

2. **Keep separate Input classes but share one `PinMutator` helper
   internally.** Already the case in practice — both tools delegate
   to the same `LockfileEntry.copy(pinned=…)` pattern. Extracting
   a named helper adds code with no LLM-facing benefit.

3. **Promote `set_clip_asset_pinned` to also accept `inputHash` as
   a fallback when `clipId` doesn't resolve.** Rejected — widens
   the ergonomic tool's interface for a case the dedicated
   escape-hatch tool handles cleanly. The cross-reference in
   helpText is the right routing signal; overloading identifiers
   would make debug failures ambiguous ("which interpretation did
   the runtime try first?").

**§3a session-project-binding note (rule 6).** Both tools' inputs
accept `projectId: String`. Once `session-project-binding` is the
canonical routing, these should resolve projectId via
`ToolContext.currentProjectId` like the other project-scoped
tools that have already been converted. Noted here so the future
binding sweep catches them — same deferral the other `project/`-tree
tools carry.

**Coverage.** Docs-only — no code touched, no test added. Existing
tests (`SetClipAssetPinnedToolTest`, `SetLockfileEntryPinnedToolTest`)
pass unchanged.

**Registration.** No registration churn. Both tools remain
registered in the 5 AppContainers (CLI / Desktop / Server / Android
/ iOS) via their existing constructor signatures.

---
