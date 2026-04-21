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
