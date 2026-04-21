## 2026-04-20 — AGENTS.md / CLAUDE.md project-instruction discovery

**Context.** Operators already drop `AGENTS.md` / `CLAUDE.md` into their
project roots for claude-code and OpenCode, and Talevia's own repo has
a 150-line `CLAUDE.md` with ongoing guidance. Until now the Talevia
agent saw none of that — every container wired in
`taleviaSystemPrompt(extraSuffix = <a tiny fragment>)` and the per-project
rules silently did nothing. That breaks the VISION §4 dual-user
promise: the "professional path" explicitly includes overriding agent
defaults per project, and we were forcing the user to stuff their
rules into every prompt manually.

**Decision.** OpenCode-parity discovery layered in at the container
level:

- **`core/commonMain/.../agent/ProjectInstructions.kt`** — platform-agnostic
  `ProjectInstruction(path, content)` record + `formatProjectInstructionsSuffix`
  that emits a `# Project context` section with one `## <path>` subheader
  per file. Empty input → empty string so containers can pass it through
  unconditionally.
- **`core/jvmMain/.../agent/InstructionDiscovery.kt`** —
  `InstructionDiscovery.discover(startDir, …)` walks from `startDir`
  upward through parent dirs (capped at `maxWalkDepth=12` levels), at
  each level trying `AGENTS.md` and `CLAUDE.md`. Optionally mixes in
  globals from `~/.config/talevia/AGENTS.md`, `~/.talevia/AGENTS.md`,
  `~/.claude/CLAUDE.md`. Dedupes by canonical path. Defensive byte
  caps (64 KiB per file, 128 KiB total) keep a stray huge instruction
  file from blowing out context.
- **Ordering** is outermost-first / innermost-last so the nearest
  (most specific) file lands at the tail of the system prompt where
  LLMs weigh it more heavily on conflict. Globals prepend so
  machine-wide defaults never beat project-specific rules.
- **Wired into all three JVM containers** (CliContainer, AppContainer,
  ServerContainer). Lazy-cached per container — `/new` sessions inherit
  the same rules rather than re-walking disk per turn. The server
  still prepends the headless-runtime permission note; the CLI still
  prepends its cwd/home context.

**Alternatives considered.**

- **Do it in `commonMain` with an injected file-reader abstraction.**
  Would work on iOS/Android too, but current platform priority is
  Core > CLI > Desktop; iOS/Android don't have a cwd concept that
  maps cleanly to AGENTS.md discovery (apps are sandboxed). Starting
  JVM-only covers the three platforms that actually have a shell
  working directory, and we can lift the logic into common with
  `okio.FileSystem` later if iOS/Android ever need it.
- **Re-read on every turn so edits land live.** Matches the "hot
  reload" story but doubles disk I/O per turn and can create
  inconsistent states mid-conversation when the user edits AGENTS.md
  between two turns. OpenCode also loads once per session; matching
  that makes session behaviour predictable. Users who edit AGENTS.md
  can `/new` (or restart the CLI) to pick up the change.
- **Stop the walk at `.git` or the worktree root.** Cleaner
  boundary, but requires detecting a repo marker and then the
  behaviour diverges for non-git projects. The 12-level depth cap
  already bounds the walk; instruction files outside a real project
  are rare enough that the extra plumbing isn't worth it.
- **Expose a config knob (`TALEVIA_AGENTS_MD=off`).** Deferred — no
  user has asked yet and the defaults cause no harm (blank files are
  filtered, oversized files are skipped). Revisit if discovery picks
  up something surprising in the wild.

**Files touched.** `core/src/commonMain/.../agent/ProjectInstructions.kt`
(new), `core/src/jvmMain/.../agent/InstructionDiscovery.kt` (new),
`core/src/jvmTest/.../agent/InstructionDiscoveryTest.kt` (new, 11 tests),
`apps/cli/.../CliContainer.kt`, `apps/desktop/.../AppContainer.kt`,
`apps/server/.../ServerContainer.kt`.

---
