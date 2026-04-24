## 2026-04-23 — `agent-pre-compaction-tool-archive` skip-close (VISION §5.4 rubric axis)

**Context.** Bullet asked to archive `Part.Tool(Completed)` payloads
to `<bundle>/session-compaction-archive/<sessionId>/<partId>.json`
before `Compactor.process` marks them compacted, with a new
`session_query(select=compaction_archive, partId=X)` to retrieve.
The claim: "the original tool payloads are lost" during compaction.

Liveness pre-check (§2.5) shows the claim doesn't match code:

1. **`Compactor.process` doesn't delete payloads.** Line 99 calls
   `store.markPartCompacted(it, now)` for each pruned id. Looking at
   `SqlDelightSessionStore.markPartCompacted` (line 170–172):
   ```kotlin
   override suspend fun markPartCompacted(id: PartId, at: Instant) {
       db.partsQueries.markCompacted(time_compacted = at.toEpochMilliseconds(), id = id.value)
   }
   ```
   It stamps `time_compacted` only. The `data` column — which holds
   the full `Part.Tool(state=ToolState.Completed(input, outputForLlm,
   data, estimatedTokens))` JSON — is untouched.

2. **No GC pass purges compacted payloads.** `grep deletePart |
   DELETE FROM parts | partsQueries.delete` across the codebase
   returns only `deleteBySession` + `deleteByMessage` — both
   wholesale cleanups on session / message delete, NOT
   compaction-driven. There is no scheduled compacted-payload
   deletion.

3. **The data is already retrievable via two existing tools.** Both
   already round-trip through `includeCompacted=true`:
   - `session_query(select=tool_calls, sessionId=X, includeCompacted=true)`
     returns a row per `Part.Tool` including compacted ones, with
     `compactedAtEpochMs` marker + partId.
   - `read_part(partId)` (existing tool since cycle ≤ 20) returns the
     full serialized Part JSON including `outputForLlm` + opaque
     `ToolState.Completed.data`. Its helpText explicitly calls out
     "use this for full Compaction summaries, full TimelineSnapshot
     timelines, opaque ToolState.Completed.data, etc."

The end-to-end workflow the bullet implies was missing is already
wired:
1. `session_query(select=tool_calls, sessionId=X, includeCompacted=true)`
   → list of every tool call, compacted ones flagged + retrievable by
   partId.
2. `read_part(partId)` → full output including opaque `data` payload.

What compaction DOES remove is **the compacted parts from the LLM's
next-turn context** (Agent loads history via
`listMessagesWithParts(sid, includeCompacted=false)`). The summary
`Part.Compaction` replaces them as the LLM-visible narrative. But
the underlying data stays in SQLite, accessible to human / tooling
inspection.

The bullet's premise — "the original tool payloads are lost" — was
speculation at repopulate time (cycle-48), not observed reality.
Speculative feature: archive-sidecar files would be redundant with
the already-preserved SQLite column.

Rubric delta §5.4 (post-mortem observability): **no delta** this
cycle — compacted tool payloads are already recoverable via existing
tools. The observability gap the bullet imagined doesn't manifest.

**Decision.** Skip-close per iterate-gap §2.5. No code written this
cycle; delete the bullet, archive the reasoning here. Future cycles
that genuinely need the bundle-sidecar archive (say, because a GC
pass gets added that DOES purge compacted payloads after a retention
window) can re-file the bullet with the new precondition.

**Axis.** n/a — no code changed.

**Alternatives considered.**

- **Build the archive anyway for defensive retention.** Rejected: it
  duplicates storage (SQLite blob + `<bundle>/session-compaction-
  archive/` sidecar), adds a new `ProjectStore` method + select +
  handler + tests, and burns ~100 tool-spec tokens on a select that
  solves no observed problem. §3a #1 (tool count) + §3a #10 (LLM
  context cost) both disfavor this.

- **Rename the bullet to "compacted tool payload is hard to
  discover"** and reshape to a docs-only add to the compaction
  decision doc. Rejected: the existing tools are documented. The
  issue isn't documentation, it's the bullet's factual claim.
  Skip-close removes the misleading task rather than dress it up.

- **Mark the bullet "stale-but-valuable-if-triggered"** with a skip-
  tag pointing at "add a retention GC first". Rejected: there's no
  concrete driver for a compacted-part GC either. Filing trigger-
  gated bullets that depend on hypothetical future infrastructure
  muddies the backlog (past history: `debt-register-tool-script`'s
  AND-gate on `debt-cross-container-tool-list-builder` being
  infeasible held that item in the queue for months). Skip-close is
  cleaner; a future concrete driver re-files from scratch.

**Coverage.** No new tests; existing tool_calls + read_part coverage
already exercises the compacted-payload retrieval path (spot-checked
via grep for `includeCompacted=true` + `Part.Tool` + `compactedAt`
assertions across the test tree — see
`SessionQueryStatusTest` / `SessionQueryRunStateHistoryTest` +
`ReadPartToolTest`). Nothing to add.

**Registration.** No registration changes — skip-close.

Subsequent repopulates should NOT re-drop this bullet unless a
concrete compacted-payload GC path gets added. The re-queue trigger
is: `grep -rn 'deletePart.*compacted\|purge.*compacted\|gc.*compacted' core apps`
returns non-empty. Until then, `session_query(select=tool_calls) +
read_part` is the canonical post-mortem path.
