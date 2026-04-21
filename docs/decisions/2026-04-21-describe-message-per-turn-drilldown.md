## 2026-04-21 — describe_message per-turn drill-down (VISION §5.4 专家路径)

Commit: `164347e`

**Context.** With `list_sessions` / `describe_session` / `list_messages`
in place (prior cycles), the agent can enumerate sessions, see aggregate
session state, and list messages. The remaining step on the session lane
was drilling into a single turn: *what happened* in this assistant
message — text, tool calls, reasoning, timeline snapshots, compaction
summaries, todos. Before this tool the agent had to call a hypothetical
part-level tool per kind (or read parts via direct DB). The actual Part
model ships with 10 subtypes and the agent needs a consolidated view.

**Decision.** `DescribeMessageTool(messageId)` — returns message metadata
plus one `PartSummary` per part with a per-kind `preview` string:

- `text` / `reasoning` → first 80 chars (truncated)
- `tool` → `toolId[state]` where state is `pending|running|completed|error`
- `media` → `assetId.value`
- `timeline-snapshot` → `N clip(s) after <callId>` (or `baseline` when pre-tool)
- `render-progress` → `job=X ratio=Y%`
- `step-start` → `"step start"` (single-line sentinel)
- `step-finish` → `<finish> input=N output=M`
- `compaction` → `compacted <from>→<to>`
- `todos` → `N todo(s) pending=X in_progress=Y done=Z`

Each row also exposes `createdAtEpochMs` and `compactedAtEpochMs` (null
when the part is still live in the LLM context). Assistant messages
additionally carry `tokensInput` / `tokensOutput` / `finish` / `error` /
`parentId`; user messages carry `agent` + model.

Full part content is **not** returned. A `Part.Compaction.summary` or a
full `Part.TimelineSnapshot.timeline` can each individually balloon a
describe call into a megabyte-scale payload. The agent can still drill
further — `Part.Tool.callId`, `Part.Compaction.replacedFromMessageId`,
`Part.TimelineSnapshot.producedByCallId` all surface identifiers the
agent can navigate with follow-up tools (future part-level read, or
`describe_project`/`describe_clip` for timeline state).

Read-only, `session.read`. Missing messageId fails loud with a
`list_messages` hint. Registered in all five AppContainers.

**Alternatives considered.**

1. *Return full part content inline.* Rejected — the timeline-snapshot
   case alone can serialise a ~20 KB JSON blob per part, and a single
   debug turn with 3 snapshots + a compaction summary + 5 tool calls
   crosses 100 KB easily. The describe verb is an orientation read; the
   drill-down to full content belongs to the part-level tool family
   (a future `read_part(id)` or equivalent).
2. *Return the raw `Part` sealed-class JSON via `@Serializable`.*
   Rejected for the same reason `describe_clip` didn't do it — tool
   outputs should expose a stable, documented DTO shape rather than
   leak serialization details. And the raw `ToolState.Completed.data` is
   an opaque `JsonElement` that's meaningful only to the specific tool
   that produced it; summarising it to `"completed"` in the preview is
   the honest answer for a generic describe tool.
3. *Truncate text previews at 40 chars (tighter).* Rejected — 80 chars
   matches the column width of a VT100 terminal and gives enough
   context to tell "the agent is introducing a new character" from
   "the agent is apologising for an error." 40 felt too aggressive
   after manual testing on the actual message prefixes we produce.
4. *Include the full `todos` list (content + status) rather than just
   counts.* Borderline — the todos content is already useful and not
   very big (~10 lines typically). Kept the counts-only preview for
   v1 matching the house-style terseness of the other kinds; a follow-
   up can surface the full list if concrete flows need it.

**Coverage.** `DescribeMessageToolTest` — four tests: assistant turn with
six diverse part kinds (text truncation, tool with callId + state,
step-finish tokens, todos count breakdown, timeline snapshot carrying
the producing callId, media assetId); user message with empty parts +
null assistant-only fields; missing messageId fails loud; compacted part
exposes `compactedAtEpochMs`.

**Registration.** `DescribeMessageTool` registered in `CliContainer.kt`,
`apps/desktop/AppContainer.kt`, `apps/server/ServerContainer.kt`,
`apps/android/AndroidAppContainer.kt`,
`apps/ios/Talevia/Platform/AppContainer.swift`. No new permission.
