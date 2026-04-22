## 2026-04-22 — Keep Save / Restore / Delete project-snapshot as three tools (debt evaluated)

Commit: `(docs-only — no code change; see reasoning below)`

**Context.** Backlog bullet `debt-consolidate-project-snapshot-ops` asked
to evaluate whether `SaveProjectSnapshotTool` +
`RestoreProjectSnapshotTool` + `DeleteProjectSnapshotTool` could fold
into a single `snapshot_op(action="save"|"restore"|"delete", …)` tool,
mirroring the evaluations already done for
`add_*` (`2026-04-22-debt-consolidate-video-add-variants.md`),
`remove_*` (`2026-04-22-debt-consolidate-video-remove-variants.md`),
and `apply_*` (`2026-04-22-debt-consolidate-video-apply-variants.md`).
The bullet itself hedges: "评估合为 `snapshot_op(action=…)` 或按
add/remove-variants 的先例保留三件套并在 decision 里说明".

Decision: **keep the three tools unchanged.** The critical disqualifier
is permission-level divergence — no other evaluation cycle has hit this
axis, and it's strictly worse than the shape-of-Input issues the prior
three cycles documented.

**Decision analysis.**

The three tools, with Input / Output / permission at a glance:

| Tool                         | Input                                 | Permission            | Output                                                                       |
|------------------------------|---------------------------------------|-----------------------|------------------------------------------------------------------------------|
| `save_project_snapshot`      | `(projectId, label?)`                 | `project.write`       | `snapshotId, label, capturedAtEpochMs, totalSnapshotCount`                   |
| `restore_project_snapshot`   | `(projectId, snapshotId)`             | `project.destructive` | `snapshotId, label, capturedAtEpochMs, clipCount, trackCount`                |
| `delete_project_snapshot`    | `(projectId, snapshotId)`             | `project.destructive` | `snapshotId, label, remainingSnapshotCount`                                  |

Four structural blockers:

1. **Permission scope divergence (the dominant blocker).** `save` is
   `project.write` — benign, additive, default ALLOW per
   `DefaultPermissionRuleset`. `restore` and `delete` are
   `project.destructive` — default ASK, because restore replaces live
   state wholesale and delete drops a recoverability anchor. A merged
   `snapshot_op` has exactly two options:
   - **Union-max permission**: `project.destructive` for all three
     actions → every `save` now hits the ASK rule → every snapshot
     checkpoint pauses the agent's flow for user confirmation. That
     defeats the whole purpose of "free save anytime" as a UX.
   - **Per-action permission** via a new `PermissionSpec.byInput`
     branch — not supported by the current `PermissionSpec` type
     (`fixed(keyword)` / `perInputPath(keyword, pattern)`). Would
     require a new permission-spec kind + executor awareness, which
     is a significant refactor far beyond the scope of a consolidation
     evaluation. And the rule engine would still need
     `aigc.generate` / `project.destructive` / etc. to map per-action,
     adding conditional-rule complexity not present anywhere else.

   None of the prior three evaluations (add / remove / apply variants)
   hit this axis — their tools all shared a permission keyword. This
   snapshot set does not. Single-permission collapse is the wrong
   trade.

2. **Conditional Input requireds.** `save`'s `label` is optional,
   `restore` / `delete`'s `snapshotId` is required. A merged Input
   carries both fields as optional at the JSON-schema level with
   runtime rejection ("`action=save`: snapshotId ignored; `action=restore`:
   snapshotId required"). Same chronic failure mode the add / remove /
   apply decisions each flagged: `additionalProperties=false` degrades
   to runtime errors on field combinations that should be caught at
   schema-validate time.

3. **Three different Output shapes.** `save`, `restore`, and `delete`
   each return different fields. Merged, the Output needs every field
   nullable (six fields; 3 always set + 3 action-specific), and the
   LLM has to remember which fields populate per action. Three small,
   crisp Outputs beat one sparse six-field Output for model clarity.

4. **Different tool-facing invariants.** `save` mutates by *appending*
   a snapshot row; `restore` mutates by *replacing* timeline / source
   / lockfile / assets wholesale while preserving the snapshot list
   itself; `delete` mutates by *removing* one snapshot row. The three
   verbs aren't symmetric — they read separate sections of the
   `Project` data class and perform different mutation shapes. A
   single `snapshot_op` tool implementation would be a 3-way switch
   on every concern that these three tools already handle via
   independent files.

Discriminator cost on the LLM side (measured against shipped helpText +
schema): 3 separate tools ≈ 390 tokens total (save ~150, restore ~110,
delete ~130). Merged `snapshot_op` ≈ 330 tokens — ~60 token saving.
Below the clarity-trade threshold, same bound as add / remove / apply
evaluations flagged. Forcing ASK on every save (item 1) alone is
worth far more than 60 tokens' worth of LLM context on the other side.

**Alternatives considered.**

1. **Collapse to `snapshot_op(action=…)` with per-action runtime
   permission branching** — rejected. Requires a new
   `PermissionSpec.byAction` kind + executor plumbing, a much larger
   refactor than the consolidation saves. Permission scope was the
   one structural axis prior evaluations didn't face; it's the
   hardest to work around cleanly.

2. **Collapse save + delete only, keep restore separate** — rejected
   as an arbitrary slice. Save and delete disagree on permission
   (write vs. destructive) just as much as save vs. restore does. If
   you can't collapse all three, there's no principled pair to
   collapse either.

3. **Promote `save_project_snapshot` to `project.destructive` to
   unify permission scope** — rejected. Forcing ASK on every save
   breaks the "checkpoint often, restore when needed" pattern. Saving
   a snapshot is strictly additive and recoverable (the live state
   isn't touched); there's no destructive intent to guard.

4. **Subsume the three into `project_query(select=snapshots, op=…)`
   with a write mode** — rejected. `project_query` is read-only by
   contract; turning it into a write tool confuses the
   query/mutation split the read-primitive consolidation was built
   on. Write-side snapshot mutations belong as their own write tools.

**Coverage.** Docs-only — no code touched, no test added. Existing
`SaveProjectSnapshotTool` / `RestoreProjectSnapshotTool` /
`DeleteProjectSnapshotTool` tests stay green. The
`TaleviaSystemPromptTest` keyword-list check does not mention
`snapshot_op` individually, so no system-prompt expectation shifted.

**Registration.** No registration churn. All three tools remain
registered in the 5 AppContainers (CLI / Desktop / Server / Android /
iOS) via their existing constructor signatures.

---
