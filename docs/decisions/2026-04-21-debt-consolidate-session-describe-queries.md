## 2026-04-21 — Fold describe_session + describe_message into session_query (R.5 near-tool-group debt)

Commit: `6ea08b9`

**Context.** `core/tool/builtin/session/` carried two drill-down tools
`DescribeSessionTool` (163 lines) and `DescribeMessageTool` (219
lines) in parallel with `SessionQueryTool`'s 8 list-style selects.
R.5 debt scan (this cycle's repopulate, commit `9ce7071`) flagged the
Describe* pair: same area (session), same verb family (describe),
same dispatch shape (single-row output, required-id input). Every
LLM turn paid for three largely-overlapping tool specs. The
`2026-04-21-unify-project-query.md` decision established the
consolidation pattern — single `*_query` tool with multiple `select`
variants — and the status select (cycle 28) already showed how
single-row drill-downs fit naturally as selects returning
`total=1, returned=1, rows=[oneRow]`.

(Why this cycle and not the other P0s: aigc-cost-tracking-per-session
requires provider-pricing tables as a product decision — skipped per
"换下一条 backlog" rule. debt-consolidate-project-describe-queries
spans 751 lines across 3 tools with rich rendering logic — too large
to ship safely in one cycle; left at P0 for a dedicated cycle.)

**Decision.** Two new selects on `SessionQueryTool`:

1. `SELECT_SESSION_METADATA = "session_metadata"` — drill-down by
   `sessionId`. Returns a single `SessionMetadataRow` with the
   same aggregate counts `DescribeSessionTool.Output` exposed
   (messageCount / userMessageCount / assistantMessageCount,
   summed TokenUsage totals, hasCompactionPart flag,
   permissionRuleCount, latestMessageAtEpochMs, archived /
   parentId / title metadata).
2. `SELECT_MESSAGE = "message"` — drill-down by new `messageId`
   input field (required here, rejected elsewhere). Returns a
   single `MessageDetailRow` with role + timestamps + token usage
   + finish/error + an embedded `List<MessagePartSummary>` using
   the same per-kind preview strategy (text/reasoning 80-char
   truncation, tool toolId+state, media assetId, timeline-snapshot
   clip count, step-finish token usage, compaction replaced-range,
   todos status counts).

Both handlers live in `core/tool/builtin/session/query/` per the
per-select file split pattern (`SessionMetadataQuery.kt`,
`MessageDetailQuery.kt`).

`DescribeSessionTool.kt`, `DescribeMessageTool.kt`, and their test
files are deleted. Five AppContainers (CLI / Desktop / Server /
Android / iOS) drop both imports + both registrations. Net LLM
context change: −2 tool specs (saving their overhead every turn) +
~180 tokens on session_query's helpText + schema.

**Alternatives considered.**

- *Keep the Describe* tools and widen them*: rejected — this is
  exactly the "近似工具群" signal the debt scan flagged. Widening
  keeps the duplicate tool specs forever.
- *Fold into `session_query` with polymorphic row wrappers that
  expose one unified `DescribeRow` variant*: rejected — the two
  drill-downs have substantially different field sets
  (session_metadata has aggregate counts; message has per-part
  previews) and collapsing them would lose typed-row clarity for
  consumers decoding via `SessionQueryTool.SessionMetadataRow.serializer()`
  etc.
- *Delete the old tools without replacement*: rejected — the
  existing functionality (get session-level aggregate counts, get
  message-level part summary) is load-bearing for orientation
  calls. The consolidation is net-zero capability.
- *Introduce `describe_message` as a new tool that uses a shared
  "DescribeVerb" base class*: rejected — conflicts with §3a #1
  ("工具数量不净增"). The backlog bullet explicitly targeted the
  query-verb consolidation, not new describe infrastructure.

Industry consensus referenced: the `*_query` consolidation pattern
is the same shape as AWS CLI's subcommand-verbs-on-one-service
(`aws s3 ls` / `aws s3 describe-bucket` share the service
abstraction), and codebase-grep's `(select, filter, sort, limit)`
shape that OpenCode treats as a unified primitive. `kotlinx.serialization`'s
sealed-class discriminator convention supports polymorphic row types
without runtime cost.

**Coverage.**

- `SessionQueryToolTest.sessionMetadataReturnsCountsAndTotals` —
  four messages (2 user + 2 assistant with distinct token totals)
  produce the correct aggregated row; `latestMessageAtEpochMs`
  reflects the newest message's timestamp.
- `SessionQueryToolTest.sessionMetadataEmptySessionFallsBackToCreatedAt`
  — empty session → `latestMessageAtEpochMs ==
  session.createdAtEpochMs`, zeros throughout aggregates.
- `SessionQueryToolTest.sessionMetadataMissingSessionFailsLoud`
  — unknown sessionId fails with "not found" hint.
- `SessionQueryToolTest.sessionMetadataRequiresSessionId` —
  missing sessionId fails with "requires sessionId" hint.
- `SessionQueryToolTest.messageDrillDownReturnsRoleAndParts` —
  assistant message with text + tool parts produces correct
  MessageDetailRow with exactly 2 part summaries and the text
  preview / tool state strings matching the old describe_message's
  rendering.
- `SessionQueryToolTest.messageDrillDownMissingMessageFailsLoud` —
  unknown messageId fails loud.
- `SessionQueryToolTest.messageDrillDownRequiresMessageId` —
  missing messageId fails loud with hint.
- `SessionQueryToolTest.messageIdOnOtherSelectFailsLoud` —
  passing messageId on a list select is rejected (regression
  guard against the new field leaking into unintended selects).

`DescribeSessionToolTest.kt` + `DescribeMessageToolTest.kt` deleted
(474 lines). Net test lines roughly break-even after the 8 new
tests; coverage axis stays the same (happy + validation + failure
modes per drill-down).

**Registration.** Five AppContainers each drop two imports + two
registration calls:
- `apps/cli/src/main/kotlin/io/talevia/cli/CliContainer.kt`
- `apps/desktop/src/main/kotlin/io/talevia/desktop/AppContainer.kt`
- `apps/server/src/main/kotlin/io/talevia/server/ServerContainer.kt`
- `apps/android/src/main/kotlin/io/talevia/android/AndroidAppContainer.kt`
- `apps/ios/Talevia/Platform/AppContainer.swift`

No new AppContainer wiring — the existing `SessionQueryTool(sessions,
agentStates)` registration now covers the new selects automatically.

§3a checklist pass:
- #1 **negative** tool count (−2 Tool.kt files). ✓
- #2 not a Define/Update pair. ✓
- #3 no Project blob changes. ✓
- #4 no binary flag. ✓
- #5 session vocabulary is genre-neutral. ✓
- #6 no session-binding surface added. ✓
- #7 new Input.messageId defaults to null; new row types have
  nullable defaults on discriminated-by-role fields (parentId,
  tokensInput/Output, finish, error, agent). ✓
- #8 five-end: all containers updated in lock-step. ✓
- #9 eight new tests cover happy paths (two selects), missing-id
  failures (both selects), missing-input-field failures (both
  selects), and cross-select field misapplication (messageId on
  select=messages rejected). ✓
- #10 **negative** LLM context — two tool specs removed (~400
  tokens saved per turn), minus ~180 tokens added to session_query
  helpText + schema. Net savings per turn: ~220 tokens. ✓
