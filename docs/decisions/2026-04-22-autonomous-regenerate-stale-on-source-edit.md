## 2026-04-22 — autoRegenHint nudges agent to batch-regen after source edits (VISION §5.5 rubric)

Commit: `5daf424`

**Context.** The refactor loop VISION §5.5 calls out — edit a
consistency node → downstream clips go stale → regenerate in one batch —
is technically complete: `set_character_ref` / `update_source_node_body`
/ `remove_source_node` already bump `contentHash`,
`staleClipsFromLockfile` already flags bound clips, and
`regenerate_stale_clips` already batches the re-dispatch. The **cost**
of stitching that flow today is:

  1. Agent runs `set_character_ref` (or similar).
  2. Tool result says "contentHash bumped — downstream clips may go
     stale (check find_stale_clips)".
  3. Agent dispatches `find_stale_clips` to find out whether there's
     anything to regenerate.
  4. If yes → agent dispatches `regenerate_stale_clips`.

Steps 2 and 3 are paid turns: the LLM has to decide to check, dispatch
the check tool, read its result, then decide on regen. In the 80% case
("yes there were bound clips; regen them"), step 3 is pure overhead —
the source-mutation tool already knows (after committing) how many
clips will be stale.

**Decision.** Source-mutation tools compute a typed hint on the way out
that tells the agent "here are N clips currently stale, the one-shot
tool to fix them is `regenerate_stale_clips`".

1. **New shared type `AutoRegenHint(staleClipCount: Int,
   suggestedTool: String = "regenerate_stale_clips")`** in
   `core/domain/ProjectStaleness.kt`. `@Serializable` so it rides
   inside every tool Output that wants it; default for `suggestedTool`
   reserves room to vary the suggestion later (e.g. "export with
   allowStale=true" when the user's intent is publish-now) without
   bumping the Output schema.

2. **New extension `fun Project.autoRegenHint(): AutoRegenHint?`** that
   returns null when `staleClipsFromLockfile()` is empty, non-null with
   the count otherwise. Single source of truth for the check — every
   mutation tool calls this and gets identical semantics.

3. **Five source-mutation tool Outputs gain a nullable
   `autoRegenHint: AutoRegenHint? = null` field**:
   - `UpdateSourceNodeBodyTool`
   - `SetCharacterRefTool`
   - `SetStyleBibleTool`
   - `SetBrandPaletteTool`
   - `SetSourceNodeParentsTool`
   - `RemoveSourceNodeTool`

   Six tools, all under `source.write`. Each one captures the
   `mutateSource` return value (the post-mutation Project), computes
   the hint, includes it in the typed Output and appends a short
   sentence to `outputForLlm` ("autoRegenHint: 3 stale clip(s) —
   suggested next: regenerate_stale_clips.") so the LLM reads the same
   signal without having to decode the typed Output.

4. **No new tools, no new `Tool.kt` files, no AppContainer changes.**
   The agent's "pick the next tool" loop reads the hint from the
   prior tool result's content and dispatches
   `regenerate_stale_clips` — existing permission flow, existing batch
   behaviour.

Scope deliberately stops at the hint. The bullet's "系统 prompt 或
agent runtime 据此在下一轮主动跑一遍 regen" is available as a follow-up
but starts with a less invasive baseline (text hint the LLM reads) than
an in-runtime auto-dispatch (which would need a permission gate and
session rule). Shipping the hint first validates the signal shape; the
runtime-auto path can layer on top later if hints alone prove
insufficient.

**Alternatives considered.**

1. **Prose-only in `outputForLlm`, no typed field** — rejected. The
   desktop / iOS UIs subscribe to typed Parts and render "3 clips need
   regen" banners from structured data, not from re-parsing English.
   Typed field is zero extra LLM tokens (agent reads the same prose);
   ~40 tokens per Output schema is well under the 500-token §3a rule
   10 threshold. Having both — structured for UIs, prose for the LLM
   — costs essentially nothing extra.

2. **Add the hint to every source-mutation tool (including
   `rename_source_node`, `add_source_node`, `fork_source_node`)** —
   considered and rejected for this cycle.
   `rename_source_node` rewrites both clip bindings and lockfile
   snapshots on the fly (`staleClipsFromLockfile` reports zero after
   rename), so the hint is always null there. `add_source_node` and
   `fork_source_node` create new nodes that existing clips aren't
   bound to; also always-null. Skipping them avoids adding an
   always-null Output field whose sole purpose is surface-consistency.

3. **Auto-dispatch `regenerate_stale_clips` at the runtime level
   (behind a new permission + session rule)** — considered. Would
   require a new "post-tool hook" hook point in `AgentTurnExecutor`
   and a new permission scope (`aigc.autoRegen`). A meaningful slice
   of the work but a larger surface (runtime plumbing + permission
   model + session rule) than this cycle should carry. The hint by
   itself is a prerequisite — auto-dispatch would read the same
   signal — so this decision pre-pays the type work for the later
   layering. Tracked by leaving the bullet's "系统 prompt 或 agent
   runtime 据此下一轮主动跑一遍 regen" variant available as a future
   escalation.

4. **Reuse `boundClipCount` instead of a new hint type** — considered.
   `UpdateSourceNodeBodyTool` already exposes `boundClipCount`
   (direct bindings to the edited node). But stale-tracking is about
   *drift across the lockfile snapshot*, not *current binding count*;
   the two can disagree (a node with zero direct bindings but
   descendants bound to its ancestors can still produce stale clips
   transitively via VISION §5.1 deep-hash propagation). `autoRegenHint`
   is computed off `staleClipsFromLockfile`, which is the authoritative
   signal for "is regen worth running?".

**Coverage.** New `AutoRegenHintTest` in
`core/src/jvmTest/.../domain/` — 4 cases: empty project, empty
lockfile, post-source-edit non-null count, multi-clip precise count
(negative assertion — no double-counting). Existing source-mutation
tool tests keep passing unchanged — every `autoRegenHint` field
defaults to null, and existing tests assert no regression.

**Registration.** No new tools, no new registration. The only
structural changes are a new data class + helper in
`core/domain/ProjectStaleness.kt` and 6 Output additions to existing
tools under `core/tool/builtin/source/`.

Tool count delta: 0 (no new `Tool.kt`). LLM context cost (§3a rule 10):
~40 tokens per Output schema × 6 tools = ~240 tokens on turns where
those schemas are bundled — below the 500-token threshold.
`AutoRegenHint` is a tight two-field type; schema weight is minimal.

`remove_source_node`'s hint may be null even when clips were bound to
the removed node — `staleClipsFromLockfile` deliberately skips nodes
that no longer exist in `source.byId` (legacy behaviour: imported
media / legacy entries are the other missing-node case we decline to
lie about). Callers expecting "I just removed a bound node, regen me"
see prose in `outputForLlm` but not a structured count. A separate
cycle can extend the detector to treat missing-node-with-snapshot as
stale; it's a correctness tightening orthogonal to this hint wiring.

---
