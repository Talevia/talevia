## 2026-04-19 — `BashTool` — shell escape-hatch for agent tool calls (OpenCode parity)

**Context.** The typed tool catalogue covers the well-trodden paths
(read/write/edit/glob/grep for files, import/export for media, AIGC
dispatchers) but there's a long tail of "I need to call `git status` /
`ffprobe` / `ls -la` right now" that we'd otherwise have to mint a
dedicated tool for each time. Both Claude Code's Bash and OpenCode's
`tool/bash.ts` solve this with a single escape hatch that shells out —
gated behind ASK permission.

**Decision.**
- New `ProcessRunner` interface in `core/platform/` (commonMain) with
  `JvmProcessRunner` in jvmMain. Interface: `run(command, workingDir?,
  timeoutMillis, maxOutputBytes) -> ProcessResult{exitCode, stdout,
  stderr, timedOut, truncated, durationMillis}`. commonMain-level so
  `BashTool` can sit alongside the fs tools; only JVM implements it.
  iOS / Android don't register `bash` (same posture as `FileSystem` —
  no shell in reach of the agent on mobile).
- JVM impl uses `ProcessBuilder("sh", "-c", command)` and drains stdout
  / stderr on dedicated daemon threads to avoid pipe-buffer deadlock
  while `waitFor`ing. Per-stream `BoundedSink` caps capture at
  `maxOutputBytes` (128 KB default) — overflow sets `truncated=true`
  instead of blowing the tool-result payload. Timeout kills the
  process tree via `destroyForcibly()`; timed-out processes return
  `exitCode=-1, timedOut=true`.
- `BashTool` (`id="bash"`) in `core/tool/builtin/shell/`. Input is just
  `{command, workingDir?, timeoutMillis?}`. Non-zero exit is returned
  as data, not thrown — the agent should be able to read stderr and
  adjust (how a shell user would). We only throw on impossible-to-start
  errors (blank command, invalid workingDir).
- **Permission pattern = first command token**, not full command.
  `git status`, `git diff`, `git log` all bucket under `git`, so
  "Always allow bash `git`" is a useful rule. Using the full command
  would make every new argv combination re-prompt forever. Fallback
  to `"*"` on any parse failure so the dispatcher stays safe.
- Default rule: `bash.exec / * / ASK`. Arbitrary shell access is the
  single biggest blast-radius capability the agent has — never
  default-allow. Server containers via `ServerPermissionService`
  auto-reject ASK so headless deployments start deny-by-default;
  operators add ALLOW rules per command as needed.
- Hard timeout ceiling = 10 minutes. Anything longer belongs in a
  dedicated tool (export / AIGC) that can report progress properly,
  not in `bash`.

**Alternatives considered.**
- **Skip `bash` entirely; mint a dedicated tool per need.** Rejected —
  every new command needs a tool-file + registration + test, which we'd
  inevitably stop doing, and the agent would end up generating
  `import_media` calls where `ffprobe` would have been the right answer.
  The escape hatch exists so typed tools can stay focused.
- **Full-command permission pattern.** Rejected per above — every new
  argv combination re-prompts.
- **Stream output to the LLM in real time (like a human tailing a log).**
  Rejected — `BusEvent.PartUpdated` + incremental tool output is a
  feature we don't have for any tool yet; adding it here first would
  diverge. The 30s default timeout plus synchronous capture is fine
  for the "short one-shot command" use case.
- **Expose stdin.** Rejected — interactive commands (`git rebase -i`,
  `ssh`) make the agent's ASK prompt lie about what's going to happen.
  If the agent needs interactivity, a human-run terminal is the
  correct tool.

**Follow-ups.**
- Streaming tool output (for `bash` and long-running `export`s) is a
  broader upgrade. Not needed for v1; revisit when users report
  long-command frustration.
- Consider per-user allowlist of common commands to pre-populate as
  ALLOW rules (`git`, `ls`, `pwd`, `echo`) so the CLI doesn't prompt
  on obviously-safe invocations. Holding off until we see the real
  usage pattern.

---
