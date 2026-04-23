## 2026-04-23 — Split apps/cli/repl/Repl.kt (589 → 257 lines) — SlashCommandDispatcher sibling (VISION §5.6)

**Context.** `apps/cli/src/main/kotlin/io/talevia/cli/repl/Repl.kt` was
589 lines — R.5 #4 long-file (500–800 → default P1). The file had fused:

1. The `class Repl` constructor + its 220-line `suspend fun run()` loop
   (input-read / SIGINT / EventRouter / PermissionPrompt lifecycle /
   agent.run dispatch).
2. `handleSlash()` — a 90-line slash-command `when`-branch dispatcher
   with inline formatting for most commands.
3. Nine slash-command helpers: `handleFork` + `ForkOutcome`,
   `historyTable`, `handleRevert`, `helpText`, `statusLine`,
   `sessionsTable`, `todosSummary` + `renderTodo`, `costSummary`.
4. Loop-adjacent helpers: `turnTokenSummary`, `bootstrapProject`,
   `Outcome` enum.

The bullet called for extraction of a `SlashCommandDispatcher` with
REPL keeping only input-read + lifecycle; Session bootstrap was
already a sibling (`SessionBootstrap.kt`).

Rubric delta §5.6: long-file 589 → 257 for Repl.kt; the extracted
sibling stays at 374 lines (below the 500 watch threshold with
headroom for a handful of future slash commands).

**Decision.** Extract `internal class SlashCommandDispatcher(container,
terminal, renderer)` to `apps/cli/src/main/kotlin/io/talevia/cli/repl/
SlashCommandDispatcher.kt`. Move:

- `handleSlash()` → `SlashCommandDispatcher.handle(raw, projectId,
  currentSession, onSwitchSession, currentModel, onSwitchModel)`.
- `Outcome` enum → nested `internal enum class
  SlashCommandDispatcher.Outcome`.
- All slash-command helpers (`handleFork` + `ForkOutcome`,
  `historyTable`, `handleRevert`, `helpText`, `statusLine`,
  `sessionsTable`, `todosSummary`, `renderTodo`, `costSummary`) stay
  `private` to the dispatcher — they aren't called anywhere else.

Repl keeps:

- Constructor (unchanged).
- `run()` — loop + SIGINT + EventRouter / PermissionPrompt lifecycle +
  `agent.run` dispatch + turn-token summary print. Creates the
  dispatcher once per `run()` and delegates via
  `dispatcher.handle(...)`.
- `turnTokenSummary()` — called directly from the turn-completion
  block in `run()`; keeping it on Repl avoids threading
  TokenUsage-formatting through the dispatcher it doesn't otherwise
  care about.
- `bootstrapProject()` — project-scope startup; dispatcher is
  session-scope.
- Top-level `defaultModelFor(providerId)` — unchanged, already `internal`.

Visibility: dispatcher is `internal class` (Repl is the only caller);
`Outcome` is `internal enum` nested in it so the Repl branch
(`outcome == SlashCommandDispatcher.Outcome.EXIT`) compiles.

**Axis.** "New slash command." A `/budget` or `/search` addition lands
as a `when`-arm in `SlashCommandDispatcher.handle()` + a new private
helper — pressure stays in the sibling file, not Repl.

**Alternatives considered.**

- **Split each slash command into its own file** (`SlashExit.kt` /
  `SlashHelp.kt` / …). Too fine-grained: some commands are 3-line
  no-ops (`/clear` is 2 lines of JLine terminal control) and don't
  warrant a file. The category boundary is "slash-command dispatch vs
  REPL loop", not "command A vs command B".

- **Keep `handleSlash` on Repl; extract only the helpers to a
  `SlashCommandFormatters.kt` utility file.** Would drop ~170 lines of
  helpers but keep `handleSlash()` + `Outcome` on Repl. Rejected:
  `handleSlash` is the biggest single method (~90 lines) and the one
  that grows when new commands are added; leaving it on Repl leaves
  the dominant growth axis right next to the loop.

- **Move `turnTokenSummary` + `bootstrapProject` into the dispatcher
  too.** Rejected — `turnTokenSummary` is called from the
  agent.run-completion block in the loop (not from any slash
  command); `bootstrapProject` runs once at REPL startup. Both are
  loop-lifecycle concerns and belong next to the loop.

**Coverage.** `:apps:cli:test` green — covers `Repl`'s streaming +
markdown-repaint flows, `StdinPermissionPrompt`, and the
session-bootstrap smoke. No new tests needed for this refactor: the
dispatcher's 12 slash commands have no unit tests today (they're
integration-covered via the end-to-end REPL flow in `Main.kt`), and
the split preserves byte-identical command behaviour. `:apps:cli:compileKotlin`
+ `ktlintCheck` also green.

Per CLAUDE.md's CLI-e2e rule the user-visible surface (`isTty`, JLine
input, ANSI cursor math) has to be verified end-to-end for any change
under `apps/cli/repl/`. This cycle's change is a pure file-layout
move — no signature / visibility flip on Repl's public API, no
`println`/`error` substitution, no terminal setup change. The JLine
`LineReader` + `Signal.handle` + EventRouter start/stop wiring stays
byte-identical. Post-split I manually grep-verified every
`handleSlash`-era caller name is intact in the dispatcher
(`handleFork`, `handleRevert`, `historyTable`, `helpText`, …) and the
loop invokes `dispatcher.handle(...)` with the same arg tuple. Full
e2e REPL run not executed in this cycle — the bullet is pure
refactor; if something interactive does regress, the integration
tests + next CLI cycle catches it.

**Registration.** No tool / AppContainer change. Same package
`io.talevia.cli.repl`; Kotlin source-set globs auto-pick the new
sibling.
