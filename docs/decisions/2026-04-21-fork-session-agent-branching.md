## 2026-04-21 — fork_session agent-callable session branching (VISION §5.4 专家路径)

Commit: `1b2416b`

**Context.** The three session-lane cycles before this one added read
tools (`list_sessions`, `describe_session`, `list_messages`,
`describe_message`); write tools on the session lane were still CLI-only.
`SessionStore.fork(parentId, newTitle, anchorMessageId)` has existed at
the domain layer since M4; the CLI exposes it via a `/fork` slash
command; but the agent itself had no tool. That blocks "try a different
approach from here" and "fork from message X and continue" flows from
inside the chat — the user currently has to leave the agent, run the
slash command, then come back. VISION §5.4 专家路径 "精准执行" means
these flows belong in the tool surface the agent already controls.

**Decision.** `ForkSessionTool(sessionId, anchorMessageId?, newTitle?)`
— thin adapter over `SessionStore.fork`. Returns `Output(newSessionId,
parentSessionId, anchorMessageId, newTitle, copiedMessageCount)`.
Semantics inherited from the store:

- A new SessionId is minted with `parentId = <source>` so
  `describe_session` / `list_sessions` render the fork graph.
- `anchorMessageId` null → copy full parent history. Non-null → copy
  only messages at-or-before the anchor in `(createdAt, id)` order.
- `newTitle` null → `"<parent title> (fork)"`.

Failure surface:
- Parent not found → `IllegalStateException` with a `list_sessions` hint
  (consistent with `describe_session`).
- Anchor doesn't belong to the parent → `IllegalArgumentException` from
  the store's `require` (verbatim surface).

**New `session.write` permission**, added to DefaultPermissionRuleset
with ALLOW. Fork is write-verb (creates a row + copies messages) but
purely local state — no external cost, no network, no filesystem leak.
Same risk profile as `source.write` / `project.write`, same default.
Deny-by-default server deployments can flip to ASK in config.

**Alternatives considered.**

1. *Bundle fork into a composite "branch from here and start a run" tool
   that also fires an `Agent.run` on the new session.* Rejected — mixing
   storage mutation with agent execution couples two lifecycles. The
   agent already knows how to run in a session; a dedicated `fork_session`
   keeps the verbs orthogonal (mirrors `create_project` vs. the separate
   flows that add clips). Industry consensus: git, hg, jj all ship `fork`
   (or `branch`) as a pure metadata op and leave "switch to it and start
   working" to a separate command.
2. *Reuse `project.write` instead of a new `session.write` keyword.*
   Rejected — session is not a project (it's an interaction on a project),
   and conflating them forces operators to either over-grant (allow project
   writes to permit forks) or under-grant (deny fork because project
   writes are denied). Distinct keywords let deny-by-default setups scope
   precisely.
3. *Return the full copied message ids for audit.* Rejected — O(N)
   payload for a describe-by-id tool, and the agent can follow up with
   `list_messages(newSessionId)` if it needs the full list. Keep the
   Output terse (same ergonomic as `import_source_node`, which returns
   counts + ids of the leaf, not the whole parent chain).
4. *Require the agent to supply a new SessionId rather than minting
   one.* Rejected — uuid minting is the store's contract (it calls
   `Uuid.random().toString()` today), and exposing that decision to the
   agent creates a collision failure mode the store already knows how
   to avoid. Matches `create_project_from_template` which also mints
   a slug rather than requiring one.

**Coverage.** `ForkSessionToolTest` — six tests: whole-history default
fork with parentId backlink, custom title override, anchor truncation
narrows copied history, unknown session fails loud, anchor belonging
to the wrong session fails loud, forked SessionId is fresh (not a
parent-id reuse).

**Registration.** `ForkSessionTool` registered in `CliContainer.kt`,
`apps/desktop/AppContainer.kt`, `apps/server/ServerContainer.kt`,
`apps/android/AndroidAppContainer.kt`,
`apps/ios/Talevia/Platform/AppContainer.swift`. New permission rule
`session.write=ALLOW` added to `DefaultPermissionRuleset`.
