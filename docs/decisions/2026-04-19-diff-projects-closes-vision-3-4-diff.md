## 2026-04-19 — `diff_projects` closes VISION §3.4 "可 diff"

**Context.** VISION §3.4 lists four properties for project-level edits: 可观察
(list_projects + get_project_state), 可版本化 (save/restore/list snapshots),
可分支 (fork_project), and 可 diff — "the agent can tell a user what actually
changed between v1 and v2 without dumping both Projects and asking the model to
spot the delta itself." The first three shipped; the diff leg was missing, so
the only way to answer "what did this fork add?" or "what did I break between
snapshots?" was to read-project-state twice and compare in-context. That
wastes tokens and is error-prone on large timelines.

**Decision.** Ship a read-only [`DiffProjectsTool`](../core/src/commonMain/kotlin/io/talevia/core/tool/builtin/project/DiffProjectsTool.kt)
(permission `project.read`). Input is `(fromProjectId, fromSnapshotId?,
toProjectId?, toSnapshotId?)` — a null snapshotId means "current state of that
project", toProjectId defaults to fromProjectId. Output has three diff
sections: timeline (tracks + clips by id), source (nodes by id, with
contentHash-change detection), and lockfile (entry-hash set-diff plus a
tool-id bucket count). Detail lists are capped; totals are always exact.

**Why clip matching by ClipId rather than by asset or position.** A moved clip
(timeRange changed) should show up as `changed`, not `remove + add`. Matching
by ClipId makes "the user moved clip c1 from 0-2s to 1-3s" one entry in
`clipsChanged` with a specific `changedFields=["timeRange"]` list — the agent
can read that back as "you shifted clip c1 by one second" rather than having
to reconstruct the match itself.

**Why source-node change detection uses contentHash, not body-equality.** We
already invalidate on contentHash elsewhere (stale-clip detection) and the
hash is always up to date on every node. Body-equality would need to pull the
JsonElement for each node on each side and compare; contentHash is one string
equality. Same notion of "change," faster.

**Why cap detail lists but keep totals exact.** A wholesale timeline rewrite
could blow the response into thousands of tokens if every clip change
serialises its full field list. Capping at `MAX_DETAIL` keeps the response
bounded; exact totals let the agent still say "47 clips changed (showing the
first 20)". If the agent needs the full list it can refine with
`get_project_state` or re-run with narrower bounds.

**Alternatives considered.**
- **Single "project identical?" boolean tool.** Rejected — the user asks
  "what's different?" as often as "anything different?". A bool-only tool
  forces a follow-up, doubling LLM turns.
- **Server-side unified diff over JSON dumps.** Rejected — meaningless for
  humans (and the agent) at the JSON-key level; domain diff (tracks/clips/
  nodes/lockfile) matches how the model reasons about the project.
- **Extend `get_project_state` with a snapshotId field so the agent can diff
  in-context.** Rejected — doubles the per-call cost (two state pulls), and
  the model isn't especially good at diffing large JSON blobs by eye. A
  typed diff tool is strictly cheaper and more reliable.
