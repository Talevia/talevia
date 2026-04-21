## 2026-04-21 — describe_session deep introspection (VISION §5.4 专家路径)

Commit: `7c5ec9d`

**Context.** `list_sessions` (cycle 2 of this same loop) gave the agent a
paginated session list; the natural /describe counterpart was missing.
Follow-up questions the list view can't answer without a second pass:

- "how long is this session?" → message count by role
- "how much context have I spent here?" → summed token usage across every
  assistant turn (input / output / cache-read / cache-write)
- "has this session been compacted?" → `Part.Compaction` presence, a
  stronger signal than `Session.updatedAt` (which bumps on metadata-only
  edits like title rename / archive flip)
- "when did we last talk here?" → `latestMessageAt`, distinct from
  `updatedAt`
- "how many Always rules have accumulated?" → `permissionRules.size`

The user can reconstruct all of these by walking `list_messages` +
`list_session_parts` client-side, but that's a 2-3 tool-call dance for
what should be one read. This tool closes that gap, mirroring the
`describe_project` / `describe_clip` / `describe_lockfile_entry` pattern
already established for other first-class objects.

**Decision.** `DescribeSessionTool(sessionId: String)`. Reads session
metadata, walks `listMessages(sid)` + `listSessionParts(sid,
includeCompacted=true)` once, and derives the aggregates. Output fields:

- Metadata: `id`, `projectId`, `title`, `parentId`, `archived`,
  `createdAt`, `updatedAt`, `compactingFromMessageId`.
- Derived: `latestMessageAt` (fallback to `createdAt` on empty session),
  `messageCount` / `userMessageCount` / `assistantMessageCount`,
  `totalTokensInput` / `totalTokensOutput` / `totalTokensCacheRead` /
  `totalTokensCacheWrite` (summed across `Message.Assistant.tokens`),
  `hasCompactionPart` (boolean — whether Compactor has run on this
  session), `permissionRuleCount`.

Reuses the `session.read` permission from the prior cycle's work.
Missing session id fails loud with a `list_sessions` hint. Registered
in all five AppContainers.

**Alternatives considered.**

1. *Include the message summaries inline (id + role + preview).*
   Rejected — matches `describe_project`'s decision to stay summary-
   level and let `list_*` tools fetch detail. The LLM often wants just
   the counts before deciding whether to fetch more; eager inlining
   would bloat every describe call. Follow-up: a `list_messages` tool
   already covers the detail path.
2. *Compute per-tool usage breakdowns (counts of `Part.Tool` by
   `toolId`).* Rejected for this cycle as scope creep — it's a new
   aggregate type and the consumer flow isn't clear yet. Can be added
   to the same Output struct when a concrete use case appears. YAGNI.
3. *Return token *cost* estimates in USD.* Rejected — the Cost field
   exists on `Message.Assistant` but is not reliably populated today
   (providers differ on whether they surface cost). Raw tokens are
   provider-neutral; USD conversion is better done client-side with a
   current pricing table, matching OpenCode's own session UI which
   shows tokens + optional computed cost from a pricing table.
4. *Skip `hasCompactionPart` and leave compaction discovery to
   `list_session_parts`.* Rejected — the agent's compaction-awareness
   is load-bearing for the "am I about to compact?" question and
   forcing the agent into a second call for a single boolean is poor
   ergonomics. One SELECT over parts is cheap.

**Coverage.** `DescribeSessionToolTest` — five tests: empty session has
zero counts + latestMessageAt falls back to createdAt; token summation
across multiple assistant turns with cache-read/write and user messages
interleaved; `hasCompactionPart=true` when a `Part.Compaction` is
upserted; `permissionRuleCount` surfaces; missing session id fails loud.

**Registration.** `DescribeSessionTool` registered in `CliContainer.kt`,
`apps/desktop/AppContainer.kt`, `apps/server/ServerContainer.kt`,
`apps/android/AndroidAppContainer.kt`,
`apps/ios/Talevia/Platform/AppContainer.swift`. No new permission (reuses
`session.read`).
