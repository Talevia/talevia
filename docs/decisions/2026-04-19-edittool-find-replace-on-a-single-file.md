## 2026-04-19 — `EditTool` — find/replace on a single file (OpenCode parity)

**Context.** `write_file` is the only way the agent can mutate an external
file today. For a one-line tweak to a subtitle / script / prompt file, that
means re-emitting the entire file through the LLM — expensive in tokens and
easy to fumble (whitespace drift, stale content near the edit site). Claude
Code's `Edit` and OpenCode's `tool/edit.ts` both solve this by making the
agent send only the matching substring and its replacement.

**Decision.**
- New `EditTool` (`id="edit_file"`) in `core/tool/builtin/fs/`. Reads the
  file via `fs.readText`, applies a literal-string find/replace, writes via
  `fs.writeText`. No new `FileSystem` method — the abstraction stays lean;
  edit is composition over the existing read/write primitives.
- **Uniqueness enforced by default.** If `oldString` matches zero times,
  fail with "not found — read the file first". If it matches >1 times and
  `replaceAll=false`, fail with "matches N times — pass replaceAll=true or
  widen oldString". This is the semantics Claude Code's Edit uses; silent
  first-match-wins would make multi-site refactors look like they worked
  when they didn't.
- **No "must have read the file this turn" rule.** Claude Code enforces
  that you can't edit a file you haven't Read in the current turn. We
  don't have turn-level tracking for per-file read state wired into
  `ToolContext`, and the uniqueness-enforcement already catches most
  "agent hallucinated the content" failures at the `oldString not found`
  check. Revisit if we see the agent blindly editing files it never
  fetched.
- Permission reuses `fs.write` with `patternFrom = path field` — an
  "Always allow fs.write on ~/Documents/scripts" decision covers both
  `write_file` and `edit_file`, which is the user's mental model.
- Wired into CLI / desktop / server containers (iOS / Android skip
  `FileSystem` entirely — same posture as `ReadFile` / `WriteFile`).
- System prompt's "# External files" section adds the tool + one line of
  guidance: "Prefer `edit_file` over `write_file` for local changes — it
  sends only the substring to replace, not the whole file".

**Alternatives considered.**
- **Line-range diff (`oldLines: 10..12`).** Rejected — brittle, breaks if
  the agent miscounts by one, and offers nothing over substring matching
  for the actual use cases (tweaking a prompt, fixing a typo in a
  subtitle).
- **Patch format (`diff --git` / `edit_file: @@ -3,5 +3,5 @@`).** Rejected
  — heavier for the LLM to generate, and the uniqueness check is simpler
  for the agent to reason about than a line-aware hunk.
- **`replaceAll=false` silently replaces the first match.** Rejected —
  silent ambiguity is how refactors get half-applied. An explicit failure
  mode teaches the agent to either widen `oldString` or opt into
  `replaceAll`.

**Follow-ups.**
- Consider a per-turn "files read this turn" hint in `ToolContext` if we
  start seeing the agent edit files it never fetched. Out of scope for v1.
- Desktop / server UIs currently render each `edit_file` call as its
  rendered `outputForLlm`; a proper inline diff renderer would be nicer.

---
