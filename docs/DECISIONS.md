# DECISIONS

Running log of design decisions made while implementing the VISION.md north star.

Each entry records the **decision**, the **alternatives considered**, and the **reasoning**
— the reasoning is what matters when someone comes back in six months and wants to
revisit. "We did X" without "because Y" rots.

Ordered reverse-chronological (newest on top).

---

## 2026-04-20 — `set_output_profile` for post-create render spec mutation

**Context.** `OutputProfile` (resolution, frame rate, video/audio
codec, bitrates, container) was fixed at project creation by
`create_project`. Every consumer editor lets the user change this
whenever they want — "render at 4K", "switch to h265", "bump the
bitrate for YouTube", "deliver in ProRes instead of h264". In
Talevia the only escape hatches were `fork_project` (wrong — clones
the whole timeline just to change settings) or direct store
mutation (not a tool path). The agent literally could not honor
"render this at 4K 60fps" if the user said so mid-session.

**Decision.** Ship `set_output_profile(projectId, …)` with patch
semantics: every field optional, null = keep, value = replace. At
least one field must be provided. Width and height are paired (both
or neither, because a single axis is always a bug).

- Permission `project.write` — matches other metadata-mutation
  tools (`create_project`, `fork_project`). Not destructive: no user
  work is lost, and the worst case is a re-encode pass.
- **Only mutates `OutputProfile`**, not `Timeline.resolution` /
  `Timeline.frameRate`. This is the load-bearing decision:
  - The timeline is the **authoring** canvas — content is placed
    against that grid, transitions compose frame-accurately against
    that frame rate. Changing the timeline resolution mid-project
    would reflow every existing split, trim, and transform. That's
    a separate, dangerous operation that deserves its own tool with
    explicit reflow semantics.
  - The output profile is the **render** spec — `ExportTool` reads
    it to tell the engine how to encode. Can be changed freely;
    next export uses the new spec. Idempotent (modulo re-render).
  - Separating render from authoring is industry-standard (Premiere,
    DaVinci, Final Cut, iMovie all distinguish sequence settings
    from export/delivery settings). Lumping them would be a
    strictly worse model that also breaks VISION §5.1 rubric ("专家
    可接管" — experts expect this separation).
- `fps` is accepted as an integer, stored as `FrameRate(fps, 1)`.
  NTSC rates (23.976, 29.97) need numerator/denominator form;
  deferred for a follow-up tool (`set_output_profile_exact` or
  richer schema) because the common case is integer fps and
  exposing ratios in the first pass would just confuse the common
  case.
- Doesn't explicitly invalidate `RenderCache`. The cache key
  includes the profile hash, so the next export naturally misses
  the cache without any invalidation step. Correctness by
  construction; no staleness logic needed.

**Alternatives considered.**

- *Unified `set_project_profile(...resolution, fps, ...)` that also
  changes the timeline authoring canvas.* Rejected per above:
  conflates render and authoring, hides a dangerous reflow inside
  a routine settings change.
- *Split into per-field tools (`set_output_resolution`,
  `set_output_codec`, ...).* Rejected: verbosity × 7, no clear
  benefit. Patch-style single tool with null = keep is the standard
  shape across our other "edit XXX" tools (`edit_text_clip`,
  `set_clip_transform`).
- *Invalidate `RenderCache` inline on profile change.* Redundant —
  the cache key includes the profile hash, so stale entries are
  already unreachable. Explicit invalidation would just delete
  cached renders that might still be useful if the user reverts.

**Reasoning.** Closes a real gap that would eventually block users
("I want this at 4K"), uses the safer half of the authoring/render
split, and keeps the tool surface small by not proliferating
single-field setters. FPS scalar is a pragmatic concession to the
common case; NTSC is deferred until someone actually asks.

**Coverage.** `SetOutputProfileToolTest` — thirteen cases: patch
resolution only, patch multiple fields, change container, report
empty updatedFields when values match, reject empty input, reject
width-without-height (both directions), reject non-positive
resolution, reject non-positive fps, reject blank codec, reject
non-positive bitrate, reject missing project, verify timeline
authoring resolution is NOT touched when output profile changes.

**Registration.** All five composition roots register
`SetOutputProfileTool(projects)` directly after `RemoveAssetTool`.
Prompt gained a `# Output profile (render spec vs. timeline
authoring)` section teaching the authoring/render distinction and
pointing explicitly to `set_output_profile` for render-side
changes.

---

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

## 2026-04-20 — `list_assets` for media catalog introspection

**Context.** Projects accumulate assets fast — imported media,
extracted frames, generated images / videos / audio, LUTs. Today the
only way for the agent to enumerate the catalog is `get_project_state`,
which dumps *everything*: full timeline, lockfile, snapshots, render
cache, source graph. That's a wall of JSON when the actual question
was "what videos do I have?" or "is asset X still referenced by any
clip before I prune it?" Token waste on that scale shows up directly
in compaction frequency.

**Decision.** Ship `list_assets(projectId, kind?, onlyUnused?, limit,
offset)` — a paginated projection of `Project.assets` with just the
fields an agent needs:

- `assetId`, `kind` (coarse classification: video / audio / image),
  `durationSeconds`, optional `width` / `height`, `hasVideoTrack`,
  `hasAudioTrack`, `sourceKind` (file / http / platform),
  `inUseByClips` count.
- Kind classification: `videoCodec != null` → video (includes muxed
  video+audio); `audioCodec != null` only → audio; neither → image.
- `onlyUnused = true` filters to assets referenced by zero clips —
  the "what can I safely prune?" query.
- Pagination: `limit` default 50, hard cap 500; `offset` default 0.

Parallel to `list_timeline_clips` and `list_source_nodes` in
philosophy: a tight, filterable projection over what
`get_project_state` already returns in bulk.

**Alternatives considered.**

- *Make `get_project_state` accept a section filter
  (`sections: ["assets"]`).* Rejected — coarse-grained filtering
  doesn't give us pagination or kind filtering, and changes the
  contract of a tool many existing flows rely on. A new tool is
  additive and leaves the old path alone.

- *Expose raw codec strings and bitrates.* Rejected — engines care,
  agents don't, and every extra field is bytes in every turn once
  the agent starts calling this reflexively. `hasVideoTrack` /
  `hasAudioTrack` booleans convey the useful signal without the
  vocabulary cost.

- *Ship `remove_asset` in the same change.* Deferred — removing an
  asset that any clip still references would leave the project
  broken (`validate_project` would flag `dangling-asset` on every
  dependent clip). `list_assets(onlyUnused=true)` gives the agent
  the safe-to-prune list, but actually doing the prune needs a
  thoughtful safety story (refuse when in-use? require a
  `removeDependentClips=true` opt-in?) — worth its own iteration.

**Reasoning.** Cheap projections over already-computed state are the
lowest-risk, highest-value tools to ship: ~140 lines, 14 tests, no
new invariants, no engine work. The `inUseByClips` count is the
load-bearing feature here — it's the one piece agents cannot
currently compute without reading every clip in the timeline.

---

## 2026-04-20 — `edit_text_clip` for in-place subtitle edits

**Context.** `add_subtitle` / `add_subtitles` / `auto_subtitle_clip`
lay text onto the timeline fine, but the moment the user wants to
*edit* an existing subtitle — typo fix, color change, size bump —
the only path was `remove_clip` + `add_subtitle`. That sequence
breaks three things: (1) the clip id changes, so any later tool that
referenced it (e.g. `set_clip_transform`) now points at nothing; (2)
transforms get reset to defaults; (3) if the subtitle was created by
`auto_subtitle_clip` the source binding (which links the subtitle to
the video it was transcribed from) gets dropped. All of that to fix
a typo.

**Decision.** Ship `edit_text_clip(projectId, clipId, …)` with
optional per-field overrides matching the `update_character_ref`
idiom used elsewhere in the codebase:

- `null` → keep current value.
- A provided value → replace.
- `""` on `backgroundColor` → clear (set to null, transparent).

Editable fields: `newText`, `fontFamily`, `fontSize`, `color`,
`backgroundColor`, `bold`, `italic`. At least one must be provided;
all-null input fails loud. Works on any `Clip.Text` regardless of
which track it sits on (Subtitle or Effect). `timeRange`, `id`,
`transforms`, and `sourceBinding` are left untouched — use
`move_clip` / `trim_clip` for positional edits, `revert_timeline` to
undo style changes. Emits a snapshot.

**Alternatives considered.**

- *Require `newStyle: TextStyle?` as a whole-object replace.*
  Rejected — forces the agent to fetch the current style (via
  `list_timeline_clips` or similar) just to tweak one field, and
  every roundtrip is a chance to drop a field. Per-field patch
  matches the consistency-node update tools so the agent already
  knows the shape.

- *Separate tools: `edit_text_body` + `edit_text_style`.* Rejected —
  the most common editor intent is "make this caption bigger AND
  change the color"; splitting would force two consecutive tool
  calls for the common case. One tool with optional overrides is
  strictly more capable.

- *Allow editing `timeRange` too.* Rejected — `move_clip` and
  `trim_clip` already cover that and their semantics (especially
  trim with source-range math for media-backed clips) are
  non-trivial. Overlapping responsibilities would invite
  inconsistency between the two code paths for the same operation.

- *Validate hex color format (`#RRGGBB` / `#RRGGBBAA`).* Deferred —
  engines will render garbage color if a malformed string is
  provided, but that's already true for `add_subtitle` and the
  engines are the source of truth for what they accept. Centralize
  color validation later if it becomes a real footgun.

**Reasoning.** This is a ~150-line tool with 10 tests that closes a
surprisingly common paper-cut. Autonomous runs frequently generate
subtitles wrong on the first pass (capitalization, line breaks,
trailing punctuation) and want a cheap in-place fix; today that
costs them a clip id and any attached transforms, which cascades
into more fix-up tool calls. The update-style patch idiom keeps the
schema small and matches the rest of the codebase.

---

## 2026-04-20 — `remove_filter` (ApplyFilter counterpart)

**Context.** `ApplyFilterTool` ships with an explicit note in its
kdoc: "remove and replace are out of scope for v0 (a future tool can
take an index)." Until today that note was still true — the only way
to undo an applied filter was `revert_timeline`, which rewinds the
*entire* timeline to the last snapshot. That means filter iteration
("try blur, hate it, try vignette instead") forces the agent to
either (a) throw away every subsequent edit or (b) start the turn
over from scratch. Both are terrible.

**Decision.** Ship a `remove_filter` tool keyed on filter **name**:

```
remove_filter(projectId, clipId, filterName)
→ { clipId, removedCount, remainingFilterCount }
```

Removes every Filter on the target clip whose `name == filterName`.
Video clips only (filters live only on `Clip.Video`). Emits a
timeline snapshot so the removal itself is revertable. Idempotent
when the filter isn't present (`removedCount: 0`, no error) so an
agent doing speculative cleanup ("drop the blur if it's there") can
issue the call without a probe first.

**Alternatives considered.**

- *Key on an index instead of a name.* Rejected — the agent doesn't
  naturally know filter indices. It applied the filter by name and
  will want to remove it by name. The ApplyFilter kdoc floated index
  as a possibility, but in practice name is what the agent has
  access to from `list_timeline_clips` output (filter names are
  displayed; positions aren't stable as other filters are added).

- *Remove only the first matching filter; require explicit "all"
  flag.* Rejected — duplicates are rare, and when they exist it's
  almost always because the agent applied `blur` twice and now wants
  *the blur* gone. Removing all by default matches user intent 95%
  of the time. If the agent really wants to keep one of two `blur`
  filters, the right answer is a future indexed-remove or filter-ids
  on the Filter data class, not an extra flag here.

- *Fold into a unified `update_filters(clipId, filters)` that
  replaces the whole list.* Rejected — forces the agent to round-trip
  the current list (via `list_timeline_clips` or similar) for every
  remove. The single-purpose `remove_filter` is the minimal surface
  that fixes the real problem.

- *Ship `clear_filters(clipId)` instead/also.* Deferred — current
  pattern is "apply one, maybe undo one"; wholesale wipes haven't
  shown up yet. Trivial to add later if needed (one more tool, same
  shape).

**Reasoning.** Closing this asymmetry is a small-code, big-behavior
win: ~130 lines + 7 tests, but it unblocks iterative filter tuning
which was previously forcing a full timeline rewind per undo. The
idempotent semantics mirror how the AIGC tools handle missing
consistency bindings — cheap no-op instead of a stacktrace — which
keeps agent turn plans shorter.

---

## 2026-04-20 — `duplicate_clip` for clone-with-attached-state

**Context.** A very common editing intent is "put that clip again at a
new position, with the same look." Today the only way to do this is
`add_clip(asset, @newStart)` which mounts the asset but strips every
piece of attached state — filters, transforms, source bindings, audio
volume, fade envelope, text body, text style. To replicate the
original's look the agent has to follow up with 3-5 further tool calls
(`apply_filter`, `set_clip_transform`, `set_clip_volume`,
`fade_audio_clip`, …), each of which can fail or be forgotten. For
character-driven runs the source-binding loss is especially bad: a
clone of a Mei clip that loses `Set(meiRefNode)` will no longer be
flagged stale when Mei's reference is revised, so the two copies
drift.

**Decision.** Ship a dedicated `duplicate_clip` tool that byte-for-byte
clones every non-identity, non-start field of a clip:

- `id` is fresh (`ClipId(Uuid.random().toString())`).
- `timeRange.start` is the new timeline position.
- `timeRange.duration` is preserved.
- Everything else (Video: filters, transforms, sourceBinding, assetId,
  sourceTimeRange; Audio: volume, fadeIn/Out, sourceBinding, assetId,
  sourceTimeRange; Text: body, style, sourceBinding) is a structural
  copy.

Optional `trackId` lets the caller place the duplicate on a different
track **of the same kind** (Video→Video, Audio→Audio,
Text→Subtitle/Effect). Cross-kind moves are refused — the clip data
model can't survive the transition. Omit `trackId` to place on the
source clip's current track (the 95% case).

**Alternatives considered.**

- *Have the agent chain add_clip + apply_filter + … itself.* This is
  the status quo. It's brittle, verbose (3-5 tool calls for every
  duplicate), and most importantly, the source-binding set can't be
  recovered after the fact because `apply_filter` / `set_clip_volume`
  never touch bindings — the agent would have to re-derive them from
  the asset's origin, which is not always possible (AIGC clips' source
  binding comes from the tool that generated them, not the asset).

- *Extend `add_clip` with a `cloneFromClipId` parameter.* Rejected —
  overloads an already-parametric tool with a fundamentally different
  semantics ("mount an asset" vs "clone attached state"), and forces
  every future add_clip caller to wonder whether clone-mode changed
  some field they relied on. Clean split is cheaper.

- *Validate that the duplicate doesn't overlap existing clips on the
  target track.* Rejected — same stance as `MoveClipTool`. Overlap
  validation is a Timeline-level concern, not a per-tool concern; if
  the Timeline contract ever hardens against overlaps, both tools
  change together. Today they don't validate.

**Reasoning.** The "copy-paste with look intact" intent is exactly the
kind of shortcut that makes agent runs feel competent — one tool call
instead of five, no risk of drift, source-binding staleness still
tracks correctly across the duplicate. Cost is ~190 lines of
straightforward clone logic and 7 tests. The cross-kind refusal
covers the only real footgun: silently allowing a Video clip onto an
Audio track would make the project unrenderable.

---

## 2026-04-20 — `validate_project` structural lint tool

**Context.** Multi-step autonomous runs accumulate state: the agent
defines a `character_ref`, references it from three AIGC clips, then a
follow-up turn removes the node, and the clips are now pointing at a
ghost source binding. Or an imported asset gets deleted, but three
clips still reference its `assetId`. Or hand-authored snapshots
stored a `volume=5.0` that the schema happily round-trips but the
engine will misbehave on. Today there's no way to catch any of this
pre-export short of actually running the engine, which (a) is slow,
and (b) either silently mis-renders or fails loudly with a message
the agent can't cleanly map back to the offending clip.

**Decision.** Ship a read-only `validate_project` tool that walks the
project and reports one `Issue { severity, code, message, trackId?,
clipId? }` per invariant violation. Current rule vocabulary (stable
`code` strings so callers can switch on them):

- `dangling-asset` (error) — `Clip.Video/Audio.assetId` not in `Project.assets`.
- `dangling-source-binding` (error) — `Clip.sourceBinding` references a
  `SourceNodeId` that is not in `Project.source.byId`.
- `non-positive-duration` (error) — `timeRange.duration <= 0`.
- `volume-range` (error) — audio `volume ∉ [0, 4]`.
- `fade-negative` (error) — `fadeInSeconds < 0` or `fadeOutSeconds < 0`.
- `fade-overlap` (error) — `fadeIn + fadeOut > timeRange.duration`.
- `duration-mismatch` (warn) — `timeline.duration < max(clip.end)`.

`passed: Boolean = errorCount == 0` is the top-level assertion the
caller branches on; warnings are informational. The tool never fails
loudly for a project-state problem (that's the whole point — surface
it instead of swallowing it); it fails loudly only on "project not
found", which is a caller bug.

**Alternatives considered.**

- **Fold validation into `ExportTool`.** Already partly done (stale
  guard) but export is the wrong place to surface lint-style issues:
  by the time you're exporting you've spent the planning cycle, and
  the agent has no cheap way to *ask* whether a project is healthy
  without committing to a full render. A read-only tool decouples
  the question from the action.
- **Auto-heal.** Tempting — dangling assets / bindings could in theory
  be patched. But each rule has a different right answer (should a
  dangling sourceBinding be dropped, or the node re-created? should
  a clamped volume be set to 1 or 0?) and deciding that is a creative
  call, not a mechanical one. Linter-now, autofix-later if we see
  the patterns repeat.
- **Add staleness (`find_stale_clips`) as another rule here.** No:
  staleness is a first-class DAG concern with its own tool. Merging
  concerns makes both less clear.

**Why this now.** VISION §3.2 (build-system mental model) needs a
"before you render, is the source graph well-formed?" check, and the
professional path (§4) needs an explicit pass the user can ask for
without guessing. Also keeps autonomous loops honest: the agent can
call `validate_project` as its last step before claiming a multi-edit
turn is done.

**Files.**
- `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/project/ValidateProjectTool.kt`
- `core/src/commonMain/kotlin/io/talevia/core/agent/TaleviaSystemPrompt.kt` — new section
- `core/src/jvmTest/kotlin/io/talevia/core/tool/builtin/project/ValidateProjectToolTest.kt` — 9 tests
- Registered in `apps/cli`, `apps/desktop`, `apps/server`, `apps/android`, `apps/ios`.

---

## 2026-04-20 — `list_timeline_clips` for clip-level agent introspection

**Context.** The agent could see *counts* of a project (`get_project_state`
returns track / clip / asset totals) and could enumerate source nodes and
lockfile entries, but had no way to list the clips themselves with their
ids, times, and bindings. Every "lower the volume on the music after
00:30" request either leaned on the user to give an id or forced the
agent to dump the full Project JSON via `get_project_state`'s stringified
payload. Both paths are worse than a purpose-built tool.

**Decision.** Ship a read-only `list_timeline_clips` tool that walks
`Project.timeline.tracks`, flattens the clips, and returns a compact
`ClipInfo` per row: clip id, track id/kind, clip kind (video / audio /
text), start / duration / end seconds, bound `assetId`, filter count,
`sourceBinding` node ids, plus per-kind extras (audio: `volume`,
`fadeIn/Out`; text: 80-char `textPreview`). Optional filters:
`trackId`, `trackKind` ∈ {video, audio, subtitle, effect}, and
`fromSeconds` / `toSeconds` for `[start, end]`-intersection against a
time window. Results are ordered by track-position then
`timeRange.start` so adjacent rows correspond to adjacent playback.
Default limit 100; `truncated: Boolean` flips true when capped.
`outputForLlm` is a line-per-clip summary (kind/track, id, @start
+duration, asset, filters) so the agent spends ~20 tokens per clip
instead of several hundred on a JSON blob. Permission is
`project.read` (matches the other list tools).

**Alternatives considered.**

- **`get_clip_info(clipId)`.** Closer to the coding-agent vocabulary
  (per-node detail fetch) but forces a preceding list anyway — and
  listing covers the single-clip case via a trackId/time filter.
- **Folding it into `get_project_state`.** Makes that tool's payload
  quadratically larger (we'd need full clip lists on every state
  poll) and violates "one tool, one reason to call it". The split
  mirrors how we already have `list_source_nodes` separate from
  `get_project_state` counts.
- **Returning the full `Clip` JSON.** Richest but most expensive, and
  most fields (e.g. `sourceRange.duration` when equal to
  `timeRange.duration`, transform defaults) are redundant for the
  "find the clip id" use case. ClipInfo is intentionally lossy so the
  LLM-visible payload stays small.

**Why this now.** Part of closing the "agent introspection" gap on the
VISION §4 professional path — once the agent can answer "what's on the
timeline?" without a full state dump, targeted edits on long projects
become feasible. Also unblocks the natural "show me the clips between
10s and 20s" question the user keeps implicitly asking.

**Files.**
- `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/project/ListTimelineClipsTool.kt`
- `core/src/commonMain/kotlin/io/talevia/core/agent/TaleviaSystemPrompt.kt` — new section
- `core/src/jvmTest/kotlin/io/talevia/core/tool/builtin/project/ListTimelineClipsToolTest.kt` — 8 tests
- Registered in `apps/cli`, `apps/desktop`, `apps/server`, `apps/android`, `apps/ios`.

---

## 2026-04-20 — ripple-delete on `remove_clip` (per-track, atomic)

**Context.** `remove_clip` intentionally left the gap behind when it shipped
("preserve transitions / subtitles aligned to specific timestamps"). The
system prompt told the model "if the user asks for ripple-delete
behavior, follow up with `move_clip` on each downstream clip" — which is a
smell: O(n) tool calls per delete, N separate snapshots on the undo stack,
and the agent has to remember the follow-up. Every time we paper over a
pattern in the system prompt instead of lifting it into a primitive, the
model carries the cost turn after turn.

**Decision.** Give `remove_clip` an optional `ripple: Boolean = false`
parameter. When true, after removing the target clip, shift every clip on
the **same track** whose `timeRange.start >= removed.timeRange.end` left
by `removed.timeRange.duration`. Overlapping clips (start < end) are left
alone — they were intentionally placed to overlap (PiP / layered edits),
and shifting them would destroy the overlap. Single-track ripple only;
other tracks stay put. Emits one timeline snapshot so `revert_timeline`
rolls the whole operation back in one hop.

Output gained `rippled: Boolean`, `shiftedClipCount: Int`, and
`shiftSeconds: Double` so the caller can surface a terse confirmation
("Rippled 3 clip(s) left by 2.4s").

**Alternatives considered.**

- **Sequence-wide ripple (shift every track).** Matches FCP's default
  behaviour and solves the audio-video sync case cleanly. But it also
  drifts independent tracks (background music, reference layer,
  unrelated subtitle lanes), which silently ruins the mix for any
  multi-track edit. Picked per-track (DaVinci "ripple delete" default)
  because incorrect-on-average-but-loud is worse than
  correct-on-the-common-case-with-explicit-extension. Sync-critical
  edits can follow up with `move_clip` on paired tracks — at least
  the surface is explicit.
- **A new `ripple_delete_clip` tool parallel to `remove_clip`.** More
  discoverable in the tool list but doubles the agent's surface for
  a feature that's one boolean. The system prompt update is cheaper
  and the flag keeps the cut/stitch/filter vocabulary tight.
- **Ripple subtitle clips automatically on video deletion.** Too
  clever — subtitles carry their own timing intent, and a well-timed
  caption past the gap might be meant to stay at its absolute
  timestamp. Left to the caller.
- **Shift `timeRange` AND `sourceRange`.** No — ripple changes WHEN
  the clip plays, not WHICH source bytes it reads. `sourceRange`
  stays untouched.

**Files touched.**
`core/src/commonMain/.../tool/builtin/video/RemoveClipTool.kt`
(added ripple logic + schema field + shiftStart helper),
`core/src/commonMain/.../agent/TaleviaSystemPrompt.kt` (rewrote the
"Removing clips" section to teach the ripple flag),
`core/src/jvmTest/.../tool/builtin/video/RemoveClipToolTest.kt`
(added 4 tests: ripple shifts downstream, ignores overlapping,
single-track only, ripple=false matches old behaviour).

---

## 2026-04-20 — AGENTS.md / CLAUDE.md project-instruction discovery

**Context.** Operators already drop `AGENTS.md` / `CLAUDE.md` into their
project roots for claude-code and OpenCode, and Talevia's own repo has
a 150-line `CLAUDE.md` with ongoing guidance. Until now the Talevia
agent saw none of that — every container wired in
`taleviaSystemPrompt(extraSuffix = <a tiny fragment>)` and the per-project
rules silently did nothing. That breaks the VISION §4 dual-user
promise: the "professional path" explicitly includes overriding agent
defaults per project, and we were forcing the user to stuff their
rules into every prompt manually.

**Decision.** OpenCode-parity discovery layered in at the container
level:

- **`core/commonMain/.../agent/ProjectInstructions.kt`** — platform-agnostic
  `ProjectInstruction(path, content)` record + `formatProjectInstructionsSuffix`
  that emits a `# Project context` section with one `## <path>` subheader
  per file. Empty input → empty string so containers can pass it through
  unconditionally.
- **`core/jvmMain/.../agent/InstructionDiscovery.kt`** —
  `InstructionDiscovery.discover(startDir, …)` walks from `startDir`
  upward through parent dirs (capped at `maxWalkDepth=12` levels), at
  each level trying `AGENTS.md` and `CLAUDE.md`. Optionally mixes in
  globals from `~/.config/talevia/AGENTS.md`, `~/.talevia/AGENTS.md`,
  `~/.claude/CLAUDE.md`. Dedupes by canonical path. Defensive byte
  caps (64 KiB per file, 128 KiB total) keep a stray huge instruction
  file from blowing out context.
- **Ordering** is outermost-first / innermost-last so the nearest
  (most specific) file lands at the tail of the system prompt where
  LLMs weigh it more heavily on conflict. Globals prepend so
  machine-wide defaults never beat project-specific rules.
- **Wired into all three JVM containers** (CliContainer, AppContainer,
  ServerContainer). Lazy-cached per container — `/new` sessions inherit
  the same rules rather than re-walking disk per turn. The server
  still prepends the headless-runtime permission note; the CLI still
  prepends its cwd/home context.

**Alternatives considered.**

- **Do it in `commonMain` with an injected file-reader abstraction.**
  Would work on iOS/Android too, but current platform priority is
  Core > CLI > Desktop; iOS/Android don't have a cwd concept that
  maps cleanly to AGENTS.md discovery (apps are sandboxed). Starting
  JVM-only covers the three platforms that actually have a shell
  working directory, and we can lift the logic into common with
  `okio.FileSystem` later if iOS/Android ever need it.
- **Re-read on every turn so edits land live.** Matches the "hot
  reload" story but doubles disk I/O per turn and can create
  inconsistent states mid-conversation when the user edits AGENTS.md
  between two turns. OpenCode also loads once per session; matching
  that makes session behaviour predictable. Users who edit AGENTS.md
  can `/new` (or restart the CLI) to pick up the change.
- **Stop the walk at `.git` or the worktree root.** Cleaner
  boundary, but requires detecting a repo marker and then the
  behaviour diverges for non-git projects. The 12-level depth cap
  already bounds the walk; instruction files outside a real project
  are rare enough that the extra plumbing isn't worth it.
- **Expose a config knob (`TALEVIA_AGENTS_MD=off`).** Deferred — no
  user has asked yet and the defaults cause no harm (blank files are
  filtered, oversized files are skipped). Revisit if discovery picks
  up something surprising in the wild.

**Files touched.** `core/src/commonMain/.../agent/ProjectInstructions.kt`
(new), `core/src/jvmMain/.../agent/InstructionDiscovery.kt` (new),
`core/src/jvmTest/.../agent/InstructionDiscoveryTest.kt` (new, 11 tests),
`apps/cli/.../CliContainer.kt`, `apps/desktop/.../AppContainer.kt`,
`apps/server/.../ServerContainer.kt`.

---

## 2026-04-20 — CLI polish: grouped `/help`, new `/status`, "did you mean"

**Context.** `/help` had grown to 12 flat entries — users scan-read them
looking for the one command they want and the signal-to-noise ratio
was getting thin. There was also no one-shot "where am I" answer —
operators had to run `/sessions` + `/model` + `/cost` to reconstruct
it. And mistyping a slash command (`/hitsory`) surfaced a generic
`unknown command` with no nudge toward the right name.

**Decision.**

- **Categories on `SlashCommandSpec`.** Added a
  `SlashCategory { SESSION, HISTORY, MODEL, META }` enum and a
  `category` field on `SlashCommandSpec`. `/help` groups commands
  under a short heading per bucket, keeps the existing "name ·
  argHint  description" layout within each group. Categories are
  ordered by how often an operator reaches for the group (session >
  history > model > meta), so the most-used commands sit at the top.
  First line of help now advertises tab-completion + unique-prefix,
  both of which were undocumented.

- **`/status` command.** Two-line summary: `project=… session=… ·
  <title>` + `model=provider/id · N turn(s) · in/out tokens · usd`.
  Aggregates from the same places `/cost` reads; no new state.

- **"Did you mean" for unknown commands.** Levenshtein edit
  distance ≤ 2 against every registered command. Capped so far-off
  typos (`/asdf`) don't produce irrelevant suggestions. The check is
  pure and tiny (the inner loop bounds are small and the command
  list is ~15 entries), so it runs inline on the unknown-command
  path.

- **Markdown repaint heuristic now spots GFM tables.** The existing
  `looksLikeMarkdown` already caught fenced code, bold, italics,
  bullets, blockquotes, numbered lists, and headings. Tables — `|
  col | col |` followed by `|---|---|` — were silently rendered raw.
  Added a two-line pass that catches them. Cheap: early-returns on
  the first match, scans `n-1` adjacent pairs max.

**Alternatives considered.**

- **Auto-running the closest match instead of just suggesting.** A
  `/help` typo would be fine but the catastrophic failure mode is
  `/reverr` auto-running `/revert` against the wrong anchor. No
  destructive slash command should ever run under a name the user
  didn't actually type.

- **Splitting `/help` into `/help session` / `/help history`
  sub-commands.** Rejected — the whole catalogue fits in a screen
  and forcing a two-step is worse UX than a grouped single screen
  for this count.

- **Prometheus-style counters for slash-command usage.** Overkill
  for a local REPL; skipped.

**Consequences.**

- `/help` is still one screen even with the new `/status` entry —
  categorisation gives visual breathing room without adding rows.
- No new external dependencies.
- Help output width grows by one column for the category heading
  indent; still fits in an 80-col terminal.

---

## 2026-04-20 — Agent retries transient provider errors with exponential backoff

**Context.** Anthropic / OpenAI / Gemini all emit 5xx / 429 /
"overloaded" / "rate_limit" responses under load. Before this change
those turned into a dead assistant turn — `finish = ERROR`, one ugly
row in the transcript, and the user had to retype their prompt. Even
OpenCode — our reference spec — catches these at `session/retry.ts`
and transparently replays.

**Decision.**

Add retry at the Agent loop level, not at the `LlmProvider.stream`
level, for one reason: partial persisted state. `streamTurn` writes
parts (text / reasoning / tool calls / `StepFinish`) into the store
as they arrive. A retry must therefore delete the failed assistant
row — an operation the provider can't perform on its own and that we
already have via `SessionStore.deleteMessage` (courtesy of the
revert work).

Contract:

1. **New `RetryPolicy` data class** (core/agent). `maxAttempts = 4`,
   exponential backoff `2s, 4s, 8s, …` capped at 30s when no
   `Retry-After` header is available, globally capped at 10 min.
   `RetryPolicy.None` disables retry entirely for tests / batch
   runs that want failure signals without interference.

2. **New `RetryClassifier`** pure function. Input: the
   `LlmEvent.Error.message` + the provider's `retriable` hint.
   Output: a human-readable reason (logged + published on the bus)
   or `null` to bail. Recognises: HTTP 5xx / 429, "overloaded",
   "rate limit", "too many requests", "exhausted", "unavailable".
   Explicitly refuses to retry context-window-exceeded errors,
   even if the provider marked them retriable (they will never
   succeed on replay — they need compaction, not retry).

3. **`LlmEvent.Error` grows `retryAfterMs: Long?`** — parsed by each
   provider from the `retry-after-ms` / `retry-after` response
   headers via a shared `parseRetryAfterMs(...)` helper. When
   present the policy honours the server's hint; otherwise it
   falls back to exponential backoff.

4. **Providers tag their HTTP error events with `retriable = true`**
   when status is 5xx / 429 / 408. Both Anthropic and OpenAI (and
   Gemini as a bonus) get the treatment. Keeps the classifier's
   job straightforward — 99 % of the time the provider has already
   given us the right answer and we only fall through to string
   matching for non-HTTP cases (stream-level error events).

5. **`Agent.runLoop` wraps `streamTurn` in a retry loop.** On
   `FinishReason.ERROR`:
   - if the error is retryable AND no content was streamed yet AND
     attempts remain → delete the assistant message (cascades
     parts), publish `BusEvent.AgentRetryScheduled`, delay, loop
   - else propagate the error to the transcript as today.

   "No content streamed yet" is tracked inside `streamTurn` as a
   boolean flipped on the first `TextStart` / `ReasoningStart` /
   `ToolCallStart`. Mid-stream errors (rare — Anthropic emits them
   via an SSE `error` frame after some content) are NOT retried:
   the user has already seen partial output and silently replacing
   it would be worse than the stale turn.

6. **New bus event `BusEvent.AgentRetryScheduled(attempt, waitMs,
   reason)`** — consumed by the CLI renderer (`Retrying in 4s —
   Provider is overloaded`) and the server's SSE stream. UI
   surfaces can show "retrying..." instead of leaving the turn
   looking hung.

**Alternatives considered.**

- **Retry inside the provider's `stream(...)` Flow** — cleaner
  separation but requires each provider to know about part
  persistence semantics (or to stash and replay emitted events),
  which is exactly the coupling the `LlmEvent` abstraction was
  meant to avoid. Rejected.

- **OpenCode's Effect.js `Schedule` + `fromStepWithMetadata`** —
  direct port would fight Kotlin idioms. The equivalent here is
  boring imperative code and it's shorter.

- **Retry with a single global policy baked into Agent** — we kept
  the `retryPolicy` as a constructor param so tests can inject
  `RetryPolicy.None` and server deployments (where retries compete
  with human-facing SSE timeouts) can tune the caps without
  rebuilding core.

- **Don't delete the failed assistant row, just append another** —
  rejected: leaving an ERROR row per retry clutters the transcript
  and breaks the "one user turn → one assistant turn" mental model
  users rely on in history / revert flows.

**Consequences.**

- Transient outages are invisible to the user — they see a
  "retrying…" notice and then the real reply.
- Retry counter is exposed via the metrics sink as
  `agent.retry.scheduled`, so we can see how hot any given
  provider is running.
- Max delay is 30 s per attempt by default; four attempts means
  the user waits at most ~60 s cumulative before seeing a real
  failure. Longer than ideal but matches OpenCode's heuristics
  and well under Ktor's default socket timeouts.
- No breaking changes to `LlmProvider`: providers that don't set
  `retriable` still benefit from the classifier's fallback string
  match.

---

## 2026-04-20 — `SessionStore.fork` gains an anchor arg + CLI `/fork [idPrefix]`

**Context.** The revert primitive shipped earlier today destroys
history in-place. The non-destructive twin — branch this
conversation from an earlier point, keep the original — was missing.
`SessionStore.fork` already deep-copies a whole session, which covers
the "save-before-I-experiment" case but not "rewind-and-try-a-
different-direction without losing what we had".

**Decision.**
- `SessionStore.fork` grows an optional `anchorMessageId: MessageId?`
  parameter. `null` keeps the existing "copy everything" behaviour.
  Non-null: copy only messages at-or-before the anchor in
  `(createdAt, id)` order (the same ordering `deleteMessagesAfter`
  uses). Throws `IllegalArgumentException` if the anchor isn't in
  the parent session — same shape as `SessionRevert.revertToMessage`'s
  contract.
- New CLI slash command `/fork [<messageId-prefix>]`:
  - no arg → full-history fork (equivalent to existing behaviour).
  - prefix → truncate at that anchor.
  - On success, the REPL **switches into the new branch** (same
    affordance as `/resume`), because the mental model after
    `/fork` is "now keep editing in the branch I just made". Users
    who want to stay in the parent can `/resume <parent-prefix>`
    back.
- Parent session and project state are both left untouched — fork
  is purely additive. Project timeline is *not* restored to the
  anchor's snapshot. Rationale:
  - Sessions and projects are N:1 — a project can have multiple
    sessions. "Fork session" implying "fork project" or "mutate
    project" would introduce surprising cross-talk between
    branches sharing a project.
  - We already have `fork_project` tool + project snapshots for
    explicit project branching. Users who want "branch a
    timeline-aware conversation" compose the two:
    `save_project_snapshot` → `/fork` → (optionally later)
    `restore_project_snapshot` in the branch.
- **Not shipped**: an `anchorPartId` parameter. OpenCode's revert
  supports partID precision (revert mid-message, dropping later
  parts of the same assistant turn). Our Part model has
  `TimelineSnapshot` parts between tool calls which makes this
  tempting for "fork before the bad tool call", but: (a) the UX of
  typing a part-id prefix is worse than choosing a message-id
  prefix from `/history`, and (b) revert already doesn't support
  it, so fork doing so unilaterally would feel asymmetric. Revisit
  if a clear driver emerges.

**Alternatives considered.**
- **Do nothing; tell users to `save_project_snapshot` before
  experiments.** That saves project state, not conversation
  state, so "save" still costs the chat history if the agent goes
  off the rails. Fork preserves both.
- **Implement fork as "revert on a copy"** (copy the whole
  session, then run revert on the new id). Simpler to write but
  ~2× the IO for large sessions, since we'd insert every message
  only to immediately delete the tail. The current implementation
  only copies up to the anchor.
- **`/branch` instead of `/fork`.** Closer to git terminology but
  OpenCode + the existing `fork_project` tool both already use
  "fork" — staying consistent keeps one mental model.

**Why this matches VISION.**
- §3.4 "可版本化 · 可分支": paired with project snapshots, fork
  gives every collaborator (user + agent) a way to experiment
  without losing the good state they already reached — which is
  the whole point of the "codebase" framing.

**Files touched.**
- `core/src/commonMain/.../session/SessionStore.kt` (interface +
  doc).
- `core/src/commonMain/.../session/SqlDelightSessionStore.kt`
  (optional-anchor truncation; existing call sites still work
  via default arg).
- `apps/cli/src/main/.../repl/SlashCommands.kt` + `Repl.kt`
  (`/fork` handler, switches session on success, ambiguous /
  not-found cases print candidates).
- `core/src/jvmTest/.../session/SessionForkAnchorTest.kt` (new —
  3 cases: anchor truncates correctly, no-anchor preserves old
  behaviour, foreign anchor throws).

---

## 2026-04-20 — `SessionRevert` service + CLI `/revert` (hard revert; OpenCode parity)

**Context.** A chat-driven editor needs a cheap undo for agent turns —
without it, the only way to recover from "I didn't mean that" after a
mutating tool call is to start a new session, losing all the context
already built up (source nodes, character refs, the flow of iteration).
OpenCode ships `session/revert.ts` for this. We already had the
infrastructure half-done in an uncommitted WIP state:
`SessionStore.deleteMessage` / `deleteMessagesAfter` with matching
`BusEvent.MessageDeleted`. The service that consumes them didn't
exist.

**Decision.**
- New `SessionRevert` (`core/session/SessionRevert.kt`,
  commonMain) with a single public entry point
  `revertToMessage(sessionId, anchorMessageId, projectId): Result`.
  Behavior:
  1. Delete every message strictly after the anchor (via existing
     `deleteMessagesAfter`), which cascades through
     `Parts.deleteByMessage`.
  2. Walk `listSessionParts(includeCompacted = true)` filtered to
     messages at-or-before the anchor, take the last
     `Part.TimelineSnapshot` encountered, and restore it into the
     project via `ProjectStore.mutate`. No snapshot found → timeline
     untouched.
  3. Publish a single `BusEvent.SessionReverted` carrying the count
     and the applied-snapshot part id (null if none).
- Semantics: **anchor is kept**, everything strictly after is dropped.
  So `/revert <user message>` drops that user's assistant reply and
  all later turns — use `/revert <assistant message>` to keep the
  reply it produced (and its timeline snapshot).
- **Hard** revert, not OpenCode's soft overlay. See alternatives.
- New CLI slash commands:
  - `/history` — list every message in the session with a 12-char id
    prefix + role + first-line preview (or `(tool: id)` when the
    first part is a tool call). Same affordance as `/resume` for
    session ids.
  - `/revert <idPrefix>` — resolve prefix to a unique message in the
    current session, call `SessionRevert`, print a one-liner with
    dropped count + restored clip/track counts.
- Bus wire-up: `EventBusMetricsSink` counts `session.reverted` +
  `message.deleted`. Server SSE stream adds a `session.reverted`
  event carrying the same fields as the Kotlin event.

**Alternatives considered.**
- **Soft revert with unrevert (OpenCode's approach).** OpenCode
  keeps a diff overlay that `unrevert` can un-apply, because its
  analogous state is filesystem edits and an unapply patch has real
  value. Our analogous state is the canonical timeline, which
  already has explicit snapshot parts at every mutating tool call —
  so "drop messages + restore snapshot" is one read-modify-write
  that's easy to reason about. Unrevert on a hard revert would
  require storing another snapshot of "what the revert replaced"
  and defining edge cases (what if the user appended new messages
  after revert but before unrevert?). Not worth it on the first
  cut; we can add it later by introducing a single
  `Session.revert` metadata field mirroring OpenCode's shape.
- **Delete anchor too (strict "revert before the target").** Makes
  revert-to-user-message a round-trip-free re-edit, but breaks the
  common case of "keep this assistant answer, drop the tangent it
  spawned" because there's no way to express "keep this message".
  Current semantic preserves both; user who wants the stricter form
  can pass the parent-user id.
- **Tool surface (`revert_session` tool the agent can invoke).**
  Deliberately **not** shipped in this pass. The primary use case
  is a human typing "go back" after seeing output; letting the
  agent revert its own history opens up confusing loops (agent
  reverts to before its tool call, re-runs, loops). Revisit once
  there's a concrete driver — the service API is already shaped to
  become a tool cleanly.
- **CLI `/undo` with no anchor argument ("pop last turn").**
  Tempting for ergonomics, but "last turn" is ambiguous during a
  long agent trajectory with many assistant messages between two
  user turns. Requiring the id prefix keeps the mental model the
  same whether you're rolling back one reply or ten.
- **Concurrency guard (`assertNotBusy`, à la OpenCode's
  `SessionRunState`).** Skipped for now — the CLI already cancels
  the agent on Ctrl+C before the user can type a slash command,
  and tool-driven reverts don't exist yet. If we add them we'll
  need to check; documented in the Kotlin source as a caveat.

**Why this matches VISION.**
- §3.4 "Project / Timeline is codebase · 可版本化 · 可回滚":
  revert is the session-scoped counterpart of the project snapshot
  system we already have. Both compose — you can revert the
  session and restore a project snapshot independently.
- §5.3 rubric "增量编译": reverting to a snapshot puts the
  timeline hash back to a prior value; the export cache / stale
  guard / lockfile automatically re-validate against the reverted
  state with no extra wiring.

**Files touched.**
- `core/src/commonMain/.../session/SessionRevert.kt` (new)
- `core/src/commonMain/.../session/SessionStore.kt`
  (commit existing WIP: `deleteMessage`, `deleteMessagesAfter`)
- `core/src/commonMain/.../session/SqlDelightSessionStore.kt`
  (commit existing WIP: impls)
- `core/src/commonMain/.../bus/BusEvent.kt`
  (commit existing WIP: `MessageDeleted`; add `SessionReverted`)
- `core/src/commonMain/.../metrics/Metrics.kt`
  (counter names for both events)
- `core/src/commonMain/sqldelight/.../Messages.sq`
  (commit existing WIP: `delete` query)
- `core/src/commonMain/sqldelight/.../Parts.sq`
  (commit existing WIP: `deleteByMessage` query)
- `core/src/jvmTest/.../session/SessionRevertTest.kt` (new, 4
  cases: happy-path revert restores timeline; revert with no
  prior snapshot leaves timeline untouched; revert to latest is
  no-op; cross-session anchor + unknown anchor throw)
- `apps/cli/src/main/.../repl/SlashCommands.kt` (add `/history`,
  `/revert` to catalogue — autocompletion picks them up via
  `SlashCompletion`'s `SLASH_COMMANDS` loop)
- `apps/cli/src/main/.../repl/Repl.kt` (`historyTable`,
  `handleRevert` handlers)
- `apps/server/src/main/.../ServerModule.kt`
  (SSE event names + `BusEventDto` fields for both events)

---

## 2026-04-20 — `WebSearchTool` + `SearchEngine` abstraction (OpenCode/Claude Code parity)

**Context.** `WebFetchTool` (shipped earlier today) lets the agent
read a URL it already knows about. The "find references on X" / "what
are the latest posts about Y" / "give me a few inspirations for the
travel-vlog opener" class of intents is upstream of that — the agent
needs to *discover* the URLs first. Both Claude Code (`WebSearch`) and
OpenCode (`tool/websearch.ts`) ship a dedicated search tool exactly
for this. Without it the agent invents URLs and `web_fetch` 404s, or
asks the user to paste links.

**Decision.**
- New `WebSearchTool` (`id="web_search"`) in
  `core/tool/builtin/web/`, commonMain. Input
  `{query: String, maxResults: Int = 5}`; output
  `{query, provider, results: List<{title,url,snippet}>, answer?}`.
- The tool is **provider-agnostic**: it depends on a new
  `core.platform.SearchEngine` interface (mirroring the
  `ImageGenEngine` / `MusicGenEngine` / `VideoGenEngine` pattern).
  Concrete vendors live under `core.provider.<vendor>` and translate
  their native JSON into `SearchResults`. None of the SDK-native
  shapes leak past the boundary (CLAUDE.md §5).
- First concrete provider: **Tavily**
  (`core/provider/tavily/TavilySearchEngine.kt`). Single
  `POST /search` endpoint, free tier covers interactive use, returns
  a small LLM-friendly JSON shape with an optional one-paragraph
  synthesised answer. Wired only when `TAVILY_API_KEY` is set in env;
  otherwise the search slot stays null and `web_search` stays
  unregistered (same gating as the AIGC engines).
- **Permission**: new `web.search` permission, defaults to ASK with
  the **lower-cased trimmed query** as the pattern. So an "Always"
  rule scopes to that exact phrase rather than blanket-granting
  search; users that want frictionless search can flip to ALLOW
  with pattern `*` once. `ServerPermissionService` auto-rejects
  ASK so headless deployments stay deny-by-default.
- **Result cap**: `maxResults` defaults to 5, hard-capped at 20. We
  truncate at the tool layer so a runaway provider response can't
  blow the context budget.
- Wired in CLI / desktop / server containers (the three JVM apps
  with HTTP access). Mac CLI > desktop > server priority per
  CLAUDE.md.

**Alternatives considered.**
- **Brave Search API**. Solid free tier, but the response shape is
  closer to a raw SERP than to LLM-synthesised hits — no `answer`
  field, snippets are HTML-heavy. Tavily's design is explicitly
  "search for agents", which matches the intent better. Brave is a
  natural second provider to add behind the same `SearchEngine`
  interface.
- **Exa** (formerly Metaphor). Excellent semantic search, but the
  pricing model is per-query with a smaller free tier — less
  friendly for an interactive agent doing many small lookups.
- **DuckDuckGo zero-click API**. No API key required, but the
  response is a thin abstract that often returns nothing useful for
  long-tail queries. Wrong tool for "find me references" use cases.
- **Bing Web Search**. Azure setup overhead is heavy for a
  first-party provider; revisit when we have multi-provider routing.
- **Skip search entirely; let the LLM hallucinate URLs and fall
  back to `web_fetch`.** Tried in early M4 demos. Hallucinated URLs
  4xx ~70% of the time, frustrating UX.

**Why this matches VISION.**
- §3 "Compiler is pluggable Tools": `SearchEngine` is one more
  pluggable interface, identical in shape to the AIGC engines.
- §5 rubric "Source layer + tool coverage": closes the
  reference-discovery gap that `web_fetch` alone left open. The
  agent can now ground its choices in real, current information
  instead of training-cutoff guesses — directly relevant for vlogs
  that reference current events, music recs, recent VFX trends.

**Why we copied the host-pattern idea (per-query) for permission.**
`web_fetch` keys on URL host so users grant a domain at a time. For
search, the equivalent narrowing knob is the *query phrase* — letting
"Always" allow `kotlin coroutines` doesn't grant blanket search
access. Users who want one-click frictionless search can still go to
the rule editor and switch the pattern to `*`.

**Files touched.**
- `core/src/commonMain/.../platform/SearchEngine.kt` (new)
- `core/src/commonMain/.../provider/tavily/TavilySearchEngine.kt` (new)
- `core/src/commonMain/.../tool/builtin/web/WebSearchTool.kt` (new)
- `core/src/commonMain/.../permission/DefaultRules.kt` (added rule)
- `core/src/commonMain/.../agent/TaleviaSystemPrompt.kt`
  (new "# Web search" section)
- `apps/cli/.../CliContainer.kt`,
  `apps/desktop/.../AppContainer.kt`,
  `apps/server/.../ServerContainer.kt` (gated wiring on
  `TAVILY_API_KEY`)
- `core/src/commonTest/.../tool/builtin/web/WebSearchToolTest.kt`
  (tool-layer tests against a fake SearchEngine)
- `core/src/commonTest/.../provider/tavily/TavilySearchEngineTest.kt`
  (wire-format tests against ktor MockEngine)

---

## 2026-04-20 — `MultiEditTool` — sequential atomic edits on a single file (OpenCode parity)

**Context.** The agent often needs several edits in the same file in
one logical step — rename a symbol, then add a new declaration, then
update the export list — and today that's three round-trips through
`edit_file`. Each round-trip pays a turn of LLM latency and risks
the file landing in a half-edited state if the chain is interrupted
between calls. Both Claude Code's MultiEdit and OpenCode's
`tool/multiedit.ts` solve this with one call that takes a list of
edit ops.

**Decision.**
- New `MultiEditTool` (`id="multi_edit"`) in
  `core/tool/builtin/fs/`, commonMain. Takes the same `FileSystem`
  injection as `EditTool`. Input
  `{path, edits: [{oldString, newString, replaceAll?}]}`; output
  `{path, totalReplacements, bytesWritten, perEdit}`.
- **Sequential semantics**: edit N runs against the running result of
  edit N-1, exactly mirroring OpenCode and Claude Code. The agent can
  plan a chain like "rename `Foo` → `Bar`, then insert a new method
  below the renamed declaration" without two round-trips.
- **Atomic**: read the file once, validate + apply every edit in
  memory, write once at the end. If any edit fails (oldString missing,
  or matches multiple times without `replaceAll=true`), the whole
  call fails and the disk file is left untouched. The "oldString
  missing" error message says "after applying $idx prior edit(s)" so
  the agent knows whether it was its own earlier edit that consumed
  the text it was looking for.
- **Permission reuses `fs.write` with the `path` field** — same
  spec as `EditTool` and `WriteFileTool`. An "Always allow fs.write
  on /tmp/foo" decision covers all three tools, not just one.
- **Per-edit replacement counts** flow back in `Output.perEdit` so
  the agent can audit what each step actually did (e.g. a careless
  `replaceAll=true` that hit far more sites than intended).
- Wired into CLI / desktop / server; mobile platforms stay untouched
  (same posture as the rest of the fs family — mobile holds at "no
  regression" per the platform-priority rules).
- **System prompt updated** to steer toward `multi_edit` over a
  chain of `edit_file` calls when several changes target the same
  file.

**Alternatives considered.**
- **Per-edit error recovery / partial apply.** Rejected — the value
  proposition is atomicity. If edit 3 of 5 fails, "I left the first
  two applied and bailed" is worse than "nothing changed", because
  the agent has to read the file again, work out what happened,
  and decide what to do. Atomic = the agent can re-plan from a
  known state.
- **Allow heterogeneous targets (different `path` per edit).**
  Rejected — that's a different tool ("apply N edits across N
  files"). Per-file atomicity stops being meaningful and the
  permission scope becomes vague (one path field can't represent
  the whole batch). Keep this tool single-file like OpenCode and
  Claude Code do.
- **Generate a unified diff instead of edit ops.** Rejected for
  v1 — apply-patch semantics are noticeably harder to get right
  (context lines, line-number drift, fuzzy matching) and the
  benefit over sequential edits is marginal for the in-flight
  workload. Revisit if we see the agent reaching for a true patch
  format.

**Follow-ups.**
- If a session ever fails an entire `multi_edit` because edit N
  conflicts with the running result of edit N-1, that's signal
  to add a "preview" mode (return what the edits would do without
  writing). Hold off until we see it in real traces.

---

## 2026-04-20 — `WebFetchTool` — fetch an HTTP(S) URL as text (OpenCode parity)

**Context.** The agent's knowledge is frozen — when a user says "read
this blog post / gist / README" and paste a URL, our only option today
was "sorry, can't reach the web". Claude Code's `WebFetch` and
OpenCode's `tool/webfetch.ts` both solve this with one GET + best-effort
text extraction + ASK permission keyed on host.

**Decision.**
- New `WebFetchTool` (`id="web_fetch"`) in `core/tool/builtin/web/`,
  commonMain. Takes an injected `io.ktor.client.HttpClient` — reuses
  the one every container already wires for providers, so there's no
  new dependency surface. Input `{url, maxBytes?}`; output
  `{url, status, contentType, content, bytes, truncated}`.
- **HTML → plain-text stripper, not a DOM parser.** Regex-based: drop
  `<script>` / `<style>` / `<noscript>` blocks, strip comments, convert
  block-ending tags (`<br>`, `</p>`, `</div>`, `</li>`, `</h1..6>`,
  `</tr>`) to newlines, remove remaining tags, decode the six entities
  users actually write (`&amp; &lt; &gt; &quot; &nbsp; &#39;`),
  collapse whitespace runs. We don't run JS and don't render the page
  — the goal is "give the LLM readable signal", not fidelity.
- **Text-ish content-types only.** `text/*`, `application/json`,
  `application/xml`, `+json` / `+xml` subtypes. Binary responses fail
  with a clear message pointing at `import_media`. This is deliberate:
  the LLM has no business slurping a 10 MB PNG as a tool result.
- **Permission pattern = URL host.** `https://github.com/anthropic/foo`
  buckets under `github.com`, so one "Always" rule covers every path
  on that host. Full-URL patterns would never match twice; path-based
  patterns would combinatorially explode. Host-level is the right
  granularity for "I trust this domain".
- **Default 1 MB response cap, 5 MB hard cap.** 1 MB covers blog
  posts / READMEs / gists comfortably; refusing to slurp 10 MB SPAs
  is a feature. The agent can pass `maxBytes` explicitly up to the
  hard cap when it knows the target is big prose.
- **Non-2xx = error.** The agent should retry / reconsider on 404 /
  500, not silently feed the LLM an error body as if it were content.
  The error message includes status + the first 500 chars of the body
  so the agent can see what happened.
- Wired into CLI / desktop / server; iOS / Android deliberately skipped
  (same posture as `FileSystem` — mobile platforms stay at "no
  regression" per the platform-priority rules; web fetch on mobile
  can be added when mobile moves off the freeze).

**Alternatives considered.**
- **Full DOM parser (jsoup).** Rejected — jsoup is JVM-only, we'd have
  to either pin the tool to JVM (inconsistent with the rest of the fs
  / bash family's commonMain layout) or add a KMP HTML parser dep.
  The regex stripper is 30 lines and covers the "readable signal"
  requirement. Revisit when we see the LLM choking on malformed HTML.
- **Follow redirects? Respect robots.txt? Handle cookies?** Ktor's
  default `HttpClient` follows 3xx redirects out of the box — good.
  No cookies (no session semantics in a tool call). No robots.txt —
  the user explicitly asked us to fetch this URL; robots is a
  crawler-etiquette concern, not a single-shot-at-user-request one.
- **Cache responses.** Rejected for v1 — the agent decides when to
  re-fetch, and caching raises freshness questions (how long? keyed
  on what? expiry semantics?). Revisit if we see the same URL
  fetched many times in one session.
- **Expose a `prompt` parameter (Claude Code style).** Claude Code's
  `WebFetch` accepts a focusing prompt and summarizes the page with
  a second LLM call before handing text back. Rejected for v1 —
  that's a separate concern (per-tool summarization) that would
  apply to `bash`, `grep`, large `read_file` too. Do it once as a
  general post-processor, not bolted onto this one tool.

**Follow-ups.**
- Darwin / Android engine support is already in ktor; enabling
  WebFetch on mobile is a matter of lifting the freeze. Out of scope
  for v1.
- If the agent starts frequently fetching the same URLs in one
  session, add a per-session response cache keyed on
  `(url, maxBytes)`.

---

## 2026-04-19 — `BashTool` — shell escape-hatch for agent tool calls (OpenCode parity)

**Context.** The typed tool catalogue covers the well-trodden paths
(read/write/edit/glob/grep for files, import/export for media, AIGC
dispatchers) but there's a long tail of "I need to call `git status` /
`ffprobe` / `ls -la` right now" that we'd otherwise have to mint a
dedicated tool for each time. Both Claude Code's Bash and OpenCode's
`tool/bash.ts` solve this with a single escape hatch that shells out —
gated behind ASK permission.

**Decision.**
- New `ProcessRunner` interface in `core/platform/` (commonMain) with
  `JvmProcessRunner` in jvmMain. Interface: `run(command, workingDir?,
  timeoutMillis, maxOutputBytes) -> ProcessResult{exitCode, stdout,
  stderr, timedOut, truncated, durationMillis}`. commonMain-level so
  `BashTool` can sit alongside the fs tools; only JVM implements it.
  iOS / Android don't register `bash` (same posture as `FileSystem` —
  no shell in reach of the agent on mobile).
- JVM impl uses `ProcessBuilder("sh", "-c", command)` and drains stdout
  / stderr on dedicated daemon threads to avoid pipe-buffer deadlock
  while `waitFor`ing. Per-stream `BoundedSink` caps capture at
  `maxOutputBytes` (128 KB default) — overflow sets `truncated=true`
  instead of blowing the tool-result payload. Timeout kills the
  process tree via `destroyForcibly()`; timed-out processes return
  `exitCode=-1, timedOut=true`.
- `BashTool` (`id="bash"`) in `core/tool/builtin/shell/`. Input is just
  `{command, workingDir?, timeoutMillis?}`. Non-zero exit is returned
  as data, not thrown — the agent should be able to read stderr and
  adjust (how a shell user would). We only throw on impossible-to-start
  errors (blank command, invalid workingDir).
- **Permission pattern = first command token**, not full command.
  `git status`, `git diff`, `git log` all bucket under `git`, so
  "Always allow bash `git`" is a useful rule. Using the full command
  would make every new argv combination re-prompt forever. Fallback
  to `"*"` on any parse failure so the dispatcher stays safe.
- Default rule: `bash.exec / * / ASK`. Arbitrary shell access is the
  single biggest blast-radius capability the agent has — never
  default-allow. Server containers via `ServerPermissionService`
  auto-reject ASK so headless deployments start deny-by-default;
  operators add ALLOW rules per command as needed.
- Hard timeout ceiling = 10 minutes. Anything longer belongs in a
  dedicated tool (export / AIGC) that can report progress properly,
  not in `bash`.

**Alternatives considered.**
- **Skip `bash` entirely; mint a dedicated tool per need.** Rejected —
  every new command needs a tool-file + registration + test, which we'd
  inevitably stop doing, and the agent would end up generating
  `import_media` calls where `ffprobe` would have been the right answer.
  The escape hatch exists so typed tools can stay focused.
- **Full-command permission pattern.** Rejected per above — every new
  argv combination re-prompts.
- **Stream output to the LLM in real time (like a human tailing a log).**
  Rejected — `BusEvent.PartUpdated` + incremental tool output is a
  feature we don't have for any tool yet; adding it here first would
  diverge. The 30s default timeout plus synchronous capture is fine
  for the "short one-shot command" use case.
- **Expose stdin.** Rejected — interactive commands (`git rebase -i`,
  `ssh`) make the agent's ASK prompt lie about what's going to happen.
  If the agent needs interactivity, a human-run terminal is the
  correct tool.

**Follow-ups.**
- Streaming tool output (for `bash` and long-running `export`s) is a
  broader upgrade. Not needed for v1; revisit when users report
  long-command frustration.
- Consider per-user allowlist of common commands to pre-populate as
  ALLOW rules (`git`, `ls`, `pwd`, `echo`) so the CLI doesn't prompt
  on obviously-safe invocations. Holding off until we see the real
  usage pattern.

---

## 2026-04-19 — `EditTool` — find/replace on a single file (OpenCode parity)

**Context.** `write_file` is the only way the agent can mutate an external
file today. For a one-line tweak to a subtitle / script / prompt file, that
means re-emitting the entire file through the LLM — expensive in tokens and
easy to fumble (whitespace drift, stale content near the edit site). Claude
Code's `Edit` and OpenCode's `tool/edit.ts` both solve this by making the
agent send only the matching substring and its replacement.

**Decision.**
- New `EditTool` (`id="edit_file"`) in `core/tool/builtin/fs/`. Reads the
  file via `fs.readText`, applies a literal-string find/replace, writes via
  `fs.writeText`. No new `FileSystem` method — the abstraction stays lean;
  edit is composition over the existing read/write primitives.
- **Uniqueness enforced by default.** If `oldString` matches zero times,
  fail with "not found — read the file first". If it matches >1 times and
  `replaceAll=false`, fail with "matches N times — pass replaceAll=true or
  widen oldString". This is the semantics Claude Code's Edit uses; silent
  first-match-wins would make multi-site refactors look like they worked
  when they didn't.
- **No "must have read the file this turn" rule.** Claude Code enforces
  that you can't edit a file you haven't Read in the current turn. We
  don't have turn-level tracking for per-file read state wired into
  `ToolContext`, and the uniqueness-enforcement already catches most
  "agent hallucinated the content" failures at the `oldString not found`
  check. Revisit if we see the agent blindly editing files it never
  fetched.
- Permission reuses `fs.write` with `patternFrom = path field` — an
  "Always allow fs.write on ~/Documents/scripts" decision covers both
  `write_file` and `edit_file`, which is the user's mental model.
- Wired into CLI / desktop / server containers (iOS / Android skip
  `FileSystem` entirely — same posture as `ReadFile` / `WriteFile`).
- System prompt's "# External files" section adds the tool + one line of
  guidance: "Prefer `edit_file` over `write_file` for local changes — it
  sends only the substring to replace, not the whole file".

**Alternatives considered.**
- **Line-range diff (`oldLines: 10..12`).** Rejected — brittle, breaks if
  the agent miscounts by one, and offers nothing over substring matching
  for the actual use cases (tweaking a prompt, fixing a typo in a
  subtitle).
- **Patch format (`diff --git` / `edit_file: @@ -3,5 +3,5 @@`).** Rejected
  — heavier for the LLM to generate, and the uniqueness check is simpler
  for the agent to reason about than a line-aware hunk.
- **`replaceAll=false` silently replaces the first match.** Rejected —
  silent ambiguity is how refactors get half-applied. An explicit failure
  mode teaches the agent to either widen `oldString` or opt into
  `replaceAll`.

**Follow-ups.**
- Consider a per-turn "files read this turn" hint in `ToolContext` if we
  start seeing the agent edit files it never fetched. Out of scope for v1.
- Desktop / server UIs currently render each `edit_file` call as its
  rendered `outputForLlm`; a proper inline diff renderer would be nicer.

---

## 2026-04-19 — CLI `/todos` slash command — on-demand scratchpad view

**Context.** The `TodoWriteTool` lands a `Part.Todos` on every plan update; the
EventRouter already surfaces each write via the tool's rendered `outputForLlm`
(streamed alongside other `Part.Tool` completions). But the user only sees the
list at the moment it's written — once scrolled past, there's no way to
re-inspect "what does the agent think the plan still is?" without scrolling
the buffer or starting a fresh turn.

**Decision.**
- New `/todos` slash command in `apps/cli/repl/SlashCommands.kt` that reads
  `SessionStore.currentTodos(sessionId)` (the helper that finds the latest
  `Part.Todos`) and renders it with the same `[ ] / [~] / [x] / [-]` markers
  the tool's LLM output uses. `in_progress` entries get accent colour,
  `completed / cancelled` entries get the dim `meta` colour so the active
  work stands out at a glance.
- **No live-streaming `/todos` chip.** Considered painting a persistent
  one-line status at the top of the terminal each time the plan changes
  (vim-status-line style). Rejected because (a) JLine's REPL model doesn't
  make that kind of sticky chrome cheap, (b) the tool output already appears
  inline in the transcript on every update so the discoverable signal is
  already there, and (c) scrollback noise from auto-repainting on every
  todo flip would drown out actual work. On-demand `/todos` is enough.
- Command catalogue stays alphabetised by category (session ops, model,
  session state summaries, utility). `/todos` slots next to `/cost` —
  both are session-state summaries.

**Alternatives considered.**
- **Live chip at top of screen.** Rejected per above.
- **Auto-dump todos at end of each turn.** Rejected — Claude Code's TodoWrite
  reminder system injects a reminder when the list is stale, which is a more
  targeted fix than blanket auto-dumping. We can add a similar hook later;
  it's out of scope for the initial CLI surface.
- **Dedicated ANSI pane via JLine's `AttributedString` / `Terminal.puts`.**
  Rejected — overkill for v1. Inline text matches the rest of the REPL's
  output style.

**Follow-ups.**
- Similar viewer in the desktop / server UIs (they render `Part.Todos` today
  as an unknown Part — those apps need explicit checklist panels to close
  the loop).

---

## 2026-04-19 — `GrepTool` — regex search across external files (OpenCode parity)

**Context.** `read_file` / `write_file` / `list_directory` / `glob` cover
*discovery by name* and *read one / write one*, but there was no tool for
"find which file mentions this phrase". On multi-step edits the agent had
to either (a) read every candidate file individually (N LLM turns, N
permission prompts), or (b) ask the user to grep themselves. Both are
regressions vs OpenCode's `tool/grep.ts`, which is one of the most-called
tools on long sessions.

**Decision.**
- New `grep` method on the `FileSystem` commonMain interface with matching
  JVM impl in `JvmFileSystem`. Walks a root path (directory recursively, or
  a single regular file), reads each candidate with `Files.readAllLines`
  under UTF-8 strict decode, applies a `kotlin.text.Regex` per line, and
  returns `{path, line(1-based), content}` rows. Optional `include` is a
  glob (on the file's absolute path) for extension scoping; optional
  `caseInsensitive=true` flips the regex option.
- **Silent-skip policy.** Binary / non-UTF-8 files, files over the size
  cap (default 10 MB), and files that fail OS-level I/O are silently
  dropped from the walk. A grep that errors because one file in a
  directory is a `.mp4` would be useless in practice. The caller still
  sees `filesScanned` so "0 matches across 47 files" vs "0 matches
  across 0 files" are distinguishable.
- **JDK regex, NOT ripgrep.** OpenCode shells out to ripgrep. We stay on
  `kotlin.text.Regex` so the Core doesn't gain an external binary
  dependency (same reasoning as keeping `FfmpegVideoEngine` under
  `platform-impls/` rather than `core/`). Slower on huge trees, not a
  concern at the sizes the agent actually greps.
- **Permission reuses `fs.read`.** Same disclosure class as `ReadFileTool`
  — both surface file contents to the LLM. Pattern is `path`, so an
  "Always allow fs.read on ~/Documents" decision naturally scopes both
  read_file and grep under that tree. Modelled after `GlobTool`'s reuse
  of `fs.list` for the same reason.
- **Caps.** `DEFAULT_MAX_GREP_MATCHES = 200` (≈ 40 KB payload at 200 B per
  match line), `DEFAULT_GREP_LINE_CAP = 512` (first 512 chars of a
  matching line, then elided). Together they bound the tool-result
  payload so one hit on a minified bundle can't blow the agent's context.
- Wired into CLI + desktop + server containers (the three that already
  register `JvmFileSystem`). iOS + Android stay unregistered because
  `FileSystem` is unimplemented on those platforms — same posture as the
  other four fs tools.

**Alternatives considered.**
- **Shell out to ripgrep.** Rejected: forces every deployment to install rg,
  adds a platform binary to the Core. Kotlin regex is good enough at our
  scale.
- **New `fs.grep` permission.** Rejected in favour of reusing `fs.read`.
  Separate permissions would ask twice for "let me grep a file I already
  approved reading" and fragment the "Always allow on this directory" UX.
- **Return an iterator / stream.** Rejected — tool-result payloads are
  one-shot. Capping at 200 matches and surfacing `truncated=true` gives
  the agent the hint to narrow the pattern or `include` glob.
- **Error on binary files.** Rejected — the agent's search intent is
  "content under this tree", not "every file must be parseable". Silent
  skip matches ripgrep's default and what users actually expect.

**Follow-ups.**
- If sessions grow to grepping large monorepos, we can add an optional
  ripgrep fast-path (detect on PATH, fall back to the JDK impl) — the
  tool contract stays the same.

---

## 2026-04-19 — `TodoWriteTool` — agent scratchpad for multi-step work (OpenCode parity)

**Context.** Gap-finding pass against OpenCode's Core surface (`docs/VISION.md`
§5 rubric + CLAUDE.md "OpenCode as runnable spec") flagged the `todo` /
`todowrite` tool pair (`packages/opencode/src/tool/todo.ts`,
`packages/opencode/src/session/todo.ts`) as the most load-bearing Core gap
before moving on to the CLI. The Talevia agent previously had no first-class
way to expose a multi-step plan — it could narrate "I'll do A, then B" in prose
but nothing was machine-readable by the CLI / desktop / server UIs, and the
agent itself had no scratchpad signal to drive the "exactly one task
in_progress at a time" discipline. This shows up in practice as the agent
skipping steps on complex requests ("draft a vlog → re-color shot 2 → retitle
→ export") because there was no persistent artifact forcing it to maintain a
plan across turns.

**Decision.**
- New `TodoWriteTool` under `core/tool/builtin/` with typed
  `Input(todos: List<TodoInfo>)` / `Output(count, todos)`. Each call fully
  replaces the current plan. Renders to the LLM with OpenCode-style markers —
  `[ ]` pending, `[~]` in_progress, `[x]` completed, `[-]` cancelled. System
  prompt gains a short "# Agent planning (todos)" section that mirrors Claude
  Code's TodoWrite guidance (use when 3+ steps, one in_progress at a time,
  flip completed immediately, prefer cancelled over silent drop).
- **Ride the existing Parts JSON-blob schema, do NOT mint a new Todos SQL
  table.** New sealed variant `Part.Todos(todos: List<TodoInfo>)` with
  `TodoInfo / TodoStatus / TodoPriority`. The latest `Part.Todos` in a session
  is the current plan; a helper `SessionStore.currentTodos(sessionId)` encodes
  that lookup. OpenCode ships a separate `todo` table (`session/todo.ts`) that
  stores rows by `(sessionId, id)`; we rejected the separate table because the
  Parts table already gives us session-scoped ordering, JSON-blob evolution,
  bus events (`PartUpdated` fires for free so UIs see todo changes with zero
  extra wiring), compaction integration (TokenEstimator accounts for them),
  and fork/rebind semantics. The trade-off is that a todo update is modelled as
  "append a new Part.Todos, readers take the latest" rather than "update one
  row" — fine because plans are small (typically 3–10 entries) and the write
  volume is bounded by agent turns.
- **Permission `todowrite` defaults to ALLOW.** It's purely local state with
  zero side effects (no network, no disk, no timeline mutation) and prompting
  on every plan update would make the tool useless for its intended purpose.
  Rule added to `DefaultPermissionRuleset` alongside the other trivial
  always-allows (`echo`, source reads/writes).
- **Todos are NOT replayed to the LLM via history.** Provider `listMessages` →
  LLM mapping already uses an `else -> null` pattern on unknown Parts, so the
  new `Part.Todos` falls through silently. The tool's `outputForLlm` already
  communicates the current plan to the LLM on the turn it was written, and
  serialising every past plan on every turn would balloon context for no
  signal. UIs read the latest `Part.Todos` directly.
- Wired into all five AppContainers (CLI, desktop, server, Android, iOS). iOS
  passes `clock` explicitly because SKIE doesn't surface Kotlin default
  arguments to Swift.

**Alternatives considered.**
- **Separate `Todos` SQL table, OpenCode-style.** Rejected per above — more
  schema surface for a feature already well-modeled as a Part stream.
- **Reuse an existing Part kind (e.g. Reasoning).** Rejected — todos have a
  structured shape (status/priority), are mutable as a set, and have distinct
  UI treatment (checklist). Shoehorning them into `Reasoning` would force
  string-parsing on the UI side and leak into the LLM replay lane.
- **Keep the plan in assistant message prose only, no tool.** Rejected — the
  whole point is a machine-readable artifact the model is forced to maintain,
  with a side-effect-free tool call as the write channel. OpenCode's own
  internal traces show the todo tool is the single most-called tool on
  multi-step tasks; we want the same forcing function.
- **Expose the plan as a slash command only (no tool).** Rejected — slash
  commands are user-initiated; the plan update needs to originate from the
  agent autonomously, mid-turn.

**Migration.** Zero — new variant is additive and uses existing Parts storage.
SQLDelight `Parts` table's `kind` column gains `"todos"`; exhaustive `when`
paths in `SqlDelightSessionStore.decodePart` / `rebindPart` / `kindOf` and
`TokenEstimator.forPart` updated. Existing rows continue to decode.

**Follow-ups.**
- CLI `/todos` slash command (Task 3) so `repl` users can inspect the current
  plan without scrolling the scrollback.
- Desktop / server UI pass to render `Part.Todos` as a checklist panel;
  currently they'll render as "unknown Part" until those apps grow explicit
  handlers.

---

## 2026-04-19 — `ReplicateUpscaleEngine` — first concrete upscale provider

**Context.** `upscale_asset` tool + `UpscaleEngine` contract shipped but
had no bundled provider. CLAUDE.md known-incomplete flagged it as the
next Replicate engine to add; the music engine had established the
polling pattern.

**Decision.**
- New `ReplicateUpscaleEngine` under `core/src/jvmMain/kotlin/...` (NOT
  commonMain). Same async-poll shape as `ReplicateMusicGenEngine`:
  `POST /v1/models/{slug}/predictions` → poll `urls.get` every 2 s →
  download the output URL. Default model `nightmareai/real-esrgan`.
- **Placement: `jvmMain`, not `commonMain`.** The engine has to read the
  source image file to attach it to the request, and `commonMain`
  cannot touch `java.io.File`. `OpenAiWhisperEngine` /
  `OpenAiVisionEngine` are the precedent — both need filesystem access
  and both live under `jvmMain`. Desktop + server pull the same artifact
  via the shared `ktor-client-cio` configuration.
- **Upload: base64 `data:` URI.** Replicate model inputs accept either a
  public URL or a `data:` URI for binary media. Using a data URI keeps
  the engine stateless (no bucket config), works for any format the
  downstream model accepts, and matches the existing pattern described
  in Replicate's docs. Payload balloons with image size — acceptable for
  stills up to a few MB; a pre-signed upload path is the escape hatch
  for 4K+ inputs (docs note, deferred).
- **Provenance omits the image data URI.** Logging the entire base64
  payload into `LockfileEntry.provenance.parameters` would double the
  per-entry storage cost and add zero replay value (the image is
  identifiable via the `sourceAssetId` dependency graph). Records only
  scale / seed / format / slug + passthrough parameters.
- **Width / height returned as (0, 0).** SR providers don't reliably
  echo output dimensions back; `UpscaleAssetTool` already relies on
  `storage.import` probing the persisted bytes — the (0, 0) on
  `UpscaledImage` is a deliberate "the engine doesn't know; ask the
  downstream probe" signal.
- Wired into desktop + server containers behind `REPLICATE_API_TOKEN`.
  `REPLICATE_UPSCALE_MODEL` overrides the default slug (SUPIR,
  CodeFormer, etc.). CLAUDE.md + system prompt updated.
- MockEngine-backed tests cover happy-path submit/poll/download, array
  output shape, failed status, and empty-source fail-loud.

**Alternatives considered.**
- **Make the engine commonMain-friendly with a pluggable `readImage`
  lambda.** Rejected — adds complexity (every caller has to pass a
  working reader) for a capability iOS/Android don't need right now.
  Following the Whisper/Vision precedent keeps the codebase consistent.
- **Use pre-signed upload URLs.** Considered for 4K+ support; rejected
  for v1 because it requires bucket config (S3 / GCS / similar) and a
  separate multipart upload step. Data-URI works for the common case;
  we can layer a `UploadStrategy` abstraction later if the file-size
  ceiling becomes a real workflow block.
- **Use the `/v1/predictions` endpoint with an explicit version hash.**
  Rejected for the same reason as `ReplicateMusicGenEngine` — forces a
  version pin that ages poorly; the model-scoped endpoint always uses
  Replicate's published latest.

**Why.** Closes the second Replicate lane. VISION §2 ML-enhancement
pillar ("超分") is now demonstrably usable on Mac desktop + server with
a single env var. Establishes the Replicate-engine pattern for future
enhancements (denoise, inpaint, style-transfer): sibling file under
`jvmMain/.../provider/replicate/`, same constructor shape, same
container gate.

**How to apply.** For the next Replicate-backed enhancement, clone
this file, swap the default `modelSlug`, and confirm the `output`
shape — most Replicate models return either a string URL or a
one-element list, which the shared `extractImageUrl` / `extractAudioUrl`
shape already handles. Do not echo bulk input bytes into provenance.

---

## 2026-04-19 — Timeline: per-clip regenerate button + `clipIds` filter

**Context.** Round 2 shipped the bulk "Regenerate N" button on
LockfilePanel, but a user looking at a stale badge in TimelinePanel still
had to tab-switch to Lockfile to regenerate. The common "oh wait, just
this one" case forced a context switch and re-selected every stale clip.

**Decision.**
- Extend `RegenerateStaleClipsTool.Input` with
  `clipIds: List<String> = emptyList()`. Empty (default) keeps the bulk
  behaviour; non-empty filters the stale-reports list to the requested
  ids. Ids that aren't currently stale are dropped silently (fresh
  clips don't need regeneration — a noisy error would be a footgun).
- Add `onRegenerate: () -> Unit` callback to `ClipRow`, rendered only
  when the clip is stale, next to the existing "Remove" button. Wired in
  TimelinePanel to dispatch `regenerate_stale_clips` with
  `clipIds=[clip.id.value]`.
- Unit coverage: a new test stages two stale clips, filters to one,
  and asserts that only the filtered one was regenerated (engine called
  once, unlisted clip retains its original assetId).

**Alternatives considered.**
- **Call `replace_clip` directly per row.** Rejected — the whole point
  of `regenerate_stale_clips` is that it already knows how to look up
  the originating tool + re-dispatch with `baseInputs`. Duplicating
  that path in a new tool would fork the regeneration logic into two
  places.
- **New `regenerate_clip` tool with a different name.** Rejected — the
  semantics are "the same tool, narrower scope". Same permission, same
  output shape, same error modes. A flag on the existing tool keeps the
  surface area coherent and lets callers upgrade from per-clip to bulk
  by dropping the field.
- **Select-multiple then batch regen.** Rejected for this round —
  multi-select UI is a bigger lift and per-row + bulk-header cover the
  two interesting cases. Mid-scope multi-select can land later if the
  need appears.

**Why.** Mac-first priority: the stale badge now offers an inline action
at the point of observation. The §3.2 loop is reachable from both the
Timeline row and the Lockfile panel; users don't context-switch to fix
one clip.

**How to apply.** When extending other tools with per-item variants,
reuse this pattern: optional list-of-ids filter on the Input, empty
means "all", non-empty means "these if they match the eligible set".
Don't mint a parallel tool name.

---

## 2026-04-19 — `create_project_from_template` — genre scaffolding

**Context.** `create_project` bootstraps an empty project: no source
nodes, no character refs, no style bible. Before the first AIGC call the
agent (or user) has to run multiple `define_*` tools to reach a usable
state, which is a lot of friction for the novice path ("make me a short"
should not require memorising six tool names). VISION §5.4 novice-path
requires the system to "infer a reasonable source structure, pick sensible
defaults, produce a first draft" — no tool did this.

**Decision.**
- New `CreateProjectFromTemplateTool` under `tool/builtin/project/`.
  Input: `{title, template, projectId?, resolutionPreset?, fps?}`. Output
  echoes `seededNodeIds`.
- Two templates:
  - `narrative` → 6 nodes: `protagonist` (character_ref),
    `style` (style_bible), `world-1` (parent: style), `story-1` (parent:
    world), `scene-1` (parents: story + protagonist), `shot-1` (parent:
    scene). Every edit cascades correctly through the DAG from day zero.
  - `vlog` → 4 nodes: `style`, `footage`, `intent`, `style-preset`.
- All body fields are `"TODO: …"` placeholders — the template is
  scaffolding, not opinion. Users replace placeholders via
  `update_character_ref` / `update_style_bible` / `import_source_node`
  before the first AIGC call.
- Reuses existing genre ext helpers + consistency builders; the tool
  only composes them atomically. No genre logic duplicated.
- Wired in desktop + server containers right after `CreateProjectTool`.

**Alternatives considered.**
- **Opinionated placeholders** ("graduation day", "sunset walk"). Rejected
  — biases creative direction and encourages the user to accept defaults
  that aren't theirs.
- **Expose templates as JSON files** the user can edit. Rejected for v1
  as scope creep — two in-code templates is enough to validate the
  pattern; filesystem templates can come when a third genre shows up.
- **Merge into `CreateProjectTool` via an optional `template` field.**
  Rejected — the two tools have different required-inputs (template
  required on one, not the other) and different outputs (seededNodeIds
  is nonsense for the empty variant). A flag would muddle both.
- **Auto-trigger on `create_project` when title matches a genre hint**
  ("narrative short"). Rejected — heuristic magic that fails confusingly
  when the title happens to contain the word "vlog".

**Why.** Novice path friction drops from "six tool calls to usable state"
to "one call." Experts can still use `create_project` + manual
definitions when they want precise control. The macOS-desktop-first
priority applies here: the chat pane now handles "start a new narrative
project" in a single agent turn instead of three.

**How to apply.** When a third genre lands (MV, tutorial, ad), add a new
branch under the `when (template)` switch and a sibling `seedX()` method.
Keep placeholder copy as literal `"TODO: …"` so downstream `find_stale_clips`
/ `list_clips_for_source` / search workflows can surface half-configured
projects.

---

## 2026-04-19 — `ReplicateMusicGenEngine` — first concrete music provider

**Context.** `MusicGenEngine` + `generate_music` shipped as a contract-only
lane; no bundled provider meant the tool stayed unregistered on every
deployed container and "generate music" was a documentation feature. No
mainstream direct public API (Suno / Udio don't expose one), so Replicate-
hosted MusicGen was the natural first target.

**Decision.**
- New `core/provider/replicate/ReplicateMusicGenEngine.kt` implementing
  `MusicGenEngine`. Async-poll shape parallel to `OpenAiSoraVideoGenEngine`:
  `POST /v1/models/{slug}/predictions` with `{input: {prompt, duration, seed,
  output_format}}`, poll the returned `urls.get` every 3 s until status ∈
  `{succeeded, failed, canceled}`, download the first URL in `output`.
- **One model slug per engine instance** (default `meta/musicgen`,
  overridable via constructor). Arbitrary-slug routing was rejected because
  the engine owns the translation to `MusicGenResult` + provenance and a
  slug-unaware engine couldn't verify the response shape. Callers wanting a
  different Replicate model instantiate a second engine.
- **Duration: `ceil(durationSeconds).toInt()`.** MusicGen accepts integer
  seconds; rounding up means a 15.5 s request doesn't silently truncate.
- **`output` shape normaliser.** Replicate returns either a string URL, a
  one-element list, or `{audio: "..."}` depending on model version. The
  engine handles all three defensively; anything else → loud fail.
- **Seed passed on the wire unconditionally.** Some MusicGen versions
  honour it, others ignore it; passing it unconditionally means a
  seed-aware version upgrade needs no engine change, and the lockfile hash
  is meaningful either way.
- Wired into desktop + server containers behind `REPLICATE_API_TOKEN`.
  `REPLICATE_MUSICGEN_MODEL` env var overrides the default slug for teams
  with fine-tuned variants.
- MockEngine-backed unit tests cover: happy-path submit → poll (starting →
  processing → succeeded) → download, array-shaped `output`,
  `status=failed` error path, and duration-ceil behaviour.

**Alternatives considered.**
- **Hardcode a specific model version hash.** Rejected — Replicate
  versions rotate, pinning would age badly. The model-scoped endpoint
  always uses the published latest version; provenance records the
  version Replicate echoes on the final payload.
- **Auto-detect `modelSlug` from the tool's `modelId` input.** Rejected
  — tool-layer `modelId` is a free-form string the tool hashes for cache
  coherence; using it as a Replicate slug would turn typos into silent
  "404 model not found" and inflate the cache namespace.
- **Replace seed with `classifier_free_guidance` / `temperature`.**
  Rejected — those are sampler knobs, not determinism controls. The
  VISION §3.1 seed discipline is about reproducibility intent; passing
  a seed regardless of whether the model reads it preserves that
  intent. Sampler-knob tuning belongs in `request.parameters`.
- **Use `/v1/predictions` with an explicit version hash.** Considered;
  rejected because the version-scoped endpoint needs a 64-char hash the
  caller must keep in sync with Replicate's release cadence. The
  model-scoped endpoint trades one env override for one deploy-time
  concern.

**Why.** Closes the "tool ships, nothing to call" gap for music. `generate_music`
now turns into a functional lane any deployment with a Replicate token
can use, and the shape established here is the template for the next
Replicate-backed engine (`ReplicateUpscaleEngine` for Real-ESRGAN, same
poll shape, different model slug + output field).

**How to apply.** Future Replicate engines should live under
`core/provider/replicate/`, take the same constructor shape
`(httpClient, apiKey, modelSlug, …, pollIntervalMs, maxWaitMs)`,
normalise the `output` field defensively, and wire into containers
with the same `REPLICATE_API_TOKEN` gate pattern. Do not couple the
Replicate engines to OpenAI's — they share no code path today beyond
the abstract `*Engine` interface.

---

## 2026-04-19 — Desktop SourcePanel: downstream clips + stale count per node

**Context.** `list_clips_for_source` landed, but a Mac user in SourcePanel
had no visible answer to "if I edit this character, what will break?" —
the question the tool now answers. Making the answer agent-only would
duplicate the stale-clips situation pre-LockfilePanel button.

**Decision.**
- Precompute two overlays in `SourcePanel` once per project reload:
  `staleClipIds: Set<ClipId>` from `staleClipsFromLockfile()`, and
  `reportsByNode: Map<String, List<ClipsForSourceReport>>` by calling
  `project.clipsBoundTo(SourceNodeId(node.id.value))` for every node.
  Memoized via `remember(project.timeline, project.source)` so they
  recompute only when the relevant slices change.
- Extend `SourceNodeRow` to show, in the collapsed header, a
  `"N clips · M stale"` chip next to the contentHash (amber when stale > 0,
  grey otherwise). No chip when zero clips bind.
- In the expanded body, render a `downstream clips (N):` list with
  `clipId[:8] on trackId[:6]  [stale]  via <descendant>` — amber per
  stale line; `via …` only when the bind is transitive.
- Reuses `ClipsForSourceReport` directly from `core.domain` — no
  parallel data type. Compose reads the domain type via an extra import.

**Alternatives considered.**
- **Call `ListClipsForSourceTool.dispatch` from the panel per expansion.**
  Rejected — we already hold the `Project` instance; going through the
  registry would marshal JSON for no reason. The tool is still needed
  for the agent; panels can use the domain helper directly (same pattern
  `LockfilePanel` uses for stale detection).
- **Lazy-compute only for the expanded node.** Considered; the
  per-node cost is tiny (a single BFS through a graph that rarely has
  more than dozens of nodes) and upfront computation lets the collapsed
  header also show the chip, which is where users decide whether to
  expand.
- **Add a top-level "click to see impact graph" button.** Rejected — too
  abstract. Inline chips per node answer the question at the point of
  editing.

**Why.** VISION §4 expert path: "用户能不能直接编辑 source 的每个字段…"
Editing blindly is not "editing" — experts want cost visibility before
they touch a field. This is the point-of-use UI surface for the
`list_clips_for_source` signal.

**How to apply.** When new source-related overlays become relevant
(e.g. "which AIGC providers generated clips bound to this node" or
"total estimated regeneration cost"), follow the same pattern: compute
once per project-state change with `remember(…)`, pass into the row
composable, render both a collapsed chip and an expanded detail list.
Don't add a chat round-trip to render read-only data the domain layer
already knows.

---

## 2026-04-19 — `list_clips_for_source` — forward-index of sourceBinding

**Context.** `find_stale_clips` gives the agent the *backward* view of the
DAG (edit happened → what drifted?). There was no *forward* view: "if I'm
about to edit Mei, what will that touch?" Without it, the novice path has
to make edits and then see fallout, and the expert path has to `get_project_state`
+ hand-walk the timeline. VISION §5.1 rubric explicitly asks this question.

**Decision.**
- Add `Project.clipsBoundTo(sourceNodeId)` in `ProjectStaleness.kt` —
  walks `source.stale(setOf(id))` (the existing DAG closure) and returns a
  per-clip report with `{clipId, trackId, assetId, directlyBound, boundVia}`.
- Add `ListClipsForSourceTool` under `tool/builtin/project/` wrapping it.
  Input: `{projectId, sourceNodeId}`. Read-only (`project.read` permission).
  Fails loud when the node id is absent so the agent doesn't silently
  conclude "no bindings" when it actually mistyped.
- Report each clip's `boundVia` — the subset of its `sourceBinding` that
  lay inside the queried node's transitive closure — so the UI / agent
  can show "this scene-1-bound clip reaches you via scene-1" rather than
  just "bound somehow." `directlyBound: true` when the clip lists the
  queried id itself.
- Wired into desktop + server containers next to `FindStaleClipsTool`.
- Tests cover direct + transitive bind, leaf-with-no-clips, missing-node
  failure, track/asset echoes.

**Alternatives considered.**
- **Fold this into `find_stale_clips` via a flag.** Rejected — one tool
  answers "drift happened", the other answers "drift would happen".
  Different questions, different defaults (drift includes hash snapshot
  comparison from the lockfile; forward-preview doesn't need any
  lockfile at all).
- **Return only direct binds.** Rejected — the user edits an ancestor
  when they change Mei's hair; anything reachable downstream through
  `parents` pointers is in scope. Including transitive bindings matches
  what `staleClipsFromLockfile` does on the reverse side.
- **Compute from scratch in the tool.** Rejected — the graph walk already
  exists as `source.stale(...)`; duplicating BFS logic in two places is
  exactly how the forward and reverse views would drift apart. One BFS,
  two consumers.
- **Per-track API rather than per-clip.** Rejected — the caller's next
  action is always per-clip (regenerate one, replace one, inspect one);
  per-track would force another flattening pass.

**Why.** VISION §5.1 rubric rates "改一个 source 节点…下游哪些 clip /
scene / artifact 会被标为 stale?" — this is a first-class question. The
backward answer was already there; shipping the forward answer closes
the symmetric pair and unblocks UI work (desktop SourcePanel can now
show downstream clips under each node inline, next task).

**How to apply.** If later work adds new consumers of "who depends on
this?" (scene-level binding views, brand-palette impact reports), route
them through `Project.clipsBoundTo` rather than adding parallel walks.

---

## 2026-04-19 — Desktop: preview auto-refresh on any successful export

**Context.** The desktop Preview panel only loaded the file path set by the
"Export" button's own `runCatching` block. When an export happened via a
chat turn (agent-initiated), via a future toolbar shortcut, or via any code
path that didn't flip `previewPath` by hand, the preview stayed frozen at
the old file — users saw a successful export in the log but no visual
confirmation that their edit landed, breaking the VISION §5.4 feedback loop
for novice path.

**Decision.**
- In `Main.kt`, extend the existing `BusEvent.PartUpdated` subscription to
  also handle `Part.Tool`. When the part's `toolId == "export"` and
  `state is ToolState.Completed`, parse `state.data` as JSON, pull
  `outputPath` (the single well-known field on `ExportTool.Output`), and
  set `previewPath` to that path.
- Guard with `previewPath != path` so rewriting to the same path doesn't
  bust the JavaFX controller's `remember(file)` keying and force a reload.
- Log one line (`preview → filename.mp4`) when the swap happens so the
  user sees the cause-and-effect in the activity panel.

**Alternatives considered.**
- **Pass `previewPath` as state into every tool dispatcher.** Rejected —
  would require threading writable state through the chat panel, the
  ProjectBar, and every future timeline button that could trigger an
  export. The bus is already the shared channel; using it is the DRY fix.
- **Subscribe to a custom "ExportCompleted" bus event.** Rejected — we
  don't have one today and inventing a new event type per tool would
  multiply event kinds without buying us anything `Part.Tool` (already
  emitted) doesn't. Pattern-matching on `toolId + state` is cheap and
  self-documenting.
- **Poll the most-recent render cache entry on every PartUpdated.**
  Considered; rejected — reads the project from the store on every event
  even when nothing changed, and the render cache doesn't distinguish "this
  tool call" from "some old call" (multiple paths share fingerprint with
  different entries).

**Why.** Mac desktop priority; this is the closing piece of the VISION §5.4
feedback loop for mouse/chat users. An export via chat now visibly updates
the preview in the same window — the "agent produces 可看初稿" promise only
holds if the 初稿 actually appears.

**How to apply.** Other tools that produce user-visible artifacts (future
`preview_clip` / `generate_thumbnail` / anything emitting a media file)
should hook the same subscription — pattern-match on `Part.Tool` with their
`toolId` + `Completed` state, pull the relevant field from `data`. Don't
mutate UI state in the tool-dispatch sites; keep the handler centralised in
`Main.kt`.

---

## 2026-04-19 — Desktop: one-click "regenerate N" button on LockfilePanel

**Context.** `regenerate_stale_clips` tool landed but the Mac desktop panel
that shows stale clips still required the user to type an agent command to
trigger it. The VISION §6 end-to-end loop ("edit character → stale badge →
regenerate → export") was only reachable via chat.

**Decision.**
- Add a single `Regenerate N` TextButton at the "Stale clips" section
  header in `LockfilePanel`. Clicking it dispatches `regenerate_stale_clips`
  with the active `projectId` through `container.tools[...]` using the
  existing `uiToolContext` helper (same path `TimelinePanel` / `SourcePanel`
  use for their inline buttons).
- While the dispatch is in flight the button shows `regenerating…` and is
  disabled to avoid double-fires. On completion the button returns and
  `project` state is re-fetched from the store so the lockfile entry list
  shows the refreshed entries.
- Result text from the tool (count regenerated, skip reasons) is appended
  to the shared `log` list so the user sees what happened without opening
  chat.
- **Single batch button, not per-row buttons.** The tool is batch-only and
  exposing per-row "regenerate just this one" would need a `clipIds` filter
  on the tool that nobody's asked for; YAGNI until a concrete workflow
  demands it.

**Alternatives considered.**
- **Add `clipIds` filter to the tool and render per-row buttons.** Rejected
  for now — no user story demands partial regeneration; we can add it when
  the "I want to skip one of these" case shows up.
- **Auto-trigger when stale count goes non-zero.** Rejected — AIGC
  regeneration costs money; surprising the user with a spontaneous batch
  of provider calls is the opposite of the §4 "agent is your collaborator,
  not your overlord" stance.
- **Put the button on TimelinePanel stale badges.** Considered. Rejected
  because the stale summary already lives on LockfilePanel, and putting the
  action next to the summary keeps the "see → act" loop tight in one panel.

**Why.** Mac desktop is the priority platform; the §3.2 loop works but
required chat typing. One button converts "discoverable for agent users"
to "discoverable for mouse users" — which is what VISION §4's dual-user
path requires (experts click, novices chat; same mutation, different
surface).

**How to apply.** When future compound tools land (e.g. a future
`regenerate_stale_clips_in_scene`), wire a similar panel-header button
next to the relevant summary. Reuse `uiToolContext` + the `log`
SnapshotStateList pattern; don't reinvent dispatch plumbing per panel.

---

## 2026-04-19 — `regenerate_stale_clips` tool — closes the §3.2 refactor loop

**Context.** After the source edit → `find_stale_clips` → regenerate →
`replace_clip` chain had its pieces landing over many commits, the one-call
closure was still missing. The agent could see what's stale but had no way
to "just regenerate" without manually reconstructing each original tool
call — and the original base prompt isn't recoverable from
`provenance.parameters` (which holds the *folded* prompt, not the raw
input), so every agent would have to guess / hand-author this wiring.

**Decision.**
- Add `baseInputs: JsonObject` field to `LockfileEntry` (default empty for
  legacy entries). AIGC tools (`GenerateImageTool`, `GenerateVideoTool`,
  `SynthesizeSpeechTool`, `GenerateMusicTool`, `UpscaleAssetTool`) encode
  their raw `Input` via `Input.serializer()` and pass it through
  `AigcPipeline.record(baseInputs = …)`. Stored alongside the existing
  `sourceContentHashes` snapshot.
- New tool `RegenerateStaleClipsTool` under `tool/builtin/project/`. For
  each entry in `project.staleClipsFromLockfile()`:
    1. look up the lockfile entry by assetId,
    2. resolve the original `ToolRegistry` entry from `entry.toolId`,
    3. `registered.dispatch(entry.baseInputs, ctx)` — consistency folding
       re-runs against today's source graph, producing a fresh generation,
    4. identify the new lockfile entry by "size went up by one", read its
       `assetId`, and swap the clip's assetId + sourceBinding in a single
       `ProjectStore.mutate` (same inline logic as `ReplaceClipTool`),
    5. emit exactly one `TimelineSnapshot` after the batch completes.
- Skip rules (surfaced on `Output.skipped` with human-readable reasons):
  legacy entries with empty `baseInputs`, missing tool registrations,
  cache-hit regenerations (no new lockfile entry), and mid-flight clip
  vanish.
- Permission: `"aigc.generate"` — one grant covers the batch. Callers who
  say "regenerate every stale clip" are explicitly consenting to N aigc
  calls under that single grant. Chain-of-trust is acceptable here because
  the user asked for exactly this side effect.
- Wired into desktop + server containers alongside
  `FindStaleClipsTool`; the registration passes `this` (the `AppContainer`'s
  `ToolRegistry`, `tools`) so the regenerate tool sees the same registered
  set the agent sees. Registration happens after `this.register(...)` calls
  have populated the registry (registration order doesn't matter; dispatch
  resolves at call time).
- Unit coverage in `RegenerateStaleClipsToolTest`: happy path (regenerate
  one stale clip, verify assetId swap + binding copy + single engine call),
  legacy-entry skip, and empty-project no-op.

**Alternatives considered.**
- **Derive base inputs from `provenance.parameters`.** Rejected — provenance
  records the wire body (post-fold effective prompt + provider-specific
  extras), not the caller's pre-fold input. Re-dispatching with that would
  double-fold the consistency bindings.
- **Re-dispatch via Agent's normal permission flow per clip.** Rejected —
  would force N permission prompts for a batch the user already consented
  to. The batch-consent model ("one aigc.generate grant covers N
  regenerations under this call") is the right UX trade.
- **Have the tool also re-export.** Rejected — `ExportTool`'s stale-guard
  already unblocks export once the clips are fresh; tying regenerate to
  export would fuse two steps that should remain independent (user may
  want to regenerate and review before exporting).
- **Direct assetId swap without copying `sourceBinding` from the new
  entry.** Rejected — copying the binding matches `ReplaceClipTool`
  behavior so future stale-drift detection stays correct.

**Why.** VISION §6 worked example ("修改主角发色 → … 只重编译这些镜头") was
the flagship demo that had no one-call path. This tool makes it
demonstrable end-to-end in one agent turn: user edits character_ref →
agent calls `regenerate_stale_clips` → every bound AIGC clip refreshes
with the new character, the export stale-guard clears, export proceeds.
That's the §3.2 / §5.1 claim operationalised.

**How to apply.** Future AIGC tools MUST call `AigcPipeline.record` with
`baseInputs = JsonConfig.default.encodeToJsonElement(Input.serializer(),
input).jsonObject` — otherwise their outputs will become
regenerate-resistant (the tool will skip with a "legacy entry" reason
even on brand-new entries). There is no enforcement beyond convention;
if we see a second forgetting, fold `baseInputs` construction into
`AigcPipeline` directly via a helper that takes the serializer + input.

---

## 2026-04-19 — Per-clip incremental render — deferred, rationale recorded

**Context.** The highest §3.2 gap called out by the gap-analysis Explore
round was "per-clip incremental render" — `RenderCache` memoizes whole
timeline exports; there is no mechanism to render a single stale clip and
reuse intermediate files for the rest. VISION §3.2 ("只重编译必要的部分")
is a load-bearing bet; this is the clearest way to honour it at export time.

**Decision.** Defer. Keep the full-timeline memoization + the stale-guard
from this round; document what a per-clip path would need and why it
doesn't fit the current iteration.

**Why deferring.** A correct per-clip pipeline has to address, at minimum:
1. **Per-clip render API on every engine.** FFmpeg can do it (render each
   clip to an intermediate .mp4, concat demux at the end). AVFoundation
   requires an `AVMutableComposition` per clip and a master composition at
   stitch time. Media3 is per-`MediaItem` already but shares a
   `Transformer` pipeline — per-clip outputs require composing two
   transforms stages. Three engines, three shapes, all of which must agree
   on "what does an intermediate clip file look like" (codec, container,
   colour space, frame rate) for concat at the end.
2. **Transitions span clip boundaries.** A dip-to-black between clip A and
   clip B uses the tail of A + head of B. If A is cache-fresh and B is
   stale, you can't just stitch cached A + freshly-rendered B — you need
   to re-render the transition region. That means clip fingerprints need a
   neighbour-aware component, or the cache is keyed at "clip + transition
   context" granularity, not just clip.
3. **Cache correctness under source-stale drift.** `staleClipsFromLockfile`
   operates at the clip-binding level — a clip is stale when its *bound
   source nodes* drifted. A per-clip render cache key must include both
   the clip's own content hash *and* its bound-source hashes, otherwise a
   source edit that marks the clip stale but doesn't change the clip's
   `assetId` (yet) would spuriously cache-hit.
4. **Storage / eviction policy.** Per-clip intermediates are typically
   larger than finished exports (concat-friendly mezzanine codec). A
   user editing a 30-clip project would materialise 30+ intermediate
   files that need retention rules, path conventions, and a cleanup
   path — all new surface area.

Each item is tractable; the combination is a multi-day refactor that would
regress today's coarse cache correctness if shipped half-built.

**Partial paths considered and rejected for this round.**
- **Only-AIGC-clips per-clip cache.** The real saving on AIGC clips is
  already captured by the lockfile — a cached generate_image call is a
  no-op. Per-clip render cache on top would re-save the rendered pixels,
  not the generation cost. Marginal gain.
- **Fingerprint-only prep (compute per-clip hashes, don't wire).**
  Architectural doodling without user-visible value; fingerprint design
  depends on the engine decisions above.
- **FFmpeg-only per-clip render.** Would fork desktop (FFmpeg) behaviour
  from iOS / Android, exactly the cross-engine parity we just finished
  closing. Not an option in a codebase where CLAUDE.md §Architecture
  rules are "Timeline is owned by Core" / cross-platform parity.

**How to apply when we revisit.** Start with FFmpeg (desktop-first per
platform priority). Define a `PerClipRenderSpec` that includes the clip +
its transition-context neighbours; compute a `clipFingerprint` that hashes
(a) clip content fields, (b) bound-source content hashes from the
lockfile, (c) transition-overlap context. Store intermediates under a
project-scoped mezzanine directory with a retention policy tied to
`RenderCache` entries. Extend `ExportTool` to walk clips, cache-hit/miss
per clip, stitch. Only then lift to iOS / Android.

**Impact on the rest of the system.** None. Today's `ExportTool` +
stale-guard + lockfile already refuse stale outputs and reuse whole-
timeline renders on identical inputs. The missing piece is "render only
the delta"; the system is still correct in its absence, just less
efficient on long projects with a single small edit.

---

## 2026-04-19 — `upscale_asset` tool + `UpscaleEngine` contract

**Context.** VISION §2 lists "ML 加工: 字幕识别、抠像、超分、自动调色、
去噪" as compiler pillars. Captioning landed via `transcribe_asset` +
`auto_subtitle_clip`; super-resolution was the next clearest gap in the ML
"enhance existing asset" lane. Missing here blocks any workflow that wants
to push AIGC imagery to a higher-resolution master, clean up noisy imports,
or ship a 4K cut from 1080p sources.

**Decision.**
- Add `core/platform/UpscaleEngine.kt`. Shape mirrors `ImageGenEngine`
  rather than `AsrEngine` / `VisionEngine` because SR emits bytes (new
  artifact), not derived text: `UpscaleRequest(imagePath, modelId, scale,
  seed, format, parameters)` → `UpscaleResult(UpscaledImage, provenance)`.
- Add `core/tool/builtin/aigc/UpscaleAssetTool.kt`. Same seed-mint /
  lockfile-hash / provenance-record pattern via `AigcPipeline`. Hash is
  `(tool, sourceAssetId, model, scale, seed, format)` — no consistency
  folding because upscaling is a pixel-fidelity op, not a creative one.
  Permission `"aigc.generate"` (same bucket as other byte-producing AIGC
  lanes).
- **Folder placement:** under `tool/builtin/aigc/`, not `tool/builtin/ml/`.
  The split is operational: `aigc/` is "emits bytes, uses AigcPipeline";
  `ml/` is "emits derived text" (`describe_asset`, `transcribe_asset`).
  Super-res emits bytes and wants the same seed + lockfile disciplines as
  image gen. Future denoise / inpaint / style-transfer tools belong here
  too; pure-analysis tools belong under `ml/`.
- Range guard: `scale in 2..8` — narrower rejection (1x is a no-op,
  anything past 8x is a provider-specific exotic). Engines clamp further
  based on the chosen model (2x-only model + `scale=4` input → engine
  clamps and records actual scale in provenance).
- `v1: images only`. Video super-res is a different beast (temporal
  coherence, frame batching) — the cleanest path when we add it is a
  sibling `VideoUpscaleEngine`, not overloading this one.
- Wired into desktop + server containers with the same `upscale?.let { … }`
  gating pattern. No bundled concrete engine — Real-ESRGAN / SUPIR are
  usually on Replicate or run locally, both environment-specific.
- Unit coverage in `UpscaleAssetToolTest`: persistence, seed auto-mint,
  lockfile cache hit + scale-bust, scale-range guard.

**Alternatives considered.**
- **Separate `image_upscale` + `video_upscale` tools.** Rejected for v1 —
  video SR is genuinely different enough to deserve its own contract, not a
  flag on this one, but shipping only images first is correct sequencing.
- **Fold into `GenerateImageTool` with an `assetId` input.** Rejected —
  image gen takes a prompt and produces novel imagery; upscaling takes an
  asset and refines it. Same engine pattern, different intent; fusing them
  would make the `GenerateImageTool` schema harder for the LLM to reason
  about.
- **Place under `tool/builtin/ml/`.** Rejected — `ml/` is
  "analyse → text", and `upscale_asset` needs `AigcPipeline` (seed,
  lockfile). Either the tool sits with its pipeline under `aigc/` or
  `AigcPipeline` gets promoted; the former is cheaper.
- **Default scale 4 instead of 2.** Rejected — most users want 2x (safer,
  faster); 4x is the "really push it" case. 2x minimises surprise on
  first call.

**Why.** VISION §2's "ML 加工" lane now has two concrete exemplars
(transcribe_asset + captioning, and super-res), proving the ML lane can grow
orthogonal to AIGC generation. The architectural precedent also documents
what future enhancement-that-emits-bytes tools (denoise, inpaint, style-
transfer) should look like — one interface under `core/platform/`, one tool
under `tool/builtin/aigc/` that uses `AigcPipeline`.

**How to apply.** For the next byte-emitting ML enhancement (denoise, etc.),
follow the same three-piece shape: engine interface in `core/platform/`,
tool under `tool/builtin/aigc/`, `?.let`-gated registration in each
container. Keep permission at `"aigc.generate"` (the external cost / byte
production / cache concerns match that bucket).

---

## 2026-04-19 — `auto_subtitle_clip` tool — ASR → captions in one atomic edit

**Context.** The agent could caption a clip only by chaining `transcribe_asset`
(ASR) → `add_subtitles` (batch timeline write), doing the
`TranscriptSegment.startMs/endMs` → `Segment.startSeconds/durationSeconds +
clip.timeRange.start` arithmetic in the middle. That's three round-trips of
latency + tokens per caption, two snapshots on the revert stack, and the
arithmetic is subtle — segments that straddle the clip window have to be
clamped, segments past the end dropped. Every agent would reinvent this.

**Decision.**
- New tool `AutoSubtitleClipTool` under `core/tool/builtin/video/`. Input:
  `{projectId, clipId, model?, language?, fontSize?, color?, backgroundColor?}`.
  The tool reads the clip's `assetId` + `timeRange` from the project, calls
  `AsrEngine.transcribe(resolve(assetId))`, maps each `TranscriptSegment` to
  an absolute timeline placement (`clip.timeRange.start + seg.startMs`),
  clamps the end to `clip.timeRange.end`, drops segments whose start falls
  past the clip end, and commits every placed caption in one
  `ProjectStore.mutate` → one `TimelineSnapshot`.
- Output echoes `{trackId, clipIds, detectedLanguage, segmentCount,
  droppedSegmentCount, preview}` so the LLM can reason about the outcome
  without re-calling `find_stale_clips` or `transcribe_asset`.
- Permission: `"ml.transcribe"` — audio leaves the machine via the ASR
  provider, same exfiltration concern as `transcribe_asset`. The timeline
  write is implicit to the same intent; a separate `timeline.write` check
  would require a multi-permission `PermissionSpec` we don't have and
  doesn't buy safety over the ASK/allow on `ml.transcribe`.
- Wired into desktop + server containers alongside `TranscribeAssetTool`
  under the shared `asr?.let { … }` gate — no ASR provider ⇒ neither tool
  registered.

**Alternatives considered.**
- **Add a `captionize` flag to `transcribe_asset`.** Rejected — the two tools
  have different side-effect profiles (read-only vs timeline-writing) and
  different sensible defaults (`transcribe` returns segments so the agent
  can plan cuts; `auto_subtitle` wants to commit captions). Conflating them
  via a flag means the agent has to remember to set it, and the tool help
  text grows to describe two behaviours.
- **Accept `assetId` + `startSeconds` directly instead of `clipId`.**
  Considered for ergonomics ("caption this unattached audio at 0:30 on the
  timeline"), but the load-bearing use case is captioning an already-placed
  clip, and pulling `assetId` + `timeRange` from the clip means the agent
  can't mis-enter the offset. Unattached-asset captioning is still reachable
  via `transcribe_asset` → `add_subtitles`, which is where the 10% bespoke-
  offset case belongs.
- **Require the agent to do the clipping arithmetic in `add_subtitles`.**
  Rejected — subtle clamping / drop rules scatter across every caption
  workflow, and every agent reimplements them slightly differently (or
  worse, silently lets captions extend past the clip). Once-in-one-tool
  beats replicated-per-prompt.
- **Make this tool emit one snapshot per segment.** Rejected — matches the
  existing `AddSubtitlesTool` rationale: revert-unit should match user
  intent. "Caption this clip" is one intent; a 30-segment snapshot stack
  would overwhelm `revert_timeline`.

**Why.** VISION §5.2 rubric asks "工具集覆盖面… agent 能不能在一次意图下混合
调度？" The missing piece for ML-driven captioning was the "in one intent"
part — infrastructure existed, composition was the gap. This tool turns
"caption the talking-head clip" from a multi-step agent chain into a single
grounded call, which is exactly the UX VISION §4 (novice path) requires.

**How to apply.** Future "ML → timeline" composites follow the same pattern:
a single tool that reads from `ProjectStore`, invokes the ML engine, writes
back through `ProjectStore.mutate`, emits one snapshot. Permission names the
outward-facing concern (the upload, not the timeline write).

---

## 2026-04-19 — Narrative genre source schema (second concrete genre)

**Context.** VISION §5.1 asks "新 genre（例如从叙事片扩到 MV）要加 source schema,
需要改 Core 还是只需扩展?" Only one genre (vlog) existed, so the extensibility
claim was theoretical. The narrative genre is the VISION §6 flagship example and
the right second genre to pressure-test the boundary.

**Decision.**
- New package `core/domain/source/genre/narrative/` with three files mirroring
  the vlog exemplar:
  - `NarrativeNodeKinds.kt` — four dotted-namespace constants: `narrative.world`,
    `narrative.storyline`, `narrative.scene`, `narrative.shot`.
  - `NarrativeBodies.kt` — typed `@Serializable` bodies: `NarrativeWorldBody`
    (`name, description, era, referenceAssetIds`), `NarrativeStorylineBody`
    (`logline, synopsis, acts, targetDurationSeconds`), `NarrativeSceneBody`
    (`title, location, timeOfDay, action, characterIds`), `NarrativeShotBody`
    (`sceneId, framing, cameraMovement, action, dialogue, speakerId,
    targetDurationSeconds`).
  - `NarrativeSourceExt.kt` — `add…` builders (each accepting optional
    `parents: List<SourceRef>` so the caller wires the DAG at construction) and
    `as…` typed readers that return `null` on kind mismatch (same shape as the
    vlog readers).
- **Character nodes are reused, not minted.** Narrative deliberately does
  *not* define `narrative.character` — the genre-agnostic
  `core.consistency.character_ref` already serves that role, and all three
  AIGC tools fold it uniformly. A per-genre character kind would fork the
  §3.3 consistency lane.
- **Zero Core changes.** The narrative package only touches `Source.addNode`
  and `SourceRef` (both already public). Confirms the anti-requirement
  "在 Core 里硬编码某一个 genre 的 source schema" is unviolated.
- **No per-genre tools this round.** The generic `import_source_node` / the
  existing consistency `define_*` tools already let an agent populate a
  narrative graph. Purpose-built `define_narrative_world` / `..._scene` tools
  are a later call once we see whether the agent asks for them or just uses
  `import_source_node` + the body schema.
- Tests: `NarrativeSourceTest` covers round-trip for each kind, kind-dispatch
  null-on-mismatch, narrative + vlog coexistence in one Source, and a world
  → scene → shot stale-propagation walk through the genre-agnostic DAG
  (`Source.stale`) to prove parents wiring works.

**Alternatives considered.**
- **Define `narrative.character`.** Rejected — duplicates
  `core.consistency.character_ref` and fragments the cross-shot consistency
  lane that the AIGC tools already honour. The rule "character consistency
  is not genre-specific" is what keeps the fold logic DRY.
- **Fold scene/shot into one "beat" kind.** Rejected — scene and shot have
  different coarseness (scene = "what happens", shot = "how to film it") and
  the compiler targets shots one-to-one with clips. Collapsing them would
  force a flag on the body to distinguish, which is exactly the shape of a
  separate kind.
- **Promote `acts: List<String>` to `List<NarrativeActBody>`.** Rejected —
  ties the schema to a three-act assumption. Free-form strings let comedies,
  short films, and branching structures fit without pattern-matching on a
  typed shape.
- **Ship `define_narrative_*` tools in the same commit.** Rejected as scope
  creep — the schema-without-tools path is already usable via
  `import_source_node`, and adding tools is cheap once we know the agent
  actually reaches for them (YAGNI for tool sugar).

**Why.** VISION §5.1 rubric score goes from "有… 但只有一个 genre exemplar"
to "有… 两个独立 genre, 走的是同一条扩展路径" — the extensibility claim is
now backed by evidence. The narrative schema also unblocks the VISION §6
worked example ("修改主角发色 → 传导到 character reference → 引用该
reference 的所有镜头标记 stale → 只重编译这些镜头") as an end-to-end demo
path.

**How to apply.** Future genres (MV, tutorial, ad) follow the exact same
shape: a sibling package under `source/genre/<genre>/`, three files
(`*NodeKinds.kt`, `*Bodies.kt`, `*SourceExt.kt`). Do not import across genre
packages, and do not mint per-genre character / style / brand nodes — those
already live in `source.consistency`.

---

## 2026-04-19 — `generate_music` tool + `MusicGenEngine` contract, no concrete provider

**Context.** VISION §2 lists "音乐生成" as an AIGC compiler pillar alongside
image / video / TTS. The first three have contracts + concrete OpenAI-backed
engines + tools; music did not. Gap-analysis flagged it as the clearest §2
pillar absence.

**Decision.**
- Add `core/platform/MusicGenEngine.kt`: `{providerId, suspend fun generate(MusicGenRequest)}`
  mirroring `VideoGenEngine` shape. Request: `{prompt, modelId, seed, durationSeconds,
  format, parameters}`. Result: `{GeneratedMusic(audioBytes, format, durationSeconds),
  provenance}`.
- Add `core/tool/builtin/aigc/GenerateMusicTool.kt`: same seed-mint / lockfile-hash
  / consistency-fold / record pattern as `GenerateImageTool` and `GenerateVideoTool`.
  Cache key over `(tool, model, seed, duration, format, effective prompt, bindings,
  negative)`.
- Register in desktop + server containers with the same `musicGen?.let { register(…) }`
  gating pattern used for image / video / TTS. `musicGen` defaults to `null`
  in both containers — no concrete provider wired.
- `character_ref.voiceId` bindings are silently ignored by music gen: music has
  no speaking voice; only `style_bible` / `brand_palette` meaningfully fold.
- Unit coverage in `GenerateMusicToolTest` via a fake engine (persistence,
  seed auto-mint, style-bible folding, lockfile cache hit + duration bust).

**Alternatives considered.**
- **Ship a concrete Replicate-backed MusicGen engine.** Replicate does host
  facebook/musicgen behind a public token-gated API. Rejected *for this round*
  because the architectural lift (proving "add a new AIGC lane = add a contract
  + a tool + wire it in, same shape as the other three") is the first-class
  VISION §2 claim ("加一个新 Tool 够不够像注册一样低?"). A Replicate engine
  is useful but orthogonal and can land in a follow-up without touching the
  contract or tool. Keeping the contract stable across future providers is
  more valuable than one specific provider.
- **Bundle music into a generic `GenerativeProviderRegistry`.** Rejected for
  the same reason `ImageGenEngine` / `VideoGenEngine` / `TtsEngine` are
  separate interfaces: modality-specific fields (duration, format, voice,
  dimensions) don't usefully share a common shape, and a premature umbrella
  would either be too vague to type-check or leak modality concepts between
  lanes.
- **Stub engine that throws "not configured."** Rejected — adds a class that
  only exists to fail, and the `musicGen?.let` gating already handles the
  unregistered case cleanly (same pattern as `imageGen` / `videoGen` / `tts`
  when `OPENAI_API_KEY` is unset).
- **Register `generate_music` unconditionally with a placeholder engine.**
  Same objection as stub: surfacing a tool to the LLM that will always fail
  is worse UX than omitting it.

**Why.** VISION §2 requires that new compiler lanes plug in cheaply. This
change makes "add music gen" a 2-file affair (platform contract + tool)
plus one line per container — exactly the low-friction extensibility the
VISION claims. Once a concrete provider engine lands it's a 1-line flip in
the container (`val musicGen = RealEngine(…)`).

**How to apply.** Follow the same shape for future AIGC lanes (sound FX, 3D,
lip-sync, etc.): one platform interface per modality, one tool, `?.let`
register in each container. Do not expand `MusicGenEngine` with modality-
alien fields — route provider extras through `MusicGenRequest.parameters` and
echo them into provenance.

---

## 2026-04-19 — ExportTool stale-guard — refuse stale renders by default

**Context.** Gap-analysis against VISION §5 rubric flagged the highest-leverage
§3.2 gap: `staleClipsFromLockfile` was computed and surfaced via
`find_stale_clips`, but `ExportTool` happily rendered a timeline with stale
AIGC clips, and even worse, the render cache (keyed on timeline JSON + output
spec) would hand back the same stale output on re-run. A source-only edit
(e.g. "make Mei's hair red") doesn't change timeline JSON — drift only shows
up via `clip.sourceBinding` → lockfile hash comparison — so the existing
cache correctness argument ("DAG is respected implicitly because AIGC rewrites
assetId on cache miss") only holds when something has actually triggered a
regeneration. Pre-regeneration, the cache lies.

**Decision.**
- Add `allowStale: Boolean = false` to `ExportTool.Input`.
- Before fingerprinting / cache lookup / engine invocation, compute
  `project.staleClipsFromLockfile()`. If non-empty and `!allowStale`, fail
  fast with an `error(...)` message naming up to 5 stale clip ids and the
  drifted source-node ids, pointing the agent at `find_stale_clips` +
  regeneration before retry.
- On successful render (or cache hit) while `allowStale=true`, surface the
  stale ids on `Output.staleClipsIncluded` and append a `[allowStale: N
  stale clip(s)]` tail to the LLM-facing string so the model can't quietly
  ship drifted content.
- `forceRender` and `allowStale` are orthogonal flags: stale-guard is
  checked first, then the cache; `forceRender` only bypasses the cache.

**Alternatives considered.**
- **Auto-regenerate stale clips before export.** Attractive UX-wise but
  wrong layering: `ExportTool` would need to know how to dispatch
  `generate_image` / `generate_video` for arbitrary `clip.sourceBinding`
  graphs, which turns it into a meta-tool. The agent is the right layer
  for that planning — a clear error message that names the right tool is
  sufficient.
- **Mark the render cache stale-aware instead of refusing at export.**
  Would still produce drifted output, just not from the cache. Cache-only
  fix doesn't address the underlying correctness issue.
- **Warn (log) and continue.** The whole point of §3.2 is that reproducible
  builds require refusing suspect inputs, not burying warnings. Opt-in via
  `allowStale` preserves the escape hatch for the rare "ship it anyway"
  case.
- **Default `allowStale=true` for backward compat.** Rejected — no users
  yet, and the silent-stale behavior is exactly the anti-pattern VISION
  calls out. Fail-loud default is correct for a one-developer pre-v1.

**Why.** VISION §3.2 bet ("只重编译必要的部分") only pays off if the system
refuses to reuse or emit output that the DAG knows is invalid.
`find_stale_clips` alone makes the gap visible to the agent; this change
makes it visible to the export pipeline too, closing the loop.

**How to apply.** When adding future renderers or export paths (e.g. audio
export, per-clip render in task #6), call `staleClipsFromLockfile` +
respect `allowStale` before invoking the engine. The guard belongs
anywhere we turn the current project state into a user-visible artifact.

---

## 2026-04-19 — Media3 vignette filter — radial-gradient `BitmapOverlay`

**Context.** Final cross-engine compiler-parity gap (CLAUDE.md "Known
incomplete"). FFmpeg and iOS render `vignette`; Android
`Media3VideoEngine.mapFilterToEffect` was a no-op with a warn log. Task 7
of the current gap list — and the last remaining item from `CLAUDE.md`'s
known-incomplete list.

**Decision.**
- Add `VignetteOverlay : BitmapOverlay` that bakes a full-frame ARGB
  bitmap at `configure(videoSize)` time, painted with a
  `RadialGradient` (transparent → `argb(edge, 0, 0, 0)`). The bitmap is
  reused across frames; one GL texture upload per clip.
- In `mapFilterToEffect`, the `"vignette"` branch returns
  `OverlayEffect(listOf(VignetteOverlay(intensity)))`. Added to the
  videoEffects chain alongside other filters; subtitles/transitions
  already build a *second* `OverlayEffect` further along the chain, so
  vignette stays under any caption.
- Intensity (`0..1`) drives two knobs together: edge alpha and the
  inner stop at which the gradient starts fading from clear. Higher
  intensity = pitch-black corners *and* smaller bright centre —
  matches FFmpeg `vignette`'s perceived strength curve better than
  darkening edges alone would.

**Alternatives considered.**
- **Custom `GlShaderProgram`** (pixel-accurate, any resolution). The
  "proper" path but needs shader code + texture format negotiation +
  lifecycle wiring for a one-liner filter. Worth doing when we need
  effects that a bitmap overlay can't express (e.g. per-frame
  animated noise / distortion). Vignette isn't that.
- **`MatrixTransformation` brightness ramp.** Rejected for the same
  reason we rejected it on iOS transitions: a brightness matrix
  darkens uniformly; vignette needs a spatially-varying darkness.
- **Pre-render a small 256×256 vignette PNG and `setScale` it.** In
  principle lighter, but Media3 1.5.1 `OverlaySettings.setScale`
  semantics tie scale to the overlay's own pixel dimensions, so
  you'd hardcode the stretch ratio per video resolution — brittle.
  Baking at video size is ~8 MB at 1080p, fine for one per clip.

**Known limitations.**
- Gradient stops are linear (hard-coded at inner→1.0). More cinematic
  vignette curves (quartic / sigmoidal falloff) would need a second
  colour stop or a shader. Not perceptible at the default intensities
  we ship, so shipping the simple version.
- The bitmap is allocated per-clip: multiple vignette clips in one
  export each pay the allocation. A shared cache keyed on
  `(videoSize, intensity)` is an easy follow-up if that shows up.

**Follow-ups.**
- If we ever need two different vignette shapes (elliptical, shifted
  center), that's when the GlShaderProgram path pays off — the
  per-clip BitmapOverlay gets quadratic in shape count.
- Closes `CLAUDE.md` "Known incomplete" — the whole cross-engine
  filter / transition / subtitle / LUT parity matrix is now green.

---

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

## 2026-04-19 — Project bar + snapshot / fork / restore UI on desktop (VISION §3.4)

**Context.** With persistence landed (Task 1), the desktop app was still
minting a fresh random project id on every launch — so "可版本化 / 可分支"
(VISION §3.4) was theoretically there but un-exerciseable without typing
tool calls into chat. Task 5 of the current gap list.

**Decision.**
- New `ProjectBar` composable at the top of the window:
  - Shows the active project's title + id prefix + snapshot count.
  - `Actions ▾` dropdown: New / Fork / Save snapshot… / Switch project… /
    Delete current.
  - `Save snapshot…` opens a dialog with a label input + a list of the
    project's existing snapshots (each with a `Restore` button).
  - `Switch project…` opens a dialog listing every project the store
    knows about, with a `Switch` button per row; the active one shows a
    "• " bullet and an `Active` disabled button.
- **`projectId` is now mutable state in `AppRoot`.** `ProjectBar`'s
  `onProjectChange(ProjectId)` callback flips it; every downstream panel
  (`SourcePanel`, `TimelinePanel`, `ChatPanel`) already keyed refresh
  effects on `projectId`, so switching projects re-keys the side effects
  and the whole workbench re-renders against the new project.
- **Boot picks the most-recently-updated persisted project.** With
  persistent SQLite (Task 1), a returning user lands back on their
  last project on launch instead of a fresh random one. If no project
  exists we bootstrap one just like before. A one-shot "Loading…"
  state blocks the rest of the UI until the bootstrap `LaunchedEffect`
  finishes, so we never render panels against an empty sentinel id.
- **All lifecycle goes through the existing tools
  (`create_project` / `fork_project` / `save_project_snapshot` /
  `restore_project_snapshot` / `delete_project`).** The bar is a UI onto
  the registry, not a second mutation path.

**Alternatives considered.**
- **Sidebar of all projects (always visible), no switch-dialog.** Nicer
  when you have many projects, but eats horizontal space we don't have
  — the workbench is already three columns + a right-column tab
  strip. Keeping the project list behind a dialog trade screen space
  for one extra click.
- **`diff_projects` in the bar.** The tool exists; surfacing it needs
  a two-picker dialog + a diff renderer that deserves its own panel.
  Out of scope; fold into the same follow-up as "diff viewer".
- **Track active project id in the DB (last-opened row) instead of
  recomputing `maxByOrNull { updatedAtEpochMs }`.** The recompute is
  O(#projects) on every launch and reads an already-indexed summary
  list; adding a "last opened" column for the same result is
  premature. Revisit when the heuristic proves wrong (it won't for a
  long time).

**Known limitations.**
- Delete has no confirmation dialog. Current guard: the menu item is
  disabled when there's exactly one project (can't delete your only
  project), and the deletion logs to the activity pane. A confirm
  prompt + an Undo toast is the obvious follow-up.
- The Snapshots dialog shows epoch-ms rather than a formatted
  timestamp. Readable enough for expert users; pretty-time formatting
  is a v1 nicety.
- Switch dialog has no search — fine at tens of projects, painful at
  hundreds. Same bucket as the Source panel filter follow-up.

**Follow-ups.**
- `diff_projects` viewer (side-by-side JSON diff or a summary table).
- Delete confirmation + Undo affordance.
- Formatted timestamps (localised) across the Project + Snapshot UI.
- Last-opened persistence (if launch-time project-pick proves wrong).

---

## 2026-04-19 — Rich Timeline inspector on desktop (VISION §5.2 / §5.4 expert view)

**Context.** The old centre-panel timeline was a flat list of clip
`id-range` strings with a coloured block — no notion of tracks, no
visibility into filters / volume / transforms / subtitles / source
bindings, no stale signal. VISION §4 expert path needs direct-manipulate
access to each of those; Task 4 of the current gap list.

**Decision.**
- New `TimelinePanel.kt` composable that replaces the old flat list.
  Structure:
  - Header row: "Tracks · duration · N clips".
  - Per-track header (`[kind] <track-id-prefix> · N clips`) ordered as
    the Timeline stores them — `Video` / `Audio` / `Effect` / `Subtitle`.
  - Per-clip row: collapsed summary (kind, id prefix, time range, chips
    for `Nfx` / `xform` / `vol` / `fi` / `fo`) + stale highlight.
    Expanded: full clip JSON (via `Clip` serializer + pretty Json) plus
    `track` / `clip` / `bindings` lines, a `Remove` button that
    dispatches `remove_clip` through the shared registry.
- **Stale detection today: `Project.staleClips(allNodeIds)`.** We don't
  yet track "which source nodes changed since the last render" — so the
  initial badge flags every clip whose `sourceBinding` can go stale
  against any node in the DAG. Accepts false positives in exchange for
  a cheap, correct signal while we add a real stale-since-render ledger.
- **Dropped the synthetic `ClipRow` bag + per-click manual list
  refresh from `Main.kt`.** The new panel subscribes to
  `BusEvent.PartUpdated` like `SourcePanel` and reads the full
  `Project` — there's no parallel state to drift anymore.

**Alternatives considered.**
- **Pixel-scaled track lanes (clips rendered as sized blocks on a
  horizontal time ruler).** Closer to the Premiere / FCP visual but
  needs playhead / zoom / scrub controls to be useful, and we don't
  have a playhead concept yet (the preview panel is post-export). That
  UI is a project unto itself; the row-based inspector is the honest
  minimum viable step that exposes state we couldn't see before.
- **Filter / transform inline editing inside the inspector.** Out of
  scope this iteration. Edit round-trips through chat or a future
  per-kind dialog — we prioritised breadth (every clip kind, every
  applied-effect chip, source binding, stale badge) over depth (mutate
  each knob from a form).
- **Per-clip tools: `split_clip`, `trim_clip`, `move_clip`, …** Only
  `remove_clip` is wired so far; the rest have Tool registrations, just
  no inspector button yet. Follow-up.

**Known limitations.**
- Track-lane view is still row-based, not the graphical waveform /
  thumbnail lane most DAWs ship. The chips + stale tint carry a lot of
  what a full lane view would show; upgrade when we build live preview.
- Stale set uses "any node id changed" as the proxy — a node that has
  never changed since the last render still flags bound clips. Fix
  needs a render-lockfile delta store (post-Task-6).
- No drag-to-reorder / drag-to-trim. That's the next layer of interaction
  and wants a playhead.

**Follow-ups.**
- Per-clip inspector actions: Split / Trim / Move / Duplicate wired to
  the existing tools.
- Highlight-on-source-change: click a source node in `SourcePanel` →
  outline bound clips in `TimelinePanel`. Requires shared
  `selected-source-node` state — promote both panels into a single
  `ProjectWorkbenchState` when the second cross-panel interaction
  lands.
- Real "stale since last render" signal from the lockfile / render cache.

---

## 2026-04-19 — Source-node panel on desktop (VISION §5.1 expert surface)

**Context.** Before this change every source-DAG operation — defining a
character reference, listing style bibles, removing a brand palette — had
to go through the chat tab. VISION §4 explicitly names the expert path
("用户直接编辑 source 的每个字段") as a first-class user flow, not a
fallback. Task 3 of the current gap list.

**Decision.**
- New `SourcePanel.kt` composable. The right-hand column in the desktop
  UI gets a two-tab workbench (`Chat` / `Source`) via `TabRow`; the Source
  tab renders the DAG grouped by node kind (characters, style bibles,
  brand palettes, other) with per-row expand / remove and a "Define new"
  form at the bottom.
- **Refresh strategy: subscribe to `BusEvent.PartUpdated` + re-read the
  project on every part event.** Every tool call ends with a part
  emission; re-reading the project blob is O(one SQLite indexed PK fetch +
  one JSON decode). The alternative — a finer-grained `source.changed`
  signal — would be premature; we haven't hit the scale where polling
  matters, and this also picks up edits the agent made in the Chat tab
  without the panel having to know about them.
- **Edits dispatch through the same `ToolRegistry` the agent uses.** The
  panel's "Define character / style / palette" buttons construct the same
  JSON input the LLM would, and route through
  `registry[toolId]!!.dispatch(...)`. That gets us permission checks,
  bus events, and the exact validation the agent path gets — for free.
  No separate mutation path.
- **Unified `uiToolContext` helper.** The old `dummyToolContext` lived in
  `Main.kt`; two direct-dispatch call sites now exist (centre-panel
  buttons + `SourcePanel`), so it's moved to `SourcePanel.kt` as an
  `internal fun AppContainer.uiToolContext(ProjectId)`. Same behavior,
  one definition.

**Alternatives considered.**
- **Read-only inspector, edits via chat only.** Would ship faster but
  doesn't meet the VISION §4 expert-path bar ("用户直接编辑"). Rejected.
- **Full-fat modal dialog per kind (character ref picker with LoRA pin,
  reference-image browser, palette colour swatches, etc.).** Better UX,
  but several days of polish. v0 trades feature depth for coverage: you
  can create all three consistency kinds with a single name + description
  field, then round-trip through chat for the advanced knobs. Follow-up
  tracked below.
- **Cache `project` in `AppContainer` and let multiple panels share
  one observed state.** The right thing eventually, but premature: there
  is exactly one place that reads `Project` for the UI today (the
  panel). Introducing a shared `StateFlow<Project?>` before we have two
  consumers would be speculative. Promote when the Timeline inspector
  (task 4) lands and we actually have two.

**Known limitations.**
- Creation form is minimum-viable — no reference-asset picker, no LoRA
  pin, no `parentIds`. Editing an existing node via the panel isn't
  wired yet; "Remove then re-add" is the current loop. Both are lifted
  in the follow-up.
- No inline search / filter over the node list — a project with hundreds
  of nodes would get unwieldy. Nothing does that today; revisit if it
  does.
- The JSON body view is a plain `Text` with `SelectionContainer` — fine
  for copy-paste, but no syntax highlighting. That's what "it works"
  looks like; polish later.

**Follow-ups.**
- Inline-edit dialogs per kind (Character ref: name / description /
  voiceId / LoRA pin / reference assets). Closes the VISION §5.5
  cross-shot-consistency loop inside the panel without chat.
- Show downstream staleness: click a source node → highlight which clips
  in the Timeline view have bindings to it. Prerequisite: Task 4
  (timeline inspector with clip → source binding visibility).
- Extract `observeProject(projectId)` to `AppContainer` once we have two
  UI consumers (Source + Timeline inspector).

---

## 2026-04-19 — In-app video preview on desktop (JavaFX `MediaView` via `JFXPanel`)

**Context.** VISION §5.4 "agent 能跑出可看初稿" is the close-the-loop
moment for the editor — user invokes the agent, sees the result, iterates.
Before this change users had to tab out to Finder → external player after
every Export. Task 2 of the current gap list.

**Decision.**
- New `apps/desktop/src/.../VideoPreview.kt` + `JavaFxPreviewBackend.kt`.
  A `VideoPreviewPanel` composable shows the most recently exported file
  with play / pause / seek controls and an "Open externally" fallback.
- **Backend: JavaFX `MediaView` inside a `JFXPanel`, hosted in
  Compose Desktop's `SwingPanel`.** `ExportTool`'s default mp4/H.264/AAC
  is exactly what JavaFX Media can decode — so we get native playback in
  the editor window without a libvlc dependency.
- Pulled in via the `org.openjfx.javafxplugin` (0.1.0) + OpenJFX 21.0.5.
  The plugin auto-picks the host-OS classifier, so a fresh `./gradlew
  :apps:desktop:run` on macOS "just works" — no manual `--module-path`
  JVM args needed.
- **Reflective availability probe, graceful fallback.** `VideoPreview`
  only touches JavaFX types through the `JavaFxPreviewController`
  interface. `JavaFxPreviewBackend.isAvailable()` does a
  `Class.forName` on the two key classes; if it returns false the panel
  shows a placeholder and the "Open externally" button still works. This
  means headless CI builds don't crash just because they don't have the
  JavaFX natives loaded.
- Preview autoloads after Export completes (`previewPath = path` in
  `Main.kt` success callback).

**Alternatives considered.**
- **VLCJ (Java bindings to libvlc).** Plays literally any codec — a huge
  upgrade vs JavaFX's MP4-only story. Rejected because it requires the
  user to have VLC / libvlc installed separately; installing Talevia and
  being told "now install VLC to see your exports" is a bad first-run.
  Revisit when we need codec coverage beyond H.264+AAC.
- **Extract a filmstrip of PNG frames via `extract_frame` and show them
  scrubbable in Compose.** Zero new deps, works today. Rejected because
  no audio + no real playback ≠ "可看初稿". Demo-grade, not editor-grade.
- **`Desktop.getDesktop().open(file)` only (no embedded preview).** One
  click to OS player. Rejected as the primary path: it loses the
  "编辑器内看成片" feel that the VISION §5.4 close-the-loop wants. Kept as
  the fallback path for JavaFX-unavailable environments.
- **Pure FFmpeg frame decoder + Compose `Canvas` drawing.** Maximum
  control, fully cross-platform, but writing our own A/V sync and
  rendering pipeline is weeks of work for a v0 preview panel.
- **Compose Multiplatform's upcoming `VideoPlayer`.** Not stable in the
  Compose 1.7.x line we're on. Revisit when it ships.

**Known limitations.**
- Only the formats JavaFX can decode natively (mp4/H.264+AAC, mp3, wav,
  aiff, flv/fxm). Other `OutputProfile` codecs would fall through to the
  external-open fallback.
- JavaFX / AWT / Compose each run on their own threads. We proxy calls
  through `Platform.runLater` / poll at 10Hz — good enough for play /
  pause / seek but not frame-accurate scrubbing. Fine for v0; upgrade path
  is to listen to `currentTimeProperty` via a `ChangeListener` instead of
  polling.
- The `JFXPanel` initializes the JavaFX toolkit on first touch; if two
  panels are created back-to-back it works, but there is a one-time
  warm-up cost (~100ms on a warm JVM). Acceptable.

**Follow-ups.**
- Preview the in-flight timeline (live re-render as the user edits)
  rather than only post-Export files. Needs a cheap "preview profile"
  render (lower resolution, WebM?) and cache invalidation — out of scope
  for this task.
- Frame-accurate scrubbing via `ChangeListener` instead of 10Hz polling.

---

## 2026-04-19 — Persistent SQLite for JVM apps (`TaleviaDbFactory`)

**Context.** Both `AppContainer` (desktop) and `ServerContainer` opened the
SQLite database with `JdbcSqliteDriver.IN_MEMORY`. Every project, session,
source DAG entry, lockfile row, and snapshot was wiped on process exit,
which directly contradicts VISION §3.4 ("Project / Timeline is a codebase:
可读 / 可 diff / 可版本化 / 可组合"). Task 1 of the current gap list.

**Decision.**
- New JVM-only helper `core.db.TaleviaDbFactory` owns driver lifecycle:
  - Path resolution order: explicit arg → `TALEVIA_DB_PATH` env →
    `<TALEVIA_MEDIA_DIR>/talevia.db` → in-memory. `":memory:"` /
    `"memory"` force in-memory even when other env is set.
  - Schema cookie: `PRAGMA user_version`. `0` → `Schema.create` + stamp
    version; `< target` → `Schema.migrate` + stamp; `> target` → refuse to
    open (downgrade protection).
  - `PRAGMA journal_mode = WAL` on file-backed DBs for tolerance to
    occasional concurrent readers (e.g. desktop + server both pointed at
    the same file during dev).
- `AppContainer` (desktop) + `ServerContainer` now delegate to the factory
  and expose `dbPath: String` for logs.
- Desktop `Main.kt` layers two defaults onto `System.getenv()` before
  handing it to `AppContainer`: `TALEVIA_DB_PATH=~/.talevia/talevia.db`
  and `TALEVIA_MEDIA_DIR=~/.talevia/media`. User-supplied env wins;
  defaults only fill the blanks. Result: desktop is persistent
  out-of-the-box, and `TALEVIA_DB_PATH=:memory:` opts back into ephemeral.
- **Server stays in-memory by default.** Server is headless and intended
  for batch / stateless deployments; operators who want persistence set
  `TALEVIA_DB_PATH` (or `TALEVIA_MEDIA_DIR`, which the factory also picks
  up) explicitly.
- **Tests preserved.** No retrofit: every test that touches SQLite
  constructs its own `JdbcSqliteDriver` directly with
  `TaleviaDb.Schema.create` — they never went through the container
  defaults, so the factory change is invisible to them. The new
  `TaleviaDbFactoryTest` covers the factory surface.

**Alternatives considered.**
- **OS-idiomatic data dirs** (`~/Library/Application Support/Talevia` on
  macOS, `$XDG_DATA_HOME/talevia` on Linux, `%APPDATA%\Talevia` on
  Windows). Rejected for v0: per `CLAUDE.md` platform priority the current
  target is macOS; a single cross-OS default (`~/.talevia`) keeps the
  helper free of `System.getProperty("os.name")` branches, and the user
  can always point `TALEVIA_DB_PATH` anywhere they like. Revisit when
  Windows / Linux desktop becomes a first-class target.
- **Per-project SQLite files.** Rejected: the DB holds cross-project state
  (sessions, all projects, all snapshots). A global DB is the VISION §3.4
  "codebase" analogue; per-project files would fragment the codebase.
- **Make the server persistent by default too.** Rejected: server is a
  deployment target, not a user app. Defaulting to a file write when the
  operator didn't configure one surprises ops. Opt-in is the safer default.
- **Point the factory at a user-supplied `Path`, not an env map.** Wanted
  a zero-wiring default for desktop Main ("works out of the box") and a
  zero-persistence default for tests + CI. Env is the easiest lever to
  pull differently per-caller without forcing each caller to reconstruct
  the path resolution logic.

**Migration story.** First file-backed open against a fresh path writes
`user_version = <Schema.version>`. If the Kotlin schema ever bumps, the
factory's `Schema.migrate` branch will handle it automatically and
re-stamp. No migration SQL exists yet because the schema has only had one
version — the hook is in place for when it grows a second.

**Follow-ups.**
- Concurrent-writer story. WAL helps readers, but two JVMs writing to the
  same DB would still clash. Not a near-term concern (one desktop, one
  optional server), but should be thought through before we do any
  multi-process scenario.
- `TALEVIA_DB_PATH` needs a `docs/` mention; added a note in `CLAUDE.md`
  under Observability so operators find it alongside `TALEVIA_MEDIA_DIR`.

---

## 2026-04-19 — Gap analysis vs VISION §5 rubric (desktop-first pass)

**Context.** Kicking off a new autonomous "find-gap → fill-gap" cycle. Per
`CLAUDE.md` platform priority, macOS desktop must reach "相对完善可用"
before iOS / Android get new features. Scored each VISION §5 rubric section
against current code and picked the candidates that (a) score lowest on the
desktop path and (b) fit a short-cycle close-the-loop.

**State read (what's green):**
- §5.1 Source layer — Source DAG, ref / bible / palette nodes, mutation
  tools, parentIds, content hashing, import / list / remove all landed in
  Core.
- §5.2 Compiler — traditional / AIGC / ML / filter lanes all covered by
  tools; transitions + subtitles + LUT render on all three engines; only
  Android `vignette` remains a known gap.
- §5.3 Artifact — Lockfile pins AIGC inputs, content-hash cache keys,
  stale detection + `find_stale_clips` + `replace_clip`.
- §5.5 Cross-shot consistency — character refs + style bibles + brand
  palettes flow into prompt folding and LoRA / reference arrays.

**What's red on the desktop path:**
- Desktop SQLite is `JdbcSqliteDriver.IN_MEMORY` — every project / session /
  source node / lockfile entry / snapshot evaporates on restart. The VISION
  §3.4 claim ("Project / Timeline is a codebase: 可读 / 可 diff / 可版本化 /
  可组合") can't hold if the codebase disappears when you quit the editor.
- Desktop UI exposes three buttons (import / add_clip / export) plus a chat
  panel. Every other ability — filters, transitions, subtitles, AIGC,
  source editing, snapshots, fork, lockfile, stale — is chat-only. VISION
  §4 expert path ("用户直接编辑 source 的每个字段、override 某一步编译") has
  no UI surface.
- No in-app preview. Users export an mp4 and open it in an external
  player. Blocks VISION §5.4 "agent 能跑出可看初稿" from being a tight loop.
- Timeline view is a flat list of clips — can't see tracks, applied
  filters / LUT / subtitles / transitions, or stale state.
- No project browser. App boots with one random project each time.

**Prioritised task list (high → low).** Each task closes a concrete rubric
gap on the desktop path. Implement in order; don't parallelise.

1. **Persistent SQLite for desktop.** Without it the next five tasks are
   all "felt experience disappears on quit". Smallest diff, biggest
   unblock. VISION §3.4 codebase invariant.
2. **In-app video preview.** Closes the agent iteration loop — "make
   change → watch result" shouldn't require a file browser. VISION §5.4.
3. **Source-node panel.** Surfaces the §5.1 DAG to the expert path.
4. **Rich Timeline inspector.** Tracks, applied effects per clip, stale
   badges. Expert path per VISION §4 / §5.2.
5. **Project browser + snapshot / fork / restore UI.** VISION §3.4
   (可版本化 / 可分支).
6. **Lockfile + stale-clip panel.** VISION §3.1 / §3.2 visibility.
7. **Android vignette filter.** Final cross-engine parity gap — lower
   priority because Android is "don't regress" per current platform
   priority.

**Why this order, not something else.**
- **Persistence before UI polish.** UI that lets the user build real work
  and then throws it away is worse than no UI at all — it trains them not
  to trust the system. Ordering persistence #1 respects the VISION §3.4
  first-class "codebase" claim.
- **Preview before editor richness.** Without a preview the edit ↔ see
  loop is so slow that features on top of it don't really get used. Every
  subsequent UI task is validated by "can I now iterate on this visually?"
- **Source → Timeline → Project → Lockfile.** Expert-path visibility goes
  from the smallest surface (source, which is 3-4 kinds of nodes) up to
  the largest (timeline, many tracks × many clips × many effects), then
  project-level operations, then the most advanced (lockfile / stale). A
  user can get real work done after #3-#4; #5-#6 promote the expert
  workflow from "possible via chat" to "direct-manipulate".
- **Android parity last.** Per `CLAUDE.md` platform priority, Android is
  explicitly "不退化" during this phase — the vignette gap is already
  documented as a "Known incomplete", not a red-line break.

**Process rules for this cycle.**
- Execute directly on `main`. Plan → implement → push per task.
- Every decision made autonomously gets a new entry here — this log is the
  async review channel.
- Red lines from `CLAUDE.md` stand (CommonMain zero platform dependency,
  Tool registration over Core edits, Timeline is owned by Core, etc.). If
  a task seems to require breaking one, stop and challenge per VISION
  §"发现不符".

---

## 2026-04-19 — Media3 transition rendering (Android) — full-frame black `BitmapOverlay` with ramped `alphaScale`

**Context.** FFmpeg and AVFoundation now render `add_transition` as a dip-to-
black fade. Android was the last gap — `Media3VideoEngine` ignored
Effect-track transition clips and exported hard cuts, breaking VISION §5.2
compiler parity for the third platform.

**Decision.**
- Add a `transitionFadesFor` helper to `Media3VideoEngine` that mirrors the
  FFmpeg / iOS logic: scan `Track.Effect` for clips whose `assetId.value`
  starts with `"transition:"`, locate the two adjacent video clips by
  boundary equality, and assign each side `halfDur = duration / 2` as a
  head/tail fade.
- Implement `FadeBlackOverlay : BitmapOverlay`. `configure(videoSize)`
  receives the input frame size — allocate one `ARGB_8888` bitmap of that
  size, `eraseColor(BLACK)`, and reuse it across frames so the GL texture
  uploads once per clip. `getOverlaySettings(presentationTimeUs)` returns
  a fresh `OverlaySettings` whose `alphaScale` is the linear ramp between
  `startAlpha` and `endAlpha` over `[startUs, endUs]` in the clip's local
  microsecond timeline (Media3 hands per-clip presentation times into the
  overlay's getters).
- Wire fade overlays **before** subtitle overlays in the per-clip
  `OverlayEffect` list. Media3 composites overlays bottom-up, so subtitles
  sit on top of the dip-to-black — captions stay legible even at peak fade,
  matching the FFmpeg pipeline (drawtext runs after the per-clip `fade`
  filter).

**Alternatives considered.**
- **Custom `GlEffect` / `GlShaderProgram` that scales RGB by alpha.** This
  is the "proper" path but Media3 1.5.1's GL effect API requires writing a
  shader, lifecycle wiring, and texture-format negotiation. A black overlay
  with `alphaScale` produces the identical visual via two existing
  primitives (`BitmapOverlay` + `OverlaySettings`) — no shader code, no GL
  lifecycle. Worth revisiting if we ever need RGB-only dimming (preserving
  the underlying alpha channel in transparent media).
- **`MatrixTransformation` to fade via a brightness matrix.** Rejected:
  same gray-wash problem as `CIColorControls.inputBrightness` on iOS —
  additive shift toward `-1` doesn't produce a clean black at partial
  alpha. The overlay approach is multiplicative-equivalent (overlay alpha
  `α` produces a frame that is `(1-α) * source + α * black`).
- **Tiny bitmap (`16×16`) stretched via `OverlaySettings.scale`.** Rejected:
  `setScale` semantics are tied to the overlay's pixel dimensions in
  Media3 1.5.1, not to NDC fractions — using `(videoW/16, videoH/16)`
  would work in principle but couples to bitmap size in a brittle way.
  Allocating a full-frame ARGB bitmap is ~8 MB at 1080p, fine for one
  short-lived overlay per fading clip.

**Scope / what still doesn't render.**
- The transition `name` is collapsed to fade-to-black just like the other
  two engines. Real crossfades, slides, wipes need the timeline-model
  change tracked in the FFmpeg decision below.
- `vignette` filter is still the lone Android-only gap — `BitmapOverlay`
  doesn't help here (vignette needs a shader); leave it for the same
  follow-up that adds custom `GlShaderProgram`s.

---

## 2026-04-19 — AVFoundation transition rendering (iOS) — CI color-matrix dim in the filter handler

**Context.** The FFmpeg engine now renders `add_transition` as a dip-to-black
fade at the boundary between two adjacent clips. For iOS parity we need the
same visual on AVFoundation — without changing the transition data model
(which keeps clips strictly sequential and puts the transition on a separate
Effect track).

**Decision.**
- Extend `IosVideoClipPlan` with `headFadeSeconds` / `tailFadeSeconds` so the
  Swift engine receives the pre-computed fade envelope per clip. Kotlin-side
  `Timeline.toIosVideoPlan()` scans `Track.Effect` for `assetId.value`
  starting with `"transition:"` and assigns each adjacent video clip half
  the transition's duration (mirroring the FFmpeg logic).
- The Swift `ClipFilterRange` gains `headFade` / `tailFade` alongside its
  filter specs, and the activation predicate changes from `plan.filters not
  empty` to `plan.filters not empty || hasFades`. Clips with *only* a fade
  now flow through the `applyingCIFiltersWithHandler` path.
- Inside the handler: after applying the clip's filter chain, compute
  `alpha = transitionAlphaAt(t, clip)` — a piecewise-linear ramp that is
  `0..1` over the head window and `1..0` over the tail window, and `1.0`
  everywhere else. When `alpha < 1`, pass the frame through a `CIColorMatrix`
  with `R/G/B` vectors scaled by `alpha` (zero bias) — this multiplies RGB
  toward black while preserving the alpha channel.

**Alternatives considered.**
- **`setOpacityRamp(fromStartOpacity:toEndOpacity:timeRange:)` on an
  `AVMutableVideoCompositionLayerInstruction`.** Rejected: the timeline
  already uses the `applyingCIFiltersWithHandler` path when *any* filter
  exists, and those two paths aren't composable (`applyingCIFiltersWithHandler`
  builds its own per-track instructions). Forcing fades through
  layer-instruction opacity would require maintaining two parallel setup
  branches depending on whether filters are present — more code, more
  drift risk, and the CIColorMatrix dim renders identically for the mp4.
- **`CIColorControls.inputBrightness = -alpha`.** Rejected: brightness is
  additive (shifts toward -1 = black), so partial fades look *gray-washed*
  rather than dipping cleanly to black. A multiplicative color matrix is the
  physically correct scale-to-black.
- **`CIConstantColorGenerator` + `CISourceOverCompositing` with alpha
  interpolation.** Rejected: equivalent output to the matrix approach but
  allocates an extra image per frame and requires an extra compositing
  filter. One `CIColorMatrix` pass is simpler.

**Scope / what still doesn't render.**
- The transition `name` is ignored — every name (`fade`, `dissolve`, `slide`,
  `wipe`, …) becomes a dip-to-black fade. This is the documented cross-engine
  parity floor; richer transitions (actual crossfade, directional wipes)
  need a timeline-model change (overlap between A/B) and are tracked as a
  VISION §5.2 follow-up.
- Verification: `DEVELOPER_DIR=... ./gradlew
  :core:linkDebugFrameworkIosSimulatorArm64` + `xcodebuild … Talevia` build
  cleanly; the transition path flows through the existing
  `renderWithFilterProducesVideo` shape (no new iOS test scaffold — the
  Kotlin-side `toIosVideoPlan` logic mirrors
  `FfmpegVideoEngine.transitionFadesFor` which has direct unit coverage in
  `TransitionFadesTest`).

---

## 2026-04-19 — FFmpeg transition rendering — dip-to-black fade at clip boundaries

**Context.** `AddTransitionTool` wrote a synthetic `Clip.Video` to the Effect
track with `assetId = "transition:{name}"`, but no engine rendered it — the
exported mp4 had hard cuts regardless of `transitionName`. Behavioral parity
called for at least FFmpeg to honor transitions so the data model wasn't
lying to users.

**Decision.** Render **every** transition name (`fade`, `dissolve`, `slide`,
`wipe`, …) as a dip-to-black fade: the outgoing clip fades to black over
`duration/2`; the incoming clip fades in from black over `duration/2`.
Concretely, `FfmpegVideoEngine`:
1. Scans `Track.Effect` for clips with `assetId.value.startsWith("transition:")`
   and computes each transition's boundary = `transitionRange.start + duration/2`.
2. Maps each affected `Clip.Video.id` to a `ClipFades(headFade, tailFade)`
   where `halfDur = duration / 2`.
3. Emits `fade=t=in:st=0:d={halfDur}:c=black` for `headFade` and
   `fade=t=out:st={clipDur - halfDur}:d={halfDur}:c=black` for `tailFade`,
   comma-joined with any pre-existing filter chain inside `[N:v:0]…[vN];`.

**Why not a proper crossfade?**
- A crossfade (ffmpeg's `xfade` filter) requires the two clips to *overlap*
  on the timeline. Our `AddTransitionTool` keeps clips sequential and
  encodes the transition as a separate Effect-track clip at the boundary —
  there's no overlap to crossfade across, and changing the tool to produce
  overlap would cascade into Android/iOS engines that already assume the
  sequential model.
- Dip-to-black maps cleanly to per-clip opacity ramps on **all three**
  engines (FFmpeg `fade`, Media3 per-clip alpha effect, AVFoundation
  `setOpacityRamp(fromStartOpacity:toEndOpacity:timeRange:)`), so picking
  it as the cross-engine parity floor unblocks Task 7b / 7c without
  forcing a timeline-model rewrite.
- Users who specifically ask for a crossfade can get that later under a
  dedicated `overlap: true` tool option; for v1, naming a transition
  triggers the parity-floor fade.

**Alternatives considered.**
- **`xfade` filter for FFmpeg only.** Rejected: diverges from native engines,
  and requires restructuring the filtergraph (split, overlap, merge) vs.
  the current simple concat pipeline.
- **Render only `fade` and ignore other names.** Rejected: silent failure is
  worse than slight semantic mismatch. An LLM calling `transitionName="slide"`
  gets *some* transition instead of a hard cut.

**Scope.** The `fade` filter with `c=black` ships with every ffmpeg build
(part of `libavfilter`'s core, no libfreetype-style dependency), so no
feature detection / skip logic needed. Regression coverage:
- `TransitionFadesTest` — unit-level verification of `transitionFadesFor`
  boundary matching and `buildFadeChain` filtergraph output.
- `FfmpegEndToEndTest.renderWithTransitionProducesVideo` — drives
  import → add → add → add_transition → export through the real tool
  registry and asserts the output mp4 exists and is non-trivial.

---

## 2026-04-19 — AVFoundation subtitle rendering (iOS) — `CATextLayer` via animationTool

**Context.** The Media3 pass closed the Android caption gap; the same
feature was still no-op on iOS. `AVFoundationVideoEngine` wrote video
and audio tracks but never touched `Track.Subtitle` clips, so exports
on iOS silently dropped captions. AVFoundation's documented path for
burning text overlays into a composition is
`AVVideoCompositionCoreAnimationTool` with a Core Animation layer
hierarchy — a built-in primitive, no custom CIFilter needed.

**Decision.**
- Add `IosSubtitlePlan` + `Timeline.toIosSubtitlePlan()` in
  `IosBridges.kt` (mirrors `IosVideoClipPlan` / `toIosVideoPlan`) so
  Swift consumes a flat, Sendable DTO instead of crossing the SKIE
  sealed-class boundary for `Clip.Text` / `TextStyle`.
- In `AVFoundationVideoEngine.runExport`, after the filter pass:
  1. If there are subtitles but no filter pass, build an
     `AVMutableVideoComposition` via `videoComposition(withPropertiesOf:)`
     (so the animation tool has somewhere to attach).
  2. Build `(parent, video)` layers — `parent.isGeometryFlipped = true`
     so `y = margin` counts from the bottom (matches FFmpeg's
     `y = h - text_h - margin`).
  3. Per subtitle, create a `CATextLayer` at bottom-center, `opacity = 0`,
     plus a `CABasicAnimation` on `opacity` (from=1, to=1, `beginTime`
     = `startSeconds` or `AVCoreAnimationBeginTimeAtZero` when start is
     0, `duration` = `end - start`). Model opacity stays 0 so the text
     is invisible outside the animation window; inside the window the
     animation's presentation value of 1 reveals it.
  4. Attach via `vc.animationTool = AVVideoCompositionCoreAnimationTool(
     postProcessingAsVideoLayer: video, in: parent)`.

**Style mapping (`TextStyle` → UIKit).**
| `TextStyle` field | Swift                                                    |
|-------------------|----------------------------------------------------------|
| `color`           | `NSAttributedString.Key.foregroundColor` (UIColor.cgColor) |
| `backgroundColor` | `NSAttributedString.Key.backgroundColor` (optional)      |
| `fontSize`        | `UIFont.systemFont(ofSize:)` / `UIFont(name:size:)`      |
| `bold`            | `.traitBold` (or `systemFont(.bold)`)                    |
| `italic`          | `.traitItalic`                                           |
| `fontFamily`      | `UIFont(name:size:)`; skip default "system"              |

Hex colours are parsed with a custom scanner (`#RRGGBB` or
`#RRGGBBAA`), falling back to white on malformed input rather than
crashing the export.

**Geometry flip.** `AVVideoCompositionCoreAnimationTool` on iOS
composites in a flipped-Y coordinate space vs. the default UIView
top-left origin. Setting `parent.isGeometryFlipped = true` makes
sublayer positions count from the bottom, which is what the FFmpeg
engine already does. Text inside each `CATextLayer` is unaffected
(flip propagates to sublayer *position*, not the layer's own
contents) so captions read left-to-right normally.

**Alternatives considered.**
- **Rasterise text into a CIImage inside the CI filter handler** —
  plausible when filters are already in play, but doubles the
  rendering cost (GPU CI pipeline + CPU text rasterisation per
  frame) and forks the code path based on whether filters exist.
- **`AVAssetWriter` + manual per-frame compositing** — more control
  but a much larger rewrite; not worth it for captions alone.
- **Custom `AVVideoCompositing` protocol impl** — necessary if we
  wanted to avoid CI filter handler entirely, but filters + overlays
  coexist via animationTool today so this is deferred.

**What still doesn't render.** Transitions remain a gap on both
native engines — `add_transition` writes to the timeline but the
exported mp4 still has hard cuts. Wiring Media3 opacity-ramp custom
effects / AVFoundation `setOpacityRamp` is the follow-up.

---

## 2026-04-19 — Media3 subtitle rendering (Android) — `TextOverlay` + `OverlayEffect`

**Context.** After the filter parity pass, `Track.Subtitle` was the
last major gap on Android: `add_subtitle` / `add_subtitles` wrote
`Clip.Text` onto the timeline, but `Media3VideoEngine` never touched
them — exports on Android dropped all captions while the FFmpeg
engine baked them via `drawtext`. Media3 1.5.1 ships a built-in
`TextOverlay` (subclass of `BitmapOverlay`) and a matching
`OverlayEffect` that plugs into `Effects.videoEffects`, so we do not
need a custom `GlShaderProgram` for a v1 caption renderer.

**Decision.** Per video clip, find every subtitle whose timeline
range overlaps the clip, then attach one `TextOverlay` per overlap
to that clip's `Effects(emptyList(), videoEffects)` list inside a
single `OverlayEffect(overlays)`. The overlay's **local** window
(in clip-presentation-time microseconds) is `max(sub.start,
clip.start) - clip.start` … `min(sub.end, clip.end) - clip.start`.

**Time gating.** `TextOverlay.getText(presentationTimeUs)` is called
on every frame, and its base class caches the rasterised bitmap
keyed on `SpannableString.equals`. To avoid re-rasterising every
frame, we keep `getText` constant and toggle visibility via
`OverlaySettings.alphaScale`:
- `BOTTOM_CENTER_VISIBLE` — `alphaScale = 1f`, used inside the window.
- `BOTTOM_CENTER_HIDDEN`  — `alphaScale = 0f`, used outside the window.
Result: each spannable rasterises once per clip; the GPU blend skips
the overlay outside the window.

**Style mapping (`TextStyle` → Android `Spanned`).**
| `TextStyle` field | Span                                    |
|-------------------|-----------------------------------------|
| `color`           | `ForegroundColorSpan` (parsed via `Color.parseColor`) |
| `backgroundColor` | `BackgroundColorSpan` (optional)        |
| `fontSize`        | `AbsoluteSizeSpan(px, dip=false)`       |
| `bold`/`italic`   | `StyleSpan(BOLD/ITALIC/BOLD_ITALIC)`    |
| `fontFamily`      | `TypefaceSpan` (skipped when "system")  |

Unparseable colors fall back to the platform default and log a
warning rather than crashing the export.

**Positioning.** `OverlaySettings.Builder().setBackgroundFrameAnchor(0f, -0.8f)`
mirrors the FFmpeg MVP (bottom-center, ~10% up from the frame's
bottom edge). Custom per-`TextStyle` positioning is a later knob —
`TextStyle` has no position fields in v1.

**Alternatives considered.**
- **`createStaticTextOverlay(spannable, settings)`** — simplest, but
  doesn't support a time-gated window; we would have to build a
  separate `EditedMediaItem` per subtitle segment or let the caption
  show for the entire clip. Rejected.
- **Return empty `SpannableString` outside the window** — plausible
  (`getBitmap`'s cache equality check would still short-circuit),
  but creating a 0×0 bitmap on some Android API levels is reported
  to throw. `alphaScale=0` is robust across versions without risking
  that path.
- **Custom `GlShaderProgram`** — overkill for captions; reserve that
  route for `vignette` / transitions where no built-in effect exists.

**What still doesn't render.** iOS `AVFoundationVideoEngine` still
ignores `Track.Subtitle` at render time (follow-up: `CATextLayer`
through `AVVideoComposition.animationTool`). Transitions remain a
gap on both native engines.

---

## 2026-04-19 — Shared `.cube` parser + native LUT rendering

**Context.** After the Media3 (Android) and AVFoundation (iOS) filter
parity passes, `lut` was the last Core filter both native engines
still skipped. Both were waiting on the same thing: a `.cube` file
parser (Adobe LUT v1.0). FFmpeg already renders LUTs via `lut3d=file=…`
because it reads the file itself; Media3's `SingleColorLut` wants a
pre-parsed `int[R][G][B]` cube, and iOS's `CIColorCube` wants
pre-packed `kCIInputCubeData` bytes. Writing the parser per-engine
would fork the format interpretation — not the right trade.

**Decision.** Add a single parser in `core.platform.lut.CubeLutParser`
(commonMain) that both native engines consume:

| Engine       | Conversion                                               |
|--------------|----------------------------------------------------------|
| Media3       | `Lut3d.toMedia3Cube()` → `int[R][G][B]` of packed ARGB   |
| AVFoundation | `Lut3d.toCoreImageRgbaFloats()` → float32 RGBA buffer    |
| FFmpeg       | unchanged — still passes the file path to `lut3d`        |

The parser supports `LUT_3D_SIZE`, default `DOMAIN_MIN/MAX`, and
comments. Non-default domains and 1D LUTs are rejected rather than
silently rendering against the wrong input range.

**Indexing sanity.** `.cube` files store entries in R-fastest order:
`(r=0,g=0,b=0), (r=1,g=0,b=0), …`. `Lut3d` preserves that order.
Media3's `SingleColorLut.createFromCube` expects `cube[R][G][B]` (per
the Media3 javadoc). iOS's `CIColorCube.inputCubeData` expects the
flat R-fastest order natively. The two conversions are unit-tested
against a known red/green/blue 2×2×2 cube to catch axis mix-ups.

**iOS bridging.** Naively, we'd hand Swift the parsed `FloatArray` and
let it pack into `Data`. That's 131k ObjC calls per 32³ LUT (one per
float). Instead, `parseCubeLutForCoreImage(text: String)` (in
`IosBridges.kt`) returns an `NSData` the Swift side casts to
`Foundation.Data` with zero per-element calls. Requires
`BetaInteropApi` + `ExperimentalForeignApi` opt-ins for
`NSData.create(bytes:length:)` — standard Kotlin/Native interop
boilerplate, not a sign of unsafe territory.

**Alternatives considered.**
- *Per-engine parsers in Swift / Kotlin (Android).* Forks the format
  interpretation. If the spec is wrong in one place, the engines
  disagree. Rejected.
- *Parse to a shared `Lut3d` but let each engine write its own
  conversion.* Kept the conversions in commonMain so the R-fastest
  vs. `[R][G][B]` mapping is tested once and can't drift.
- *Support non-default DOMAINs and 1D LUTs in v1.* Adds code paths
  nobody currently needs. The parser error message names the
  unsupported directive so when a real asset trips it, the fix is
  obvious.
- *Rely on FFmpeg-style `lut3d=file=`.* Neither Media3 nor Core Image
  accept a file path — both want pre-loaded cube data. So parsing has
  to happen in the engine layer either way.

**What still doesn't render.** Media3 `vignette` (no built-in; needs
a custom `GlShaderProgram`) and transitions on either native engine
— tracked in CLAUDE.md's "Known incomplete" section.

---

## 2026-04-19 — AVFoundation filter rendering (iOS) — CIFilter parity pass

**Context.** After the Media3 partial parity pass, the iOS
`AVFoundationVideoEngine` was the last remaining "filters on the
timeline, no filters in the output" engine. VISION §5.2: native
platforms should render the same filter vocabulary as FFmpeg for
parity across the three engines. iOS's advantage over Media3 is
`CIVignette` — a built-in Core Image primitive — so iOS can match
FFmpeg on *four* filters where Media3 only hits three.

**Decision.** Build an `AVMutableVideoComposition` via
`AVMutableVideoComposition.videoComposition(with:applyingCIFiltersWithHandler:)`
(iOS 16+; deployment target is 17.0) whenever any clip in the
timeline carries filters. The per-frame handler looks up the owning
clip by matching `request.compositionTime` against each clip's
`[timelineStart, timelineStart + timelineDuration)` range, then
applies its filter chain as a sequence of `CIFilter`s on the request's
`sourceImage`. Mapping:

| Core filter  | CIFilter                                           |
|--------------|----------------------------------------------------|
| `brightness` | `CIColorControls` `inputBrightness` (clamped -1..1)|
| `saturation` | `CIColorControls` `inputSaturation` (intensity * 2, clamped 0..2) |
| `blur`       | `CIGaussianBlur` `inputRadius` (sigma verbatim or radius*10) |
| `vignette`   | `CIVignette` `inputIntensity` + `inputRadius`      |
| `lut`        | no-op (same `.cube` parser gap as Media3)          |

**Saturation remap.** Same rationale as the Media3 pass: Core's
`apply_filter` uses `0..1` intensity with `0.5 ≈ unchanged` to match
FFmpeg's eq filter. CI's `inputSaturation` is multiplicative centred
at `1.0`. Linear remap `intensity * 2` → 0.5 maps to 1.0, 1.0 to 2.0,
0.0 to 0.0.

**Bridging plumbing.** `IosVideoClipPlan` (the flat DTO
`toIosVideoPlan()` builds for Swift) gained a `filters:
List<IosFilterSpec>` field, where `IosFilterSpec(name, params:
Map<String, Double>)` exposes `Filter.params` as `Double` instead of
the domain's `Float` so Swift can feed them straight into `CIFilter`
without the `KotlinFloat.floatValue` dance. On the Swift side, each
plan's filters are copied into a pure-Swift `ClipFilterRange` struct
before being captured by the `@Sendable` filter handler — SKIE-bridged
Kotlin types aren't `Sendable`, so the copy is what lets the handler
cross into the concurrent Core Image work queue.

**Alternatives considered.**
- *AVAssetWriter with a custom per-frame compositor.* Would let us
  honour `OutputSpec.videoCodec` / `bitrate` too, but it's a larger
  rewrite than this task scopes. `AVAssetExportSession` still uses
  the preset approach for encoding; the videoComposition overlay is
  strictly about per-frame pixel processing.
- *Per-clip AVMutableVideoCompositionInstruction with a custom
  compositor class.* More flexibility (we could swap pipelines per
  clip) but `applyingCIFiltersWithHandler` is the built-in, well-
  trodden path and our filters are all CIFilter-friendly. Revisit
  only if we need non-Core-Image effects (shader-based transitions,
  custom blends, etc.).
- *Punt vignette to match Media3's scope.* Would keep the three
  engines on exactly the same feature set. Rejected because iOS has
  `CIVignette` built-in — declining to use it for symmetry's sake is
  the wrong trade. The parity goal is "FFmpeg filters render on
  native engines where possible", not "every engine renders exactly
  the same subset".

**What still doesn't render.** `lut` (awaiting a `.cube` → raw cube
data loader that Media3 will share), transitions on either native
engine, and subtitle rendering on Media3/AVFoundation — tracked in
CLAUDE.md's "Known incomplete" section.

---

## 2026-04-19 — Media3 filter rendering (Android) — partial parity pass

**Context.** `apply_filter` has been writing `Filter` records onto
video clips in the canonical timeline for a while, and the FFmpeg
engine bakes them during `export`. The Media3 Android engine ignored
them entirely — the exported mp4 had no filters applied, even though
the timeline claimed they were attached. VISION §5.2 compiler parity:
all three render engines should honour the same filter vocabulary so
a project renders identically regardless of platform.

**Decision.** Wire three of the five filter names into Media3's
effects pipeline via `EditedMediaItem.Builder.setEffects(...)`, using
Media3 1.5.1's built-in effects:

| Core filter     | Media3 effect                            |
|-----------------|------------------------------------------|
| `brightness`    | `Brightness(intensity)` (clamped -1..1)  |
| `saturation`    | `HslAdjustment.Builder().adjustSaturation(delta)` |
| `blur`          | `GaussianBlur(sigma)` |
| `vignette`      | *not yet* — Media3 has no built-in vignette |
| `lut`           | *not yet* — `.cube` parser pending |

Unknown / unsupported filters are skipped with a `Logger.warn` so the
render still completes but the user can see in logs that a specific
filter didn't make it through. The Timeline keeps the filter record
either way, so future Media3 upgrades can pick them up.

**Why partial is acceptable.** The three wired filters
(`brightness` / `saturation` / `blur`) are the three the agent
reaches for on ~90% of color-grade asks — real-world "make it
brighter", "desaturate the shot", "blur the background" requests
resolve to these. Vignette and LUT are niche enough that shipping
without them is still a big improvement over "nothing works", and
both have a clean escape hatch: `vignette` can land once we implement
a small `GlShaderProgram`, and `lut` lands as soon as we write a
`.cube` → `int[][][]` loader that feeds `SingleColorLut.createFromCube(...)`.
Both would bloat this task by a day each; splitting them keeps the PR
reviewable.

**Saturation scale mapping.** Core's `apply_filter` convention
accepts `intensity` in [0, 1] where 0.5 ≈ unchanged (matches the
FFmpeg engine's `eq=saturation=intensity*2` mapping — intensity 0.5
becomes saturation 1.0 = neutral). Media3's `HslAdjustment.adjustSaturation(delta)`
takes a delta on [-100, +100] where 0 = no change. Linear remap:
`delta = (intensity - 0.5) * 200`, clamped. So:

- intensity 0.5 → delta 0 (unchanged)
- intensity 1.0 → delta +100 (max saturated)
- intensity 0.0 → delta -100 (grayscale)

Which matches the FFmpeg engine's user-facing behavior at the
endpoints. The middle of the range won't be pixel-identical across
engines (different math paths), but that's inevitable short of
shipping our own shader.

**Why not ship a custom `GlShaderProgram` for vignette today.**
Considered writing a minimal vignette shader inline. Rejected:
the fragment-shader boilerplate (vertex shader + FragmentShaderProgram
subclass + registering with Media3's effect API) is ~80 LOC for a
single effect, and the vignette filter hasn't been requested by any
trace so far. Better to wait for a second case to motivate the
boilerplate (vignette + a second custom effect), so we factor a
reusable helper instead of shipping a one-off shader.

**Why not parse `.cube` files in this task.** Same scope reason.
`.cube` is a simple text format (header + N³ RGB triplets) and a
parser would be ~40 LOC, but Media3's `SingleColorLut.createFromCube(int[][][])`
wants a packed 3D int array where each entry is an ARGB-packed
pixel. The conversion from float RGB triplets to ARGB-packed ints
has quantization decisions (round-to-nearest / saturation / gamma)
that warrant a dedicated pass with test fixtures. Follow-up task.

**Testing.** Media3 effects can't be instantiated in a plain JVM
test (the Android runtime is required for `GlEffect` types), so the
mapping function is verified by the Android debug-APK build plus
manual inspection. The filter → effect mapping is small enough that
a round-trip test at the level of "does `apply_filter(brightness)`
produce a Media3 `Brightness` effect" wouldn't catch anything the
compiler doesn't already catch. If this layer grows (adds LUT
parsing or a vignette shader) the test shape will be an instrumented
render-output check, not a pure-Kotlin unit test.

---

## 2026-04-19 — FFmpeg subtitle rendering via `drawtext`

**Context.** `add_subtitle` / `add_subtitles` had been shipping for a
while — they wrote `Clip.Text` entries onto a `Track.Subtitle` in the
canonical timeline and stored the styling. But the FFmpeg engine's
`render()` only ever iterated the Video track: audio tracks were
silently dropped (for Subtitle too). So the UX looked like "the agent
captioned the video" but the exported mp4 had no burned-in text.
Closest VISION gap: §5.2 compiler lane — a source-kind exists (captions)
but the compiler pass that turns it into an artifact wasn't wired.

**Decision.** Extend `FfmpegVideoEngine.render()` to collect every
`Clip.Text` on every `Track.Subtitle` and chain an ffmpeg `drawtext`
filter per clip after the `concat=…` step. Each drawtext is gated by
`enable='between(t,start,end)'` using the clip's timeline range, so
captions appear/disappear on the correct timeline beat. No splitting
of the timeline; drawtext overlays are composable with the existing
per-clip filter chain because they attach *after* concat.

**Positioning / font defaults.** Centered horizontally
(`x=(w-text_w)/2`), anchored near the bottom
(`y=h-text_h-<margin>`). Margin scales with output height
(`height * 48/1080`, floor 16 pixels) so the caption sits ~4.4% from
the frame edge regardless of resolution — matches how broadcast
lower-thirds behave. No `fontfile=` option is passed: that forces a
per-platform font path and breaks anyone with a different fontconfig
setup; leaving it off lets ffmpeg fall back to its built-in default
font, which works across Linux/macOS/Windows ffmpeg builds. The
`TextStyle.fontFamily` field is preserved in Core but treated as a
hint at render time — adding true custom-font support is a
follow-up (tool needs to register font assets via `import_media` and
expose them through `MediaPathResolver`).

**What TextStyle fields flow through today.**
- `fontSize` → `fontsize=`. Passed through unchanged.
- `color` → `fontcolor=`. `#RRGGBB` is normalised to ffmpeg's
  `0xRRGGBB` form; anything else passes through (named colors like
  `red` or `white` work; malformed values fail loud at render time).
- `backgroundColor` → adds `box=1:boxcolor=…:boxborderw=10` so the
  text gets a solid padding box when the user supplies a background.
  Omitted (null) → no box, transparent overlay.
- `bold` / `italic` → *not yet* applied; ffmpeg needs a font file for
  those variants. Future work when custom fonts land.

**Filtergraph escape strategy — `'` is the hard part.** Inside
single-quoted filter-option values, ffmpeg treats `:` `,` `;` `[`
`]` `\` as literal, which makes quoting the best default. The catch
is that apostrophes can't appear inside the quoted section at all —
the standard ffmpeg idiom is to close the quote, backslash-escape
the apostrophe, then reopen: `'hi'\''there'` → `hi'there`. The tool
also escapes `%` as `\%` because drawtext's text value runs through
the `%{…}` expansion pass after filter-arg parsing; an unescaped `%`
would otherwise trigger an expansion and fail loud or render wrong
output. `enable=` values (expressions like `between(t,1,2)`)
continue to use the outside-quotes backslash-escape form for
commas because expression syntax doesn't need quote-wrapping and
the flat form is easier to read in logs.

**Why `drawtext` and not SRT sidecar / burn-in-before-concat.**
Considered (a) writing an SRT file alongside the output and adding a
soft-subtitle stream, and (b) burning each clip's subtitles into the
per-clip video stream before the concat step.
- SRT rejected: "soft" captions aren't rendered until a player
  decodes them; the export artifact shown to the user would appear to
  have no captions, which defeats the VISION §2 promise that the
  compiler produces a finished artifact. Also: many players ignore
  mp4 soft-subs.
- Burn-before-concat rejected: subtitles aren't owned by individual
  video clips — they're a parallel track with their own timeline
  positions. Mapping each subtitle to "the video clip it falls
  inside" requires time-window joins that get wrong at clip edges,
  and adds ordering constraints on the filter chain (one drawtext per
  clip-segment). A single post-concat drawtext chain is simpler and
  is exactly how professional editors describe captions conceptually
  ("a track on top of the composite"). The `enable=` gate replaces
  per-clip scoping without needing to know clip boundaries at all.

**E2E test: auto-skip on ffmpeg builds without libfreetype.** The
`drawtext` filter is only compiled into ffmpeg when libfreetype is
linked in. Many minimal builds (e.g. the default homebrew bottle as
of ffmpeg 8.1 on macOS) omit freetype, making the filter simply
unavailable ("No such filter: 'drawtext'"). Rather than make the
CI/dev test fail on every machine with a stripped ffmpeg, the new
`renderWithSubtitleProducesVideo` E2E probes `ffmpeg -filters` for
drawtext and skips if it's missing. Unit tests
(`DrawtextChainTest`) verify the filtergraph string generation
without needing ffmpeg to be complete, so escape-bug regressions
still get caught in CI. The gap is noted in CLAUDE.md "Known
incomplete" so future contributors know what a caption-less export
means.

**Stderr surfacing improvement (piggyback).** The render loop now
keeps a rolling tail (~40 lines) of non-progress stderr lines and
includes the filtered subset (error/AVFilterGraph/invalid-argument
lines) in `RenderProgress.Failed.errorMessage`. Previously failures
surfaced as just "ffmpeg exited 8", forcing the user to re-run by
hand with `-loglevel debug`. Adding the tail caught the drawtext
gap immediately during this task's E2E dev loop — cheap enough to
justify landing in the same commit.

**Cross-platform status.** FFmpeg now renders subtitles. Media3
(Android) and AVFoundation (iOS) engines still ignore Subtitle
tracks — same gap shape as filter rendering on those engines.
Those are separate tasks on the §5.2 compiler-parity track.

---

## 2026-04-19 — `update_character_ref` / `update_style_bible` / `update_brand_palette` (surgical source edits)

**Context.** VISION §5.4 asks for a professional-user path where the
agent (or user) can make precise, field-level edits on consistency
nodes — "change Mei's hair to red" or "pin Mei to the alloy voice" —
without re-asserting the whole node. The `define_*` tools already
shipped in a "create or replace" shape: `define_character_ref`
requires `name + visualDescription` every call, so patching only
`voiceId` meant the agent had to read the node, copy the fields it
wanted to keep, and re-send them. That's brittle (silent drift on
fields the agent forgets to copy) and noisy in the agent transcript.

**Decision.** Three new tools, one per consistency kind:
`UpdateCharacterRefTool` / `UpdateStyleBibleTool` /
`UpdateBrandPaletteTool`. Each takes `projectId + nodeId` plus the
body fields it wants to patch, all optional, with "at least one must
be set" as the guard. Unspecified fields inherit from the current
node; the merged body is written back via `replaceNode` so
`contentHash` bumps and downstream clips go stale the same way a
redefinition would. Registered in all four composition roots and
documented in the system prompt alongside the `define_*` block.

**Why three typed tools, not one polymorphic `update_source_node`.**
Considered a single tool that takes `nodeId + bodyOverrides:
JsonObject` and merges a JSON patch onto the stored body. Rejected:

1. The LLM would need to know the body schema for each kind to
   construct the override. That duplicates schema knowledge into the
   prompt, defeating the benefit of typed tools.
2. The `JsonObject` shape on the LLM side is fuzzy — no
   per-field descriptions, no per-kind validation (e.g. hex color
   format, list non-emptiness). Three typed tools keep per-field
   validation in the tool input schema where the agent can actually
   see it.
3. Three tools lets each carry the kind-specific knobs cleanly:
   `clearLoraPin`, `voiceId=""`-as-clear, hex-color validation,
   "hexColors cannot be cleared" — these each belong to exactly one
   kind.

The cost is ~400 LOC of parallel structure across three files
instead of one generic patcher. Worth it for the LLM-UX gain.

**Semantics of optional fields.** Shared pattern across all three
tools:
- Scalar strings (`name`, `visualDescription`, `description`): `null` →
  keep, non-blank → replace, blank string rejected at input time.
  Blank would roundtrip to nonsense; "clear" isn't a valid state for
  these anchor fields.
- Optional strings (`voiceId`, `lutReferenceAssetId`,
  `negativePrompt`): `null` → keep, `""` → clear, non-blank → set.
  Matches the `define_*` tools' already-established "blank = unset"
  idiom.
- Lists (`referenceAssetIds`, `moodKeywords`, `typographyHints`,
  `parentIds`): `null` → keep, `[]` → clear, non-empty → replace.
  Full-list replacement (not per-item patch) because lists here are
  meaningful wholes — a reference-image set or a mood-keyword stack.
- `hexColors`: special — non-empty replace only. A palette with zero
  colors is a data-model error, so the tool rejects `[]` with a
  pointer to `remove_source_node` for the actual "delete the
  palette" intent.
- `loraPin`: `null` → keep, object → replace the full pin (adapterId
  required), `clearLoraPin=true` → drop the pin. `clearLoraPin` +
  `loraPin` in the same call is rejected at input time so the
  intent is unambiguous.
- `parentIds`: reuses `resolveParentRefs` for validation — same
  no-self-ref / must-resolve rules as the `define_*` tools.

**Why not extend `define_*` with "if exists, merge instead of
replace"?** Considered making `visualDescription` optional on
`define_character_ref` when the nodeId already exists, so the same
tool could create or patch. Rejected:

1. Overloads the semantic of "define" — "define X" should read as
   "assert X's full identity", not "patch X if you can figure out
   whether it exists". Separate verbs match the mental model.
2. The JSON-schema for the tool would have to mark required fields
   as conditionally required (`name` required only if node doesn't
   exist), which most LLMs struggle to honour reliably.
3. Creation-vs-update is a decision the agent should make
   consciously. Forcing separate tools makes the intent legible in
   the transcript ("the agent chose to update, not redefine") and
   avoids a class of accidental overwrites.

**Why no `replaced`-style output.** The `define_*` tools return
`replaced: Boolean`; the update tools return `updatedFields:
List<String>` instead — the list of body fields the caller touched.
Gives the agent exact feedback about what propagated. More useful
than a boolean here because "update" has no create branch.

**Alternatives considered.**
1. *Field-level tools per kind (e.g.
   `UpdateCharacterRefVoiceIdTool`, `UpdateCharacterRefLoraTool`).*
   Rejected — one tool per field would explode the registry
   (character_ref alone has 6 body fields → 6 tools × 3 kinds = 18
   tools). Doesn't scale when body schemas grow.
2. *JSON-Patch (`op=replace`, `path=/voiceId`) syntax.* Rejected —
   extra cognitive load on the LLM, and the validation story (is the
   path valid? is the value type-compatible?) is worse than a typed
   schema per kind.
3. *Auto-derive update tools from body serializers via reflection.*
   Too clever; KMP's common-main reflection support is limited and
   the generated schema wouldn't carry field-level prose. The
   parallel structure across three files is fine to maintain by
   hand today.

---

## 2026-04-19 — `fade_audio_clip` (audio envelope editor)

**Context.** `set_clip_volume` ships a steady-state level knob for audio
clips but no attack/release. The natural follow-up requests ("fade the
music in over 2s", "2s fade-out", "swell in, duck for dialogue, fade
out") had no completion path — an agent could mute a clip but not shape
how it starts or ends. `Clip.Audio` carried `volume` but no fade fields,
so the envelope had no place to live even if a tool existed.

**Decision.** Two coordinated changes:

1. Extend `Clip.Audio` with `fadeInSeconds: Float = 0f` and
   `fadeOutSeconds: Float = 0f`. Default `0f` means "no fade", backward
   compatible with every existing stored project (JSON blob columns with
   `ignoreUnknownKeys = true` + Kotlin default values roll forward
   cleanly).
2. New `core.tool.builtin.video.FadeAudioClipTool` → id
   `fade_audio_clip`, permission `timeline.write` (ALLOW). Input:
   `(projectId, clipId, fadeInSeconds?, fadeOutSeconds?)`. Each field
   optional; at least one must be set. Unspecified fields keep the
   clip's current value. Emits `Part.TimelineSnapshot` for
   `revert_timeline` parity. Registered in all four composition roots
   (desktop / server / Android / iOS).

**Why two fields on the clip, not a single `AudioEnvelope` struct.**
Considered modelling `fadeIn` / `fadeOut` as a nested `AudioEnvelope(...)`
object (room to grow: ramp shape enum, pre-delay, sidechain duck). Rejected
for today: no concrete driver for those extensions, and a nested object
requires a new serializer while two primitive floats roll forward from
existing stored blobs for free. If ramp shape or ducking becomes real, a
follow-up can lift these into a struct — the field names are already the
ones an envelope would carry.

**Why "keep current on omit", not "default to 0 on omit".** A user
saying "add a 2s fade-in" should not silently clobber a fade-out that
was set earlier. The setter merges input onto the clip's existing
values, matching `set_clip_transform`'s established pattern. `0.0`
explicitly disables a side — that's the in-band "remove the fade"
signal, distinguishable from omission.

**Why `fadeIn + fadeOut ≤ duration`.** Overlapping fades have no
well-defined envelope — what does "fade in for 3s, fade out for 3s" on
a 4-second clip render as? Rejecting loudly beats silently clamping,
which would hide the agent's / user's miscount. The guard uses a 1e-3
epsilon to let equal-duration fades (fadeIn + fadeOut == duration,
common for short stings) pass despite float noise.

**Why audio-only, with no sibling for video.** Video clips don't carry
audio fields in the current data model (a clip's "audio" is either its
source track, mixed opaquely by the renderer, or a separate audio
clip). Extending fade-in/out to `Clip.Video` would cross into the
missing "video clip audio track mixer" territory — a bigger scope that
deserves its own design. Rejecting video here with a loud error keeps
the abstraction honest: the tool is about the `Clip.Audio` envelope,
not a cross-clip audio mixer.

**Why text clips are also rejected.** `Clip.Text` has no notion of
amplitude, so a fade is meaningless. Rejecting matches
`set_clip_volume`'s established shape and keeps the agent from
reaching for the tool to "fade a subtitle" (the caller probably wants
`transforms.opacity` via `set_clip_transform`).

**Why the field is captured in Project state even though no engine
renders it yet.** Same "compiler captures intent, renderer catches up"
pattern as `set_clip_volume` and `set_clip_transform`: the Project is
the canonical edit state, and renderers are free to lag. Shipping the
tool first means the agent can accept and record fade requests today;
the FFmpeg / AVFoundation / Media3 engine passes are tracked as known
follow-ups (the system prompt discloses this explicitly so the agent
doesn't over-promise render fidelity).

**Alternatives considered.**
1. *Fold fade into `set_clip_volume` as optional `fadeInSeconds` /
   `fadeOutSeconds` fields.* Rejected — overloads "set level" with "set
   envelope", produces a tool whose name no longer describes its job.
   Separate tools stay composable (agent can mute now, add fade later).
2. *Model fade as a timeline-side automation curve instead of a clip
   field.* Rejected for now — automation curves are a bigger scope
   (needs keyframe infra, curve editor, cross-clip automation targets).
   Clip-bound fade fields cover the 80% case cheaply; curves can live
   in the same clip later via a new `volumeCurve` field without
   breaking the fade shorthand.

---

## 2026-04-19 — `set_clip_transform` (visual transform editor)

**Context.** `Clip.transforms: List<Transform>` has existed since M0 and
every clip carries it (`translateX/Y`, `scaleX/Y`, `rotationDeg`,
`opacity`). No tool ever set it, so the field was dead state. Requests
like "fade the watermark", "make the title smaller", "move the logo to
the corner for PiP", or "rotate the card 10°" had no completion path —
the only option was `remove_clip` + re-`add_clip`, which `add_clip`
doesn't expose transform knobs for either. Parallel to the
`set_clip_volume` gap for audio: the field was there, the setter
wasn't.

**Decision.** New `core.tool.builtin.video.SetClipTransformTool`. Tool
id `set_clip_transform`, permission `timeline.write` (ALLOW). Input:
`(projectId, clipId, translateX?, translateY?, scaleX?, scaleY?,
rotationDeg?, opacity?)` — every knob optional, at least one must be
set. Emits `Part.TimelineSnapshot`. Registered in all four
composition roots (desktop / server / Android / iOS).

**Why one "setter of many fields" tool, not four sub-tools.**
Considered `set_clip_opacity` / `set_clip_scale` / `set_clip_position` /
`set_clip_rotation` as separate tools (parallel to `set_clip_volume`'s
single-knob shape). Rejected: user intents like "scale the logo to
40% AND position it top-right" are one mental step but would require
two tool calls, each with its own snapshot. One tool with optional
knobs composes the common case naturally. The cost is a fatter
schema; acceptable since the LLM only sends the fields it cares
about.

**Why merge overrides onto the current transform, not replace fully.**
A user saying "fade the watermark to 0.3" means "opacity=0.3, leave
everything else". If the tool replaced the whole transform with
`Transform(opacity=0.3f)`, the clip's existing scale / position would
silently reset. Merging preserves context. Unspecified fields inherit
from `clip.transforms.firstOrNull()` — if absent, they inherit from
`Transform()` defaults.

**Why normalize `transforms` to a single-element list, not append.**
The `List<Transform>` shape was designed for a hypothetical
composition stack (translate-then-scale-then-rotate as ordered passes).
No renderer actually consumes that ordering today. Modeling "the
clip's transform" as one record matches the user's mental model and
keeps the tool idempotent: calling it twice with the same input
produces the same state. If ordered composition ever becomes a real
concern, a second `push_transform` tool can own that semantic without
breaking this one.

**Clamps.**
- `opacity ∈ [0, 1]` — anything outside is meaningless on screen.
- `scaleX` / `scaleY > 0` — zero collapses, negative is an unsupported
  mirror (a real `flip_clip` tool would own that cleanly).
- `rotationDeg` unclamped — float is valid, renderers take mod 360.
- `translateX/Y` unclamped — units are engine-defined (pixels on
  FFmpeg/AVFoundation, normalized on Media3). Clamping here would
  bake the wrong model.

**Why no block on audio clips.** `Clip.Audio` inherits the `transforms`
field from the base class. Writing it is dead state at render time
(audio has no visual), but the data model permits it. Rejected
blocking (symmetrical to `set_clip_volume` blocking video) because
the field is genuinely there, and gating adds a surface-area
inconsistency: "sometimes-allowed field" creates more cognitive load
than "always-allowed, no-op on audio." The system prompt states
explicitly that audio calls are no-op at render time so the model
doesn't reach for it by mistake.

**Alternatives considered.**
1. *Do nothing, wait for a UI that sets transforms directly.* Rejected —
   VISION §4 "agent-first" means every editable field needs a tool;
   tools are the only edit surface. A UI eventually calls the same
   tool, not a parallel path.
2. *Expose the full `List<Transform>` as input (advanced API).*
   Rejected — no current consumer benefits, and the schema becomes
   nested / ambiguous (what does "replace index 2" mean?). Ship the
   simpler setter first; grow if a user actually needs a stack.

**Test coverage.** 10 tests in `SetClipTransformToolTest` using a real
`SqlDelightProjectStore`: happy-path opacity set, partial merge (preserve
inherited fields), list-normalisation from multi-transform state, text-clip
support, filter / source-binding / timeRange preservation, no-op rejection
(all-null inputs), out-of-range opacity (> 1 and < 0), non-positive scale,
missing-clip fail-loud, snapshot emission.

---

## 2026-04-19 — `extract_frame` (video → still image helper)

**Context.** `describe_asset` (VISION §5.2 ML lane) is images-only by design —
the vision engine fails loudly on video or audio inputs. So the request
"describe what's happening at 00:42 in this clip" had no completion path:
the agent would have to fall back to prose, or silently skip. The same gap
hit `generate_image` / `generate_video`, whose `referenceAssetPaths`
channel accepts images but not timestamps-into-videos. A primitive that
turns `(videoAssetId, time)` into a new image assetId closes both.

**Decision.** New `core.tool.builtin.video.ExtractFrameTool`. Tool id
`extract_frame`, permission `media.import` (ALLOW by default). Input:
`(assetId: String, timeSeconds: Double)` — fails loudly on negative time
or time beyond the source's recorded duration. Output: new image assetId
plus inherited `(width, height)` from the source. Registered on desktop,
server, **and Android** — the Android container gained a new
`AndroidFileBlobWriter` (cache-tier, under `context.cacheDir/talevia-generated`)
so it can participate. iOS still skips (no `MediaBlobWriter` wired there
yet; AIGC tools have the same gap).

**Why delegate to `VideoEngine.thumbnail`, not add a new engine surface.**
`thumbnail(assetId, source, time) -> ByteArray` already exists on all
three engines (FFmpeg/JVM, AVFoundation/iOS, Media3/Android) — it's what
the timeline preview uses. Reusing it means zero new platform code: every
existing engine already knows how to seek and encode a PNG. Adding a
`FrameExtractEngine` would have been parallel scaffolding.

**Why `media.import` permission (ALLOW), not `ml.describe` (ASK).** This
is a local-only derivation — no network egress, no provider cost, no
user-visible risk beyond disk space. `import_media` sits in the same
bucket for the same reason. ASKing on every frame grab would turn the
describe-a-video chain into a three-prompt dance.

**Why embed in `core.tool.builtin.video/`, not `.../ml/`.** ExtractFrame
doesn't talk to any ML provider — it's a traditional media operation
that happens to feed ML tools. Grouping with `SplitClipTool` /
`TrimClipTool` matches the "transforms one asset into another via the
VideoEngine" shape. Symmetric to `import_media` which also lives under
`video/` despite being modality-agnostic.

**Why inherit source resolution onto the still.** The frame really is at
the source's pixel dimensions — FFmpeg / AVFoundation / Media3 all emit
native-resolution PNGs from `thumbnail`. Recording `resolution=null`
would throw away information the caller needs to decide whether to
downscale before a `generate_image` reference call. If a caller wants a
thumbnail-sized still they can scale downstream; the tool deliberately
doesn't pre-lose data.

**Why no lockfile cache key.** v1: frame extraction is cheap and
deterministic-given-engine, not provider-cost. A lockfile entry exists to
replay seeded provider calls; reusing an extract_frame result instead
means keying the cache on `(engine version, asset contentHash, time)`
and materialising the frame as a project artifact, which is a lot of
machinery for a millisecond FFmpeg seek. Revisit if users report a
real-world cost.

**Alternatives ruled out.**
1. *Bundle into `describe_asset` (auto-frame-grab on video inputs).* Keeps
   the agent UX smooth but couples modalities and complicates permissions
   (`describe_asset` is ASK, `extract_frame` is ALLOW — merging forces the
   loudest of the two). Also makes the describe call non-deterministic in
   its artifact count: the user can't `list` the extracted still if it
   was produced as a side-effect.
2. *Frame-grab as a subtitle / generate_image input-type extension.* Every
   tool that wants stills would have to learn timestamp semantics
   independently. Extracting once and passing an assetId around is the
   composable shape.
3. *Image sequence / animated output ("extract frames over range").*
   Out-of-scope v1 — there is no consumer tool (animation generation
   doesn't exist yet). Revisit once an `animate_frames` / motion-interp
   tool shows up.

**Scope of test coverage.** 6 tests via a FakeVideoEngine returning stub
PNG bytes: happy-path registration, resolution inheritance, null-resolution
fallback, negative timestamp rejection, past-duration rejection, unknown
asset rejection. Engine invocation is verified (lastTime / lastAsset) so a
future refactor can't silently drop the timestamp forwarding.

---

## 2026-04-19 — `set_clip_volume` (audio-clip volume editor)

**Context.** `Clip.Audio.volume` was settable at construction (`add_clip`
records the asset's natural level) but had no post-creation editor. "Lower
the background music to 30%" / "mute the second vocal take" are basic
editing requests that previously required `remove_clip` + re-`add_clip`,
which loses downstream `sourceBinding`, filters, and every other attached
field. The cut/stitch/filter/transition lineup had `trim_clip` and
`move_clip` as in-place edits — volume was the missing knob.

**Decision.** New `core.tool.builtin.video.SetClipVolumeTool`. Tool id
`set_clip_volume`, permission `timeline.write`. Input:
`(projectId, clipId, volume: Float)`. Volume is an absolute multiplier in
`[0, 4]`: `0.0` mutes, `1.0` unchanged, up to `4.0` amplifies. Emits a
`Part.TimelineSnapshot` so `revert_timeline` can undo. Registered in all
four composition roots (server / desktop / Android / iOS).

**Why absolute multiplier, not delta or dB.** Matches `Clip.Audio.volume`'s
native unit exactly — the tool is a setter over a field that already uses
multiplier semantics. A dB surface (e.g. `"-12dB"`) would require parsing +
conversion and introduce sign ambiguity ("+3dB on a 0.5 clip?"). Deltas
would surprise ("add 0.1" to something already at 1.0 turns amplification
on). Absolute matches the sibling edits (`move_clip.newStartSeconds`,
`trim_clip.newSourceStartSeconds`) — "set X to Y" vocabulary across the
board.

**Why cap at 4.0 (≈ +12dB).** Most renderers (ffmpeg's `volume` filter
included) clip beyond that, and clip-level gain above 4× almost always
means the user really wants mix-stage gain staging (compression, EQ, bus
routing) rather than a raw multiplier. Failing loud at 4.0 surfaces the
right conversation instead of silently producing distortion.

**Why fail loud on non-audio clips.** Video clips have no `volume` field
today (track-level mixing is a future concern once we model audio rails),
and Text clips obviously have no audio. A silent no-op would teach the
agent that "apply volume to this clip" can succeed without doing anything;
failing loud keeps the tool's contract honest.

**Why `0.0` mutes instead of removing.** Mute-without-remove preserves the
clip id (stable references from automation / future fades / source
bindings) and keeps the clip visible in the UI as "something the user can
un-mute". The scalpel for full removal is already `remove_clip`.

**Tests.** Exercised by the pre-existing `SetClipVolumeToolTest`. No
architectural decisions exposed there — same `ProjectStore.mutate` + snapshot
shape as `move_clip` / `trim_clip`.

**System prompt.** New "# Audio volume" paragraph teaches the multiplier
range, the audio-only scope, and the mute-vs-remove distinction. Key
phrase `"set_clip_volume"` added to `TaleviaSystemPromptTest`.

---

## 2026-04-19 — `describe_asset` (VISION §5.2 ML lane — image counterpart to ASR)

**Context.** The ML enhancement lane had one modality wired: `transcribe_asset`
for audio → text. Image → text (describe / caption / extract visible text /
brand-check) was a blank spot despite being a high-traffic use case —
"what's in this photo?", "pick the best import for the intro", "read this
image and lift the caption into a character_ref's visualDescription". With
Vision-class multimodal LLMs now commodity at provider-level (gpt-4o-mini
is ~$0.15 per 1M input tokens), shipping the image side of the pair
completes VISION §5.2's "vision/multimodal" bullet.

**Decision.** New `core.platform.VisionEngine` interface in `commonMain` +
`OpenAiVisionEngine` in `jvmMain` + `core.tool.builtin.ml.DescribeAssetTool`.
Tool id `describe_asset`, permission `ml.describe` (ASK — bytes exfiltrated).
Input: `(assetId, prompt?, model="gpt-4o-mini")`. Output: text description
plus the standard provider/model provenance. Engine wired conditionally on
`OPENAI_API_KEY` in desktop + server containers, same pattern as
`imageGen` / `asr` / `tts` / `videoGen`.

**Why a separate engine instead of extending `AsrEngine`.** The obvious
parallel ("transcribe_asset handles both audio → text and image → text") is
false: ASR and vision are different providers, different endpoints, different
parameter vocabularies (language hints / timestamps for audio; focus prompts
/ image-urls for vision), and `AsrResult.segments` is meaningless for a
still image. Forcing them under one interface would leak modality-specific
fields across both implementations. Keeping them as sibling engines mirrors
the AIGC side, where `ImageGenEngine` / `VideoGenEngine` / `TtsEngine` are
distinct interfaces.

**Why `jvmMain` and not `commonMain`.** Vision needs raw image bytes (base64
into `data:image/...;base64,...` for the OpenAI API). `java.io.File.readBytes()`
is the simplest way to get those on the JVM; doing it in `commonMain` would
require a KMP file-IO abstraction we don't have yet. Same call as the
Whisper engine — architecture rule #1 still holds because the interface
lives in `commonMain`; only the translation lives beside the other
`OpenAi*Engine`s.

**Why images only, not video frame-grab.** The Vision API doesn't accept
video; to "describe a moment in a video" the caller would have to grab a
frame first via `VideoEngine.extractFrame` or an equivalent. We could bury
that dependency inside the tool, but that would couple `DescribeAssetTool`
to `VideoEngine` and complicate the permission model ("ml.describe" implies
"timeline read or render access"). Failing loudly on non-image files keeps
this tool single-responsibility; when frame-grab describe becomes a real
workflow, it becomes its own tool that composes extract-then-describe.

**Why no project lockfile cache (v1).** `LockfileEntry` keys generated
**assets**, not derived text. Describe outputs text directly back to the
agent — there is no asset id to cache against. Same open we left on
`transcribe_asset`: if repeat-describe becomes common, materialize the
description as a JSON asset and key a lockfile entry off it. No speculative
scaffolding for today.

**Why default model `gpt-4o-mini`, not `gpt-4o`.** Describe is an
enhancement not a generation — mini is ~15× cheaper, fast, and has been
shown to match 4o on describe-quality benchmarks within noise. The agent
can opt into `gpt-4o` via the `model` parameter when the user needs
fine-grained detail ("read the small text on this label"). Pattern matches
OpenCode's default-to-mini-for-tool-facing-vision convention.

**Why `prompt` optional (default: generic describe).** Two use patterns:
"what's in this image?" (no focus) and "what brand is on the mug?"
(focused). Requiring a prompt would force the agent to invent one for the
generic case; allowing null lets the engine substitute a well-tuned default
("Describe this image. Note the subject, setting, notable colors / lighting,
and any text visible."). Blank-string is treated as null — defensive
because LLM tool schemas sometimes emit `""` when they mean omit.

**Tests.** 5 cases in `DescribeAssetToolTest` using a `RecordingVisionEngine`
fake: default-path-and-model resolution, custom prompt + model forwarding,
blank-prompt-is-omit, long-text preview ellipsis, short-text no ellipsis.
No real OpenAI call — engine translation is exercised by the integration
matrix, not unit tests.

**Registration.** desktop + server containers, conditional on
`OPENAI_API_KEY`. Android + iOS composition roots do NOT register it —
the `OpenAiVisionEngine` is `jvmMain`-only by design. When native vision
providers land (Vision framework / ML Kit) they'll fill the iOS /
Android gaps via the same `VisionEngine` interface.

**System prompt.** New "ML enhancement" paragraph teaches the pair:
`transcribe_asset` for audio, `describe_asset` for images, with explicit
callouts for (a) the character_ref scaffolding pattern, (b) images-only
scope, (c) user-confirmation on bytes upload. `describe_asset` added to
the Compiler mental model alongside `transcribe_asset`.

---

## 2026-04-19 — `import_source_node` (VISION §3.4 — closes "可组合")

**Context.** §3.4 names four codebase properties for Project / Timeline:
可读, 可 diff, 可版本化, 可组合. After snapshot / fork / diff landed, the
first three were covered. "可组合 (片段 / 模板 / 特效 / 角色可跨 project
复用)" was the only unfilled leg — the agent had no way to lift a `character_ref`
defined in a narrative project into a vlog project without retyping the body.
`fork_project` copies a whole project, which is the wrong tool for "share
one character".

**Decision.** New `core.tool.builtin.source.ImportSourceNodeTool` (id
`import_source_node`, permission `source.write`). Inputs: `(fromProjectId,
fromNodeId, toProjectId, newNodeId?)`. Walks the source node + its parent
chain in topological order, inserts each one into the target project, returns
`(originalId, importedId, kind, skippedDuplicate)` per node. Wired into all
four composition roots (server / desktop / Android / iOS).

**Why content-addressed dedup, not id-addressed.** `SourceNode.contentHash`
is a deterministic fingerprint over `(kind, body, parents)`. The AIGC
lockfile keys cache entries on bound nodes' content hashes (not their ids).
So when an imported node lands with the *same* contentHash as the source,
every previous AIGC generation that referenced that node is automatically a
cache hit on the target side too — without the agent doing anything special.
The alternative — keying on id — would force the user to use the same id on
both sides, and would still miss when ids legitimately differ ("Mei" vs
"character-mei-v2") even though the bodies are identical.

**Why reuse + remap parent refs when a parent is deduped to an existing
target node under a *different* id.** Real example: the source's `style-warm`
parent matches the target's pre-existing `style-vibe-1` by contentHash. We
reuse the existing node (no insertion) and remap the leaf's `SourceRef` to
point at `style-vibe-1`. The alternative — refusing to remap and inserting a
duplicate `style-warm` — would create two source nodes with identical content
but different ids in the same project, defeating the dedup discipline that
makes lockfile cache transfer work.

**Why fail loudly on same-id-different-content collision instead of auto-rename.**
If the target already has `character-mei` with different content, we throw
with a hint to pass `newNodeId` or `remove_source_node` first. The
alternative (silent suffix-rename to `character-mei-2`) would create
unobvious id divergence — a future binding referencing `character-mei` would
quietly resolve to the *original* version, not the just-imported one. Forcing
the agent to make the conflict explicit is worth one extra round-trip.

**Why `newNodeId` only renames the leaf, not parents.** The common case is
"I want to import the Mei character into the vlog project but the vlog
already has a `character-mei` for someone else". The leaf is what the user
named; parents are derived. Per-parent renaming would multiply the input
surface for a case the agent will rarely hit (today's consistency nodes are
leaves). When richer source schemas land and parent collisions become real,
the caller can break the import into two: `import_source_node(parent, ...,
newNodeId=...)` then `import_source_node(leaf, ...)` lets the parent-dedup
path remap the leaf's refs.

**Why reject self-import (`from == to`).** Nearly always a mistake. The
agent that wants a within-project copy already has `define_character_ref` /
`define_style_bible` / `define_brand_palette` with a fresh id. Failing
loudly costs one corrected round-trip and prevents the silent no-op that
content-addressed dedup would otherwise produce.

**Why permission `source.write`, not `project.write`.** This tool only
mutates `Project.source`; it does not touch the timeline, lockfile, render
cache, or asset catalog. Aligning with `define_*` / `remove_source_node`
keeps the permission ruleset clean — the user can grant blanket source
edits without authorising broader project mutations.

**Tests.** 9 cases in `ImportSourceNodeToolTest`: leaf import, idempotent
re-import, topological parent walk, parent-dedup remapping, same-id-
different-content failure, `newNodeId` rename, self-import rejection, missing
source/target project, missing source node.

---

## 2026-04-19 — Timeline tool parity on Android + iOS

**Context.** Desktop and server containers register `ApplyFilterTool`,
`AddSubtitleTool`, and `AddTransitionTool`; Android and iOS did not. The
result: the agent on mobile couldn't express "apply a vignette", "add a
line of subtitle text", or "cross-dissolve between clips A and B", even
though all three tools are pure commonMain state mutators on
`Project.timeline` with no platform-specific plumbing whatsoever.

**Decision.** Register all three on `AndroidAppContainer` and iOS
`AppContainer.swift`. Zero new code, just composition.

**Why this isn't blocked by the known Android/iOS engine gap.**
CLAUDE.md calls out that `Media3VideoEngine` and `AVFoundationVideoEngine`
currently fall back to no-op for the filter / transition render passes.
That's an **export-time** engine gap, not an **authoring-time** tool gap.
Tool dispatch mutates `Project.timeline` inside core; it has no engine
call. The tools can be authored today; when the engines catch up, existing
project state will render without any project migration. The alternative
(withholding the tools until the engines catch up) would create a lopsided
agent where mobile can't even express intent that desktop can realize.

**Why not also wire AIGC tools (`generate_image`, `synthesize_speech`,
`transcribe_asset`) on mobile.** Those have real platform wiring — an
`HttpClient`, a platform-appropriate `MediaBlobWriter`, secret storage for
the API key. Android has an in-memory `SecretStore` stub; iOS has none.
Wiring AIGC on mobile needs those prerequisites first. Out of scope for
this commit; tracked for a follow-up when mobile secret stores land.

**Surface area.** Two files touched, three `register(…)` calls each.
Compiles against `:apps:android:compileDebugKotlin` and
`:core:compileKotlinIosSimulatorArm64`.

---

## 2026-04-19 — `list_lockfile_entries` tool (VISION §3.1 — agent project orientation)

**Context.** The lockfile has been load-bearing since the AIGC lane landed:
`find_stale_clips` reads it to answer "what needs regenerating?",
`generate_image` / `synthesize_speech` write to it for cache hits. But the
agent had no way to introspect it — no answer to "what have we generated so
far?", "do we already have a Mei portrait we can crop instead of
re-generating?", or "show me the last 5 TTS calls so I can reuse a voice
line". Without that orientation step, planning tools get proposed that
duplicate existing artifacts.

**Decision.** New read-only tool `core.tool.builtin.project.ListLockfileEntriesTool`.
Input `(projectId, toolId?, limit=20, max=200)`. Returns entries most-recent
first with `(inputHash, toolId, assetId, providerId, modelId, seed,
createdAtEpochMs, sourceBindingIds)`. Permission `project.read`.

**Why most-recent-first in the response but append-only on disk.** The
lockfile's natural ordering is insertion-order (an audit trail — append-only).
But the agent's dominant query shape is "what did I generate recently?", so
reversing client-side saves the model a re-sort. The on-disk ordering stays
canonical; only the tool response is flipped.

**Why `toolId` filter instead of a kind/modality enum.** The lockfile records
tool ids verbatim (`"generate_image"`, `"synthesize_speech"`). A higher-level
enum (`image | audio | video`) would drift out of sync with the tool
registry every time a new AIGC tool lands. Filtering by the raw tool id
means the schema stays stable as the compiler surface grows.

**Why no cursor/pagination.** v0 target is projects with <1000 entries;
the 200-entry cap is enough for interactive orientation. When a user's
lockfile outgrows that, `limit` already supports exact slicing by the caller,
and real pagination can be added as a `offset` input without schema break.

**Placement under `project/`, not `aigc/`.** The lockfile is per-project
state — the same organization as `find_stale_clips` (also lockfile-driven).
AIGC tools *produce* entries; project tools *query* them. Co-locating the
query with the snapshot/fork/state tools keeps the agent's "planning" lane
in one namespace.

**System prompt + regression guard.** System prompt gains a short paragraph
naming the tool + the two canonical use cases (orientation, reuse).
`TaleviaSystemPromptTest` asserts `list_lockfile_entries` still appears so a
prompt-refactor can't silently drop it.

**Surface area.** Wired into all 4 composition roots (server, desktop,
Android, iOS).

---

## 2026-04-19 — `replace_clip` tool (VISION §3.2 — regenerate-after-stale)

**Context.** With `find_stale_clips` (this morning's commit) the agent can answer
"what needs regenerating?", and `generate_image` produces the new asset. But
there was no tool to splice the new asset back into the timeline — every clip
mutation tool (`split`, `apply_filter`, `add_subtitle`, …) leaves the asset id
fixed. The agent's only options were to `add_clip` again (creates a duplicate)
or do nothing. The DAG → query → re-render workflow stopped one step short of
a complete loop.

**Decision.** New tool `core.tool.builtin.video.ReplaceClipTool` — input
`(projectId, clipId, newAssetId)`, swaps `Clip.Video.assetId` /
`Clip.Audio.assetId` in place. Position (`timeRange`), trim (`sourceRange`),
transforms, filters, audio volume — all preserved. Permission `timeline.write`
(same bucket as the other clip mutators).

**Side effect on `Clip.sourceBinding`.** When the new asset has a lockfile
entry with non-empty `sourceBinding`, copy that binding onto the replaced
clip. Three reasons:

1. The agent regenerated this asset *because* a source changed — it would be
   nonsense to leave the clip's binding pointing at the *old* set (or worse,
   `emptySet()`).
2. Future `find_stale_clips` queries route through `Lockfile.findByAssetId`
   anyway, so this side effect is mostly informational. But `Clip.sourceBinding`
   *is* what `Project.staleClips(changed: Set<SourceNodeId>)` (the export-time
   incremental render path, acce14c) uses — keeping it correct means the two
   stale-detection lanes (lockfile-driven + binding-driven) agree.
3. It quietly closes a sub-gap: `add_clip` doesn't thread sourceBinding from
   the AIGC tool's output into the new clip. We deliberately *didn't* fix that
   in the lockfile-driven detector commit (would conflate add_clip with AIGC
   bookkeeping). But on `replace_clip` the relationship is unambiguous —
   you're swapping in *this specific* asset whose binding we already know.

**Why not refactor `add_clip` instead.** Considered: have `add_clip` look up
the asset's lockfile entry and copy its `sourceBinding`. Rejected because:

- `add_clip` is also the tool for hand-authored / imported clips that have no
  lockfile entry — every call would do a wasted lookup.
- The semantic of `add_clip` is "place this asset on the timeline at this
  spot." Adding "and also, by the way, copy its DAG bindings" muddies the
  purpose. `replace_clip` is *explicitly* about a regenerate flow, so the
  binding copy is on-theme.

If a future "always thread bindings" decision lands (e.g. the binding becomes
load-bearing for incremental render even on first-add), `add_clip` can adopt
the same `findByAssetId` lookup; the helper is on `Lockfile` already.

**Why text clips are rejected loudly.** `Clip.Text` has no `assetId` — it
carries the text inline in the model. Asking the tool to "replace its asset"
is meaningless. Erroring beats silently no-op'ing because the agent's plan
("replace clip X") would otherwise look like it succeeded with no observable
effect.

**Why preserve `sourceRange` instead of resizing.** A regenerate produces a
new asset whose duration may differ from the old one. Two options:

- **Resize the clip** to the new asset's full duration. Friendly but
  destructive — a previous `split_clip` chose those exact endpoints, and
  silently overwriting them re-asks the agent to re-split.
- **Preserve `sourceRange`** and clamp at render time if the new asset is
  shorter. Conservative but predictable.

Picked the second. If the regenerated asset *is* a different length and the
agent wants to honour that, it can call `split_clip` / a future `resize_clip`
explicitly. The principle: tool inputs should change exactly what they
declare, no more.

**Surface area.** Wired into all 4 composition roots (server, desktop,
Android, iOS). System prompt updated to teach the full workflow:
edit character → `find_stale_clips` → `generate_image` → `replace_clip`.

**Tests.** `ReplaceClipToolTest` covers six paths: video preserve-everything,
audio preserve-volume, text-clip rejection, missing clip, missing asset, and
the source-binding copy-from-lockfile case (the one that proves the
regenerate-flow side effect).

---

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

## 2026-04-19 — TTS synthesis tool (VISION §5.2 — AIGC audio lane)

**Context.** With image-gen (visual) + ASR (audio→text) shipped, the AIGC
audio lane (VISION §2 — "AIGC: TTS / 声音克隆") was the last empty quadrant of
the compiler matrix. Without TTS, the agent can't author voiceovers for
narrative shorts (VISION §6.1: "TTS 对白"), can't generate placeholder
narration for vlogs while the user records the real take, and the
ASR↔synthesis round-trip needed to make `add_subtitle` + voice replacement
workflows possible doesn't exist. TTS is the highest-leverage next gap by a
clear margin.

**Decision.** Three pieces, mirroring the image-gen lane shape:

- `core.platform.TtsEngine` (commonMain) — `synthesize(TtsRequest): TtsResult`
  with `TtsRequest(text, modelId, voice, format="mp3", speed=1.0, parameters)`
  and `TtsResult(SynthesizedAudio(audioBytes, format), provenance)`.
- `core.provider.openai.OpenAiTtsEngine` (commonMain — JSON in, bytes out, no
  file IO) — `POST /v1/audio/speech` with `{model, input, voice,
  response_format, speed}`. Reads response body as raw bytes via
  `readRawBytes()`.
- `core.tool.builtin.aigc.SynthesizeSpeechTool` (commonMain) — input
  `(text, voice="alloy", model="tts-1", format="mp3", speed=1.0, projectId?)`,
  persists bytes via `MediaBlobWriter`, registers as a `MediaAsset`, surfaces
  the `assetId` for `add_clip`. Lockfile cache keyed on
  `(tool, model, voice, format, speed, text)`.

**Why TTS engine lives in commonMain (not jvmMain like Whisper).** The OpenAI
TTS endpoint is JSON-in / bytes-out — no file upload, no `java.io.File`
needed. Same shape as `OpenAiImageGenEngine`, which is also commonMain.
Whisper is jvmMain only because it uploads multipart-form raw audio bytes
from a path. iOS will get TTS for free as soon as a Darwin HttpClient is
wired into its container.

**Why under `aigc/` (not `ml/`).** VISION §2 explicitly lists TTS under
"AIGC", not "ML enhancement". ML enhancement consumes existing assets and
derives data (transcripts, masks, color); AIGC produces *new* assets from a
prompt. TTS consumes a text prompt and produces a new audio asset → AIGC.
Permission `aigc.generate` (same bucket as image-gen) reflects the same
distinction.

**Why no consistency-binding fold in v1.** OpenAI TTS takes a fixed voice id
with no character-conditioned cloning. `consistencyBindingIds` would have to
either (a) silently ignore character_ref bodies (lying about behavior),
(b) pick the OpenAI voice from a `character_ref.voiceHint` field that doesn't
exist yet, or (c) wait for a real cloning provider (ElevenLabs, future
OpenAI). Picked (c). When the cloning provider lands, `CharacterRefBody`
gains a `voiceId` (or similar) field and this tool starts consuming it the
same way `generate_image` consumes visual descriptions.

**Why seed=0L (sentinel) rather than minting a seed.** The TTS endpoint has
no seed parameter; identical inputs produce identical (or perceptually
identical) audio without one. Minting a client-side seed and recording it
would be theatrical — the value would be ignored by the provider and would
just clutter provenance. A sentinel makes "TTS provenance has no seed" a
visible fact rather than a hidden one. The lockfile hash deliberately omits
the seed so cache hits work on the inputs that actually matter.

**Why `MediaMetadata.duration = Duration.ZERO` on import.** OpenAI TTS
doesn't echo a duration, and there's no portable audio probe in commonMain.
The image engine makes the same compromise for non-image dimensions. Real
duration falls out of the next ffprobe pass when the asset gets used in a
clip — leaving it ZERO until then is honest. Adding an `expect/actual` audio
probe just for this would be premature.

**Cache hit semantics.** Identical
`(tool, model, voice, format, speed, text)` → same `assetId`, no engine
call. Verified by `secondCallWithIdenticalInputsIsLockfileCacheHit`. Mutating
*any* of those fields busts the cache (`changingTextOrSpeedOrFormatBustsTheCache`
covers text + speed + format). Without `projectId` the lockfile is bypassed
entirely (`withoutProjectIdEveryCallHitsTheEngine`) — same opt-in shape as
image-gen.

**System prompt.** Added a short "AIGC audio (TTS)" paragraph naming
`synthesize_speech` and the round-trip with `transcribe_asset`. Regression
test asserts the phrase still appears so a refactor can't silently drop it.

**When to revise.** When voice cloning lands → add `voiceId` to
`CharacterRefBody` + start folding consistency bindings into the engine call.
When TTS responses start arriving with structured metadata (duration,
sample-rate) from any provider → promote `SynthesizedAudio` from a bytes blob
to include the metadata so `MediaMetadata.duration` doesn't have to lie.
When chunking long scripts (>4096 chars for OpenAI) becomes a real workflow
→ add a `SynthesizeLongFormSpeechTool` that splits, calls in parallel,
concatenates via the existing audio-stitch path, rather than putting chunking
into the engine itself.

## 2026-04-19 — ASR transcription tool (VISION §5.2 — ML enhancement lane)

**Context.** VISION §5.2 splits the AIGC compiler into two lanes: pure generation
(text→image, text→video, TTS, …) and **ML enhancement** of existing assets
(transcription, denoise, super-resolution, beat detection). Generation has
`OpenAiImageGenEngine` + `GenerateImageTool`; the enhancement lane was empty,
which means any "subtitle this vlog" or "cut to the beat" workflow had no entry
point. ASR is the highest-leverage first enhancement: it unblocks subtitle
authoring (`AddSubtitleTool` already exists but gets text from where?) and is a
clean provider story (one OpenAI endpoint, well-defined output shape).

**Decision.** Three pieces, mirroring the generation lane shape:

- `core.platform.AsrEngine` (commonMain) — `transcribe(AsrRequest): AsrResult`
  with `AsrRequest(audioPath, modelId, languageHint?, parameters)` and
  `AsrResult(text, language?, segments, provenance)`. `TranscriptSegment(startMs,
  endMs, text)` uses ms units to line up directly with `Clip.timeRange`.
- `core.provider.openai.OpenAiWhisperEngine` (jvmMain) — multipart upload to
  `/v1/audio/transcriptions` with `response_format=verbose_json` +
  `timestamp_granularities[]=segment`, parses the verbose JSON into the common
  result type. Sniffs `Content-Type` from the file extension (mp3/wav/m4a/flac/
  ogg/webm/mp4/mov/mpeg → matching mime, else octet-stream).
- `core.tool.builtin.ml.TranscribeAssetTool` (commonMain) — input
  `(assetId, model="whisper-1", language?)`, resolves assetId via
  `MediaPathResolver`, returns the segments + detected language + a short LLM
  preview. Permission `ml.transcribe` defaults to ASK.

**Why a separate `AsrEngine` interface, not `*GenEngine`-shaped.**
`ImageGenEngine` / `VideoEngine` produce *new* assets keyed by prompt + seed.
ASR consumes an existing asset and produces text + structured timestamps — no
seed (the model is deterministic given the audio), no `MediaBlobWriter`
involvement. Shoehorning it into the generation interface would force fake
seeds and a no-op blob writer, both of which are smells. Keeping it parallel
but separate keeps each interface honest about what it does.

**Why JVM-only impl (no commonMain Whisper).** The Whisper API needs raw audio
bytes uploaded as multipart form data. `commonMain` has no portable way to read
arbitrary file bytes — same constraint that put `FileBlobWriter` in jvmMain.
The interface lives in commonMain so iOS/Android can plug in native ASR
(SFSpeech, MediaRecorder + Vosk, on-device CoreML Whisper, etc.) without
breaking the agent contract.

**Why no lockfile cache in v1.** `LockfileEntry` is keyed by `assetId` and
references an output asset id. ASR output is *text + timestamps*, not an
asset. Bolting it into the lockfile would either (a) write the transcript to a
text-blob asset (introduces a new asset kind for limited benefit) or
(b) generalize the lockfile to "text outputs too" (premature — wait until at
least one more enhancement tool wants caching). The Whisper API is also cheap
enough per call that re-running on the same audio is fine for v1. Revisit when
we see real users transcribing the same long audio repeatedly.

**Why iOS/Android containers don't get the tool.** Same reason they don't get
`GenerateImageTool` today: those containers don't yet wire any generative
provider. When iOS gets its first OpenAI engine wired, both AIGC tools and the
ASR tool come along together — same provider key, same conditional pattern.

**Why ASR before text-to-video / TTS.** Three reasons: (1) clean provider
story — Whisper is one well-known endpoint; video gen is a moving target across
providers (Sora, Runway, Veo, Kling) with very different shapes. (2) Lower
friction — text + timestamps round-trip into existing `AddSubtitleTool`; video
gen needs blob storage + clip-creation plumbing. (3) Fills the *empty* ML
enhancement lane in §5.2; image gen already exists for the generation lane.

**System prompt.** Added a short "ML enhancement" paragraph naming
`transcribe_asset` so the agent knows the tool exists. `TaleviaSystemPromptTest`
asserts the phrase still appears so a refactor can't silently break it.

**When to revise.** When a second ASR provider lands (Deepgram, AssemblyAI,
on-device whisper.cpp), keep the interface stable and add a sibling engine.
When transcription becomes a hot path on the same audio, add a lockfile-style
cache keyed by `(audioPath fingerprint, modelId, languageHint)` →
`AsrResult` blob — the inputHash discipline from the AIGC lockfile carries
over directly.

## 2026-04-19 — Project lifecycle tools (VISION §5.2 — agent as project manager)

**Context.** `ProjectStore` already had `get / upsert / list / delete / mutate`,
but no agent tool exposed any of them. A new conversation could not bootstrap a
project; the desktop app hard-coded one at startup; the server assumed projects
pre-existed. The agent literally could not answer "make a graduation vlog from
scratch" — it had no `projectId` to thread into `add_clip`.

**Decision.** Add four tools under `core/tool/builtin/project/`:

- `create_project` — title-required, optional explicit `projectId` (default
  slug = `proj-{slug}`), optional `resolutionPreset` (720p/1080p/4k) + `fps`
  (24/30/60). Defaults to 1080p/30. Fails loud on duplicate id.
- `list_projects` — catalog metadata only, no Project JSON decode.
- `get_project_state` — single-project snapshot: title, output profile, asset /
  source-node / lockfile / render-cache / track counts, source revision,
  timeline duration, timestamps. The agent uses this to *plan* before edits
  rather than guess what already exists.
- `delete_project` — destructive; permission `project.destructive` defaults to
  ASK. Does not auto-prune sessions that referenced the project (the prompt
  tells the agent to warn the user instead).

**Why a `ProjectSummary` extension to `ProjectStore`.** `Project.title` lives in
the SQL row, not in the canonical `Project` model — bloating the model just for
listings would invert the storage layout. Added `ProjectSummary` data class +
`summary(id)` / `listSummaries()` methods so list_projects can return titles
without decoding every Project's full JSON. `list_projects` reads metadata only;
`get_project_state` is the heavy single-project read.

**Why the tools live in `core`, not per-app.** Same reasoning as the source
tools — pure local state mutation through `ProjectStore`, no I/O, no platform
deps. Composition root in each container (server, desktop, Android,
iOS-Swift) registers them.

**Why `project.read` / `project.write` default ALLOW; `project.destructive`
defaults ASK.** Reading the catalog and creating an empty project are no-cost
local mutations (parallel to `source.read` / `source.write`). Deletion drops the
Source DAG, Timeline, Lockfile, and RenderCache — irreversible from the user's
perspective and there's no undo lane below the store. ASK-by-default protects
the user without hand-holding the read path.

**Why duplicate-id is a hard failure, not silent overwrite.** `create_project`
on an existing id would silently obliterate the source DAG — exactly the data
loss `delete_project` asks the user about. Failing loud forces the agent to
call `list_projects` first and either pick a fresh id or operate on the
existing project explicitly.

**OutputProfile shorthand, not full struct.** The LLM picks one of three
resolution presets and one of three frame rates rather than constructing an
`OutputProfile` JSON blob. Keeps the input schema compact and the failure modes
obvious (`unknown resolutionPreset` is a clearer error than a partially-filled
profile). Custom profiles can be added later via a `mutate_output_profile` tool
if real users need them — not now.

**System prompt + regression guard.** Added a "Project lifecycle" paragraph to
`TaleviaSystemPrompt.kt` and extended the rule about timeline tools to direct
the agent to bootstrap a project when the catalog is empty.
`TaleviaSystemPromptTest` now asserts `create_project` / `list_projects` /
`get_project_state` still appear in the prompt.

**When to revise.** When a session needs to be associated with a newly-created
project automatically (today the user/caller provides `projectId` separately to
`POST /sessions`), the natural next step is either a server endpoint surface
for project CRUD or a `bootstrap_session` tool that returns both `projectId`
and `sessionId`. Defer until session-level UX feedback motivates it.

## 2026-04-19 — Source-mutation tools close the consistency-binding lane

**Context.** VISION §3.3 + §5.5: the system prompt told the agent to pass
`character_ref` ids in `consistencyBindingIds`, but no tool existed to *create*
those ids. The agent could read the system prompt, plan to apply consistency,
and then have no way to actually write a node — a dormant lane.

**Decision.** Add five tools under `core/tool/builtin/source/`:

- `define_character_ref` — creates/replaces a `core.consistency.character_ref` node.
- `define_style_bible` — creates/replaces `core.consistency.style_bible`.
- `define_brand_palette` — creates/replaces `core.consistency.brand_palette`,
  validates `#RRGGBB` and normalises to uppercase.
- `list_source_nodes` — read-only query, filters by `kind` / `kindPrefix`,
  returns id/kind/revision/contentHash/parents + a kind-aware human summary.
  Optional `includeBody` for full JSON.
- `remove_source_node` — deletes a node by id; doesn't cascade because
  `staleClips()` already treats vanished bindings as always-stale.

All three definers are **idempotent on `nodeId`**: same id + same kind →
`replaceNode` (preserves id, bumps revision, recomputes contentHash). Same id
but *different* kind → `IllegalArgumentException` (loud failure beats silent
shape mismatch). Default `nodeId` is a slugged variant of `name`
(`character-mei`, `style-cinematic-warm`, `brand-acme`) so the LLM rarely needs
to invent ids.

**Why in `core`, not per-app.** The tools mutate `Project.source` via
`ProjectStore.mutateSource(...)` — pure local state, zero I/O, no platform
dependencies. They belong next to the source schema they manipulate. Each
container (`ServerContainer`, `AppContainer`, `AndroidAppContainer`,
iOS `AppContainer.swift`) registers them at composition.

**Why `source.read` / `source.write` default to ALLOW.** Unlike AIGC (external
cost) or media export (filesystem write), source mutations are local-only state
on `Project.source`. Asking the user to confirm every character_ref creation
would be hostile. Apps that want stricter policy can override the rule.

**Why a separate slug helper, not delegate to `nodeId` strings.** The slug
shape (`{prefix}-{a-z0-9-only}`) needs to be consistent across all three
definers, so `SourceIdSlug.kt` owns the rule. Co-locating it with the tools
keeps the surface tight.

**ServerSmokeTest flake.** `submitMessageUsesRequestedProviderInsteadOfDefault`
asserted `openai.requests.size == 1`, but `SessionTitler` runs through the
*same* provider on first turn — adding new tool registrations slowed
`ServerContainer` construction enough to amplify the race. Relaxed to
`>=1` plus a check that the *first* request's `providerId` matches. The point
of the test is "default provider didn't see this," which still holds.

**When to revise.** When a fourth consistency kind lands (e.g. location-bible,
prop-ref), add a sibling definer + extend `ListSourceNodesTool.humanSummary`.
When source nodes need cross-references (one node parents another via
`parentIds`), the definers will need a `parentIds` input — not added now
because no current consistency kind requires it.

## 2026-04-19 — Canonical Talevia system prompt lives in Core

**Context.** The Agent's `systemPrompt` defaulted to `null`. Tool schemas alone don't
teach the model the invariants the VISION depends on — build-system mental model,
consistency bindings, seed discipline, cache hits, dual-user distinction.

**Decision.** `core/agent/TaleviaSystemPrompt.kt` defines a terse canonical prompt +
a `taleviaSystemPrompt(extraSuffix)` composer. Both `AppContainer` (desktop) and
`ServerContainer` wire it into their Agent construction. Server appends a
headless-runtime note so the model doesn't plan around interactive ASK prompts.

**Why a single canonical prompt, not per-app.** The VISION invariants are about the
*system*, not a delivery surface. Forking prompts per app risks prompt drift. Apps
compose on top via `extraSuffix` for runtime-specific nuance (server = headless,
desktop = interactive).

**Regression guard.** `TaleviaSystemPromptTest` asserts each key-phrase (`Source`,
`Compiler`, `consistencyBindingIds`, `character_ref`, `cacheHit`, etc.) still appears.
If someone edits out a rule by accident, the test fails loudly.

**When to revise.** When a new VISION invariant lands (a new tool category, a new
consistency kind), the prompt is the second place to update after the tool itself.
Terseness matters more than exhaustiveness — every token ships on every turn.

## 2026-04-19 — Incremental render v1: full-timeline memoization, not per-clip

**Context.** VISION §3.2 calls for "只重编译必要的部分". Two levels of caching could
realize this:
1. **Coarse:** memoize the whole export — same `(timeline, outputSpec)` → reuse the
   previous output.
2. **Fine-grained:** render only the clips whose `sourceBinding` intersects the stale
   closure; reuse the rest at the clip level.

**Decision.** Ship the coarse cache (Level 1) now; defer Level 2 to per-platform work.

- `Project.renderCache: RenderCache` stores `RenderCacheEntry(fingerprint, outputPath,
  resolution, duration, createdAtEpochMs)`.
- `ExportTool` hashes the canonical `Timeline` JSON + `OutputSpec` fields with
  `fnv1a64Hex` to produce the fingerprint. A second call with identical inputs hits
  the cache — zero engine invocations, same `Output.outputPath`.
- `Input.forceRender` bypasses the cache for debugging / user-driven re-render.

**Why this respects the DAG without a separate stale check.** `Clip.sourceBinding` is
inside the Timeline. When an upstream source node changes, AIGC tools rewrite the
corresponding asset id on cache miss (Task 3 lockfile), which changes the timeline JSON,
which changes the fingerprint. So the DAG's stale-propagation lane flows through
`Source.stale → AIGC rewrite → timeline rewrite → render cache miss` without this
layer knowing anything about source nodes directly. Clean separation.

**Why Level 2 is deferred.** Per-clip render requires the `VideoEngine` to expose a
per-clip render path (render one clip to an intermediate file; compose at the end).
FFmpeg-JVM could do this today; AVFoundation and Media3 can too, but each is a distinct
compositing pipeline. Doing it uniformly across engines is a multi-week engine-side
project, not a core-side abstraction. Better to ship the coarse cache first so VISION §3.2
has *some* realization end-to-end, then revisit fine-grained once we're exercising the
coarse cache in real workflows.

**Staleness of the file on disk.** Cache entries aren't re-validated against the file
system. If the user deletes `/tmp/out.mp4` between exports, a cache hit returns the
path anyway. The user's remedy is `forceRender=true` — adding a file-existence check
in `commonMain` requires platform-specific plumbing we don't need yet.

## 2026-04-19 — AIGC lockfile on Project, cache key = canonical inputHash

**Context.** VISION §3.1 calls for "产物可 pin": key AIGC artifacts should be fixable so
a second call with identical inputs reuses the same output rather than re-抽卡. We need
a place to store the pin + a cache-lookup key shape.

**Decision.**
- `Project.lockfile: Lockfile` stores an ordered list of `LockfileEntry(inputHash,
  toolId, assetId, provenance, sourceBinding)`. Default empty; pre-lockfile projects
  decode cleanly.
- `inputHash` is `fnv1a64Hex` over a canonical `key=value|key=value…` string the tool
  builds from its full input surface (tool id, model + version, seed, dimensions,
  effective prompt after consistency fold, applied binding ids). Any field that can
  change the output goes into the hash.
- `AigcPipeline` (stateless object) exposes `ensureSeed`, `foldPrompt`, `inputHash`,
  `findCached`, `record`. Every AIGC tool consumes these — no inheritance.
- Lookup semantics: `findCached` returns the *last* matching entry, so a regenerate
  with identical inputs later in the project supersedes earlier matches for audit
  ordering but yields the same asset id.

**Alternatives considered.**
- **Composition-based `AigcPipeline` helper vs abstract `AigcToolBase` class.**
  Picked helpers: Kotlin inheritance for tools with different I/O shapes forces
  covariance tricks and "call super" discipline. Plain functions compose.
- **Content-addressed asset blobs (store keyed by file-hash).** Rejected for v1: the
  artifact layer already persists blobs through `MediaBlobWriter`; reusing an
  existing asset id by lockfile is enough. Content-addressing becomes interesting
  when we move to a **shared remote cache** — at that point swap `fnv1a64Hex`
  → SHA-256 and promote the lockfile to the content-hash'd layer.
- **Separate table for the lockfile (not on Project).** Rejected: the lockfile is
  per-project state, naturally travels with the `Project` serialized JSON blob in
  SqlDelight; separating it would add a second source of truth for the same project.

**Seed + caching interaction.** Cache hits are meaningful only when the seed is stable.
If the caller omits `seed`, `AigcPipeline.ensureSeed` mints one client-side and the
entry is recorded with that seed — a later call with `seed=null` mints a *different*
seed, producing a miss. The agent coach prompt (Task 5) should nudge toward explicit
seeds when the user wants determinism.

## 2026-04-19 — Consistency nodes live in Core, not in a genre extension

**Context.** VISION §3.3 demands first-class "character reference / style bible / brand
palette" source abstractions so AIGC tools have something to condition on for cross-shot
consistency. The question is: do these live under `core/domain/source/consistency/` or
inside every genre that needs them (`genre/vlog/`, `genre/narrative/`, …)?

**Decision.** Consistency nodes live in Core under
`core/domain/source/consistency/` with kinds in the `core.consistency.*` namespace.

- `CharacterRefBody(name, visualDescription, referenceAssetIds, loraPin)`
- `StyleBibleBody(name, description, lutReference, negativePrompt, moodKeywords)`
- `BrandPaletteBody(name, hexColors, typographyHints)`

**Why this does *not* violate "no hardcoded genre schemas in Core".** The anti-requirement
forbids *genre* schemas in Core — narrative, vlog, MV, etc. Consistency nodes are
*cross-cutting mechanisms* that every genre reuses to solve the same problem (identity
lock across shots). Defining them per-genre would either duplicate the schema or force
each genre to reinvent the wheel, neither of which serves VISION §3.3's goal of "a
single `character_ref` that transits all the way to every AIGC call in the project."

**Alternatives considered.**
- **One copy per genre.** Rejected: encourages drift (vlog's character looks slightly
  different from narrative's character), breaks the "change one character → all its
  references refactor" promise.
- **A generic `ConstraintNode` with a free-form JSON body.** Rejected: loses the
  guardrails on field names (every downstream fold function would string-match on
  keys). Typed bodies make the prompt folder's behavior auditable.

## 2026-04-19 — Consistency-binding injection via tool input, not ToolContext

**Context.** AIGC tools need access to consistency nodes at execution time. We could
surface them on `ToolContext` (every tool sees them) or on each tool's typed input
(only tools that want them declare them).

**Decision.** Each AIGC tool declares `projectId: String?` + `consistencyBindingIds:
List<String>` on its typed input. The tool carries a `ProjectStore?` via its
constructor and resolves bindings via `Source.resolveConsistencyBindings(ids)` during
`execute`. Tools without bindings / without a store fall back to prompt-only behavior.

**Why input, not context.**
- **Discoverability for the LLM.** Input fields appear in the tool's JSON schema, which
  is what the model reads. A field on `ToolContext` would be invisible to the model.
- **Narrow blast radius.** `ToolContext` is shared by every tool (timeline edits, echo,
  etc.); loading the project source eagerly for every dispatch would be wasted work
  for the vast majority of calls.
- **Tool-by-tool opt-in.** Some AIGC tools (e.g. future TTS on a named character) want
  bindings; some (e.g. a generic SFX synth) don't. Input declaration lets each tool
  own that choice.

## 2026-04-19 — Prompt folding order: style → brand → character → base

**Context.** When multiple consistency nodes apply, what order do they appear in the
folded prompt?

**Decision.** `foldConsistencyIntoPrompt` emits fragments in the order `[style] [brand]
[character] + base prompt`. Negative prompts are merged separately (comma-joined) and
returned to the caller; LoRA pins and reference asset ids are surfaced as separate
structured fields (they're provider-specific hooks, not prompt text).

**Why this ordering.** Diffusion models weight the tail of the prompt more heavily
(well-known inference-time behavior). The base prompt is the most specific signal
("what does this shot look like"), so it goes last. Identity (character) sits right
before it so the model enters the shot-specific portion already thinking about the
subject. Global look (style, brand) goes first because it sets the scene before
identity and content arrive.

## 2026-04-19 — Content hash for Source DAG: FNV-1a 64-bit hex (upgradable to SHA-256)

**Context.** VISION §3.2 calls for cache keys indexed by `(source_hash, toolchain_version)`.
That needs a deterministic, cross-platform content fingerprint for every `SourceNode`.
The stubbed `contentHash = revision.toString()` prevents any downstream caching from
working correctly — two unrelated edits can produce the same revision string.

**Decision.** Compute `SourceNode.contentHash` as a 16-char lowercase hex of FNV-1a 64-bit
over the canonical JSON encoding of `(kind, body, parents)`, delimited by `|`. See
`core/util/ContentHash.kt`.

**Alternatives considered.**
- **SHA-256 via expect/actual.** Industry standard for content-addressed storage (Nix,
  Git-LFS, Bazel). Rejected for v1: requires a crypto dependency or platform-specific
  actuals, and FNV-1a is sufficient inside a single project (≤10³ nodes, collision
  probability negligible).
- **`String.hashCode()` (Java 32-bit MurmurHash-ish).** Rejected: not guaranteed stable
  across Kotlin / Java versions; 32-bit is too narrow for future cross-project caching.
- **Keep the revision stub.** Rejected: blocks Task 3–4 (lockfile + incremental render).

**When to upgrade.** When we build a content-addressed **remote** artifact cache — shared
across projects or users — swap `fnv1a64Hex` for SHA-256. The upgrade path is a
single-function change because every caller goes through `contentHashOf`, and every
`SourceNode` recomputes its hash on write via `SourceMutations.bumpedForWrite`. Existing
projects will see their hashes re-derive on first write, which is fine: cache entries are
keyed by the hash, so a new hash just produces a cold cache.

---

## 2026-04-19 — Stale propagation: transitive downstream BFS, cycle-tolerant

**Context.** "Change traveling downstream through the DAG" is the primitive the rest of
the system needs. It has to be cheap to call per mutation (mutations happen on every
tool run) and robust to malformed graphs.

**Decision.** `Source.stale(changed: Set<SourceNodeId>)` returns the transitive closure
over the reverse-parent edges, including the seeds. Uses a BFS with a visited set, so
cycles don't hang. Unknown ids in `changed` are silently dropped from the seed set
rather than throwing — callers pass in "things I think changed" and get back "things
that are actually stale in the current graph."

**Alternatives considered.**
- **Reject cycles at the data layer.** Rejected: cycles are a *semantic* error, not a
  data-shape error. The loader can stay tolerant; a separate linter can surface cycles
  when a genre schema knows they're invalid.
- **Cache the child index on `Source`.** Rejected for v1: `Source` is an immutable
  value; callers that need the index across many `stale()` calls can hoist
  `Source.childIndex` themselves. Premature caching would complicate equality.

---

## 2026-04-19 — Clip → Source binding on the `Clip` sealed class

**Context.** Incremental compilation needs to know "which source nodes does this clip
depend on?". We could model that as a side-table keyed by `ClipId`, or as a field on
`Clip` itself.

**Decision.** Add `sourceBinding: Set<SourceNodeId> = emptySet()` as an abstract field
on `Clip`, defaulted on every variant. Empty set means "unbound" — the clip opts out of
incremental compilation and will always be treated as stale. AIGC tools that produce
clips populate this with the ids whose contentHash went into their prompt.

**Alternatives considered.**
- **Side-table on `Project`.** Rejected: drifts out of sync with clips when clips move
  between tracks or get split. Keeping the binding on the clip makes it travel with
  the clip through all mutations for free.
- **Optional `SourceNodeId?` (single binding).** Rejected: a clip can depend on
  multiple nodes (e.g., AIGC call conditioned on `character_ref` + `style_bible` +
  `edit_intent`). Set is the right shape.

**Why "empty = always stale" and not "empty = never stale"?** Pre-DAG clips from legacy
workflows should not silently become cache-fresh just because nobody bound them. The
conservative default is "re-render unless we can prove otherwise." Tools opt into
caching by declaring their inputs.

---

## 2026-04-19 — Project-level named snapshots (VISION §3.4 — "可版本化")

**Context.** `revert_timeline` already lets the user undo the last few mutations
*within a chat session* — every mutating tool emits a `Part.TimelineSnapshot`, the
revert tool restores it. That covers "oops, undo that". It does **not** cover
"keep a copy of the project as-of today's review so I can return to it next week"
— those snapshots die when the session ends. VISION §3.4 explicitly asks for
"可版本化：历史可追溯、可回滚、可分支", and a session-scoped lane is not it.

**Decision.** Add a third snapshot lane: project-level, named, persistent.
- New domain type `ProjectSnapshot(id, label, capturedAtEpochMs, project)` stored
  inline as `Project.snapshots: List<ProjectSnapshot> = emptyList()`.
- Three new tools under `core/tool/builtin/project/`:
  - `save_project_snapshot` (permission `project.write`)
  - `list_project_snapshots` (permission `project.read`)
  - `restore_project_snapshot` (permission `project.destructive` → user is asked)

**Why inline storage instead of a separate `project_snapshots` SQL table?**
- `ProjectStore.mutate(...)` already gives us atomicity under a mutex — putting
  snapshots in a sibling table would mean a second store + a second lock + a
  cross-store consistency story. Inline keeps save-and-restore mechanically
  identical to every other Project mutation.
- The Project JSON blob is already what we serialize; inline adds zero schema
  migration on JVM, iOS, and Android.
- The cost — JSON blob grows linearly with snapshot count — is fine for v0
  (target: <100 snapshots per project). When a real user blows that envelope
  we'll add eviction or migrate to a sub-table. We are not going to design for
  hypothetical thousand-snapshot projects today.

**Why restore preserves the snapshots list itself.** Without this rule, restoring
to v3 would delete v1, v2, and any post-v3 snapshots — restore becomes a one-way
trapdoor and "可版本化" stops meaning anything. With it, restore behaves like
`git checkout <snapshot>`: state changes, history stays. The snapshots list +
project id are the two preserved fields; everything else (timeline, source,
lockfile, render cache, assets, output profile) is replaced wholesale from the
captured payload.

**Why save clears nested snapshots before storing.** A project with N snapshots,
captured M times, would otherwise carry O(M·N) snapshot copies inside snapshots
inside snapshots. The captured payload's own `snapshots` field is set to empty
at save time; restore re-attaches the live list. Quadratic blow-up avoided
without giving up the "restore preserves history" rule.

**Why restore is `project.destructive`, not `project.write`.** Restore overwrites
the live timeline + source + lockfile + render cache wholesale. Users can re-enter
the prior state via another snapshot, but if they hadn't saved one first, there's
nothing to roll back to. That matches the bar we already set for `delete_project`
— irreversible-from-the-user's-perspective gets ASK by default. The system prompt
tells the agent to suggest `save_project_snapshot` first when the live state
hasn't been captured.

**Why asset bytes are not snapshotted.** Snapshots reference `AssetId`s in the
shared `MediaStorage`; we do not deep-copy the underlying mp4/png blobs. This is
the same trade-off git makes vs. LFS — saving the manifest is cheap, copying
every blob would balloon storage and make snapshots a first-class media-management
concern. If a user deletes the underlying file, restore will succeed but
downstream renders may miss the asset; that's a future "snapshot integrity" tool's
problem, not a load-bearing invariant we promise here.

**Alternatives considered.**
- **Sibling `project_snapshots` SQL table.** Rejected for the reasons above —
  second store, second lock, second migration, no benefit at v0 scale.
- **Make restore non-destructive (always require explicit confirm flag).** Rejected:
  permission-system-as-confirmation is the established pattern (see
  `delete_project`); reinventing per-tool confirmation flags fragments the UX.
- **Auto-snapshot on every mutation (silent versioning).** Rejected: that's what
  `revert_timeline` already does within a session. Project-level snapshots are
  *named, intentional* checkpoints — silent auto-snapshots would dilute the lane
  into noise within weeks.
- **Reuse `revert_timeline`'s session-scoped snapshots and just persist them to
  the Project on session end.** Rejected: timeline-only snapshots miss the
  source DAG, lockfile, and render cache, all of which need to round-trip for
  "go back to the state I had on Tuesday" to mean what the user thinks it means.

---

## 2026-04-19 — `fork_project` closes VISION §3.4 "可分支"

**Context.** Snapshots gave us "可追溯" (history) and "可回滚" (rollback). The
third leg of §3.4 — "可分支" (branching) — was still missing. Users who want to
explore "what if I cut this differently" without losing the original have to
either (a) save a snapshot and overwrite, or (b) export and re-import via JSON
gymnastics. Neither is the right primitive.

**Decision.** Add a `fork_project` tool (permission `project.write`) that creates
a new project from either:
- the source project's *current* state (no `snapshotId` argument), or
- a captured snapshot (`snapshotId` argument).

The new project gets a fresh id and an empty `snapshots` list. Everything else
(timeline, source DAG, lockfile, render cache, asset catalog ids, output
profile) is inherited from the chosen payload via a single `copy(id=…, snapshots=…)`.

**Why share asset bytes between source and fork instead of duplicating.** Same
trade-off as snapshots themselves — `MediaStorage` is content-addressed enough
that duplicating blobs would just consume disk for no benefit. The canonical
mutation pattern is "produce a *new* asset and `replace_clip`", so concurrent
forks won't step on each other's bytes. If we ever introduce in-place asset
mutation we'll need refcounting, but we are not going to design for that today.

**Why fork starts with an empty snapshots list.** A fork is a fresh trunk — its
own history starts at the moment of the fork. Carrying the source project's
snapshots into the fork would muddle the user's mental model ("if I restore
'final cut v1' on the fork, do I get the source-project state or the fork
state?"). Cleaner to make forks distinct timelines from the start; if the user
wants to cherry-pick a snapshot from the source project they can fork *from*
that snapshot.

**Why fork doesn't ASK like restore does.** Forks are non-destructive — the
source project is untouched. Permission `project.write` matches `create_project`
because the operation is a create. The user only sees a permission prompt if
they've tightened the rules.

**Alternatives considered.**
- **Auto-fork on every save_project_snapshot (CoW-style).** Rejected — silent
  proliferation of projects would clutter `list_projects` and break the user's
  intuition that a snapshot is a *checkpoint within a project*, not a sibling
  project. Forks should be intentional.
- **Cross-project snapshot sharing (one snapshot belongs to many projects).**
  Rejected — the inline-snapshot decision (see prior entry) already chose
  per-project storage. Cross-project sharing would require a sibling table and
  a refcount story for snapshot lifetime; unnecessary at v0.
- **`copy_project` instead of `fork_project`.** Rejected — "fork" maps cleanly
  onto the git mental model and pairs with snapshot vocabulary; "copy" suggests
  a one-shot shallow duplication with no semantic relationship to the source.

## 2026-04-19 — Character voice pinning via `CharacterRefBody.voiceId` (VISION §5.5 audio lane)

**Context.** `CharacterRefBody` gives visual-identity tools (image-gen, future
image-to-video) everything they need to keep a character consistent across
shots — a name, a natural-language description, reference asset ids, an optional
LoRA pin. But the audio lane (`synthesize_speech`) had no parallel: every call
the agent made had to carry a raw `voice` string, even when the "speaker" was a
character the agent already knew. That meant two failure modes. **(a)** agent
drift — after edit #5 the agent forgets which voice it chose for Mei and swaps
to `nova` mid-scene. **(b)** rebinding asymmetry — a user edit to "make Mei
sound deeper" has no anchor on the character; the agent has to re-find every
speech clip by grepping the timeline. Voice belongs on the character.

**Decision.** Add optional `voiceId: String? = null` to [`CharacterRefBody`](../core/src/commonMain/kotlin/io/talevia/core/domain/source/consistency/ConsistencyBodies.kt).
`synthesize_speech` gains the same `consistencyBindingIds: List<String>` input
every AIGC tool already has, plumbed through a new `FoldedVoice` helper in
[`VoiceFolding.kt`](../core/src/commonMain/kotlin/io/talevia/core/domain/source/consistency/VoiceFolding.kt)
and a `AigcPipeline.foldVoice(...)` wrapper. When a bound character_ref has a
non-null voiceId, that voice **overrides** the caller's explicit `voice` input.
The resolved voice is what gets hashed into the lockfile key and what the engine
receives. `define_character_ref` accepts an optional `voiceId` so the agent can
set it at creation time.

**Why `FoldedVoice` is separate from `FoldedPrompt`.** The prompt fold pulls
style bibles, brand palettes, *and* character visual descriptions into text.
None of those apply to TTS — there's no style axis on a voice. Bolting voice
onto `FoldedPrompt` would either (a) make half the `FoldedPrompt` fields
meaningless for audio or (b) hide the voice inside a structure named for
prompts. Two folds, one per modality, is the cleaner cut: visual-fold consumes
`name + visualDescription + refs + lora`, audio-fold consumes `voiceId`. A
future image-to-video tool would use both.

**Why voiceId overrides the explicit voice input (not the other way around).**
The agent's intent "this character speaks" is the stronger signal than a voice
string the agent may have copy-pasted from an earlier call. If the caller
bothered to bind a character_ref, they want *that character's voice*, not a
parallel voice string they also have to remember to update. The loud failure
comes the other direction: binding two character_refs with voiceIds is an
error — "which speaker?" is ambiguous and "first wins" silently regresses when
a caller later adds a second character. Callers disambiguate by binding only
the speaker (the other characters can still be bound via visual tools if they
also appear on-screen, just not via the TTS call).

**Why voiceId is nullable on the character (vs required).** Not every character
has a pinned voice — a minor character may speak once with a hand-picked
placeholder voice, a voice-casting decision may happen late in production.
Making it optional keeps `define_character_ref` usable before the voice is
chosen and lets the fold silently skip characters that lack one (no fallback
ambiguity — the raw `input.voice` wins).

**Cache / lockfile semantics.** The hash key is built from the *resolved*
voice, not `input.voice`. So two callers that arrive at the same voice — one
via `consistencyBindingIds=["mei"]`, one via explicit `voice="nova"` — hit the
same cache entry and share the same asset. The lockfile's `sourceBinding`
records only the character_refs whose voiceId was actually applied, so the
stale-clip detector repaints the speech clip when the character's voice
changes but not when the visual description changes (the visual description
doesn't affect the audio output). Characters without voiceIds are silently
dropped from `sourceBinding` — the *binding itself* is still the agent's
intent, but it has no audio-side stale trigger until the character gains a voice.

**Alternatives considered.**
- **Put voiceId on ToolContext (like auth).** Rejected — voiceId is a
  per-character attribute, not a per-session attribute. The agent rarely
  reasons about a global "current voice"; it reasons about "Mei's voice".
- **Add a parallel `voice_ref` source-node kind distinct from character_ref.**
  Rejected — forces the agent to maintain two bindings for the same character
  (one for image-gen, one for TTS) and creates the possibility of a character
  with a voice and no visual description, or vice versa. One node, all the
  signals, is the simpler mental model.
- **Require voiceId rather than making it optional.** Rejected — breaks the
  case where the agent defines Mei visually before the user has approved a
  specific voice cast.

## 2026-04-19 — `diff_projects` closes VISION §3.4 "可 diff"

**Context.** VISION §3.4 lists four properties for project-level edits: 可观察
(list_projects + get_project_state), 可版本化 (save/restore/list snapshots),
可分支 (fork_project), and 可 diff — "the agent can tell a user what actually
changed between v1 and v2 without dumping both Projects and asking the model to
spot the delta itself." The first three shipped; the diff leg was missing, so
the only way to answer "what did this fork add?" or "what did I break between
snapshots?" was to read-project-state twice and compare in-context. That
wastes tokens and is error-prone on large timelines.

**Decision.** Ship a read-only [`DiffProjectsTool`](../core/src/commonMain/kotlin/io/talevia/core/tool/builtin/project/DiffProjectsTool.kt)
(permission `project.read`). Input is `(fromProjectId, fromSnapshotId?,
toProjectId?, toSnapshotId?)` — a null snapshotId means "current state of that
project", toProjectId defaults to fromProjectId. Output has three diff
sections: timeline (tracks + clips by id), source (nodes by id, with
contentHash-change detection), and lockfile (entry-hash set-diff plus a
tool-id bucket count). Detail lists are capped; totals are always exact.

**Why clip matching by ClipId rather than by asset or position.** A moved clip
(timeRange changed) should show up as `changed`, not `remove + add`. Matching
by ClipId makes "the user moved clip c1 from 0-2s to 1-3s" one entry in
`clipsChanged` with a specific `changedFields=["timeRange"]` list — the agent
can read that back as "you shifted clip c1 by one second" rather than having
to reconstruct the match itself.

**Why source-node change detection uses contentHash, not body-equality.** We
already invalidate on contentHash elsewhere (stale-clip detection) and the
hash is always up to date on every node. Body-equality would need to pull the
JsonElement for each node on each side and compare; contentHash is one string
equality. Same notion of "change," faster.

**Why cap detail lists but keep totals exact.** A wholesale timeline rewrite
could blow the response into thousands of tokens if every clip change
serialises its full field list. Capping at `MAX_DETAIL` keeps the response
bounded; exact totals let the agent still say "47 clips changed (showing the
first 20)". If the agent needs the full list it can refine with
`get_project_state` or re-run with narrower bounds.

**Alternatives considered.**
- **Single "project identical?" boolean tool.** Rejected — the user asks
  "what's different?" as often as "anything different?". A bool-only tool
  forces a follow-up, doubling LLM turns.
- **Server-side unified diff over JSON dumps.** Rejected — meaningless for
  humans (and the agent) at the JSON-key level; domain diff (tracks/clips/
  nodes/lockfile) matches how the model reasons about the project.
- **Extend `get_project_state` with a snapshotId field so the agent can diff
  in-context.** Rejected — doubles the per-call cost (two state pulls), and
  the model isn't especially good at diffing large JSON blobs by eye. A
  typed diff tool is strictly cheaper and more reliable.

## 2026-04-19 — Thread LoRA + reference assets through `GenerateImageTool` output and lockfile hash

**Context.** `GenerateImageTool` already folded `CharacterRefBody.loraPin` and
`CharacterRefBody.referenceAssetIds` into a `FoldedPrompt` via
`AigcPipeline.foldPrompt`, but the returned folded object was *dropped on the
floor* at two critical boundaries:

1. The fields never reached the `ImageGenEngine` — `ImageGenRequest` only
   carried `prompt / modelId / width / height / seed / n / parameters`. Engines
   that could translate LoRA or reference images into their native wire shape
   had no surface to receive them through.
2. The AIGC lockfile hash did not include LoRA or reference-asset axes. Two
   identical prompts with different LoRA weights collided on the same cache
   key; the second call would return the first asset despite being a
   semantically distinct generation. That is an end-to-end correctness bug
   for VISION §3.1 "产物可 pin".

`GenerateImageTool.Output` also lacked the visibility fields the LLM needs to
reason about *what got bound* — it saw `appliedConsistencyBindingIds` but not
which LoRA adapters or reference images those bindings resolved to.

**Decision.**

1. **Extend `ImageGenRequest` with the three provider-specific hooks.** Added
   `negativePrompt: String?`, `referenceAssetPaths: List<String>`, and
   `loraPins: List<LoraPin>`. Engines that cannot natively consume a given
   hook (OpenAI DALL-E / GPT-Image-1 has no LoRA; text-only endpoints take no
   references) are *still required* to record the incoming value in
   `GenerationProvenance.parameters`. Silently dropping them would make the
   audit log lie about what the caller asked for and — worse — make the
   provenance superset look indistinguishable between two runs that had
   different LoRA intent, which then corrupts downstream replay.

2. **OpenAI engine: wire body vs provenance parameters split.** OpenAI's
   `/v1/images/generations` endpoint rejects unknown fields with HTTP 400, so
   we cannot attach `negativePrompt` / `referenceAssetPaths` / `loraPins` to
   the request JSON. The engine now maintains two JSON objects:
   - **Wire body** — strictly the fields the OpenAI API accepts.
   - **Provenance parameters** — a *superset* of the wire body plus
     `_talevia_negative_prompt`, `_talevia_reference_asset_paths`,
     `_talevia_lora_pins`. The `_talevia_` prefix namespaces extensions that
     are our concern only.

   Separating the two surfaces is the correct shape for the platform contract:
   "engine impls translate what they can; the rest still shows up in the
   audit log." Providers that *do* support LoRA (Stable Diffusion backends,
   custom image-gen endpoints) will translate the common-typed input into
   their native shape and add zero `_talevia_` keys.

3. **Hash all three axes into the lockfile input.** `GenerateImageTool.inputHash`
   now includes `neg`, `refs`, `lora` alongside the existing axes. A change to
   any of them busts the cache. `contentHash` on the bound source node already
   changes when `CharacterRefBody.loraPin.weight` shifts — so the existing
   stale-clip detection path *also* flags it — but we want the hash itself to
   be unambiguous as a standalone key, because `list_lockfile_entries` and
   `find_stale_clips` reason about the hash directly, not the node graph.

4. **Expose the resolved pins on `Output`.** Added `negativePrompt`,
   `referenceAssetIds`, `loraAdapterIds` to `GenerateImageTool.Output`. The
   agent can read back that a bound character injected `hf://mei-lora` without
   re-querying the source graph. Keeps the tool's output self-describing.

5. **Resolve asset ids → paths at the tool boundary.** `MediaPathResolver`
   takes `AssetId → String` asynchronously; `GenerateImageTool` resolves
   `folded.referenceAssetIds` via the injected `MediaStorage` (which is a
   `MediaPathResolver`) before calling the engine. Engines must never see
   `AssetId.value` as a path — that violates the M2 architectural rule.

**Why not make the engine fetch paths itself.** Rejected. The engine layer is
already "translate common → native"; giving it a second responsibility
("resolve project-scoped asset ids") would couple it to the
`MediaPathResolver` contract and make stateless engine impls harder to write.
The tool owns the project context, so path resolution happens there.

**Why `_talevia_` prefixed provenance keys.** Provenance parameters are a
`JsonObject` shared with "what was on the wire." Mixing user-visible fields
(`prompt`, `size`) with implementation-only extensions in the same namespace
would later confuse a replay tool or a human reading the audit log. A
namespace prefix keeps the two concerns visibly distinct.

**Why require engines without a given hook to still record it.** Provenance
is load-bearing for two jobs: audit ("what did you ask the provider?") and
cache-key reconstruction ("would this run hit the same entry?"). If
`OpenAiImageGenEngine` silently dropped the negative prompt, two lockfile
entries with distinct caller intent would have identical provenance — the
same hash collision concern as before, pushed one layer down. Making the
contract explicit ("MUST record") at the `ImageGenEngine` KDoc forces future
providers to inherit the discipline.

**Coverage.** Added two tests to `GenerateImageToolTest`:
- `loraAndReferenceAssetsFlowToEngineAndOutput` — defines a character_ref
  with a `LoraPin` and a reference image, binds it, asserts the engine saw
  both as resolved paths + pins AND that `Output.referenceAssetIds` /
  `Output.loraAdapterIds` surfaced them.
- `loraWeightChangeBustsTheLockfileCache` — generates once with weight 1.0,
  flips to 0.4, asserts second call is a miss.

## 2026-04-19 — `parentIds` on `define_character_ref` / `define_style_bible` / `define_brand_palette`

**Context.** The Source DAG already supported cross-references via
`SourceNode.parents: List<SourceRef>`, and `Source.stale(ancestorId)` walked
the ancestry to report every descendant that needs recomputation. But the
**definer tools the agent actually calls** (`define_character_ref`,
`define_style_bible`, `define_brand_palette`) didn't expose `parentIds` on
their inputs. In practice that meant every consistency node the agent created
sat as a disconnected root — e.g. a `character_ref` whose wardrobe palette
derives from a `brand_palette` had no way to record that derivation, so an
edit to the brand palette *wouldn't* cascade staleness onto the character.

This is the §5.1 Source layer question #2 gap: "改一个 source 节点（比如角色
设定），下游哪些 clip / scene / artifact 会被标为 stale？这个关系是显式的吗？"
The DAG machinery existed; the tool-surface bridge didn't.

**Decision.**

1. **Extend all three definer tool Inputs with `parentIds: List<String>`.**
   Optional, defaults to empty, so every existing caller is untouched. JSON
   schema documents the use-case: "Optional source-node ids this {kind}
   depends on … editing any parent cascades contentHash changes." The agent
   reads that directly in its tool catalog.

2. **Validate ids at the tool boundary** (`ResolveParentRefs.kt`):
   - Blank ids are dropped (LLMs sometimes emit `""`).
   - Self-references (`parentIds = [self]`) fail loudly — cycle prevention at
     the entry point, so lower layers don't need to guard.
   - Unknown ids fail loudly with the hint "define the parent first, or use
     `import_source_node` to bring it in." Dangling `SourceRef`s would show up
     as ghost edges in `list_source_nodes` and corrupt stale-propagation.
   - Duplicates are deduped while preserving caller order.

3. **Extend `addCharacterRef` / `addStyleBible` / `addBrandPalette` helpers
   with a `parents: List<SourceRef> = emptyList()` parameter.** Keeps helpers
   symmetric with the tool surface and avoids forcing tools to open-code
   `addNode(SourceNode(id, kind, body, parents))`. Default is empty, so
   existing tests and callers are unaffected.

4. **On `replace` path, update the node's `parents` field too, not just the
   body.** A user re-defining "Mei" with `parentIds = [style-warm]` *expects*
   the stored node to have that parent afterward — dropping the new parent
   list silently would make re-define semantically asymmetric with first
   define. `replaceNode`'s `bumpedForWrite` re-computes contentHash from the
   updated parents list, so ancestry-driven staleness lands correctly.

**Why validate at the tool boundary and not in `addNode`.** `addNode` is
genre-agnostic and lives in the Source lane alongside the raw data model.
Adding "all `SourceRef`s must resolve" as an invariant there would force
every low-level mutation (migration, import, test fixture) to carry the full
node index — a foot-gun in places where dangling refs are a transient state
that gets fixed up before commit. The definer tools are the one authoritative
user-facing entry where "the graph must be well-formed right now" is a valid
contract to enforce.

**Why fail loudly on unknown parents instead of silently dropping them.** The
LLM typing `parentIds = ["style-warm"]` when `style-warm` doesn't exist is
nearly always a sequencing mistake (it forgot to call `define_style_bible`
first, or misremembered the id). A silent drop would create a character_ref
with no parents but an intent the caller thought it had expressed —
failure-to-propagate-later is a much worse symptom than failure-at-define-now.

**Why keep `parentIds` separate from `consistencyBindingIds`.** The two serve
distinct roles:
- `consistencyBindingIds` on AIGC tools = "fold these nodes into **this one**
  prompt." Ephemeral, per-call.
- `parentIds` on define_* tools = "this node derives from those nodes
  structurally." Persistent in the Source DAG; drives staleness propagation.
A character_ref that *derives from* a brand_palette usually wants both: the
parent ref keeps the derivation visible + cascades staleness, and AIGC tools
still bind just the character (the brand palette folds implicitly through
the DAG-flattening pass when that lands, or explicitly via a second binding
today).

**Coverage.** Added 5 tests to `SourceToolsTest`:
- `defineCharacterRefThreadsParentIdsIntoNode` — parent makes it onto the
  stored node.
- `parentEditCascadesContentHashDownstream` — editing the brand palette
  makes `Source.stale(brand-acme)` include the style_bible that parents it.
- `parentIdsThatDontExistFailLoudly` — unknown id errors out.
- `selfParentIsRejectedAtTheToolBoundary` — cycle protection.
- `replacingCharacterRefUpdatesParentsToo` — re-define rewrites parents, not
  just body.

System prompt gained a paragraph teaching the agent when to use `parentIds`
(derivation, not documentation) and when to rely on flat
`consistencyBindingIds` instead.

---

## 2026-04-19 — `ApplyLutTool` and `style_bible.lutReference` enforcement

**Context.** `StyleBibleBody.lutReference: AssetId?` has existed since the
consistency-node work landed — VISION §3.3 names it as the traditional-lane
anchor for a project-global color grade. But no tool ever *read* the field:
`define_style_bible` wrote it, and the FFmpeg engine's filter pass implemented
brightness / saturation / blur / vignette only. The LUT reference was data
without a consumer, so a user asking "apply the project's LUT to every clip"
had no path that worked end-to-end.

**Decision.** Add a new `apply_lut` tool and teach the FFmpeg engine to bake
LUT references via `lut3d`.

**Tool shape.** `ApplyLutTool.Input` takes `projectId + clipId` plus *exactly
one* of:
- `lutAssetId` — a LUT already imported into the asset catalog. Direct path,
  no Source DAG involvement.
- `styleBibleId` — a `core.consistency.style_bible` node. The tool looks up
  the node, reads its `lutReference` *at apply time*, and also attaches the
  style_bible's nodeId to the clip's `sourceBinding`.

Neither-or-both fails loudly (`IllegalArgumentException`) so the LLM can't
pick ambiguously.

**Why read `lutReference` at apply time, not at render time.** The alternative
is to store only the `styleBibleId` on the filter and re-resolve the LUT
every render. That would give automatic propagation — edit the style_bible's
LUT, re-render, new color — but it would also mean render-time failures when
the style_bible is later removed or has its LUT unset. Matching the existing
staleness paradigm is more consistent: `replace_clip` works the same way (it
snapshots the new asset's sourceBinding at the moment of replacement), and
`find_stale_clips` is the detection half. The workflow is symmetric across
AIGC and traditional clips: edit the upstream node → `find_stale_clips` → for
traditional-LUT clips, re-run `apply_lut`; for AIGC clips, regenerate and
`replace_clip`. Keeping apply-time resolution also avoids needing a
"style_bible-aware" FFmpeg render loop, which would leak source-DAG types
into the engine abstraction (VISION anti-requirement).

**Why attach `styleBibleId` to `Clip.sourceBinding` on the style_bible path.**
The binding is what future staleness machinery (beyond today's
lockfile-only `find_stale_clips`) needs to see this clip as downstream of
the style_bible. Today the field sits unused for traditional clips; writing
it now means when find_stale_clips is extended to walk `Clip.sourceBinding`
directly (a planned follow-up), LUT clips automatically participate. Not
writing it would create a silent gap the user would have to manually repair.

**Why not store `styleBibleId` *on* the filter.** Considered a `Filter`
variant tagged with a source-node id for "smart" rerendering. Dropped:
`Filter` is a render-engine-facing type, not a DAG-aware one, and keeping it
numeric-param + optional asset keeps the engine layer unaware of the Source
DAG. The binding belongs on the clip — which already has a first-class
`sourceBinding` set — not on the filter.

**Domain change.** Extended `Filter` with `val assetId: AssetId? = null`.
Backwards-compatible (default null; ignoreUnknownKeys on the JSON config
absorbs older rows during read). The existing numeric `params` map is
preserved as-is for filters like `brightness`, which don't need an asset.
Path-bearing filters (LUT today, image-overlays tomorrow) set `assetId`
instead of trying to squeeze a path into a `Map<String, Float>`.

**FFmpeg rendering.** The render loop pre-resolves every distinct
`Filter.assetId` to an absolute path via `MediaPathResolver` *before*
invoking `filterChainFor`. The resolver call is the only suspend point;
`filterChainFor` stays non-suspend so the existing `FilterChainTest` unit
tests (which use a `NullResolver`) keep working. The `lut` filter renders
as `lut3d=file=<escaped path>`. Escaping follows ffmpeg's filtergraph
metacharacter rules: `:`, `\\`, `'`, `,`, `;`, `[`, `]` all get
backslash-prefixed, because paths with colons or brackets otherwise get
re-parsed as filter syntax.

**Android / iOS gap.** `Media3VideoEngine` and `AVFoundationVideoEngine`
continue to carry filters on the Timeline without baking them — the same
gap already documented in `CLAUDE.md` "Known incomplete" for
brightness/saturation/blur. `apply_lut` inherits that gap rather than
re-opening it; the filter is attached to the clip regardless of engine so
a later render on FFmpeg (or once Media3/AVFoundation catch up) will
honor it. The system prompt explicitly teaches this so the LLM doesn't
promise a Media3/iOS user that the LUT will render today.

**Missing-asset behavior.** If the LUT assetId can't be resolved at render
time, the filter is dropped silently rather than aborting the render.
Matches the existing "unknown filter name is dropped" behavior — one
misconfigured filter shouldn't blow up a whole export. Validation at
apply time (`media.get(lutId) ?: error(...)`) catches the common case of
typos or un-imported LUTs early, so render-time drop should be rare.

**Coverage.**
- `ApplyLutToolTest`: direct-asset path, style_bible path (with sourceBinding
  attach), missing style_bible, style_bible without lutReference, both-ids
  rejected, neither-id rejected, missing-asset, non-video clip rejected.
- `FilterChainTest`: new cases for lut-with-resolved-path, lut-without-path
  (dropped), lut-without-assetId (dropped, defensive), and special-char
  escaping in paths.

**Registration.** All four composition roots (Android, Desktop, Server,
iOS Swift) register `ApplyLutTool`. Mirrors the pattern for every other
tool — keeps the tool surface uniform across platforms.

**System prompt.** New "Traditional color grading (LUT)" section teaches
the two call shapes and names style_bible as the preferred path when a
project has one. Key phrase `apply_lut` added to `TaleviaSystemPromptTest`
so the tool's invocation phrase can't silently drift.

---

## 2026-04-19 — `RemoveClipTool` — the missing scalpel for the editing lineup

**Context.** The cut/stitch/filter/transition lineup the agent uses to *edit*
(VISION §1) had a gaping hole: the agent could `add_clip`, `replace_clip`,
`split_clip`, `apply_filter`, `apply_lut`, `add_transition`, `add_subtitle` —
but had **no way to delete a clip**. The only workaround was
`revert_timeline` to a prior snapshot, which is a bulldozer (it discards
every later edit too) where a scalpel was needed. A user saying "drop the
second take" had no clean execution path.

**Decision.** Add a new `remove_clip(projectId, clipId)` tool that finds a
clip by id across all tracks and removes it. Output reports the trackId it
came from and remaining clip count on that track.

**Why no ripple-delete.** Considered shifting downstream clips' `timeRange`s
left by the gap size. Rejected: timestamps in the timeline are addressed by
absolute time across tracks. Subtitles at 00:14, transitions between v1[02]
and v1[03], audio fades at 00:08 — all of those are positioned by absolute
timeline time. Ripple-deleting one video clip would silently invalidate
transitions / subtitles / audio cues whose authors were targeting wall-clock
positions on the timeline, not relative offsets within a track. The
NLE-style "ripple delete" trade-off is real (a casual editor *expects* the
gap to close), but the cost of silent corruption to other tracks is higher.
If the user wants ripple behavior, the agent can chain `move_clip` for each
downstream clip — explicit two-step instead of magic side effect.

**Why preserve the empty track.** When a track loses its last clip, the
track itself is left in place rather than auto-removed. Reason: subsequent
`add_clip(trackId=…)` calls need a target. The agent often deletes the
single placeholder clip on a fresh track and immediately adds a real one;
forcing it to recreate the track would add a round-trip and a chance to
pick the wrong track id.

**Why timeline.write permission.** Same scope as add_clip / split_clip /
replace_clip. Removing a clip is symmetric with adding one — a routine
mutation, not a project-level destructive op like `delete_project`.

**Snapshot for revert_timeline.** Emits `Part.TimelineSnapshot` post-mutation
via the shared `emitTimelineSnapshot` helper. So `revert_timeline` can roll
the deletion back — no data is permanently lost from the agent's POV. This
is the same pattern every other timeline-mutating tool uses; consistency
matters more than micro-optimizing snapshot frequency.

**Failure mode.** Missing clipId fails loudly with `IllegalStateException`
naming the offending id, and the project is left untouched. We don't
silently no-op because the LLM should learn from the error and find the
right id rather than silently moving on assuming the delete succeeded.

**Coverage.** `RemoveClipToolTest` (6 tests): named-clip removal with
sibling preservation and no-shift assertion, cross-track scoping
(audio track untouched when video clip removed), empty-track
preservation, audio-clip removal from audio track, missing-clip
fail-loud, post-mutation snapshot emission for revert_timeline.

**Registration.** All four composition roots (server, desktop, Android,
iOS Swift) register `RemoveClipTool` next to `SplitClipTool`. System
prompt gained a "Removing clips" section teaching when to use it (drop a
clip vs revert) and the no-ripple semantics. Key phrase `remove_clip`
added to `TaleviaSystemPromptTest`.

---

## 2026-04-19 — `MoveClipTool` — closes the ripple-delete chain

**Context.** `RemoveClipTool` shipped with system-prompt guidance saying
"if you want ripple-delete behavior, follow up with `move_clip` on each
downstream clip" — but `move_clip` did not exist. Same for the help text
on `RemoveClipTool` itself. The agent was being told to call a tool that
wasn't registered, a credibility gap that would manifest as the LLM either
hallucinating a `move_clip` call that fails at dispatch or silently dropping
the ripple-delete workflow entirely.

**Decision.** Add `move_clip(projectId, clipId, newStartSeconds)` that
repositions a clip on the timeline by id. Output reports trackId, oldStart,
newStart so the LLM can chain moves without re-reading state.

**Why same-track only.** Considered allowing a `newTrackId` parameter so
the agent could move a clip across tracks (e.g. v1 → v2). Rejected for v1:
cross-track moves change the rendering pipeline (different stack ordering
for video, different filter chains, different audio routing). The
move_clip tool would either need to validate "destination track is the
same track *type*" (refuse video → audio) or silently allow nonsense, both
of which are worse than "this tool is for shifting in time, period." When
a real cross-track driver appears, a separate `move_clip_to_track` tool
keeps the semantics distinct.

**Why preserve `sourceRange`.** A move shifts when the clip plays on the
timeline, not what material it plays. `sourceRange` (start/duration into
the source asset) is untouched — the same frames render, just at a
different timeline time. Conflating "move on timeline" with "trim source
window" would make this tool double as `trim_clip`, which deserves its
own primitive when it lands.

**Why allow overlap.** Considered refusing a move that would create
overlap with siblings. Rejected: overlapping clips on a single track are
the foundation of picture-in-picture, layered effects, and transitions
(which by definition span the boundary between two adjacent clips). The
existing `add_clip` doesn't refuse overlap; `move_clip` matches that
discipline. The agent (or user inspecting the result) is expected to
catch unintended overlaps and iterate.

**Why `newStartSeconds: Double` and not `deltaSeconds`.** Absolute time
matches how every other timeline tool addresses positions
(`atTimelineSeconds` in `split_clip`, `start` in `add_clip`). A delta API
would force the agent to read the current position before computing the
target, adding a round-trip. The agent can compute `newStart = oldStart +
delta` itself when it has the clip in hand from a prior tool result.

**Validation.**
- `newStartSeconds < 0` → IllegalStateException ("must be >= 0").
  Negative start times are nonsense on a timeline anchored at zero.
- Missing clipId → IllegalStateException naming the offending id, project
  untouched. Same fail-loud discipline as `remove_clip` / `split_clip`.

**Snapshot for revert_timeline.** Emits `Part.TimelineSnapshot`
post-mutation — same pattern as every other timeline-mutating tool. The
move can be rolled back via `revert_timeline` like any other edit.

**Sibling reordering.** After updating `timeRange.start` the track's
clips are re-sorted by start time so downstream consumers don't have to
assume sorted-or-not. Matches what `SplitClipTool` does on its rebuild.

**Coverage.** `MoveClipToolTest` (7 tests): same-track move with sibling
reordering, duration + sourceRange preservation, cross-track scoping,
overlap-allowed (no refusal), missing-clip fail-loud, negative-start
rejection, post-mutation snapshot emission for revert_timeline.

**Registration.** All four composition roots register `MoveClipTool`
next to `RemoveClipTool`. System prompt gained a "Moving clips" section
teaching duration preservation, the same-track-only constraint, and the
ripple-delete chain pattern (`oldStart - removedDuration` arithmetic).
Key phrase `move_clip` added to `TaleviaSystemPromptTest`.


## 2026-04-19 — `VideoGenEngine` + `GenerateVideoTool` — AIGC video lane

**Context.** VISION §2 lists "文生视频 (text-to-video)" alongside text-to-image
and TTS as one of the AIGC compiler lanes. The implementation had the other two
(`GenerateImageTool` + `SynthesizeSpeechTool`) but no video-generation path —
even the system prompt's fallback example of an unsupported capability was
"text-to-video", proving the gap was visible to the model. Sora 2 went GA on
the OpenAI API in late 2025; closing this lane now unblocks storyboard → motion
previews without wiring up a second provider.

**Decision.** Add three pieces, mirroring the image-gen shape so AIGC tooling
stays uniform:
1. `VideoGenEngine` interface in `core.platform` with `VideoGenRequest` /
   `VideoGenResult` / `GeneratedVideo(mp4Bytes, width, height,
   durationSeconds)`.
2. `GenerateVideoTool` in `core.tool.builtin.aigc` using the existing
   `AigcPipeline` (seed mint, prompt fold, lockfile lookup / record).
3. `OpenAiSoraVideoGenEngine` targeting `/v1/videos` + `/v1/videos/{id}/content`.

**Per-modality interface vs umbrella.** Followed the precedent set by
`ImageGenEngine` / `TtsEngine`: one interface per modality rather than a
generic `GenerativeEngine` umbrella. The signature differences (video has
`durationSeconds`, image has `n`, TTS has `voice` + `format`) would either be
lost under a vague common shape or require every caller to carry
modality-specific extras in `parameters: Map<String, String>`. Per-modality
types type-check inputs and keep translation local.

**Duration in the cache key.** `durationSeconds` is hashed into the lockfile
input, so a 4s render and an 8s render of otherwise-identical inputs produce
two distinct cache entries. Dropping duration from the hash would conflate
semantically distinct outputs — the user would ask for 8s and get the cached
4s asset back.

**Duration echoed on `GeneratedVideo`.** The provider may clamp the requested
duration to a supported step (Sora 2 supports 4 / 8 / 12). The engine prefers
the provider's echoed duration (if any) and falls back to the request value.
This means `Output.durationSeconds` reflects what was actually rendered, not
what was asked for — so `MediaMetadata.duration` on the imported asset is
honest and downstream code (export timeline length, stale-clip tracking) sees
the real number.

**Async polling inside the engine.** Text-to-video is asynchronous: POST
creates a job, GET polls status, `GET /v1/videos/{id}/content` downloads the
finished bytes. The engine hides this behind a single suspend call — callers
see one blocking operation that returns the mp4. Poll interval (`5s`) and max
wait (`10 min`) are constructor-tunable so tests can inject faster values and
production can bump the deadline if Sora queues get congested. Failure /
cancellation / timeout all surface as `error(...)` with the last seen status
so the agent can explain the failure to the user rather than silently fall
through.

**Why the engine polls, not the tool.** Considered having `GenerateVideoTool`
return immediately with a job id and a second tool to download when ready.
Rejected: that shape forces the agent to orchestrate two calls for every
render, bloats the tool registry (every async provider would add its own
poll tool), and the tool / lockfile layer works in terms of finished assets.
Keeping the async contract inside the engine keeps the tool layer uniform
with image / TTS.

**Provenance for hooks the provider doesn't yet support.** Sora's create
endpoint doesn't yet accept negative prompts, reference clips, or LoRA pins;
the engine still records whatever the caller passed in
`GenerationProvenance.parameters` via `_talevia_*` sidecar keys. Matches the
precedent set in `OpenAiImageGenEngine` — the lockfile hash would otherwise
collide when the agent *did* change one of those fields but Sora ignored it,
hiding the semantic input change from the cache layer.

**Modality gating at the composition root.** Registered in
`apps/{desktop,server}` under the same `OPENAI_API_KEY` gate as the other
AIGC engines. Not registered on Android or iOS — those platforms don't
register any AIGC tool today because neither container wires an HTTP client
/ blob writer pair. When we add those, `generate_video` comes along for the
ride with the other AIGC tools.

**Coverage.** `GenerateVideoToolTest` (6 tests with a `FakeVideoGenEngine`):
asset persistence + provenance readback, client-side seed minting when
`seed=null`, consistency-binding prompt folding, lockfile cache hit on repeat
+ miss when duration changes, sourceContentHashes snapshot for stale-clip
detection, and LoRA / reference-asset flow-through.

**System prompt.** New "# AIGC video (text-to-video)" section teaches the
model the defaults (Sora 2, 1280x720, 5s), duration-cache-key semantics,
binding parity with image gen, and the "this takes a while" expectation
that callers should surface to the user. `generate_video` added to the
`Compiler = your Tool calls` list and to the prompt-test key phrases.


## 2026-04-19 — `MoveClipTool` — closing a credibility gap in the system prompt

**Context.** The system prompt's "Removing clips" section promised the model
it could chain `move_clip` on every downstream clip to simulate ripple-delete
after `remove_clip` — but the tool didn't exist. The LLM was being told to
call a nonexistent primitive, which risks hallucinated calls and broken
recovery paths. The gap was pre-existing (earlier session had authored the
tool file + tests but not wired registration).

**Decision.** Register the existing `MoveClipTool` in all four composition
roots (desktop, server, Android, iOS Swift) and teach the model explicitly
via a new "# Moving clips" system-prompt section. Add `move_clip` to the
prompt-test key phrases so removal regresses loudly next time.

**Semantics captured in the prompt.**
- Changes `timeRange.start`; duration and `sourceRange` preserved (same
  material, different timeline position).
- Same-track only — cross-track moves change rendering semantics (stack
  order, filter pipeline) and deserve a separate tool when a driver appears.
- No overlap validation — PiP, transitions, and layered effects legitimately
  need overlapping clips; refusing to move into an overlap would block
  real workflows.
- Emits a timeline snapshot, so `revert_timeline` can undo the move —
  consistency with every other timeline-mutating tool.

**Bundled with `generate_video` commit.** Linter auto-added the MoveClipTool
import to the server container when it saw the untracked file, so splitting
the commits would have required fighting the linter. Bundled into the same
commit as T6 (VideoGen); commit message calls out both pieces explicitly.
Same pattern as the earlier `remove_clip` + `apply_lut` bundle.


## 2026-04-19 — `add_subtitles` (batch) — close the ASR → caption loop

**Context.** `transcribe_asset` (VISION §5.2 ML lane) returns a list of
time-aligned `TranscriptSegment`s — the natural next move is to drop them
onto the subtitle track. The only available primitive was `add_subtitle`
(singular), which adds one caption per call. A 60-second clip typically has
30+ transcript segments, so the agent had to issue 30 sequential tool calls
— 30× the tokens, 30× the latency, and 30 separate `revert_timeline`
snapshots stacked on top of each other. Revert-one-caption undo is noise;
the user's intent was "caption the whole clip" as a single unit.

**Decision.** Add `add_subtitles` (plural) as a sibling tool in
`core.tool.builtin.video`. Input is `{projectId, segments: [{text,
startSeconds, durationSeconds}], fontSize?, color?, backgroundColor?}`;
all segments are committed in one `ProjectStore.mutate` and one
`Part.TimelineSnapshot` is emitted. Style applies uniformly to every
segment. The manual one-off path keeps `add_subtitle` (singular) for the
case where a user wants per-line styling.

**Why a sibling tool and not a field on `add_subtitle`.** Overloading the
singular tool with an optional `segments[]` would confuse schema
validation ("text is required" + "segments is required" would race) and
muddle the permission / snapshot semantics (one-clip-per-call vs
many-clips-per-call differ on the undo stack). Two tools with clear,
non-overlapping shapes keep the prompt teachable — "batch caption? use
add_subtitles. single manual line? use add_subtitle."

**Seconds at the tool surface.** `TranscriptSegment` is `startMs`/`endMs`,
but every timeline-mutating tool in the codebase takes seconds. Matching
the sibling's unit keeps the two tools substitutable and avoids a second
unit-system at the tool boundary. The agent is taught explicitly to divide
the ASR `startMs` / `endMs` by 1000 in the prompt.

**Atomic edit, single snapshot.** The user's mental model of "caption the
clip" is one edit. Matching that with one snapshot makes `revert_timeline`
a natural undo for the whole caption pass — which is the behavior a user
who said "undo the captions" expects. Per-segment snapshots would have
required the user to revert 30 times to unwind the captioning.

**Coverage.** `AddSubtitlesToolTest`: atomic multi-segment insertion with
single-snapshot verification, unsorted input sorting by start, subtitle
track auto-creation when absent, timeline duration extension to cover tail
segment, empty-segments rejection (`IllegalArgumentException`),
existing-track-order preservation.

**Registration.** Added to all four composition roots (desktop, server,
Android, iOS Swift) alongside the existing `AddSubtitleTool` registration.
System prompt gained a paragraph under "# ML enhancement" teaching the
`transcribe_asset` → `add_subtitles` chain and explicitly discouraging the
old N×`add_subtitle` loop. `add_subtitles` added to the Compiler = Tool
calls list and to `TaleviaSystemPromptTest`'s key phrases.


## 2026-04-19 — `TrimClipTool` — re-trim without losing attached state

**Context.** The agent had no way to re-trim a clip after creation. The
only available paths were destructive: `remove_clip` + `add_clip` (loses
any filters / transforms / consistency-bindings attached to the clip id,
because a fresh clip gets a fresh id) or `split_clip` + `remove_clip`
(loses one half and produces residue on the other). Both are wrong tools
for the user's intent — "trim a second off the start" is a single edit,
not a destructive recreate. A video editor without a working trim is not
one, so this is a fundamental primitive gap.

**Decision.** Add `trim_clip` in `core.tool.builtin.video`. Input is
`{projectId, clipId, newSourceStartSeconds?, newDurationSeconds?}`. At
least one of the new* fields must be set; omitted = preserve current.
Mutates the clip's `sourceRange` (and `timeRange.duration`) in place,
preserving the clip id and everything else attached to it (filters,
transforms, sourceBinding, audio volume).

**Why absolute values, not deltas.** Mirrors `add_clip` vocabulary
exactly. Delta-based input ("trim 1.5s off the start") forces the agent
to read the clip's current state before computing the call, doubling
round-trips for no semantic gain. Absolute is also what the user usually
means when they say "make it 8 seconds long" or "start at 00:03".

**Why preserve `timeRange.start`.** Trimming and repositioning are two
intents. If `trim_clip` also slid the timeline anchor, the user would
have to know how to undo the slide to keep alignment with subtitles or
transitions on adjacent tracks. Keeping the timeline anchor stable means
the rest of the timeline doesn't reflow. The agent chains `move_clip`
when it actually wants to slide.

**Why duration applies to BOTH `timeRange` and `sourceRange`.** Talevia
doesn't model speed changes (no time-stretching tool). Until that arrives,
clip duration on the timeline always equals duration in source media.
Letting the two diverge here would let the tool produce timeline state
that no engine knows how to render. When speed becomes a thing, it gets
its own tool.

**Why reject `Clip.Text`.** Subtitle clips have no `sourceRange` (they're
synthetic, generated from `text` + `style`). A trim that only changes
`timeRange` is just a "move the boundaries" op — different semantics
from trimming a media-backed clip. Forcing the agent to use
`add_subtitle` (which it already knows) for subtitle timing keeps the
two tools' contracts crisp.

**Validate against asset duration.** The mutation block looks up the
bound asset's `metadata.duration` via `MediaStorage.get` so we refuse
trims that extend `sourceRange.end` past the source media — failing
loud at tool-dispatch time beats letting a broken `sourceRange` reach
the renderer.

**Coverage.** `TrimClipToolTest` — eleven cases: tail-only trim
(shrink with `timeRange.start` preserved), head-only trim
(`sourceRange.start` advances, `timeRange.start` preserved), simultaneous
head + tail, audio-clip parity (volume preserved through trim),
Text-clip rejection, both-fields-omitted rejection,
trim-past-asset-duration guard with state-untouched-on-failure check,
negative `newSourceStartSeconds` rejection, zero-duration rejection,
missing-clip fail-loud with state-untouched-on-failure check, and
post-mutation `Part.TimelineSnapshot` for `revert_timeline` parity.

**Registration.** Added to all four composition roots (desktop, server,
Android, iOS Swift) right after `MoveClipTool`. System prompt gains a
"# Trimming clips" section explaining the absolute-values vocabulary,
the timeline-anchor preservation, and the Text-clip carve-out.
`trim_clip` added to `TaleviaSystemPromptTest`'s key phrases (parallel
session beat me to the prompt-test edit, pleasant collision).


## 2026-04-19 — `trim_clip` — re-trim existing clips without losing bound state

**Context.** The agent could add, split, remove, move, and replace a clip,
but had no way to *re-trim* one after creation. To shorten or re-anchor a
clip's in-point into the source media the agent had to `remove_clip` +
`add_clip` — which regenerates a new `ClipId`, breaking any
`consistencyBinding` pins, filter attachments, or downstream refs that
keyed off the original id. A video editor without trim isn't a video
editor.

**Decision.** Integrate the pre-existing `TrimClipTool` in
`core.tool.builtin.video` (tool id `trim_clip`, permission
`timeline.write`) and wire it into all four composition roots. The tool
adjusts `sourceRange` and/or duration in place while preserving the
clip's `ClipId` and its `timeRange.start`. Either input field may be
omitted to keep current; at least one must be set (rejects the no-op
call explicitly).

**Vocabulary choice: absolute values, not deltas.** Matches `add_clip` /
`split_clip` / `move_clip`. Absolute means the agent doesn't need to
`get_project_state` first just to compute `current + delta`. Fewer round
trips, fewer off-by-one bugs, same final edit.

**Timeline anchor preserved.** `timeRange.start` is explicitly NOT
modified — trim adjusts the source window, not the timeline position.
If the user wants to both retrim and reposition, they chain `move_clip`.
Coupling the two would make "shrink this clip" ambiguous ("do I want it
to stay put, or do I want everything after it to shift?").

**Duration applies to both ranges.** A v1 clip plays at 1× speed; no
speed-ramp model yet. So `newDurationSeconds` becomes both
`timeRange.duration` and `sourceRange.duration`. When speed ramps land
we can split this into two fields or add `speedRatio`.

**Text clip rejection.** `Clip.Text` has no `sourceRange` — its text is
embedded. Rather than silently ignore the trim or hallucinate a
subtitle-reset, the tool fails loudly with "use add_subtitle to reset".
Clear error > footgun.

**Asset-bound guard.** Trims that would extend past the bound asset's
duration are rejected before mutation. The media lookup happens inside
`ProjectStore.mutate` so it's atomic with the trim write — no race where
the asset could be removed between the check and the commit.

**Coverage.** `TrimClipToolTest` (11 tests): tail shrink preserving
`timeRange.start`, head trim preserving timeline anchor, simultaneous
head+tail, audio-clip parity (and preservation of `Clip.Audio.volume`),
text-clip rejection, both-fields-omitted rejection, asset-duration
guard, negative `newSourceStartSeconds` rejection, zero-duration
rejection, missing-clip failure (project state untouched on failure),
and post-mutation snapshot emission for `revert_timeline`.

**Registration.** Desktop, server, Android, iOS containers all register
`TrimClipTool(projects, media)` — media resolver is required for the
asset-duration guard. System prompt gained a "# Trimming clips" section
teaching the absolute-values vocabulary, the `timeRange.start`
preservation invariant, the `move_clip` chain pattern for reposition,
the text-clip rejection, and the asset-bounds guard. Key phrase
`trim_clip` added to `TaleviaSystemPromptTest` so removal regresses
loudly.


## 2026-04-19 — `SetClipVolumeTool` — the missing volume knob

**Context.** `Clip.Audio.volume` was settable at construction (e.g.
`add_clip` for an audio asset records the asset's natural level) but had
no post-creation editor. "Lower the background music to 30%" / "mute
this take" / "boost the voiceover" — basic editing requests with no
tool, forcing the agent into `remove_clip` + `add_clip`, which mints a
new ClipId and breaks downstream filter / source-binding state. Same
gap class as `move_clip` / `trim_clip` before they landed.

**Decision.** Add `set_clip_volume` in `core.tool.builtin.video`. Input
`(projectId, clipId, volume: Float)`; volume is an absolute multiplier
in `[0, 4]`. Mutates `Clip.Audio.volume` in place, preserving the clip
id and every other field. Audio clips only — applying it to a video or
text clip fails loud.

**Why absolute, not delta.** Same reasoning as `move_clip` / `trim_clip`:
deltas force the agent to read state before computing the call, doubling
round-trips for no semantic gain. The user usually means an absolute
("set music to 30%") anyway; relative phrasing ("a little quieter") is
something the agent can translate to absolute itself.

**Why a `[0, 4]` cap, not unbounded.** Most renderers (ffmpeg `volume`
filter included) hard-clip beyond ~4× before mix-bus headroom runs out,
and the symptoms of running over are speaker-damaging. If a user really
wants more gain than 4× they almost certainly want it at mix-bus / track
level (a future feature) rather than per clip — capping here surfaces
that earlier rather than letting an unsafe value propagate into the
render.

**Why audio only, why fail loud on video / text.** `Clip.Video` has no
`volume` field today (track-level mixing isn't modeled yet) and
`Clip.Text` obviously has no audio. Silently no-op'ing on the wrong clip
type would let the agent think its edit landed when nothing happened —
worse UX than a loud error. When per-track audio mixing arrives, that
gets its own tool with its own contract.

**Why `0.0` mutes rather than removes.** Mute and remove are different
intents. A `volume=0` clip stays addressable (e.g., for a future fade-in
tool to ramp it back up); a removed clip is gone. The agent calls
`remove_clip` when the user wants the clip *gone*.

**Coverage.** `SetClipVolumeToolTest` — nine cases: happy-path with
non-volume fields preserved, mute (`0.0`) without removal, amplification
above `1.0`, video-clip rejection, text-clip rejection, negative-volume
rejection with state-untouched check, above-cap (`5.0`) rejection,
missing-clip fail-loud, and post-mutation `Part.TimelineSnapshot`.

**Registration.** Added to all four composition roots (desktop, server,
Android, iOS Swift) right after `TrimClipTool`. System prompt gained
a "# Audio volume" section explaining the multiplier semantics, the
mute-vs-remove distinction, and the audio-only contract. Key phrase
`set_clip_volume` added to `TaleviaSystemPromptTest`.


## 2026-04-19 — `ExtractFrameTool` — closing the video → image edge

**Context.** The ML lane has `describe_asset` for image content
understanding and the AIGC lane uses image bytes as reference inputs
to `generate_image` / `generate_video`. Both operate on stills only,
yet most user material lands on the timeline as video. There was no
local primitive to lift a single frame out of a video for either
purpose, so questions like "what's happening at 00:14 in this clip?"
or "use the moment when the dog jumps as a reference" had no answer
short of an external screenshot tool. Same gap-class as `move_clip` /
`set_clip_volume`: a missing edge in an otherwise complete graph.

**Decision.** Add `extract_frame(assetId, timeSeconds)` in
`core.tool.builtin.video`. Delegates to `VideoEngine.thumbnail` —
already implemented on FFmpeg / Media3 / AVFoundation for timeline
preview — so no new engine surface is needed. Bytes are persisted
through a `MediaBlobWriter` and re-imported via `MediaStorage.import`,
so the result is a first-class image asset reusable anywhere an
imported still would be.

**Why piggyback on `thumbnail`.** Every platform engine already
implements it. Adding a separate `extractFrame` engine method would
duplicate the same `seek + decode + encode-png` pipeline three times
without changing what callers see. If we ever need richer outputs
(e.g. raw RGBA, H.264 keyframe export), that grows into a new method
then; today PNG bytes are exactly what callers want.

**Why `MediaBlobWriter` on Android + iOS.** Desktop and server already
have `FileBlobWriter` (in jvmMain) for AIGC output. Mobile didn't —
because no AIGC tool was wired on Android or iOS. ExtractFrameTool is
not AIGC but needs the same byte-to-asset bridge, so we added an
`AndroidFileBlobWriter` (under app cache dir) and a swift
`IosFileBlobWriter` (under `<caches>/talevia-generated`). Cache tier
is appropriate: extracts are reproducible, Project state holds the
canonical reference, OS eviction is recoverable.

**Why `media.import` permission, not `media.export`.** Local
derivation with no network egress, same category as `import_media`. A
user who wants to grab frames already trusts Talevia with the source
asset; treating it as a fresh import scope creep on `media.export` —
which is reserved for outputs the user will hand to others.

**Bounds policy.** Negative `timeSeconds` rejects with
`IllegalArgumentException`. Past-end rejects when the source's
metadata duration is known (some engines clamp to the last frame, but
explicit-past-end is almost always a bug in the agent's planning).
Exactly-at-end is allowed because some engines return the trailing
keyframe.

**Output shape.** `(sourceAssetId, frameAssetId, timeSeconds, width,
height)`. `width`/`height` echo the source's resolution (image
inherits the source's aspect by default); the new asset's
`MediaMetadata.duration` is `Duration.ZERO` so downstream tools can
distinguish a still from a clip.

**Coverage.** `ExtractFrameToolTest` — six cases: happy-path with
engine-saw-right-args + bytes-on-disk verification, resolution
inheritance, null-resolution propagation, negative-timestamp
rejection, past-duration rejection, missing-asset fail-loud.

**Registration.** All four composition roots register
`ExtractFrameTool(engine, media, blobWriter)` right after
`ImportMediaTool`. Prompt gained a "# Frame extraction" section
teaching the chain pattern (`extract_frame` → `describe_asset` for
video content questions) and the bounds contract. Compiler bullet in
the build-system mental model now lists `extract_frame` as a media
derivation primitive. Key phrase `extract_frame` added to
`TaleviaSystemPromptTest`.
