## 2026-04-19 — Lockfile-driven `find_stale_clips` (VISION §3.2 close-the-loop)

**Context.** The Source DAG (068a350), incremental render path (acce14c), and
AIGC lockfile (b125574) all landed, but they were three pieces of theory: the
agent had no way to *use* them. After the user edited a `character_ref`, the
agent could not answer "which clips on the timeline are now stale?" without
reading the entire project JSON and reasoning manually. The DAG paid for
itself only at export time, never at planning time.

**Decision.** Detect staleness from the **lockfile**, not from
`Clip.sourceBinding`, and surface it as a read-only `find_stale_clips` tool.

- Snapshot `SourceNode.contentHash` for every bound id at lockfile-write time
  (new field `LockfileEntry.sourceContentHashes: Map<SourceNodeId, String>`).
- New domain extension `Project.staleClipsFromLockfile(): List<StaleClipReport>`
  walks each clip on the timeline, looks up the lockfile entry that produced
  its asset (`Lockfile.findByAssetId`), and compares snapshotted hashes against
  current source hashes. Mismatch on any bound node → flag the clip and report
  *which* source ids drifted.
- New tool `core.tool.builtin.project.FindStaleClipsTool` returns
  `(staleClipCount, totalClipCount, reports[clipId, assetId, changedSourceIds])`.
- System prompt teaches the workflow: edit character → `find_stale_clips` →
  regenerate each reported clip with the same bindings.

**Why lockfile-driven, not `Clip.sourceBinding`-driven.** `Clip.sourceBinding`
is the field VISION §3.2 says clips *should* carry. But today `AddClipTool`
does not thread the AIGC tool's `sourceBinding` output into the new clip — it
takes `(assetId, trackId, …)` and constructs `Clip.Video(sourceBinding =
emptySet())`. Two ways to close the loop:

1. Refactor `AddClipTool` to look up the asset's lockfile entry and copy its
   `sourceBinding` into the clip. Conflates "place this asset on the timeline"
   with "AIGC bookkeeping" — every future clip-creating path would need the
   same special-casing.
2. Drive detection from `Clip → AssetId → LockfileEntry → sourceBinding`,
   leaving the Clip layer untouched.

Picked (2). The lockfile is already the audit record of "what produced what";
piggy-backing the staleness query on it keeps the Clip layer decoupled from
AIGC and makes legacy hand-authored clips work the same as AIGC clips
(neither has a `Clip.sourceBinding`, both are queried by asset id). Future
work to add a `replace_clip` tool can adopt the same lookup without a schema
migration.

**Why "empty `sourceContentHashes` = unknown, not stale".** Legacy lockfile
entries written before the snapshot field existed have `emptyMap()`. Two
choices:

- **Always-stale**: every legacy entry's clips show up in every report. Loud,
  but mostly false positives — the agent would propose regenerating clips
  that haven't actually drifted.
- **Always-fresh / skip**: silently exclude legacy entries from the report.

Picked the second. Lying-about-stale erodes trust in the report; lying-about-
fresh just means a one-time "you have N legacy clips that we can't reason
about — regenerate if you've edited their sources" message at most. The
common case (entries written after this commit) is unaffected, and projects
created today have zero legacy entries.

**Why under `tool/builtin/project/` (not `source/`).** The query crosses
*both* layers — it walks the timeline (project) and reads the source DAG. Two
sibling tools — `get_project_state` (counts across both) and
`find_stale_clips` (joins across both) — both belong with the cross-cutting
project tools. `source/` is reserved for tools that only mutate / read the
source DAG itself (`define_character_ref`, `list_source_nodes`, …).

**Why read-only (`project.read`), not gated.** The tool produces no side
effects, makes no provider calls, costs nothing. Gating it would force the
agent to ASK before every diagnostic — strictly worse than letting it poll
between edits.

**Why report only direct-changed source ids, not the transitive closure.**
The user-facing fix is "regenerate clip X". The agent doesn't need the full
DAG of derived nodes to do that — it needs to know *what changed* so it can
explain in the chat ("Mei's hair changed from teal → red, so I'll regenerate
…"). Transitive descendants would bloat the report on chatty graphs without
helping the action.

**Surface area.** Added to all 4 composition roots (server, desktop, Android,
iOS) so every platform with a chat surface can dispatch it. No engine /
permission rule changes — `project.read` already exists.

**Tests.** `FindStaleClipsToolTest` covers six scenarios: fresh project,
character edit flags clip, multi-binding only one changes (proves the
"changedSourceIds" precision), legacy entry skipped, imported clip without
lockfile entry skipped, empty lockfile short-circuit. `GenerateImageToolTest`
gained `lockfileEntrySnapshotsBoundSourceContentHashes` to lock in the
snapshot-on-write contract — the detector is dead without it.

---
