## 2026-04-20 — `MultiEditTool` — sequential atomic edits on a single file (OpenCode parity)

**Context.** The agent often needs several edits in the same file in
one logical step — rename a symbol, then add a new declaration, then
update the export list — and today that's three round-trips through
`edit_file`. Each round-trip pays a turn of LLM latency and risks
the file landing in a half-edited state if the chain is interrupted
between calls. Both Claude Code's MultiEdit and OpenCode's
`tool/multiedit.ts` solve this with one call that takes a list of
edit ops.

**Decision.**
- New `MultiEditTool` (`id="multi_edit"`) in
  `core/tool/builtin/fs/`, commonMain. Takes the same `FileSystem`
  injection as `EditTool`. Input
  `{path, edits: [{oldString, newString, replaceAll?}]}`; output
  `{path, totalReplacements, bytesWritten, perEdit}`.
- **Sequential semantics**: edit N runs against the running result of
  edit N-1, exactly mirroring OpenCode and Claude Code. The agent can
  plan a chain like "rename `Foo` → `Bar`, then insert a new method
  below the renamed declaration" without two round-trips.
- **Atomic**: read the file once, validate + apply every edit in
  memory, write once at the end. If any edit fails (oldString missing,
  or matches multiple times without `replaceAll=true`), the whole
  call fails and the disk file is left untouched. The "oldString
  missing" error message says "after applying $idx prior edit(s)" so
  the agent knows whether it was its own earlier edit that consumed
  the text it was looking for.
- **Permission reuses `fs.write` with the `path` field** — same
  spec as `EditTool` and `WriteFileTool`. An "Always allow fs.write
  on /tmp/foo" decision covers all three tools, not just one.
- **Per-edit replacement counts** flow back in `Output.perEdit` so
  the agent can audit what each step actually did (e.g. a careless
  `replaceAll=true` that hit far more sites than intended).
- Wired into CLI / desktop / server; mobile platforms stay untouched
  (same posture as the rest of the fs family — mobile holds at "no
  regression" per the platform-priority rules).
- **System prompt updated** to steer toward `multi_edit` over a
  chain of `edit_file` calls when several changes target the same
  file.

**Alternatives considered.**
- **Per-edit error recovery / partial apply.** Rejected — the value
  proposition is atomicity. If edit 3 of 5 fails, "I left the first
  two applied and bailed" is worse than "nothing changed", because
  the agent has to read the file again, work out what happened,
  and decide what to do. Atomic = the agent can re-plan from a
  known state.
- **Allow heterogeneous targets (different `path` per edit).**
  Rejected — that's a different tool ("apply N edits across N
  files"). Per-file atomicity stops being meaningful and the
  permission scope becomes vague (one path field can't represent
  the whole batch). Keep this tool single-file like OpenCode and
  Claude Code do.
- **Generate a unified diff instead of edit ops.** Rejected for
  v1 — apply-patch semantics are noticeably harder to get right
  (context lines, line-number drift, fuzzy matching) and the
  benefit over sequential edits is marginal for the in-flight
  workload. Revisit if we see the agent reaching for a true patch
  format.

**Follow-ups.**
- If a session ever fails an entire `multi_edit` because edit N
  conflicts with the running result of edit N-1, that's signal
  to add a "preview" mode (return what the edits would do without
  writing). Hold off until we see it in real traces.

---
