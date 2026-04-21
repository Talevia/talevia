## 2026-04-21 — list_session_ancestors walks to root (VISION §5.4 专家路径)

Commit: `7db7b97`

**Context.** The prior cycle's `list_session_forks` exposed the
down-tree walk (one hop per call, returning immediate children).
Ancestor walking — "where did this branch come from?" — still
required the agent to chain `describe_session` calls, each one
exposing `parentId` and bouncing back for another describe. Three
hops back to the root = four tool calls; a deeper narrative project's
fork chain (a 10-level nested experiment) = 11 tool calls. Orders of
magnitude worse than necessary for a linear walk.

**Decision.** `ListSessionAncestorsTool(sessionId)` — walks the
`parentId` chain iteratively and returns `Output(sessionId, depth,
ancestors: List<Summary>)`. Ordered parent-first → root, matching the
conceptual direction of the question ("who's my parent? who's their
parent? …"). Empty list means the session is itself a root.

Walking the whole chain in one call (vs. the forks tool's one-hop
stance) because **ancestor lineage is O(depth)** — never fans out.
Even a pathological 20-level fork chain is bounded by total session
count, and the user's mental model is always the whole-chain query
("trace this back to the root"). Returning one hop at a time would
force the agent to iterate for zero benefit.

Cycle-safe via visited-set — shouldn't happen given `fork_session`'s
"fresh uuid" contract, but a bad DB row won't put the walk in an
infinite loop. Broken parent edges (parent id references missing
session) stop the walk cleanly rather than throwing.

Archived ancestors are included in the chain so the lineage is
contiguous. Each `Summary` carries the `archived` flag for callers
that want to render it distinctly.

Read-only, `session.read`. Registered in all five AppContainers.

**Alternatives considered.**

1. *One-hop per call, matching `list_session_forks`.* Rejected —
   fork's one-hop stance exists because children fan out; ancestor
   walking is linear. Forcing the agent to iterate for a linear walk
   is unergonomic without a cost savings on the SQL side (each hop
   is a single `getSession` call anyway). The one-hop-vs-full-chain
   asymmetry is load-bearing, not inconsistent.
2. *Return the chain root-first (inverse order).* Rejected —
   parent-first matches the reading order of the question ("my
   parent? their parent? their parent?") and lets the agent take a
   prefix when it only cares about immediate ancestry. Postgres's
   recursive CTE convention for parent walks is the same
   (start-at-node, traverse-to-root).
3. *Fail loud on broken parent edges (unknown parentId).* Rejected —
   the session lane's contract is "dangling parent ids are a rare
   data-corruption case we defend against but don't actively fix."
   Failing loud means a single bad row in an otherwise healthy chain
   blocks every ancestor query for its descendants. Stopping the
   walk at the break gives the caller a truthful partial answer.
4. *Include the starting session in the output.* Rejected — the
   caller already has it (they passed its id in). Returning ancestors
   only keeps the tool's purpose tight and matches the SQL-CTE
   convention of excluding the start node from "ancestors of X."

**Coverage.** `ListSessionAncestorsToolTest` — six tests: root
returns empty chain; deep 3-level chain walked parent-to-root with
correct ordering + root's `parentId = null`; archived ancestor
surfaces `archived=true` in the Summary; missing session fails
loud; broken parent edge stops walk cleanly (returns empty when the
first parent resolution fails); direct parent is first in the list
for a single-hop case.

**Registration.** `ListSessionAncestorsTool` registered in
`CliContainer.kt`, `apps/desktop/AppContainer.kt`,
`apps/server/ServerContainer.kt`, `apps/android/AndroidAppContainer.kt`,
`apps/ios/Talevia/Platform/AppContainer.swift`. No new permission
(reuses `session.read`).
