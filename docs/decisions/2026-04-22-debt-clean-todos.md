## 2026-04-22 — debt-clean-todos: 32-baseline audit finds zero code-debt comments

Commit: `c1b3feb`

**Context.** The `debt-clean-todos` backlog bullet took the raw
`grep -rnE 'TODO|FIXME|HACK|XXX' core/src/commonMain/kotlin | wc -l = 32`
as a baseline and asked: "走读 32 条 TODO，凡能 5 分钟内修的就修，剩余的把未来的
backlog bullet 补上；目标 ≤ 25." The assumption was that 32 regex matches
corresponded to ~32 code-level TODOs worth triaging.

**Decision.** After walking all 32 hits, **none are code-debt TODO /
FIXME / HACK / XXX comments**. The distribution:

- **27 user-facing template placeholders** (intentional, user-visible
  strings in `Vlog / Narrative / Tutorial / MusicMv / Ad` template
  bodies — e.g. `"TODO: describe the visual style"`,
  `"TODO: product name"`, `"TODO: one-sentence pitch"`). These are
  copy the agent or expert user replaces via `update_source_node_body`
  when filling in a scaffolded project. They're documented in
  `CreateProjectFromTemplateTool.kt`'s KDoc as the explicit
  placeholder convention.
- **3 documentation mentions in `CreateProjectFromTemplateTool.kt`**
  (lines 44, 104, 186) describing the placeholder convention itself
  to agents calling the tool.
- **1 regex example in `GrepTool.kt` schema description**
  (`"Regex applied per line (Kotlin/Java flavour, e.g. TODO.*\\bMei\\b)"`)
  — illustrates syntax to the LLM. Not a comment.
- **1 GrepTool doc example** describing a legitimate agent use case
  ("find the TODO we left in the…"). Not a comment either.
- **1 agent-prompt reference in `PromptEditingAndExternal.kt:212`**
  describing what kinds of content file-search tools surface
  ("a phrase in a subtitle, a TODO in a script"). Not a comment.

Zero lines match `// TODO` / `// FIXME` / `// HACK` / `// XXX` /
`/* TODO` or the block-comment variants. The `grep -rnE 'TODO|FIXME|HACK|XXX'`
count was an overcount — it picked up every string literal and
documentation mention.

**Alternatives considered.**

1. **Rewrite the 27 template placeholders to avoid the `TODO` string**
   (e.g. `"<BRAND NAME>"`, `"<<describe visual style>>"`). Rejected —
   the placeholders are *intentionally* shaped as `TODO: …` because
   the agent's prompt has been tuned to recognise that convention
   and prompt the user to fill them in. Renaming is churn with no
   semantic win, and it would silently regress the "template + fill"
   UX that downstream tools depend on.

2. **Exclude template-body string literals from the debt metric in
   future repopulations** (add a suppression grep, e.g. `grep -v '^.*= "TODO:'`).
   Rejected for now — the baseline grep is intentionally broad to
   catch new real TODO comments. Suppressing the template hits would
   mask regressions if someone added a genuine `// TODO: fix this`
   comment while the template count drifted.

3. **Leave the bullet as-is and aim for 25 on the raw count**.
   Rejected because reducing from 32 → 25 requires deleting
   user-facing template copy, which is the exact "churn with no win"
   VISION anti-pattern.

**Outcome.** No code change. The `debt-clean-todos` bullet is removed
from `docs/BACKLOG.md`. Future repopulations that want to track
genuine code-level debt should use the narrower
`grep -rnE '(//|/\*|\*)\s*(TODO|FIXME|HACK|XXX)\b'` pattern (or
similar) so the baseline reflects actual code-debt comments, not
template string literals. Current narrower-pattern count: **0**.

**Coverage.** N/A — no code change means the existing `:core:jvmTest`
/ `ktlintCheck` remain green (they were green at task start). No new
assertions added.

**Registration.** N/A — no new tool / class to register.

---
