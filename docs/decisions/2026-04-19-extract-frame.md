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
