## 2026-04-23 — `compaction-diagnosis-query` skip-close (VISION §5.4 rubric axis)

**Context.** Cycle-40 top-P1 bullet. Request was: add
`session_query(select=compaction_trace)` enumerating `Part.Compaction`
entries in session order with `(replacedFromMessageId,
replacedToMessageId, createdAt, summaryPreview)`, so operators /
agents can audit historical compactions on re-opened sessions.

Liveness pre-check (§2.5) shows the bullet is **stale — already
resolved**:

- `grep SELECT_ core/.../SessionQueryTool.kt` surfaces
  `SELECT_COMPACTIONS = "compactions"` as one of the registered
  selects.
- `core/.../session/query/CompactionsQuery.kt` (at `913186bd`,
  cycle-25) already implements it:
  ```kotlin
  @Serializable data class CompactionRow(
      val partId: String,
      val messageId: String,
      val fromMessageId: String,
      val toMessageId: String,
      val summaryText: String,
      val compactedAtEpochMs: Long,
  )
  ```
  That's a richer shape than the bullet asked for:
  - `fromMessageId` / `toMessageId` — identical to requested
    `replacedFromMessageId` / `replacedToMessageId`.
  - `compactedAtEpochMs` — identical to requested `createdAt` as epoch ms.
  - `summaryText` — the **full** summary instead of a truncated
    `summaryPreview`. The bullet conservatively asked for a preview
    (presumably to cap payload size); the landed implementation
    decided full-summary was worth it because `select=parts`'s 80-char
    preview already exists for the size-conscious caller. One call
    reconstructs the exact compactor output.
  - Bonus: `partId` + `messageId` — lets the agent cross-reference
    into other session_query selects.

- `runCompactionsQuery` walks `listSessionParts(session.id,
  includeCompacted = true)`, filters `is Part.Compaction`, sorts by
  `createdAt` descending (newest first), paginates `limit`/`offset`
  from the shared Input surface.

The bullet's naming (`compaction_trace`) differs from the landed
select (`compactions`), but the landed name matches the rest of the
`session_query` select vocabulary (`messages`, `parts`, `forks`,
`ancestors`, `tool_calls`, `compactions`) — adding a second
`compaction_trace` alongside would double the spec without any
semantic gain.

Rubric delta §5.4: compaction-pass auditability was **有** already
since `913186bd`; this bullet was queued before `913186bd` landed and
survived three repopulate passes undetected. No delta this cycle.

**Decision.** Skip-close per iterate-gap §2.5. No code written this
cycle; delete the bullet + archive the reasoning here; `git log -S
'SELECT_COMPACTIONS' -- core/...` aligns this decision with the
`913186bd` feat that originally solved the symptom.

**Axis.** n/a — no code changed.

**Alternatives considered.**

- **Rename the landed select from `compactions` to `compaction_trace`
  to match the bullet text.** Rejected: the bullet's slug was a
  proposed name, not a binding spec; the landed name matches the rest
  of the `session_query` noun-plural vocabulary (`messages`, `parts`,
  `forks`, `ancestors`). A rename would be API churn for zero agent
  benefit and would break any existing `docs/decisions/` reference to
  the `compactions` name.

- **Add `summaryPreview` alongside `summaryText` as a payload-size
  guard.** Rejected: `select=parts&kind=compaction` already provides
  the 80-char preview shape; `select=compactions` is specifically the
  "give me the full story" verb. The bullet's "preview" mention was a
  conservative guess at the right shape, not a hard requirement.

**Coverage.** No new tests. Existing coverage:
`core/.../session/query/CompactionsQueryTest.kt` (landed with
`913186bd`) exercises the happy path + empty-session case. Unchanged.

**Registration.** n/a — `SELECT_COMPACTIONS` is already wired through
the existing `session_query` registration in every AppContainer.
