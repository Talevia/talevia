## 2026-04-21 — list_messages per-session listing (VISION §5.4 专家路径)

Commit: `82ea6e5`

**Context.** The session lane's list / describe pair (added in the prior two
cycles) gave the agent visibility into *which* sessions exist and their
aggregates, but no way to see the *messages* inside a session other than
its own. `ToolContext.messages` supplies the in-flight history; messages
on any other session (including the one the agent is about to fork from,
or the one the user is asking to audit) were invisible at the tool layer.

Two flows that hit this today:
- Continuation: "resume from the edit we did on session X" — the
  `SessionStore.fork(parentId, anchorMessageId=…)` call needs a
  `MessageId` from session X. Without `list_messages` the agent has to
  ask the user to paste the id from the shell, or guess.
- Audit: "what did we do on session X?" — the user points at a session
  by title or id and expects the agent to answer without hand-holding.

**Decision.** `ListMessagesTool(sessionId, limit?)` — read-only,
most-recent-first (by `createdAt`). Per-row `Summary` keeps the payload
terse:
- Common: `id`, `role` (`"user"` | `"assistant"`), `createdAt`,
  `modelProviderId`, `modelId`.
- User-only: `agent` (which agent authored the turn).
- Assistant-only: `parentId`, `tokensInput`, `tokensOutput`, `finish`
  (lowercased enum name — `"stop"` / `"tool_calls"` / `"error"`), `error`.

Content (text parts, tool calls, tool results) is **not** returned. The
list verb's job is orientation; drilling into a single message's parts
is the complement's job (a future `describe_message` / part-level tool).
Matches `list_lockfile_entries` / `list_timeline_clips` / `list_source_nodes`
which all hand back summaries + point at a describe verb for detail.

Reuses the `session.read` permission keyword introduced with
`list_sessions`. Registered in all five AppContainers. Default limit 50,
max 500 — same cap as `list_sessions`.

**Alternatives considered.**

1. *Include parts inline (text excerpt + tool-call summary per message).*
   Rejected — bloats every list read, and parts detail is a follow-up
   concern. The npm / git ecosystem analogue: `ls` vs `cat` are separate
   verbs for a reason. The session lane mirrors that: list, describe, then
   drill into parts.
2. *Return messages oldest-first (like the SQL `selectBySessionOrderedByTime`
   does internally).* Rejected — "most recent first" is the call the agent
   reasons about ("last 5 messages before I fork"). Matches `list_lockfile_entries`
   which also inverts the store's append-only order on the wire.
3. *Accept an `includeTools: Boolean` flag to opt into parts.* Rejected for
   this cycle as YAGNI — the current describe-family tools
   (`describe_lockfile_entry`, `describe_session`) already establish the "ask
   for the drill-down by id" pattern. A drill-down tool when the need arises.
4. *Expose `finish` as the serialized SerialName (`"tool-calls"`) instead
   of the enum `.name.lowercase()` (`"tool_calls"`).* Chose the enum name
   form — both tokens the agent might parse ("stop", "error",
   "tool_calls") are already inside our runtime; the SerialName is a
   provider-facing wire format. Lowercase enum names avoid introducing a
   third spelling for the same concept. Tests pin the format.

**Coverage.** `ListMessagesToolTest` — seven tests: empty session zero
case; sort order inverted to most-recent-first; assistant row carries
tokens + finish + parentId; user row carries agent + model + null
assistant-only fields; limit cap; missing session fails loud with a
`list_sessions` hint; error field round-trips when assistant turn failed.

**Registration.** `ListMessagesTool` registered in `CliContainer.kt`,
`apps/desktop/AppContainer.kt`, `apps/server/ServerContainer.kt`,
`apps/android/AndroidAppContainer.kt`,
`apps/ios/Talevia/Platform/AppContainer.swift`. No new permission.
