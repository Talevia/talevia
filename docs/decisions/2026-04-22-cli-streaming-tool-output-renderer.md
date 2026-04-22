## 2026-04-22 — CLI renderer gains in-place rewrites for tool state + RenderProgress

Commit: `(pending)`

**Context.** Backlog bullet `cli-streaming-tool-output-renderer`. The CLI
[`Renderer`](../../apps/cli/src/main/kotlin/io/talevia/cli/repl/Renderer.kt)
already handled streaming assistant text (per-token deltas + Mordant repaint
on finalise) but was one-line-per-update for:

1. **`Part.Tool` state transitions** — `toolRunning(partId)` printed
   `⟳ toolId`, then `toolCompleted(partId)` printed a second line
   `✓ toolId · summary`. Rows stacked; no visual "upgrade from ⟳ to ✓ on
   the same row" you'd expect from `docker pull` / `npm install` /
   `git clone`-class CLIs.
2. **`Part.RenderProgress`** — not wired at all. `EventRouter` only had
   branches for `Part.Text` and `Part.Tool`; RenderProgress events fell
   into `else -> Unit`. A 3-minute export emits dozens of ticks with the
   same `jobId` and none reached the user.

VISION §5.4 (expert path) explicitly calls out that a power user watching
an export should see mid-render progress — the CLI was blind to it.

**Decision.** Extend the existing `Renderer` with "bottom-of-buffer" state
tracking so that:

- Same-`partId` `toolRunning → toolCompleted/Failed` repaints the row in
  place via `ESC[1A` + `CR` + `ESC[2K` + new line, when `ansiEnabled =
  true` (TTY-derived). Any intervening write invalidates the slot and the
  completion fresh-lines.
- `renderProgress(jobId, ratio, message, thumbnailPath)` is a new writer
  method: consecutive ticks on the same `jobId` repaint in place; a tick
  with a new `jobId` or any non-progress write fresh-lines. Terminal
  ticks (`ratio = 1.0` or `message = "completed"` / starts with
  `"failed"`) swap `⟳` for `✓` / `✗` and lock the slot so a stray
  re-emit doesn't overwrite unrelated output below.

Line format (non-styled view):
```
  ⟳ export-job-123 [=======>            ]  37% · preview · preview=…/frame-00037.jpg
```

20-char progress bar, `=…>` head so you can see where the tick landed,
short path with ellipsis (max 40 chars) for thumbnail. Mordant `Styles`
supply color.

**Key design choices.**

1. **New `ansiEnabled: Boolean = markdownEnabled` Renderer param.**
   Defaults to the existing `markdownEnabled` so test harnesses calling
   `Renderer(terminal, markdownEnabled = false)` see exactly the old
   per-line behaviour (and the existing `MarkdownRepaintTest` invariant
   — "no repaint may erase the bullet list" — still holds). Repl.kt now
   passes `ansiEnabled = isTty` explicitly: cursor-up is safe in any
   sane TTY even when the richer Mordant markdown repaint is opted out
   of (`TALEVIA_CLI_MARKDOWN` remains off by default because of
   documented CJK/wrap fragility).

2. **Bottom-slot is a single unit, not a stack.** `bottomToolPartId:
   PartId?` and `bottomRenderJobId: String?` — mutually exclusive;
   whichever was written last owns the bottom row. Every unrelated
   write (`println`, `error`, `retryNotice`, `streamAssistantDelta`,
   `ensureAssistantText`, `finalizeAssistantText` tail, `toolRunning`
   for a different partId, `renderProgress` for a different jobId)
   calls `invalidateBottomLocked()` first. Avoids the complexity of
   "where is each pending in-place slot" and matches the single-row
   in-place idiom of every established progress CLI.

3. **Completion locks the slot for RenderProgress.** Once a job reaches
   `ratio ≥ 0.999f` or a terminal message, `renderProgressTerminal`
   records the jobId and follow-up ticks fresh-line — this protects
   against a late re-emit overwriting a subsequent user prompt or
   other event that landed between.

4. **No width-based truncation of the progress line itself.** The ANSI
   `Styles.running()` / `Styles.meta()` prefixes wrap the visible text
   in escape bytes a column-counting `take(width)` would cut mid-
   escape, corrupting color state. Terminal soft-wrap is acceptable on
   narrow widths — the worst case is a second row below the bar that
   the next tick's `ESC[1A` only partially clears; still strictly
   better than today's "N lines forever". If this becomes a real
   complaint a visible-column stripper can be added later.

5. **Renderer owns jobId bookkeeping, not EventRouter.** The router
   forwards every RenderProgress PartUpdated to `renderer.renderProgress`
   without filtering; the renderer decides in-place vs fresh based on
   its own state. Matches the pattern used for `toolRunning` /
   `toolCompleted` (router forwards, renderer dedupes via
   `announcedTools` / `finalisedTools`).

**Alternatives considered.**

1. **Emit JSON Lines for machine consumers + pretty UI for TTYs.**
   Rejected — scope creep. The bullet was about UX, and the existing
   `markdownEnabled=false` path already produces grep-able newline-
   terminated lines for pipe consumers. A structured protocol is a
   separate design.

2. **Mordant `ProgressBar` widget.** Rejected — Mordant's progress
   widget assumes exclusive control of the bottom region and
   repositions cursor on animation frames. We already interleave tool
   cards, assistant text, errors, retry notices; composing Mordant's
   widget with all those flows would mean maintaining a ledger of
   currently-animating widgets anyway. A hand-rolled `=…>` bar with
   our own bottom-slot invalidation is simpler and more predictable.

3. **Buffer RenderProgress in EventRouter and emit a debounced tick
   every 200ms.** Rejected today — the engine is already emitting at
   a reasonable cadence (Started / N Frames / Completed for
   whole-timeline; 0% + per-clip + 100% for per-clip). A debounce
   belongs inside the engine if anywhere; the CLI rendering layer
   shouldn't drop user-visible state. If a future driver emits 50
   ticks/sec we can revisit with a rate limiter at the renderer.

4. **Dedicated `Part.RenderProgress` subscriber in EventRouter that
   subscribes to `BusEvent.PartUpdated` filtered by `Part.RenderProgress`
   type.** Rejected — the existing `when (val p = ev.part)` dispatch
   already has access to all part types in one place; branching off
   RenderProgress into its own subscription would double the
   coroutine count with no functional benefit.

**§3a structural check.**

| # | Check | Result |
|---|---|---|
| 1 | Net tool count growth | +0. No Core tools added or removed. |
| 2 | LLM context cost | None. Renderer changes don't touch tool schemas or prompts. |
| 3 | AppContainer churn | Zero. Renderer is constructed in `Repl.kt`; change is one extra constructor arg. |
| 4 | Default-behavior divergence | None in non-TTY / `markdownEnabled=false` paths. TTY users see the upgraded in-place UX. |
| 5 | KMP impact | None. `apps/cli/` is JVM-only. |
| 6 | Platform sync | N/A (CLI is Mac priority per CLAUDE.md). Desktop separately got its own mid-render preview in cycle `a3ac109`. |
| 7 | Kotlin idiom | Mutex-guarded mutable state (matches existing Renderer pattern); private helpers kept alongside. |
| 8 | Test coverage | New `StreamingToolOutputTest` with 8 scenarios: tool in-place (ansi on + off), non-matching-partId fallback, RenderProgress same-jobId, different-jobId, completion locks slot, println invalidates, thumbnail path rendering. |
| 9 | VISION alignment | §5.4 expert path — the user watching a long export sees the export. |
| 10 | Failure mode visibility | Unit tests assert exact ANSI escape bytes; fallback path (no ANSI) pins line-count invariant so regressions break a test. |

**Testing.**

- `./gradlew :apps:cli:test` — green (including `MarkdownRepaintTest`,
  `TextStreamingIntegrationTest`, new `StreamingToolOutputTest`).
- `./gradlew :apps:cli:ktlintCheck` — green.
- `./gradlew :apps:cli:compileKotlin :apps:cli:compileTestKotlin` — green.

**Visual end-to-end validation was NOT performed** in this cycle. Per
CLAUDE.md's "CLI changes require end-to-end validation" rule, the ANSI
cursor math's real-world correctness — especially across terminal
emulators (Terminal.app, iTerm, Alacritty, tmux) and on narrow widths
/ with CJK in tool summaries — needs to be eye-verified by running
`./gradlew :apps:cli:run` against a real provider and triggering both
a tool call and an export. The unit tests pin the ANSI escape bytes
that are emitted; they do not pin what a terminal does with them.

**Impact.**

- `apps/cli/.../Renderer.kt`: +128 lines; adds `ansiEnabled` param,
  `bottomToolPartId` / `bottomRenderJobId` state, `renderProgress`
  method, `formatRenderProgressLine` + `progressBar` + `shortenPath`
  helpers, three ANSI constants, `invalidateBottomLocked` helper, and
  threads `invalidateBottomLocked()` into `streamAssistantDelta` /
  `ensureAssistantText` / `finalizeAssistantText` / `println` /
  `error` / `toolRunning` / `retryNotice` to keep the bottom-slot
  state honest.
- `apps/cli/.../EventRouter.kt`: +6 lines; new
  `is Part.RenderProgress -> renderer.renderProgress(...)` branch in
  the PartUpdated handler.
- `apps/cli/.../Repl.kt`: +6 lines; passes `ansiEnabled = isTty`.
- `apps/cli/.../StreamingToolOutputTest.kt`: new, 209 lines, 8 tests.
- Backlog bullet `cli-streaming-tool-output-renderer` removed.

**Follow-ups.**

- **Visual E2E.** Run `./gradlew :apps:cli:run` → trigger a tool-
  calling prompt + an export → eyeball the ⟳→✓ collapse and the
  single-row progress bar across Terminal.app and iTerm.
- **Visible-column width stripper.** If narrow-terminal soft-wrap
  becomes a complaint, add an `ansiStripLen()` helper that counts
  visible columns for truncation. Not planned today.
- **Per-part streaming tool output.** Today `Part.Tool.state` only
  carries `input / outputForLlm / data` at terminal states —
  `Running` has only `input`. If future tools want to stream
  intermediate output (e.g., long-running `describe_image`), the
  extension point is `ToolState.Running` gaining an `intermediate:
  String?` field that `Renderer.toolRunning` can re-render in place
  analogously to `renderProgress`. Backlog-grade, not scope for today.
