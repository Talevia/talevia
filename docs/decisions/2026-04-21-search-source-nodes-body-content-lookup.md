## 2026-04-21 — search_source_nodes for body-content lookup (VISION §5.4 专家路径)

Commit: `2e3ead8`

**Context.** VISION §5.4 专家路径 asks: "用户能不能直接编辑 source 的每个字段、
override 某一步编译、在 agent 的某个决策点接管?" — the precise-editing leg
depends on being able to *find* the node to edit. Existing lookup tools:

- `list_source_nodes` filters by id / kind / kindPrefix — great when the user
  knows the kind or prefix.
- `describe_source_node` / `describe_source_dag` drill in or summarize structurally.

Neither answers "which node mentions 'neon'?" on a 40-node narrative DAG or
"find every node referencing SKU-12345" on a marketing project with 20
variants. The workaround was a list → iterate-and-fetch-body → client-side
grep, which forces the agent to pull every body through context to answer a
substring question.

**Decision.** `SearchSourceNodesTool` — serialize each node's body via
`JsonConfig.default` (stable across re-encodes so case-normalisation works),
substring-match against the query, case-insensitive by default with
`caseSensitive = true` as an opt-in for brand-name / ISO-code lookups.
Returns `Match(id, kind, parentIds, snippet, matchOffset)`; the snippet is
±32 chars around the first hit with `…` clipping markers. The **full body
is not returned** — agents who need it follow up with
`describe_source_node` on a specific id. That keeps the grep-and-drill
pattern lightweight in context. Optional `kind` narrows the match set to
one kind (reuses the `kind` filter shape from `list_source_nodes`). `limit`
caps output (default 20, max 200) mirroring `list_lockfile_entries`.

**Alternatives considered.**

1. *Regex match instead of substring.* Rejected for v1 — regex on an LLM
   surface invites confusion around anchors (`^`, `$` match serialized-JSON
   boundaries, not field boundaries), escaping edge cases, and catastrophic
   backtracking on malformed patterns. Substring covers >90% of "find
   mentions of X" queries and if the agent really needs regex later we can
   add a `regex=true` flag without breaking callers.
2. *Match against the human summary (like `list_source_nodes.summary`)
   instead of the serialized body.* Rejected — summaries are lossy by
   design (first-string / top-keys shortcuts), so a query against "neon"
   would miss nodes where "neon" lives in a motifs array or a negative
   prompt. Searching the serialized body is the industry-consensus answer
   (ripgrep, git grep, OpenCode's `grep.ts` all search source-of-truth text).
3. *Extend `list_source_nodes` with a `query` arg.* Rejected — mixing a
   substring search into a structural-filter tool conflates two mental
   models. `grep` and `ls` are separate in every unix toolkit for a reason.
4. *Per-kind typed field search (e.g. `find_character_ref(name_like=…)`).*
   Rejected — would require hard-coding per-kind search shape in Core,
   violating CLAUDE.md's "no genre schema in core/commonMain" architecture
   rule. The serialized-JSON surface is genre-agnostic by construction.

**Coverage.** `SearchSourceNodesToolTest` — eight tests: case-insensitive
hit across kinds, case-sensitive hit + intentional miss, kind filter,
limit cap, empty-result still reports totalNodes, blank query rejection,
snippet brackets the match, missing project fails loud.

**Registration.** `SearchSourceNodesTool` registered in `CliContainer.kt`,
`apps/desktop/AppContainer.kt`, `apps/server/ServerContainer.kt`,
`apps/android/AndroidAppContainer.kt`,
`apps/ios/Talevia/Platform/AppContainer.swift`.
