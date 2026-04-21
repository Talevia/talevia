## 2026-04-20 — `remove_asset` for catalog pruning

**Context.** `list_assets(onlyUnused=true)` could surface dangling
media — AIGC regenerations superseded by a newer lockfile entry,
imports that ended up on the cutting-room floor, bulk-imported
candidates the user never used. Until now there was no tool to drop
them. The only catalog-mutating surface was `delete_project` (nuclear)
and the implicit catalog grow on every `import_media` /
`generate_image` / etc. (one-way). An agent shown an unused asset
couldn't act on that information without scorched-earth.

**Decision.** Ship `remove_asset(projectId, assetId, force=false)`
that:

1. Requires the asset to exist in the project catalog (else fail-loud).
2. By default, refuses when any clip references the asset; error lists
   the dependent clipIds so the agent can prune them first.
3. With `force=true`, removes anyway. Dangling clips are left in
   place; `validate_project` already reports missing-asset references
   as errors, so the fallout is observable — not silent corruption.
4. Does **not** touch MediaStorage bytes. The same `AssetId` may live
   in snapshots (`save_project_snapshot`), forks (`fork_project`),
   another project sharing the same catalog upload, or the lockfile
   as a historical artifact. Byte-level GC is a cross-project
   concern, out of scope for a per-project catalog mutation.
5. Does **not** auto-delete dependent clips. Cascading delete
   entangles the contract: the agent that asked for "remove this asset"
   probably wants to know its clips will vanish, and a cascade hides
   that. `remove_clip` + `remove_asset` composed explicitly stays
   auditable.

Permission `project.write` — same as `create_project`,
`fork_project`, `save_project_snapshot`. Not `project.destructive`:
this is a catalog-level prune, not an irrecoverable loss of user
work (the underlying bytes remain, the project remains, the agent
can re-import if it was a mistake). Destructive is reserved for
`delete_project` / `restore_project_snapshot` where a whole branch
of user intent disappears.

**Alternatives considered.**

- *Cascade-delete dependent clips automatically.* Attractive for
  ergonomics but the agent loses visibility into the blast radius.
  Better surfaced: the default-refuse error names every dependent
  clipId, so the agent consciously decides between "prune clips then
  remove asset" and "force remove and accept danglers."
- *Also delete MediaStorage bytes.* Tempting for a clean "delete
  everything about this asset" flow, but unsafe: shared
  AssetIds across projects / snapshots / lockfile would break silently.
  GC belongs in a separate, storage-scoped tool that checks all refs
  before freeing bytes — not wedged into a per-project catalog mutation.
- *`project.destructive` permission.* Would prompt the user to
  confirm every removal. Overkill — the worst case is "dangling clips
  the agent asked for, visible in validate_project." Matches the
  tier used by other catalog-mutating tools (`create_project`,
  `fork_project`).
- *Bulk interface `remove_asset(assetIds: List)`.* Premature. The
  common case is surgical ("this one regeneration went wrong"). An
  agent that wants to bulk-prune can call `list_assets(onlyUnused=true)`
  then loop — three round-trips for three removals is not the
  bottleneck.

**Reasoning.** Closes the list→remove loop on the asset catalog with
the minimum surface that keeps correctness explicit. Refuse-by-default
on in-use mirrors the Unix `rm` philosophy where `-f` is an
explicit opt-in to dangerous behavior. Keeping bytes and cascades
out of scope keeps the tool's failure modes local and inspectable.

**Coverage.** `RemoveAssetToolTest` — six cases: removes unused
asset, refuses when in use (error lists clipIds), force removes and
reports dependents (dangling clips persist), rejects missing asset,
rejects missing project, removal is persisted across calls.

**Registration.** All five composition roots register
`RemoveAssetTool(projects)` directly after `ListAssetsTool`. Prompt
gained a paragraph under the `list_assets` section teaching the
safe-prune flow (`list_assets(onlyUnused=true)` → `remove_asset`)
and contrasting with `regenerate_stale_clips` for the AIGC-regen
path.

---
