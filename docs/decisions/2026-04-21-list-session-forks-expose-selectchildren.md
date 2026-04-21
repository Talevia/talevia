## 2026-04-21 — list_session_forks exposes selectChildren (VISION §5.4 专家路径)

Commit: `e390df8`

**Context.** `Sessions.sq` has shipped a `selectChildren` query
(`WHERE parent_id = ? ORDER BY time_created ASC`) since M4, but no
call site ever materialised: `SessionStore` didn't expose a wrapper
method, no tool delegated to it. Meanwhile `fork_session` creates
branches that write `Session.parentId` backlinks, `list_sessions`
returns the global catalog, and `describe_session` carries the
parentId of a single session — but none of them answered the lineage
question "which forks did *this* session spawn?" The workaround was
`list_sessions(limit=500)` client-filtered by `parentId`, which is
O(all) for an O(k-children) question and hits the cap once the
catalog grows beyond a few dozen sessions.

**Decision.** Three-layer exposure:

1. **SessionStore interface** — new
   `listChildSessions(parentId: SessionId): List<Session>` method.
   Kdoc calls out that archived children are included (the tree /
   lineage is complete regardless of archive state; the caller
   filters if they care).
2. **SqlDelightSessionStore** — one-line delegation to
   `sessionsQueries.selectChildren(parentId.value)`.
3. **Tool (ListSessionForksTool)** — `list_session_forks(sessionId)`.
   Verifies the parent exists first (distinguishes "no forks" from
   "wrong id"), returns `Output(parentSessionId, forkCount, forks:
   List<Summary>)` with the same `Summary` shape as `list_sessions`
   so the agent can pipe the output directly into subsequent
   describe/fork/rename calls.

Returns immediate children only — the agent walks a tree via repeated
`list_session_forks(child.id)` calls. One-hop per call matches the
`git branch --list` / `find -maxdepth 1` ergonomic rather than baking
transitive traversal into every call (cheap at the SQL layer, always
obvious at the reasoning layer).

Reuses `session.read` permission. Registered in all five AppContainers.

**Alternatives considered.**

1. *Return the full transitive tree.* Rejected — an unbounded
   recursive walk is cheap per-row in SQLite but the tool would need
   to decide a depth cap (and pick a rendering shape) with no
   concrete driver for the choice. One-hop keeps the verb focused;
   the agent can recurse deliberately if a flow asks for it.
2. *Bake `parentId` filtering into `list_sessions` as a flag.*
   Rejected — conflates two mental models (global listing vs.
   tree-walk) and the existing index `Sessions_parent_idx` on
   `parent_id` is purpose-built for the `selectChildren` path. Two
   dedicated tools beat one with mode flags.
3. *Skip the parent existence check (let an empty list speak).*
   Rejected — "no forks" and "wrong session id" would look identical
   to the caller. The check preserves the "missing id fails loud"
   contract the rest of the session lane follows (matching
   `describe_session` / `rename_session` / `fork_session`).
4. *Expose a separate `get_session_parent(sessionId)` tool for
   ancestor walks.* Rejected as YAGNI for this cycle —
   `describe_session` already returns `parentId`, so walking *up* the
   tree is already possible. Walking *down* was the missing verb.

**Coverage.** `ListSessionForksToolTest` — five tests: immediate
children only (grandchildren excluded); empty family returns zero;
archived children included in the result with `archived=true` flag;
oldest-first ordering; missing parent fails loud with
`list_sessions` hint.

**Registration.** `ListSessionForksTool` registered in
`CliContainer.kt`, `apps/desktop/AppContainer.kt`,
`apps/server/ServerContainer.kt`,
`apps/android/AndroidAppContainer.kt`,
`apps/ios/Talevia/Platform/AppContainer.swift`. No new permission
(reuses `session.read`).
