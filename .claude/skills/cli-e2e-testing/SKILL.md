---
name: cli-e2e-testing
description: End-to-end validation recipe for changes to the Talevia CLI (apps/cli). Three-layer playbook — piped-stdin smoke, DumbTerminal integration test, real-PTY spot-check — plus the `TerminalBuilder` false-green gotcha that made earlier verifications misleading. TRIGGER when editing apps/cli/** (Renderer, EventRouter, StdinPermissionPrompt, Repl, CliContainer, slash commands) or when adding/changing a tool whose JSON schema is loaded by the CLI. Also when the user reports "CLI shows nothing / truncated reply / flash then cleared / token line glued to text". SKIP for desktop/server/iOS/Android, for pure core library changes that don't surface through the CLI, or for docs-only edits.
---

# CLI end-to-end testing

Any change that touches `apps/cli/` must be end-to-end validated before it's declared done. `ktlintCheck` + `:apps:cli:test` green is **not** sufficient — the CLI's user-visible surface depends on `isTty`, ANSI cursor math, CJK wrapping, and JLine quirks that unit tests routinely skip.

Pick the highest layer that still fits the change. Layers are cumulative: if the change is TTY-sensitive, Layer 3 is required **in addition to** Layers 1–2, not instead of them.

## Layer 1 — piped-stdin smoke (fastest, non-TTY path only)

```bash
./gradlew :apps:cli:installDist
mkdir -p /tmp/talevia_e2e && cd /tmp/talevia_e2e && \
  printf '<prompt>\n<permission answer e.g. A>\n/exit\n' \
  | /Volumes/Code/talevia/apps/cli/build/install/talevia/bin/talevia 2>&1 | tail -40
```

Prerequisites:
- `~/.talevia/secrets.properties` (or `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` in env) — otherwise the CLI exits at bootstrap.
- `:apps:cli:installDist` re-run after the change so the binary reflects edits.

Covers: agent loop, EventBus, tool dispatch, SessionStore persistence, permission flow, token accounting, raw streaming output.

Does **not** cover: anything gated on `isTty` / `markdownEnabled`. When stdin is a pipe, `System.console()` is null → `isTty = false`, `markdownEnabled = false`, `Styles.setEnabled(false)` — no ANSI escapes, no Mordant repaint, no colors.

Use for: tool schema changes, EventRouter changes, SessionStore / persistence, provider wiring, slash-command logic that doesn't depend on terminal state.

## Layer 2 — DumbTerminal integration test (deterministic, covers ANSI + stream ordering)

Patterns to follow: `apps/cli/src/test/kotlin/io/talevia/cli/repl/TextStreamingIntegrationTest.kt`, `MarkdownRepaintTest.kt`.

Key fixture — **do not** use `TerminalBuilder.builder().streams(...)`:

```kotlin
private fun dumbTerminal(out: ByteArrayOutputStream): Terminal =
    org.jline.terminal.impl.DumbTerminal(
        "test", "dumb",
        ByteArrayInputStream(ByteArray(0)),
        out,
        StandardCharsets.UTF_8,
    ).also { it.setSize(Size(120, 40)) }
```

`TerminalBuilder.builder().dumb(true).streams(in, out).build()` **silently promotes to `PosixPtyTerminal` on macOS**. Writes land in a PTY, `out` stays empty, and most assertions pass vacuously — false-green. This burned several rounds of "verification" in 2026-04 before the builder path was ripped out. Construct `DumbTerminal` directly, and pin the type if you care:

```kotlin
check(terminal::class.java.name == "org.jline.terminal.impl.DumbTerminal")
```

For full event-pipeline coverage: wire a real `EventBus` + `SqlDelightSessionStore` on `JdbcSqliteDriver.IN_MEMORY` + `EventRouter`, then publish events in the exact order `AgentTurnExecutor` emits them:

1. `TextStart` → `store.upsertPart(Part.Text(text=""))` — fires `PartUpdated(text="")`.
2. `TextDelta` → `bus.publish(BusEvent.PartDelta(...))`.
3. `TextEnd` → `store.upsertPart(Part.Text(text=full))` — fires `PartUpdated(text=full)`.

Assert on `out.toString(UTF-8)`. Strip ANSI for content checks:

```kotlin
val visible = captured.replace(Regex("\\u001B\\[[0-9;?]*[A-Za-z]"), "")
```

Do not reason about the tail via `captured.takeLast(N)` — Mordant's render can land mid-ANSI-sequence under test conditions and mislead you. Assert on substring presence of visible content, not on exact positions.

Covers: stream order, EventRouter filters, Renderer mutex behavior, finalise-vs-delta races, token-line separation, `Part.Text` empty-upsert handling.

Does not cover: real-TTY cursor arithmetic, PTY line-discipline, JLine LineReader interaction with streamed text, actual terminal width/wrapping of CJK content.

## Layer 3 — real PTY spot-check (TTY-only code)

Required when the change touches:
- `markdownEnabled=true` paths (gated behind `TALEVIA_CLI_MARKDOWN=on|1|true`, OFF by default).
- Cursor ANSI (`\e[NA`, `\e[0J`) in `Renderer.finalizeAssistantText` or elsewhere.
- JLine keybindings, slash-command tab completion, Ctrl+C handling.
- Anything depending on `terminal.width` / `terminal.height` being real.

Layers 1 + 2 **do not** substitute for Layer 3 on TTY-only code.

Options, in order of preference:
1. **Ask the user to run the CLI interactively and confirm.** Cheapest, most reliable. Do not declare a TTY-sensitive change done on your own.
2. Python pty (untested in this repo, likely works):
   ```bash
   python3 -c "
   import pty, os
   pid, fd = pty.fork()
   if pid == 0:
       os.execvp('/Volumes/Code/talevia/apps/cli/build/install/talevia/bin/talevia', ['talevia'])
   # parent: feed fd, read fd, log to file
   "
   ```
3. `script(1)` wrapping the CLI — stdin still needs a driver (`expect` or similar).

## Environment toggles

- `TALEVIA_CLI_MARKDOWN=on|1|true` — opt into the Mordant markdown repaint path. OFF by default (since 2026-04-21) because the cursor math regresses on CJK wide chars and interleaves badly with the permission prompt's direct terminal writes. Layer 2 tests for affected code should include a `markdownEnabled = true` variant when this flag is on.
- `TALEVIA_DB_PATH=:memory:` / `TALEVIA_PROJECTS_HOME=<tmpdir>` / `TALEVIA_RECENTS_PATH=<tmpdir>/recents.json` — isolated session / project state. Use for smoke runs so you don't poison `~/.talevia/talevia.db` with test sessions or `~/.talevia/projects/` with throwaway bundles.
- `TALEVIA_CLI_LOG_LEVEL=DEBUG` — verbose `~/.talevia/cli.log`.

## Debug order when the CLI misbehaves

1. `grep ERROR ~/.talevia/cli.log`, or look at the latest `agent: run.finish` line. It records provider HTTP errors, agent `FinishReason`, and token counts — usually tells you whether the problem is provider/schema, agent loop, or renderer before you start guessing.
2. Run Layer 1 smoke for the exact scenario. If the raw output is also wrong, the bug is below the renderer.
3. Write/extend a Layer 2 test that replays the offending event sequence. If that repros the bug, you have a deterministic local handle.
4. If 1–3 all look clean but the user still sees the bug, the change is in Layer 3 territory. Ask the user to retest interactively; don't declare done on Layer 1+2 alone.

## Checklist before declaring a CLI change done

- `:apps:cli:compileKotlin :apps:cli:test` green.
- `:apps:cli:installDist` re-run so the binary reflects the change.
- Layer 1 smoke captured for the user-facing scenario the change addresses — paste the output in the handoff.
- Layer 2 test added or extended when the change touches Renderer / EventRouter / event-ordering surface. `DumbTerminal` class pinned in the fixture to prevent silent PTY promotion.
- For TTY-only changes: explicitly note that Layers 1–2 don't cover it and ask the user to verify interactively before closing out. Never report TTY-only fixes as verified on your own.
