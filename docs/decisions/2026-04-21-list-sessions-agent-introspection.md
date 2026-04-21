## 2026-04-21 — list_sessions for agent session introspection (VISION §5.4 专家路径)

Commit: `21dabda`

**Context.** Every shell layer in the codebase already calls
`SessionStore.listSessions` — the CLI picker, the desktop sidebar, the
server's session-index endpoint — but the *agent itself* had no
corresponding tool. In real flows that matters: the user says "fork the
session where we defined Mei" or "continue the edit we did last night",
and the agent had no way to find the target session id. It had to ask
the user to copy-paste the id from the shell, or rely on memory from the
running conversation. Both are friction against VISION §5.4 专家路径
"精准执行" — the expert user expects the agent to operate on references
they're already looking at.

**Decision.** `ListSessionsTool(projectId?: String = null, limit: Int? = null)`.

- Projectless call returns every session across every project, sorted by
  `updatedAt` descending so the most-recent is first.
- With a `projectId` filter, scoped to that project only.
- `limit` caps output (default 50, max 500) — matches
  `list_lockfile_entries.limit` conventions.
- Returns `Summary(id, projectId, title, parentId, createdAt, updatedAt,
  archived)`. `parentId` surfaced so the agent can reason about session
  lineage after `fork`.
- A new `session.read` permission keyword, added to
  `DefaultPermissionRuleset` with ALLOW — session listing is local
  metadata with zero cost, same silent-default as `project.read` /
  `source.read`.
- New `core/tool/builtin/session/` package (first tool in the session
  lane).

**Archived sessions are intentionally out of scope.** The store's
`selectAll` / `selectByProject` SQL already `WHERE archived = 0`, so the
`Session.archived` field is load-bearing but not user-visible via this
tool today. The initial draft added an `includeArchived` flag; dropped
when tests revealed the SQL filter was unreachable. A follow-up could
add `selectAllIncludingArchived` queries + a store method if archived
browsing becomes a real flow — the tool's help text calls out the gap so
the agent doesn't promise what doesn't work.

**Alternatives considered.**

1. *Expose session introspection via `describe_project` / `get_project_state`.*
   Rejected — those tools are project-centric (timeline, source DAG,
   lockfile), and dragging sessions into their output payload bloats the
   most-common read in the codebase for a comparatively rare use. Keep
   session awareness as its own verb, mirror `list_lockfile_entries` /
   `list_source_nodes` shape.
2. *Include full message counts / token budgets per session.* Rejected —
   computing those requires a per-session message scan that would turn
   a cheap metadata list into an O(N×M) query. Fork this into
   `describe_session` when the need arises (current use cases are
   satisfied by id + title + timestamps).
3. *Reuse `project.read` rather than add a new permission keyword.*
   Rejected — `project.read` reads the domain state; session listing
   is a separate logical capability (the same `project.read`=ALLOW
   could coexist with `session.read`=ASK in a deny-by-default
   deployment). Distinct names let admins scope independently.
4. *Add an `includeArchived: Boolean = false` flag in the initial
   tool.* Tried, dropped — the SQL layer can't produce archived rows
   from the existing queries, so the flag was inert. Keeping it would
   mislead the agent into believing `includeArchived=true` would work.

**Coverage.** `ListSessionsToolTest` — seven tests: default sort by
`updatedAt` desc, project filter scope, archived-always-excluded
(documenting the SQL filter), `parentId` round-trip for forks, limit
cap, empty-project returns empty, etc.

**Registration.** `ListSessionsTool` registered in `CliContainer.kt`,
`apps/desktop/AppContainer.kt`, `apps/server/ServerContainer.kt`,
`apps/android/AndroidAppContainer.kt`,
`apps/ios/Talevia/Platform/AppContainer.swift`. New rule
`session.read=ALLOW` added to `DefaultPermissionRuleset`.
