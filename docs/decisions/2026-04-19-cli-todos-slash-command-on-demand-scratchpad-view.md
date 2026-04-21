## 2026-04-19 — CLI `/todos` slash command — on-demand scratchpad view

**Context.** The `TodoWriteTool` lands a `Part.Todos` on every plan update; the
EventRouter already surfaces each write via the tool's rendered `outputForLlm`
(streamed alongside other `Part.Tool` completions). But the user only sees the
list at the moment it's written — once scrolled past, there's no way to
re-inspect "what does the agent think the plan still is?" without scrolling
the buffer or starting a fresh turn.

**Decision.**
- New `/todos` slash command in `apps/cli/repl/SlashCommands.kt` that reads
  `SessionStore.currentTodos(sessionId)` (the helper that finds the latest
  `Part.Todos`) and renders it with the same `[ ] / [~] / [x] / [-]` markers
  the tool's LLM output uses. `in_progress` entries get accent colour,
  `completed / cancelled` entries get the dim `meta` colour so the active
  work stands out at a glance.
- **No live-streaming `/todos` chip.** Considered painting a persistent
  one-line status at the top of the terminal each time the plan changes
  (vim-status-line style). Rejected because (a) JLine's REPL model doesn't
  make that kind of sticky chrome cheap, (b) the tool output already appears
  inline in the transcript on every update so the discoverable signal is
  already there, and (c) scrollback noise from auto-repainting on every
  todo flip would drown out actual work. On-demand `/todos` is enough.
- Command catalogue stays alphabetised by category (session ops, model,
  session state summaries, utility). `/todos` slots next to `/cost` —
  both are session-state summaries.

**Alternatives considered.**
- **Live chip at top of screen.** Rejected per above.
- **Auto-dump todos at end of each turn.** Rejected — Claude Code's TodoWrite
  reminder system injects a reminder when the list is stale, which is a more
  targeted fix than blanket auto-dumping. We can add a similar hook later;
  it's out of scope for the initial CLI surface.
- **Dedicated ANSI pane via JLine's `AttributedString` / `Terminal.puts`.**
  Rejected — overkill for v1. Inline text matches the rest of the REPL's
  output style.

**Follow-ups.**
- Similar viewer in the desktop / server UIs (they render `Part.Todos` today
  as an unknown Part — those apps need explicit checklist panels to close
  the loop).

---
