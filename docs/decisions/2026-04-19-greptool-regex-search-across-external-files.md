## 2026-04-19 — `GrepTool` — regex search across external files (OpenCode parity)

**Context.** `read_file` / `write_file` / `list_directory` / `glob` cover
*discovery by name* and *read one / write one*, but there was no tool for
"find which file mentions this phrase". On multi-step edits the agent had
to either (a) read every candidate file individually (N LLM turns, N
permission prompts), or (b) ask the user to grep themselves. Both are
regressions vs OpenCode's `tool/grep.ts`, which is one of the most-called
tools on long sessions.

**Decision.**
- New `grep` method on the `FileSystem` commonMain interface with matching
  JVM impl in `JvmFileSystem`. Walks a root path (directory recursively, or
  a single regular file), reads each candidate with `Files.readAllLines`
  under UTF-8 strict decode, applies a `kotlin.text.Regex` per line, and
  returns `{path, line(1-based), content}` rows. Optional `include` is a
  glob (on the file's absolute path) for extension scoping; optional
  `caseInsensitive=true` flips the regex option.
- **Silent-skip policy.** Binary / non-UTF-8 files, files over the size
  cap (default 10 MB), and files that fail OS-level I/O are silently
  dropped from the walk. A grep that errors because one file in a
  directory is a `.mp4` would be useless in practice. The caller still
  sees `filesScanned` so "0 matches across 47 files" vs "0 matches
  across 0 files" are distinguishable.
- **JDK regex, NOT ripgrep.** OpenCode shells out to ripgrep. We stay on
  `kotlin.text.Regex` so the Core doesn't gain an external binary
  dependency (same reasoning as keeping `FfmpegVideoEngine` under
  `platform-impls/` rather than `core/`). Slower on huge trees, not a
  concern at the sizes the agent actually greps.
- **Permission reuses `fs.read`.** Same disclosure class as `ReadFileTool`
  — both surface file contents to the LLM. Pattern is `path`, so an
  "Always allow fs.read on ~/Documents" decision naturally scopes both
  read_file and grep under that tree. Modelled after `GlobTool`'s reuse
  of `fs.list` for the same reason.
- **Caps.** `DEFAULT_MAX_GREP_MATCHES = 200` (≈ 40 KB payload at 200 B per
  match line), `DEFAULT_GREP_LINE_CAP = 512` (first 512 chars of a
  matching line, then elided). Together they bound the tool-result
  payload so one hit on a minified bundle can't blow the agent's context.
- Wired into CLI + desktop + server containers (the three that already
  register `JvmFileSystem`). iOS + Android stay unregistered because
  `FileSystem` is unimplemented on those platforms — same posture as the
  other four fs tools.

**Alternatives considered.**
- **Shell out to ripgrep.** Rejected: forces every deployment to install rg,
  adds a platform binary to the Core. Kotlin regex is good enough at our
  scale.
- **New `fs.grep` permission.** Rejected in favour of reusing `fs.read`.
  Separate permissions would ask twice for "let me grep a file I already
  approved reading" and fragment the "Always allow on this directory" UX.
- **Return an iterator / stream.** Rejected — tool-result payloads are
  one-shot. Capping at 200 matches and surfacing `truncated=true` gives
  the agent the hint to narrow the pattern or `include` glob.
- **Error on binary files.** Rejected — the agent's search intent is
  "content under this tree", not "every file must be parseable". Silent
  skip matches ripgrep's default and what users actually expect.

**Follow-ups.**
- If sessions grow to grepping large monorepos, we can add an optional
  ripgrep fast-path (detect on PATH, fall back to the JDK impl) — the
  tool contract stays the same.

---
