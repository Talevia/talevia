## 2026-04-22 — Keep RemoveClip / RemoveFilter / RemoveTrack / RemoveTransition as four tools (debt evaluated)

Commit: `(docs-only — no code change; see reasoning below)`

**Context.** The backlog bullet `debt-consolidate-video-remove-variants`
asked the same question for the four Remove* tools that earlier
`debt-consolidate-video-add-variants` asked for the Add* quartet:
evaluate whether they should fold into one `remove_from_timeline(target=…)`
tool. The add-variants decision (see
`2026-04-22-debt-consolidate-video-add-variants.md`) kept the four Add*
tools because their Input shapes have effectively zero overlap and
consolidation would cost more clarity than it saves.

Tenth cycle-level skip on `per-clip-incremental-render` (still deferred
per the 2026-04-19 multi-day-refactor rationale). This bullet is the
paired `debt-*` task flagged explicitly by the 2026-04-22 add-variants
decision: "The same evaluation applies to the Remove* quartet — don't
re-litigate, confirm the symmetry when the bullet comes up."

Decision: **keep the four tools unchanged**, same rationale as the
add-variants decision.

**Decision analysis.**

Current Input shapes, excluding the session-resolved `projectId`:

| Tool                  | Required              | Optional | Field count |
|-----------------------|-----------------------|----------|-------------|
| `remove_clip`         | `clipId`              | (plus `sourceRevision` drift-guard) | 1–2 |
| `remove_filter`       | `clipId`, `filterName` | —       | 2 |
| `remove_track`        | `trackId`             | `force: Boolean = false` (non-empty track override) | 1–2 |
| `remove_transition`   | `transitionClipId`    | —       | 1 |

A merged `remove_from_timeline(target=…)` Input would need **5 distinct
id-shaped fields + the `force` track-override + a `filterName`
scalar**, with `target="filter"` requiring both `clipId` and
`filterName` simultaneously (the only nested case). Every call would
populate 1–3 of them; the rest would be irrelevant or loudly rejected
per target. Same three structural problems the add-variants evaluation
flagged:

1. **Schema validation falls out of JSON-schema into runtime.** Today
   every Remove* tool sets `additionalProperties: false`, so the LLM
   gets clean typo feedback on invalid fields. A union-schema can't
   express "if target=filter then filterName required".

2. **Applicability signal degrades.** `RemoveClipTool` /
   `RemoveFilterTool` require clips to exist (they'd ride
   `RequiresAssets` or project-binding), while `RemoveTrackTool`
   operates on track structure and `RemoveTransitionTool` on a
   transition clip. The merged tool could only advertise the minimum
   (probably `RequiresProjectBinding`), teaching the model to try
   `remove_from_timeline(target=clip)` in empty projects where
   `remove_clip` today correctly stays hidden.

3. **LLM token cost isn't the deciding factor.** 4 × ~80 tokens = ~320
   token baseline; merged would be ~260 tokens. Net savings are
   recoverable by provider prompt caches on stable tool specs.

Weighed against: richer loud-validation, per-target applicability
gating, distinct Output shapes (Remove* tools echo different id fields
— `clipId` / `trackId` / `transitionClipId` / counts-of-filters-
removed), and the fact that the user's natural verbs ("remove that
clip", "remove that track") are already the tool names. Consolidating
would force the LLM to route through a discriminator that adds zero
semantic information.

**Alternatives considered.**

1. **Fold into `remove_from_timeline(target=clip|filter|track|transition)`**
   — rejected per the analysis above. Same call the bullet anticipated
   as a possible outcome ("保留四件套并在 decision 说明").
2. **Pair-fold Remove + Add into a single `edit_timeline(verb=add|remove,
   target=…)` mega-tool** — rejected. Doubles the sparse union shape (two
   orthogonal discriminators: verb × target) and introduces a matrix of
   incompatible field combinations ("add+clip needs assetId; remove+clip
   needs clipId"). Pure cost.
3. **Keep the four tools and mark the bullet resolved (decision-only
   commit)** — the chosen path. Mirrors the 2026-04-22 add-variants
   decision commit shape exactly: a docs-only commit documenting that
   the evaluation ran, the outcome is "keep four", and pointing at the
   add-variants doc as the canonical analysis for this consolidation
   pattern.

**Coverage.** No code change. Existing `RemoveClipToolTest`,
`RemoveFilterToolTest`, `RemoveTrackToolTest`, `RemoveTransitionToolTest`
continue to guard the individual tools; no test gains or losses.

**Registration.** No registration changes — the four tools remain
registered unchanged in CLI / desktop / server / Android / iOS
containers.

---
