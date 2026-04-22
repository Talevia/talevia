## 2026-04-22 — Keep DuplicateClip / DuplicateTrack as two tools (debt evaluated)

Commit: `(docs-only — no code change; see reasoning below)`

**Context.** Backlog bullet `debt-consolidate-video-duplicate-variants`
asked to evaluate whether `DuplicateClipTool` + `DuplicateTrackTool`
could fold into a single `duplicate_from_timeline(target="clip"|"track")`
tool, following the same evaluation pattern already run on:
- `add_*` (`2026-04-22-debt-consolidate-video-add-variants.md`)
- `remove_*` (`2026-04-22-debt-consolidate-video-remove-variants.md`)
- `apply_*` (`2026-04-22-debt-consolidate-video-apply-variants.md`)
- `snapshot_*` (`2026-04-22-debt-consolidate-project-snapshot-ops.md`)

Bullet hedges: "评估合为 `duplicate_from_timeline(target="clip"|"track")`；
或按先例（divergent Input）保留两件套并在 decision 说明".

This cycle is the evaluation. Decision: **keep the two tools
unchanged.** Same structural blockers as the four prior evaluations;
the discriminator saving is again dwarfed by clarity + applicability
loss.

**Decision analysis.**

The two Input shapes (excluding session-resolved `projectId`):

| Tool              | Required                                   | Optional          | Applicability          |
|-------------------|--------------------------------------------|-------------------|------------------------|
| `duplicate_clip`  | `clipId`, `timelineStartSeconds: Double`   | `trackId?`        | `RequiresAssets`       |
| `duplicate_track` | `sourceTrackId`                            | `newTrackId?`     | `Always` (default)     |

Four structural blockers, matching the prior evaluations:

1. **`ToolApplicability` would downgrade to conservative-max.**
   `duplicate_clip` is `RequiresAssets` (hidden when the project
   has no media — a clip duplicate without assets to reference is
   meaningless). `duplicate_track` is `Always` — an empty-project
   user can duplicate an empty track template for layout. A merged
   tool could only advertise the weaker union (Always), re-exposing
   the "empty project sees duplicate_clip, calls with made-up ids,
   errors, burns a turn" trap the applicability system was built to
   close. The 2026-04-22 add-variants decision explicitly flagged
   this as the disqualifying axis; it re-applies here.

2. **Different placement semantics.** `duplicate_clip` places the
   new clip at `timelineStartSeconds` on the target track — that
   parameter is *required* because the duplicate has to land
   somewhere specific (and concretely not on top of the original's
   time range). `duplicate_track` appends the new track at the end
   of `Project.timeline.tracks` with no per-clip placement — clips
   inherit their original time ranges on the new track. Merging the
   two means `timelineStartSeconds` becomes required only when
   `target="clip"` and rejected when `target="track"` — a runtime
   branch on what JSON Schema should validate.

3. **Different ID semantics for the optional field.**
   `duplicate_clip.trackId?` targets an **existing** track (must
   match kind — audio clip can't land on a video track). `duplicate_track.newTrackId?`
   proposes the **new** track's id (must NOT collide with any
   existing track id). Same field shape (`String?`), opposite
   validation. A merged `newTrackId?` / `trackId?` pair would carry
   both fields as separately-optional with branch-specific reject
   logic — classic "union-schema" anti-pattern the prior four
   evaluations all rejected.

4. **Different Output shapes.** `duplicate_clip` returns single-clip
   timing (`timelineStartSeconds`, `timelineEndSeconds`). `duplicate_track`
   returns a bulk summary (`clipCount`). Merged, the Output carries
   every field nullable (4 timing fields + 1 count + 2 id fields,
   of which any given call populates half). The agent has to remember
   which fields populate per target — three small Outputs would beat
   one bloated six-field Output.

Discriminator cost on the LLM side (measured against shipped helpText +
schema): 2 separate tools ≈ 350 tokens total (duplicate_clip ~190,
duplicate_track ~160). Merged tool ≈ 290 tokens — ~60 token saving.
Same trade-off bound as the prior four evaluations hit; the clarity
+ applicability loss is worth far more than 60 tokens/turn.

**Alternatives considered.**

1. **Collapse into `duplicate_from_timeline(target=…)` with
   per-target runtime validation** — rejected on the four
   structural axes above. Same failure mode the add / remove /
   apply / snapshot evaluations documented.

2. **Merge just `duplicate_clip`'s single-clip mode with a new
   "duplicate selected clips in batch" mode (no track duplicate)** —
   out of scope for this bullet. A batch-clip-duplicate would be a
   different product decision and doesn't address whether
   `duplicate_track` should live alongside `duplicate_clip`.

3. **Promote `duplicate_track` to `RequiresAssets` for applicability
   symmetry** — rejected. An empty-project user who wants to
   template a "video A + audio B + subtitle C" track layout gets
   utility from duplicating an empty-clip track before importing.
   Tightening applicability to match would remove a real path for
   marginal consistency.

**Coverage.** Docs-only — no code touched, no test added.
`DuplicateClipTool` / `DuplicateTrackTool` tests stay green.

**Registration.** No registration churn. Both tools remain
registered in the 5 AppContainers (CLI / Desktop / Server / Android
/ iOS) via their existing constructor signatures.

---
