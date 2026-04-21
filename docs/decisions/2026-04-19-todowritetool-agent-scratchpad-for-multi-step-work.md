## 2026-04-19 — `TodoWriteTool` — agent scratchpad for multi-step work (OpenCode parity)

**Context.** Gap-finding pass against OpenCode's Core surface (`docs/VISION.md`
§5 rubric + CLAUDE.md "OpenCode as runnable spec") flagged the `todo` /
`todowrite` tool pair (`packages/opencode/src/tool/todo.ts`,
`packages/opencode/src/session/todo.ts`) as the most load-bearing Core gap
before moving on to the CLI. The Talevia agent previously had no first-class
way to expose a multi-step plan — it could narrate "I'll do A, then B" in prose
but nothing was machine-readable by the CLI / desktop / server UIs, and the
agent itself had no scratchpad signal to drive the "exactly one task
in_progress at a time" discipline. This shows up in practice as the agent
skipping steps on complex requests ("draft a vlog → re-color shot 2 → retitle
→ export") because there was no persistent artifact forcing it to maintain a
plan across turns.

**Decision.**
- New `TodoWriteTool` under `core/tool/builtin/` with typed
  `Input(todos: List<TodoInfo>)` / `Output(count, todos)`. Each call fully
  replaces the current plan. Renders to the LLM with OpenCode-style markers —
  `[ ]` pending, `[~]` in_progress, `[x]` completed, `[-]` cancelled. System
  prompt gains a short "# Agent planning (todos)" section that mirrors Claude
  Code's TodoWrite guidance (use when 3+ steps, one in_progress at a time,
  flip completed immediately, prefer cancelled over silent drop).
- **Ride the existing Parts JSON-blob schema, do NOT mint a new Todos SQL
  table.** New sealed variant `Part.Todos(todos: List<TodoInfo>)` with
  `TodoInfo / TodoStatus / TodoPriority`. The latest `Part.Todos` in a session
  is the current plan; a helper `SessionStore.currentTodos(sessionId)` encodes
  that lookup. OpenCode ships a separate `todo` table (`session/todo.ts`) that
  stores rows by `(sessionId, id)`; we rejected the separate table because the
  Parts table already gives us session-scoped ordering, JSON-blob evolution,
  bus events (`PartUpdated` fires for free so UIs see todo changes with zero
  extra wiring), compaction integration (TokenEstimator accounts for them),
  and fork/rebind semantics. The trade-off is that a todo update is modelled as
  "append a new Part.Todos, readers take the latest" rather than "update one
  row" — fine because plans are small (typically 3–10 entries) and the write
  volume is bounded by agent turns.
- **Permission `todowrite` defaults to ALLOW.** It's purely local state with
  zero side effects (no network, no disk, no timeline mutation) and prompting
  on every plan update would make the tool useless for its intended purpose.
  Rule added to `DefaultPermissionRuleset` alongside the other trivial
  always-allows (`echo`, source reads/writes).
- **Todos are NOT replayed to the LLM via history.** Provider `listMessages` →
  LLM mapping already uses an `else -> null` pattern on unknown Parts, so the
  new `Part.Todos` falls through silently. The tool's `outputForLlm` already
  communicates the current plan to the LLM on the turn it was written, and
  serialising every past plan on every turn would balloon context for no
  signal. UIs read the latest `Part.Todos` directly.
- Wired into all five AppContainers (CLI, desktop, server, Android, iOS). iOS
  passes `clock` explicitly because SKIE doesn't surface Kotlin default
  arguments to Swift.

**Alternatives considered.**
- **Separate `Todos` SQL table, OpenCode-style.** Rejected per above — more
  schema surface for a feature already well-modeled as a Part stream.
- **Reuse an existing Part kind (e.g. Reasoning).** Rejected — todos have a
  structured shape (status/priority), are mutable as a set, and have distinct
  UI treatment (checklist). Shoehorning them into `Reasoning` would force
  string-parsing on the UI side and leak into the LLM replay lane.
- **Keep the plan in assistant message prose only, no tool.** Rejected — the
  whole point is a machine-readable artifact the model is forced to maintain,
  with a side-effect-free tool call as the write channel. OpenCode's own
  internal traces show the todo tool is the single most-called tool on
  multi-step tasks; we want the same forcing function.
- **Expose the plan as a slash command only (no tool).** Rejected — slash
  commands are user-initiated; the plan update needs to originate from the
  agent autonomously, mid-turn.

**Migration.** Zero — new variant is additive and uses existing Parts storage.
SQLDelight `Parts` table's `kind` column gains `"todos"`; exhaustive `when`
paths in `SqlDelightSessionStore.decodePart` / `rebindPart` / `kindOf` and
`TokenEstimator.forPart` updated. Existing rows continue to decode.

**Follow-ups.**
- CLI `/todos` slash command (Task 3) so `repl` users can inspect the current
  plan without scrolling the scrollback.
- Desktop / server UI pass to render `Part.Todos` as a checklist panel;
  currently they'll render as "unknown Part" until those apps grow explicit
  handlers.

---
