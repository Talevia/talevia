## 2026-04-20 — CLI polish: grouped `/help`, new `/status`, "did you mean"

**Context.** `/help` had grown to 12 flat entries — users scan-read them
looking for the one command they want and the signal-to-noise ratio
was getting thin. There was also no one-shot "where am I" answer —
operators had to run `/sessions` + `/model` + `/cost` to reconstruct
it. And mistyping a slash command (`/hitsory`) surfaced a generic
`unknown command` with no nudge toward the right name.

**Decision.**

- **Categories on `SlashCommandSpec`.** Added a
  `SlashCategory { SESSION, HISTORY, MODEL, META }` enum and a
  `category` field on `SlashCommandSpec`. `/help` groups commands
  under a short heading per bucket, keeps the existing "name ·
  argHint  description" layout within each group. Categories are
  ordered by how often an operator reaches for the group (session >
  history > model > meta), so the most-used commands sit at the top.
  First line of help now advertises tab-completion + unique-prefix,
  both of which were undocumented.

- **`/status` command.** Two-line summary: `project=… session=… ·
  <title>` + `model=provider/id · N turn(s) · in/out tokens · usd`.
  Aggregates from the same places `/cost` reads; no new state.

- **"Did you mean" for unknown commands.** Levenshtein edit
  distance ≤ 2 against every registered command. Capped so far-off
  typos (`/asdf`) don't produce irrelevant suggestions. The check is
  pure and tiny (the inner loop bounds are small and the command
  list is ~15 entries), so it runs inline on the unknown-command
  path.

- **Markdown repaint heuristic now spots GFM tables.** The existing
  `looksLikeMarkdown` already caught fenced code, bold, italics,
  bullets, blockquotes, numbered lists, and headings. Tables — `|
  col | col |` followed by `|---|---|` — were silently rendered raw.
  Added a two-line pass that catches them. Cheap: early-returns on
  the first match, scans `n-1` adjacent pairs max.

**Alternatives considered.**

- **Auto-running the closest match instead of just suggesting.** A
  `/help` typo would be fine but the catastrophic failure mode is
  `/reverr` auto-running `/revert` against the wrong anchor. No
  destructive slash command should ever run under a name the user
  didn't actually type.

- **Splitting `/help` into `/help session` / `/help history`
  sub-commands.** Rejected — the whole catalogue fits in a screen
  and forcing a two-step is worse UX than a grouped single screen
  for this count.

- **Prometheus-style counters for slash-command usage.** Overkill
  for a local REPL; skipped.

**Consequences.**

- `/help` is still one screen even with the new `/status` entry —
  categorisation gives visual breathing room without adding rows.
- No new external dependencies.
- Help output width grows by one column for the category heading
  indent; still fits in an 80-col terminal.

---
