## 2026-04-21 — list_tool_calls session audit (VISION §5.4 专家路径)

Commit: `0031e15`

**Context.** Two common audit questions within a single session:
- "How many `generate_image` calls did we make?"
- "Which tools has this session used so far?"

Before this tool, the agent's only path was `list_messages` → iterate
N messages → `describe_message` per row → client-side filter for
`Part.Tool` entries. O(1 + N + K) where K is parts-per-message. A
session with 40 turns × 3 tool calls = 121 tool-calls for a single
audit. Expensive, and most of that work is redundant: the store can
already materialise `Part.Tool` entries directly via
`listSessionParts`.

**Decision.** `ListToolCallsTool(sessionId, toolId?, includeCompacted?,
limit?)` — single `listSessionParts(sessionId, includeCompacted)`
call, `filterIsInstance<Part.Tool>`, optional `toolId ==` narrow,
sort descending by `createdAt`, take the cap (default 100, max
1000). Returns per-call `Summary(partId, messageId, toolId, callId,
state, title?, createdAt, compactedAt?)`.

`state` is the lowercase discriminator of `ToolState` —
`"pending"` / `"running"` / `"completed"` / `"error"`. Title is the
`Part.Tool.title` field (populated by `Agent.dispatchTool` from
`ToolResult.title`) so the agent gets a human-readable line like
"generate image of Mei" without decoding the completed payload.

`includeCompacted=true` default — audit view wants everything; set
false to mirror the current LLM context after compaction.

Reuses `session.read` permission. Registered in all five AppContainers.

**Alternatives considered.**

1. *Extend `list_messages` with a `toolsOnly: Boolean` flag.*
   Rejected — `list_messages` is a messages listing. Tool calls live
   inside messages as parts; conflating the two layers breaks the
   clean separation the rest of the describe/list family maintains
   (`list_messages` vs `describe_message` vs `read_part`). One verb
   per mental model.
2. *Also return the full `ToolState` payloads inline.* Rejected —
   the completed state's `data` field is an opaque `JsonElement`
   that can be arbitrarily large (an ExportTool result includes the
   whole MediaAttachment set; a GenerateImageTool result carries
   sourceBinding + provenance). The audit view wants a compact row
   per call; callers who need one specific payload follow up with
   `read_part(partId)`.
3. *Make this a generic `list_parts(kind: String?)`.* Rejected for
   this cycle — Part.Tool is the one kind that's worth auditing at
   scale; the other kinds (text, reasoning, step-start/finish) are
   either cheap to see in `describe_message` or numerous enough to
   rate their own dedicated tool. YAGNI until a concrete
   non-Tool-kind audit flow surfaces.
4. *Default to the agent's current session (via ToolContext).*
   Rejected — the tool's value is cross-session audit ("what did we
   do in the previous session?"). Defaulting would prevent that.
   Required sessionId matches every other session-lane verb.

**Coverage.** `ListToolCallsToolTest` — six tests: tool parts returned
most-recent-first + non-tool parts ignored; toolId filter narrows to
one kind; state discriminator surfaces `completed`/`error` correctly;
limit caps output; missing session fails loud; empty session returns
zero + empty list.

**Registration.** `ListToolCallsTool` registered in `CliContainer.kt`,
`apps/desktop/AppContainer.kt`, `apps/server/ServerContainer.kt`,
`apps/android/AndroidAppContainer.kt`,
`apps/ios/Talevia/Platform/AppContainer.swift`. No new permission
(reuses `session.read`).
