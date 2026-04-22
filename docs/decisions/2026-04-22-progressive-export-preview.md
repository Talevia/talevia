## 2026-04-22 — FFmpeg `-update 1` side-output + `RenderProgress.Preview` wire mid-render JPEGs (VISION §5.4 expert takeover)

Commit: `f899e45`

**Context.** `ExportTool.executeRender` used to stream only
`RenderProgress.Started` / `Frames` / `Completed` — the `Frames` events
carry a ratio but no visual. A 3-minute export produced zero mid-stream
artifacts: the expert path described in VISION §5.4 ("专家路径能中途接管")
couldn't actually be exercised, because there was nothing to inspect
before the final file landed. The backlog bullet called for a
`VideoEngine.render` flow event carrying preview bytes and a
corresponding `Part.RenderProgress.thumbnail` field.

**Decision.** Path-based, optional, opt-in per engine. Four changes:

1. **`RenderProgress.Preview(jobId, ratio, thumbnailPath)`** — new
   sealed-interface variant in
   `core/src/commonMain/kotlin/io/talevia/core/platform/VideoEngine.kt`.
   Carries an on-disk path to a JPEG the engine wrote, not bytes in
   memory. Contract:
   - The file is **mutable** — engines typically overwrite the same
     file on each tick (canonical `image2 -update 1` recipe).
     Consumers that want to keep a specific frame must copy it before
     the next event.
   - The path is valid until the terminal `Completed` / `Failed` event
     is emitted; the engine cleans the sidecar up in its `finally`.
   - Optional — engines that can't cheaply produce a side output
     simply never emit the variant. Media3 / AVFoundation keep their
     existing contract; only FFmpeg opts in today.

2. **`Part.RenderProgress.thumbnailPath: String? = null`** — new field
   in `core/src/commonMain/kotlin/io/talevia/core/session/Part.kt`.
   Default-null keeps existing SQLite blobs / JSON session records
   parseable (§3a rule 7). Carries the engine's `thumbnailPath`
   verbatim on `Preview` events; nullable + docstring call out that
   the file is ephemeral / UIs must read eagerly.

3. **`FfmpegVideoEngine.render`** fans the final `[outv]` label
   through `split=2[outv_main][preview_src]` and pipes `[preview_src]`
   through `fps=1,scale=320:-2[preview]`. A second output — `-map
   [preview] -c:v mjpeg -q:v 5 -update 1 -f image2 <sidecar>` — makes
   ffmpeg continuously overwrite a single JPEG as new frames arrive.
   The sidecar lives at `<outputDir>/.talevia-preview-<jobId>.jpg`
   (hidden so a directory listing of the user's output folder doesn't
   advertise the half-written artefact). The progress loop polls
   `Files.getLastModifiedTime` on the sidecar each time it processes
   a progress KV line (cheap — ffmpeg emits those several times per
   second) and emits a `RenderProgress.Preview` only when mtime
   advances. The sidecar is unconditionally deleted in the render
   flow's `finally` block.

4. **`ExportTool.runWholeTimelineRender`** gains the `Preview` case,
   producing `Part.RenderProgress(..., message="preview", thumbnailPath=ev.thumbnailPath)`.
   `Started` / `Frames` / `Completed` / `Failed` keep the existing
   shape — their parts have `thumbnailPath = null`.

Constants `PREVIEW_FPS = 1` and `PREVIEW_WIDTH = 320` live in the
FFmpeg engine's private companion. 320px matches
`FfmpegProxyGenerator.THUMB_WIDTH` / `Media3ProxyGenerator` so
consumers that render thumbnails in the UI can reuse one scaling
path regardless of whether a proxy or a live preview is on screen.

**Alternatives considered.**

1. **Embed JPEG bytes directly in `Part.RenderProgress.thumbnail: ByteArray?`**
   (closest to the bullet's literal phrasing) — rejected. `Part.RenderProgress`
   is persisted to SQLite as a JSON blob inside the session's message record.
   A 5-minute export at 1 preview/second produces ~300 events × ~20 KB JPEG =
   6 MB of base64 JSON in one session record. This is the §3a rule 3 failure
   mode (unbounded blob growth driven by tool invocations); path-based keeps
   session records lean (one `String?` per event) and still gives UIs the
   image. The sidecar is ephemeral — no durable write-amplification.

2. **Second ffmpeg process that tails the output file** — rejected.
   mp4's moov atom is written at the *end* of encoding; reading a
   half-written mp4 produces nothing useful. A matroska intermediate
   could work but would force the primary output's container or a
   dual-encode, both with bigger downsides than a trivial side-output
   filter. Matches the "use one ffmpeg process" design the existing
   engine already commits to.

3. **Emit previews only on the bus, no `Part.RenderProgress` change**
   — rejected. `Part.RenderProgress` is how the existing render
   pipeline surfaces *to persisted session state* — a UI component
   that opens a session after the render finished still wants to
   show a trailing preview (handoff across UI restarts). Bus-only
   previews would require every consumer to subscribe before the
   render starts, which isn't how sessions work. Path persists
   through the JSON blob cheaply; the file itself is ephemeral but
   that's fine — the persisted record is a pointer, not the artifact.

4. **`-vf` with frame sampling instead of `split` + filter-complex
   output** — rejected. The existing renderer already uses
   `-filter_complex` with labelled outputs (`[outv]`, `[outa]`, plus
   whichever drawtext chain may sit between concat and final). A
   parallel `-vf` would conflict with that graph. `split` + a second
   `-map` is the idiomatic ffmpeg recipe for "same frames, two
   encodings" (ffmpeg docs §filtergraph `split`).

5. **Bake preview generation into `concatMezzanines` instead of the
   whole-timeline `render` path** — deferred. The per-clip path
   already emits coarse `Frames` events (one per clip); adding
   mid-concat previews there means tapping concat's single ffmpeg
   invocation, same technique as what this cycle landed for the
   whole-timeline path. Worth doing but scope-creep for this
   bullet; leaving the fine cut for a follow-up cycle when driver
   surfaces (nobody is watching per-clip exports yet).

**Coverage.**

- `core/src/jvmTest/kotlin/io/talevia/core/tool/builtin/video/ExportToolTest.kt`
  — new `previewEventsForwardedAsRenderProgressPartsWithThumbnailPath`:
  drives a `PreviewEmittingEngine` that interleaves `Started` /
  `Preview(ratio=0.25)` / `Frames(0.5)` / `Preview(ratio=0.75)` /
  `Completed`. Asserts two preview parts with the engine-supplied
  paths and `message="preview"`, plus non-preview parts keep
  `thumbnailPath = null`. §3a rule 9 semantic-boundary case: covers
  the forwarding branch AND the anti-branch (non-preview events
  must not leak a path).

- `platform-impls/video-ffmpeg-jvm/src/test/kotlin/io/talevia/platform/ffmpeg/FfmpegEndToEndTest.kt`
  — new `renderEmitsProgressivePreviewEvents`: drives a real
  `FfmpegVideoEngine.render` on a 4-second generated clip, collects
  the full flow, asserts at least one `Preview` event arrived with
  ratio ∈ [0, 1] and a sidecar path under the export's parent dir
  ending in `.jpg`. Also verifies the sidecar is **cleaned up** after
  `Completed` fires — the terminal-state cleanup contract is real
  system behaviour, not docstring promise. Auto-skips when `ffmpeg`
  isn't on PATH, matching the existing E2E style.

- Existing whole-test-suite runs (`:core:jvmTest`,
  `:platform-impls:video-ffmpeg-jvm:test`, `:apps:server:test`,
  `:apps:android:assembleDebug`, `:apps:desktop:assemble`,
  `:core:compileKotlinIosSimulatorArm64`) all green. `ktlintCheck`
  clean.

**Registration.** No AppContainer change — this is a contract
extension on an existing `VideoEngine` interface + a new optional
field on an existing `Part` variant. No new tool, no new engine
binding, no new platform requirement.

§3a rundown:
- Rule 1 (tool growth): 0 new tools. ✓
- Rule 3 (`Project` blob): 0 new fields. ✓
- Rule 4 (binary state): the `thumbnailPath: String?` is naturally
  tri-valued (absent / present / stale after Completed) — the
  Preview event itself semantically carries the "fresh" signal. ✓
- Rule 7 (serialization): new field has `= null` default; the new
  sealed variant forces exactly one compile error in the single
  exhaustive consumer (`ExportTool`), which this change fixes. ✓
- Rule 8 (5-platform wiring): no new tool; the contract extension
  is backward-compatible for Media3 / AVFoundation (they keep their
  no-Preview behaviour). ✓
- Rule 10 (LLM context): `Part.RenderProgress` isn't serialised into
  the LLM context window; the tool's `outputForLlm` string is
  unchanged. 0 tokens added. ✓

---
