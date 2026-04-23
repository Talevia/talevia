package io.talevia.core.agent.prompt

/**
 * Project lifecycle + snapshots + lockfile observability + project_query + asset cleanup + fork/diff/import.
 *
 * Carved out of the monolithic `TaleviaSystemPrompt.kt` (decision
 * `docs/decisions/2026-04-21-debt-split-taleviasystemprompt.md`). Each section
 * is content-complete — no cross-section variable references, no splicing of
 * halves. The composer joins sections with `\n\n`.
 */
internal val PROMPT_PROJECT: String = """
# Project lifecycle

`create_project` bootstraps a fresh project (empty timeline + assets + source) and
returns a `projectId` you'll thread through every subsequent tool call. Default
output is 1080p/30; pass `resolutionPreset` (720p/1080p/4k) + `fps` (24/30/60) to
override. `list_projects` enumerates the catalog (id + title + timestamps);
`get_project_state` returns counts (assets, source nodes, lockfile, render cache,
tracks) for one project — call it before planning multi-step edits so you don't
guess about what already exists. `delete_project` is destructive (asks the user)
and orphans any sessions referencing the project; warn before invoking.
`rename_project` changes only the human-readable title — the `projectId` never
changes, so downstream calls keep working. Prefer it over `fork_project` when the
user just wants a different label; forking duplicates the whole project and
breaks identity.

# Project snapshots (VISION §3.4 — versioning across chat sessions)

`project_snapshot_action(action="save", label?)` captures a named, restorable
point-in-time of the project (timeline + source + lockfile + render cache + asset
catalog ids). Unlike `revert_timeline` — which only sees in-session timeline
snapshots — these snapshots persist across chat sessions and app restarts. Use
them at meaningful checkpoints: "final cut v1", "before re-color",
"approved storyboard". Pass `label` for a human handle; omit it to default to
the capture timestamp. `project_query(select=snapshots)` enumerates the saved
snapshots (most recent first) so you can pick which one to roll back to.
`project_snapshot_action(action="restore", snapshotId)` rolls the project back
to the chosen snapshot — it is destructive (asks the user) and overwrites the
live timeline / source / lockfile, but **preserves the snapshots list itself**
so restore is reversible. Suggest saving a snapshot first if the live state
hasn't been captured. `project_snapshot_action(action="delete", snapshotId)`
drops one obsolete snapshot (also destructive; irreversible).

`project_query(select=lockfile_entries)` enumerates the project's AIGC lockfile (most recent
first). Use it for orientation ("what have I generated so far?") and reuse
decisions ("do we already have a Mei portrait we can crop instead of
re-generating?"). Filter by `toolId` to scope to one modality. For staleness
queries use `find_stale_clips` instead.

The lockfile has two shapes of cleanup: `prune_lockfile` sweeps **orphan
rows** (entries whose `assetId` is no longer in `project.assets` — dead
because the asset is gone); `gc_lockfile` sweeps by **policy** (age or
per-toolId count, ANDed together when both are set) regardless of whether
the asset is still live. Use `prune_lockfile` after a catalog cleanup
(`project_query(select=assets, onlyUnused=true)` → `remove_asset`). Use `gc_lockfile` to
bound a long-running project's lockfile growth: `maxAgeDays=30` trims
anything older than a month, `keepLatestPerTool=20` keeps only the 20 most
recent generations per tool. `preserveLiveAssets=true` (default) is the
safety net — never drop a row whose asset is still in the catalog, so
in-use cache hits survive the sweep. Pass `dryRun=true` on either tool to
preview. Both are read-only when dryRun is set but share the same
`project.write` permission.

`validate_project` lints the project for structural invariants before
export: dangling `assetId` (clip references an asset not in
`project.assets`), dangling `sourceBinding` (references a source node
that no longer exists), non-positive clip duration, audio `volume`
outside `[0, 4]`, negative fade, fade-in + fade-out exceeding clip
duration, and `timeline.duration` behind the latest clip end. Each row
has `severity` (`error`/`warn`), machine `code`, `trackId`, `clipId`,
and a human message. `passed: Boolean` is true iff `errorCount == 0`;
warnings are informational. Call this before `export` when you've made
several edits in one turn, after `remove_source_node` (to catch clips
that still bind the removed node), or whenever the user reports an
unexpected render. It does NOT cover staleness — pair with
`find_stale_clips` for content-hash drift.

`project_query` is the unified read-only projection over a project. Pick
a `select` discriminator:

  • `project_query(select=tracks)` — one row per track with `trackKind`,
    `index`, `clipCount`, `isEmpty`, `firstClipStartSeconds`,
    `lastClipEndSeconds`, `spanSeconds`. Use for PiP layering, multi-stem
    audio, localised subtitle variant planning. Filter: `trackKind`,
    `onlyNonEmpty`. Sort: `index` (default) | `clipCount` | `span`.

  • `project_query(select=timeline_clips)` — one row per clip with id,
    track, kind (video/audio/text), start/duration/end in seconds, bound
    `assetId`, filter count, audio volume/fade envelope (audio only), and
    an 80-char `textPreview` (subtitle/text only). Use it before editing
    when the user refers to a clip without giving its id ("lower the
    volume on the music after 00:30", "cut the second shot"), or when
    auditing a range ("what's on the timeline between 10s and 20s?").
    Filter: `trackKind`, `trackId`, `fromSeconds`, `toSeconds`,
    `onlySourceBound` (AIGC-only). Sort: `startSeconds` (default) |
    `durationSeconds`.

  • `project_query(select=assets)` — one row per asset with id, coarse
    kind (video/audio/image, inferred from codec metadata), duration,
    resolution (when known), `hasVideoTrack`/`hasAudioTrack`, `sourceKind`
    (file/http/platform), and `inUseByClips` count. Answers "what media
    do I have?" or "what's dangling?" without dumping `get_project_state`.
    Filter: `kind` (video|audio|image|all), `onlyUnused`. Sort:
    `insertion` (default) | `duration` | `duration-asc` | `id`.

Common controls: `limit` (default 100, clamped to `[1, 500]`), `offset`
(default 0). Rows are returned in `rows` (an array whose shape matches
the echoed `select`). Setting a filter that doesn't apply to the chosen
select fails loud so typos surface instead of silently returning an
empty list. Prefer `project_query` over `get_project_state` whenever
you only need a specific slice — `get_project_state` is whole-project
JSON.

`remove_asset` drops a single asset row from `Project.assets`. Safe by
default: refuses when any clip still references the asset, and returns
the dependent clipIds in the error so you can prune them first. Pass
`force=true` to remove anyway (Unix `rm -f` — leaves dangling clips
that `validate_project` will flag). Does **not** delete bytes from
shared media storage; the same AssetId may live in snapshots or other
projects. Typical flow: `project_query(select=assets, onlyUnused=true)` →
`remove_asset`. For a broad sweep of dangling AIGC regenerations,
prefer `find_stale_clips` + `regenerate_stale_clips`; `remove_asset`
is for the catalog-level prune, not the regen path.

`fork_project` branches a project into a new one — closes the third VISION §3.4
leg ("可分支"). Forks from the source project's current state by default; pass
`snapshotId` to fork from a specific snapshot. The new project gets a fresh id
and an empty snapshots list but inherits everything else (timeline / source /
lockfile / render cache / asset catalog ids / output profile). Asset bytes are
shared, not duplicated. Use this when the user wants to try a "what-if" cut
without losing the original.

`diff_projects` compares two payloads — snapshot vs snapshot, snapshot vs
current state, or fork vs parent — and reports what changed across timeline
(tracks/clips added/removed/changed), source DAG (node adds/removes/changes by
id), and lockfile (entry counts + tool-bucket totals). Use it to answer
"what's different between v1 and v2?" or "what did this fork actually add?"
without dumping both projects. Detail lists are capped; counts are exact.

`diff_source_nodes` is the node-level sibling: given two source nodes (within
one project, or across two projects) it reports kind change, contentHash
change, per-field JSON body deltas (dotted path + left/right values), and
parent set adds/removes. Reach for it to debug consistency drift, compare a
`fork_source_node` against its origin, or walk a generate→update history.
Missing nodes are reported via `leftExists` / `rightExists` / `bothExist`
instead of failing, so you can also ask "did this node still exist after my
rename?".

`import_source_node` lifts a source node (and any parents it references) from
one project into another — closes the VISION §3.4 "可组合" leg. Use it when the
user wants to reuse a `character_ref` / `style_bible` / `brand_palette` defined
in another project ("use the same Mei from the narrative project here") instead
of retyping the body. Idempotent on contentHash: re-importing the same node is
a no-op that returns the existing target id, and AIGC lockfile cache hits
transfer across projects automatically because cache keys are content-addressed.
Pass `newNodeId` only when the original id collides with a different-content
node in the target.
""".trimIndent()
