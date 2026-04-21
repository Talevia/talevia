## 2026-04-21 — list_parts kind-filterable listing (VISION §5.4 专家路径)

Commit: `3aa88bf`

**Context.** `list_tool_calls` (previous cycle) proved the "filter
session parts" pattern is useful, but it's hard-coded to `Part.Tool`.
Other audit flows want the same shape for other kinds:
- "show me every `TimelineSnapshot` this session produced" — trace
  undo points a tool chain created.
- "show me every `Compaction` part" — pair with `read_part` to see
  what the Compactor summarised away.
- "show me every `Todos` update" — trace how the agent's scratchpad
  evolved.

Before this tool, each of these required `list_messages` + `describe_message`
across every message. That's O(1+N) for every audit question.

**Decision.** `ListPartsTool(sessionId, kind?, includeCompacted?,
limit?)` — single `listSessionParts` call, optional kind filter
(the `@SerialName` discriminator string), sort descending by
`createdAt`, cap at default 100 / max 1000. Per-row `Summary(partId,
kind, messageId, createdAt, compactedAt?, preview)`.

Preview strings reuse describe_message's per-kind vocabulary: first
80 chars for text/reasoning, `toolId[state]` for tool, clip count +
callId for timeline-snapshot, `finish input=X output=Y` for
step-finish, replaced-range for compaction, status counts for todos.
One shape per kind; same vocabulary the agent has already seen in
describe_message so there's no new mental model to learn.

Kind validation: unknown kinds rejected loudly. The `VALID_KINDS`
set exhaustively mirrors the `Part` sealed hierarchy — if someone
adds a new Part subtype, Kotlin's `when` exhaustiveness in
`kindDiscriminator` + `preview` will force the set update. The
`require(kind in VALID_KINDS)` guards the wire-facing filter.

Reuses `session.read` permission. Registered in all five
AppContainers.

**Alternatives considered.**

1. *Return an empty list instead of rejecting unknown kinds.*
   Rejected — "no matches for kind `timeline-snaphot`" (typo with
   missing `s`) would look identical to "no matches for kind
   `timeline-snapshot`" (correct). Failing loud preserves the
   "typos surface" contract the rest of the codebase follows (see
   `list_tracks` which validates `trackKind` the same way).
2. *Skip `list_parts` and extend `list_tool_calls` with a `kind`
   field.* Rejected — that's a layering inversion. Tool calls are a
   specific kind; a kind-filterable listing is the generalisation.
   list_tool_calls stays because it surfaces tool-specific fields
   (`callId`, `state`, `title`, `toolId`) that this generic tool
   deliberately elides. Two tools, two mental models (generic audit
   vs. tool-specific audit), same pattern as
   `list_source_nodes` vs. `list_character_refs` (if the latter
   existed — the consistency tools use the typed `list_*` form for
   the same reason).
3. *Fold `list_parts` with `describe_message` by letting describe
   take a `kind` filter.* Rejected — describe_message is per-message
   (and covers all parts in that message); list_parts is
   cross-message (all parts in a session). The scope difference is
   load-bearing.

**Coverage.** `ListPartsToolTest` — six tests: returns every part
most-recent-first; kind filter narrows to one subtype; unknown
kind rejected loudly; per-kind previews carry the right substrings
(`input=100` for step-finish, `compacted` for compaction, `clip(s)`
for timeline-snapshot); limit caps output; missing session fails
loud.

**Registration.** `ListPartsTool` registered in `CliContainer.kt`,
`apps/desktop/AppContainer.kt`, `apps/server/ServerContainer.kt`,
`apps/android/AndroidAppContainer.kt`,
`apps/ios/Talevia/Platform/AppContainer.swift`. No new permission
(reuses `session.read`).
