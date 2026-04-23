## 2026-04-23 — extract-frame-tool-bundle-write: bullet is stale; FfmpegEndToEndTest KDoc scrub (VISION §3a-3 hygiene)

**Context.** Backlog bullet `extract-frame-tool-bundle-write` claimed
`ExtractFrameTool` still constructed `MediaStorage` + `MediaBlobWriter`
(the old API) and wrote extracted frames to a global
`~/.talevia/media/generated/` directory outside the project bundle.
Walking the current code today:

1. `ExtractFrameTool.kt:45-48` takes a `BundleBlobWriter` as a ctor arg,
   not `MediaStorage`/`MediaBlobWriter`. No MediaStorage symbol is
   imported or referenced.
2. `ExtractFrameTool.kt:124` writes the frame via
   `bundleBlobWriter.writeBlob(pid, frameAssetId, bytes, "png")`,
   which lands under `<bundleRoot>/media/<assetId>.png` (the contract
   of `FileBundleBlobWriter`).
3. `ExtractFrameTool.kt:134` appends the new asset via
   `projects.mutate(pid) { it.copy(assets = it.assets + frameAsset) }`
   — same path every other bundle-writing tool uses.
4. All five `AppContainer`s register `ExtractFrameTool(engine,
   projects, bundleBlobWriter)` — CLI, Desktop, Server, Android,
   iOS (verified via grep). §3a-8 clean.

Rubric delta: none. The migration the bullet described is already in
`main` — it landed as part of the `9d0f70f` sweep that deleted
`MediaStorage` / `FileBlobWriter` and routed every AIGC tool through
`BundleBlobWriter`. Third stale bullet in a row (same shape as
`fork-project-tool-trim-stats-bug` and
`import-media-tool-bundle-resolver`), confirming the backlog-hygiene
pain point logged in the last two cycles.

**Decision.** No change to `ExtractFrameTool`. In the same commit,
scrubbed the one remaining stale reference to a deleted
`InMemoryMediaStorage` class in
`platform-impls/video-ffmpeg-jvm/src/test/kotlin/.../FfmpegEndToEndTest.kt:107`.
The comment explained "MediaPathResolver (InMemoryMediaStorage) resolves
asset ids to original file paths at render time" — the resolver is now
the per-project `BundleMediaPathResolver` constructed by `ExportTool`
from the loaded project. Updated the comment to match; narrative
intent unchanged.

**Alternatives considered.**
- **Leave the FfmpegEndToEndTest comment** as-is since it's test-only
  narration. Rejected: referencing a class name that doesn't exist
  anywhere in the repo is actively misleading — a future reader
  hunting for "InMemoryMediaStorage" would chase a ghost. Ghost
  references accumulate as dead weight; clean them when you pass by.
- **Do a full test-file-comment sweep for any historical `MediaStorage`
  mention across the repo.** Rejected: the live test comments in
  `RegenerateStaleClipsToolTest.kt` / `GenerateMusicToolTest.kt` /
  `ReplicateUpscaleEngine.kt` explicitly frame `MediaStorage` as "what
  the OLD path did" — they're accurate narrations that help readers
  understand why the test shape or behaviour is what it is. Those
  stay. The rule is: narrative-about-history stays; broken-reference-
  to-a-class-that-no-longer-exists goes.

**Coverage.** `:core:jvmTest` green (no source change under test), `ktlintCheck` green.

**Registration.** None — `ExtractFrameTool` registration already
correct in all 5 containers. Comment-only edit to a test file.
