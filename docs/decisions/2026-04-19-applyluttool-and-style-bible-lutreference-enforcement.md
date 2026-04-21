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
