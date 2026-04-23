## 2026-04-22 — CLI auto-resumes last session; add --session/--new (VISION §5.4 / CLI)

**Context.** Backlog bullet `cli-resume-last-session`. Before this change,
running `talevia` with no flags always created a fresh session, throwing away
the context the user built up in the previous run. `--resume` was already
wired (existing Clikt flag) and picked the most-recently-updated non-archived
session — but the user had to remember to pass it. The bullet calls for:
(1) the no-arg default to auto-resume, (2) a way to pick a specific session
by id prefix (`--resume=<id>` per the bullet), and (3) keep fresh-session
ergonomics available as an opt-in.

Rubric delta: §5.4 "CLI restarts remember context" 无 → 有.

**Decision.** New `BootstrapMode` sealed interface + pure `bootstrapSession`
function + three CLI flags:

1. **`BootstrapMode` sealed interface** (new `repl/SessionBootstrap.kt`):
   - `Auto` — resume most recent non-archived session; fall back to fresh
     if none exists. The new default.
   - `ForceNew` — always create fresh. Mapped from `--new`.
   - `ByPrefix(prefix: String)` — resume session whose id starts with prefix;
     fall back to fresh when zero or multiple matches. Mapped from
     `--session=<prefix>`. Mirrors the existing `/resume <id-prefix>`
     slash-command's uniqueness semantics so CLI and in-session behavior
     stay aligned.
2. **`bootstrapSession(sessions, projectId, mode, clock)`** — pure function
   over `SessionStore`. Returns `BootstrapResult(sessionId, reason,
   createdFresh)`. `reason` surfaces in the startup banner so the user can
   tell at a glance whether they resumed, landed fresh due to empty
   history, landed fresh due to prefix mismatch, etc. No terminal I/O, no
   cross-dependencies on the REPL harness → unit-testable in isolation.
3. **`TaleviaCli` Clikt opts**:
   - Keep `--resume` flag (back-compat alias for default Auto). Scripts
     that ran `talevia --resume` see no regression because Auto now does
     exactly what `--resume` used to do.
   - Add `--session=<prefix>` (string, default ""). Explicit pick by prefix.
   - Add `--new` (flag). Force fresh.
4. **Precedence collapsed into `resolveBootstrapMode`**: `--new` > `--session`
   > `--resume` > default. Collisions pick the stronger option rather than
   erroring — shell pipelines that mix flags (e.g. an alias always passing
   `--resume` plus an ad-hoc `--new`) get consistent behavior.
5. **`runProjectFlow` (subcommand path `talevia open <path>` / `talevia
   <path>`) honors the same three flags** via `parseSessionOption` +
   `resolveBootstrapMode`. Subcommand restArgs scan is deliberately simple
   (Clikt doesn't own this path) but matches how the pre-existing
   `--resume` stray-arg honoring worked.

**Behavior change — no-arg default flips.** Before: `talevia` → fresh
session. After: `talevia` → auto-resume. This is the bullet's explicit
direction. Users who want fresh each launch run `talevia --new`; scripts
that assumed fresh can update to `--new`. The banner's new `(reason)`
annotation ("resumed", "fresh (no prior sessions)", "fresh (--new)", etc.)
makes the mode visible every launch so the change isn't silent.

**Alternatives considered.**

1. **Keep no-arg default fresh; only add `--session=<prefix>`.** Rejected:
   directly contradicts the bullet's "无参时从 SqlDelightSessionStore 取最近
   一次活跃 session" direction. The whole point is ergonomic persistence —
   halfway implementation is worse than either extreme.
2. **Single `--resume` option that takes optional value** (Clikt syntax
   `--resume[=<prefix>]`). Clikt doesn't support optional-value options
   cleanly. Would need a custom converter. The separate `--session=<prefix>`
   is more explicit and reuses the pattern from other CLI tools (git's
   `--branch=` / `--ref=`, npm's `--registry=`).
3. **Archived sessions should be resumable by explicit prefix.** Rejected
   for this cycle: if the user explicitly archived something, silently
   resuming on prefix match defeats the archive. Keeps the `/resume`
   slash-command's existing archive-filter semantics aligned. Users who
   want an archived session back can unarchive first with
   `unarchive_session`, then restart.
4. **Pick cross-project recent.** Rejected: the CLI binds to the project
   returned by `bootstrapProject()` (max-updatedAt across projects),
   and session resume is always scoped to that project. Cross-project
   session pick would break the "one launch = one project" mental model
   the CLI already has.

业界共识对照:
- Most REPL tools auto-resume (python-repl's `sys.ps1` history, bash's
  history file, claude-code's own session-by-default behavior). "Remember
  my last context" is the assumed default; "fresh" is the opt-in.
- Clikt's flag + option separation is idiomatic
  (`--flag` = boolean, `--option=value` = string). `--session=<prefix>`
  matches.
- `/resume <id-prefix>` (existing slash command in Repl.kt) uses prefix
  matching; `--session=<prefix>` reuses the same semantics so the two entry
  points don't diverge.

**Coverage.** `SessionBootstrapTest` — 12 cases in one file:
- Precedence resolver (`resolveBootstrapMode`): `--new` wins; `--session`
  beats `--resume`; `--resume` maps to Auto; no-flags = Auto; blank
  `--session=""` falls through to Auto.
- Bootstrap behavior: Auto picks most-recent non-archived; Auto skips
  archived even when newer; Auto → fresh when no sessions; Auto → fresh
  when all archived; `ForceNew` creates fresh alongside existing active;
  `ByPrefix` unique match resolves; zero-match falls back to fresh with
  reason; ambiguous falls back to fresh with reason; prefix match skips
  archived; Auto doesn't cross project boundaries.

`:apps:cli:test` + `:core:jvmTest` + `:apps:desktop:assemble` +
`:apps:android:assembleDebug` + `:core:compileKotlinIosSimulatorArm64`
all green. `ktlintCheck` green.

The `cli-e2e-testing` playbook's three-layer TTY / DumbTerminal / PTY
recipe is scoped in CLAUDE.md for changes that touch `Renderer`,
`EventRouter`, `StdinPermissionPrompt`, Repl render paths, or tool JSON
schemas. This cycle only touches Repl's constructor signature + the
banner string (plain `println`) and adds an arg-parsing surface that's
fully covered by the pure-function unit tests. E2E playbook skipped for
that reason.

**Registration.** No tool registration — `bootstrapSession` is a top-level
helper, `BootstrapMode` is a sealed interface, neither is a `Tool<I, O>`.
Main.kt + Repl.kt + one new SessionBootstrap.kt + one new test file.
