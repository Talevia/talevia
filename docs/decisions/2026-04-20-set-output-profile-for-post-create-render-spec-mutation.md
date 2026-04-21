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
