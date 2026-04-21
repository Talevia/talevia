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
