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
