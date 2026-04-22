## 2026-04-22 — Desktop render-progress panel renders `thumbnailPath` as `Image`

Commit: `(pending)`

**Context.** Backlog bullet `desktop-render-preview-panel`. Cycle 32 added
`Part.RenderProgress.thumbnailPath: String?` so the FFmpeg engine can
write a small JPEG snapshot of the in-progress frame while the export
is running (VISION §5.4 "expert path can see mid-render output"). But
`apps/desktop/.../Main.kt` only consumed `RenderProgress.ratio` +
`RenderProgress.message`, producing the "started / 38% / 72% / done"
progress text. Nothing on screen until the export finished, which for
a 3-minute timeline is 30+ seconds of blank staring at the progress
bar.

**Decision.** Add a mid-render preview `Image` under the existing
`LinearProgressIndicator` in the export column. Re-read the JPEG bytes
from `p.thumbnailPath` on every `RenderProgress` tick that carries a
non-null path, decode via Skia (`org.jetbrains.skia.Image.makeFromEncoded`),
hold as a Compose `ImageBitmap` state, clear on completion.

Key design choices:

1. **Re-read per tick, not cache by path.** The engine overwrites the
   same file between preview emissions (documented on
   `Part.RenderProgress.thumbnailPath`: "The file at this path is
   overwritten by subsequent preview ticks and deleted once the render
   completes"). Caching by path would show the same frame for every
   tick. Caching by `createdAt` would work but re-reading is simpler
   and the decode is cheap (JPEG of a video thumbnail, ~10–30KB).

2. **Silent `runCatching`.** The file may be in the middle of a rotate
   or already deleted at the completion tick. Losing one frame's
   preview is strictly better than crashing the UI or popping an
   error dialog — the progress bar itself doesn't depend on the
   thumbnail. `log += "preview decode failed"` would be noisy for a
   transient race.

3. **Clear on completion, not persist.** The final `previewPath`
   (full exported file) is loaded into `VideoPreviewPanel` by the
   existing `Part.Tool` completion arm. The mid-render thumbnail is
   expert-path scaffolding; it should disappear once the finished
   video is available so the UI doesn't double-show the frame. Clear
   runs in the same `delay(1_200) + null-out` block that already
   clears `renderProgress` / `renderMessage`.

4. **`aspectRatio` sized to the bitmap.** FFmpeg's `preview` filter
   doesn't guarantee a fixed size; assume it matches the output
   profile. Using `bmp.width / bmp.height` as the aspect ratio keeps
   the preview in proportion without hard-coding 16:9.

**Alternatives considered.**

1. **Dedicated `RenderPreviewPanel` composable** in its own file —
   overkill at 6 lines of Compose. If this grows (per-clip path
   preview, cache-hit indicator overlay, "clip 2 of 5" ordinal) a
   split is cheap; today it's three lines inside the existing
   export column.

2. **Load `thumbnailPath` via `VideoPreviewPanel`** (the same
   JavaFX-backed component already shown for finished exports) —
   rejected. The final-preview component is video-capable (loads
   MP4s, supports seek); loading a single JPEG through a JavaFX
   `MediaPlayer` is a square-peg / round-hole mismatch. Compose's
   `Image` is what this wants.

3. **Persist the last preview across renders** (chain of
   mid-export frames visible as history) — rejected as feature
   creep. The bullet asked for "see the latest preview"; history is
   a different concept. If a user wants a filmstrip view, that's a
   separate backlog item.

**Testing.** `:apps:desktop:compileKotlin` + `:apps:desktop:assemble`
+ `:apps:desktop:ktlintCheck` all green. Unit-test coverage would
require a fake `BusEvent.PartUpdated` emitter plus a Compose test
rule; neither is wired in this repo. **Visual end-to-end validation
was NOT performed** in this cycle — the FFmpeg preview path requires
a running `ffmpeg` with `-progress` plumbing, and this was an
autonomous loop cycle without the export rig stood up. Per CLAUDE.md
"if you can't test the UI, say so explicitly": the recommendation
is to run the desktop app against a real project + click Export to
confirm the preview image renders correctly before treating this as
done. Compile-time wiring is validated; runtime visual is not.

**Impact.**

- `apps/desktop/.../Main.kt`: +1 state var (`renderThumbnail`), +1
  decode branch in the `Part.RenderProgress` collector, +1 `Image`
  under the existing progress bar, +1 cleanup in the completion
  delay, 4 imports. No refactor of existing UI.
- No API surface change on `core/` — consumer of an existing field.
- Backlog bullet `desktop-render-preview-panel` removed.

**Follow-ups.** If a future `RenderProgress.Preview` event payload
grows (resolution, clip ordinal, cache-hit flag), the decode branch
is the extension point. Not planned today.
