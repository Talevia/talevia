## 2026-04-19 — Lockfile + stale-clip panel on desktop (VISION §3.1 / §3.2)

**Context.** The lockfile (`Project.lockfile`) has been pinning AIGC
inputs + model + seed + source-hash snapshots for a while, and
`Project.staleClipsFromLockfile()` can compute a precise "stale since
generation" report — but nothing in the desktop UI exposed either.
Agents could call `list_lockfile_entries` / `find_stale_clips`; human
users saw nothing. Task 6 of the current gap list.

**Decision.**
- New `LockfilePanel.kt` added as a third right-column tab
  (`Chat` / `Source` / `Lockfile`). Shows two coupled views:
  - **Stale clips** (when any exist): a highlighted strip at the top
    listing clip id + asset id + which source nodes changed since the
    generation snapshot. Selection-enabled so the user can copy a
    clip id into chat and ask the agent to regenerate.
  - **Entries**: every `LockfileEntry` with `toolId  inputHash`,
    `modelId@version  seed N`, expand-to-JSON parameters + snapshot
    hashes + source bindings. A "Stale only" switch filters the list.
- **Upgraded the Timeline stale badge to
  `Project.staleClipsFromLockfile()`.** Previously it was the crude
  `staleClips(allNodeIds)` proxy (task 4 known-limitation) which
  flagged every clip whose binding could go stale against any node.
  The lockfile-based signal is precise: only clips whose backing
  asset's pinned `sourceContentHashes` diverge from today's source
  hashes. Fixes the "why is every AIGC clip flagged stale the moment
  its ref exists?" false positive.
- **No in-panel regenerate button.** `replace_clip` needs a
  `newAssetId`; we don't have a one-click "regenerate with the same
  consistencyBindings" primitive yet. Designing one badly (guess the
  tool to call, guess the prompt) is worse than linking the user into
  the agent path via copy-the-id. Queued as a follow-up.

**Alternatives considered.**
- **Make the panel auto-regenerate stale clips.** Rejected: the
  regenerate call depends on which AIGC tool produced the entry
  (`generate_image` vs `generate_video` vs `synthesize_speech`), the
  effective prompt (post consistency-fold), model, seed, etc. A
  "clone the pinned inputs and replay" tool would be the right
  primitive, and one doesn't exist yet. Don't build a UI button that
  assumes a tool we don't have.
- **Fold the stale-clip strip into `TimelinePanel` rather than the
  Lockfile tab.** Tried this in sketch form and it clutters the main
  timeline view. The stale tint on the timeline rows is the summary
  signal; the Lockfile tab is where the user goes to understand *why*
  something is stale. Different tasks, different surfaces.
- **Filter lockfile entries by tool / by model / by date.** Only
  "stale only" is wired. Add others when projects start accumulating
  hundreds of entries; today the list fits.

**Known limitations.**
- `createdAtEpochMs` is shown as a raw epoch integer. Same pretty-
  time-formatting follow-up as the Snapshots dialog.
- No bytes-on-disk column. Would require joining the entries against
  `MediaStorage` size metadata which isn't exposed through the
  `MediaPathResolver` surface we already have. Follow-up.
- Stale view is read-only. See decision above — regeneration wants a
  new tool we haven't designed.

**Follow-ups.**
- A `regenerate_from_lockfile(clipId)` tool that replays the pinned
  inputs through the originating AIGC tool with the same model / seed
  (or a new seed if the user wants variation). That's the primitive
  that finally makes the stale-clip UI one-click-resolvable.
- Graphical source-binding link view: click an entry's
  `sourceBinding` → jump to the `SourcePanel` node. Requires the
  cross-panel state promotion already queued from Task 4.

---
