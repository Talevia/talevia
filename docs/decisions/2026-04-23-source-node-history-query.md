## 2026-04-23 — `source_query(select=history)` + per-node JSONL body audit trail (VISION §5.5 rubric axis)

**Context.** Before this cycle, `update_source_node_body` overwrote each
source node's body wholesale and the pre-edit state was irretrievable —
the only audit surface was `git log` on `talevia.json`, which captures
the write but strips bundle-internal semantic diffs (whole-file JSON
diffs are barely readable for deeply nested bodies, and don't show
"which body existed at commit C?" without rebuilding the project).
Re-opening a project after a stray LLM rewrite: the draft the user
actually wanted was gone.

Rubric delta §5.5 (source-layer auditability): moves from **无** (every
body update silently destroys the prior state, no programmatic recall
path) to **有** (every `update_source_node_body` call appends the pre-
edit body to an audit log; `source_query(select=history, root=<id>)`
surfaces the last N revisions newest-first).

**Decision.** Three additions:

1. **`BodyRevision`** type (`core/src/commonMain/.../domain/source/
   BodyRevision.kt`). Snapshot record `(body: JsonElement,
   overwrittenAtEpochMs: Long)`. Full-snapshot, not a diff — body sizes
   are small (structured JSON) and reconstructing at read time from a
   diff chain costs more than the duplication saves. `JsonElement` (not
   `JsonObject`) matches `SourceNode.body`'s type exactly so no cast is
   needed at read time.

2. **`ProjectStore`** gains two default-no-op methods:
   `appendSourceNodeHistory(id, nodeId, revision)` and
   `listSourceNodeHistory(id, nodeId, limit)`. Default is no-op + empty
   so in-memory test fakes don't have to implement history — only
   `FileProjectStore` persists it. History is best-effort: a filesystem
   write failure is swallowed rather than failing the enclosing
   update_source_node_body dispatch (history is an audit log, not
   canonical state — a dropped entry is soft loss, a dropped body
   update would be hard loss).

3. **`FileProjectStore`** overrides the methods against
   `<bundle>/source-history/<nodeId>.jsonl` — one file per node, JSONL
   append-only, one `BodyRevision` per line. The JSONL directory is
   NOT gitignored (history lives inside the canonical bundle so
   `git push` carries it to collaborators). Write path:
   read-existing + atomic-rewrite (trivial cost at one line per
   revision; avoids the Kotlin/Native ambiguity around Okio's
   `appendingSink().use { ... }` — see Engineering caveat below).
   Read path: slurp file, split on newlines, reverse (JSONL is append-
   order so oldest-first), take `limit` (default 20, clamped `[1, 100]`).

4. **`source_query` gains `SELECT_HISTORY = "history"`** (now 6 selects:
   nodes / dag_summary / dot / descendants / ancestors / history).
   Reuses the existing `root` input field (same "target node id"
   semantics as descendants/ancestors); `limit` caps the revision
   window. `rejectIncompatibleFilters` extended to:
   - require `root` for history (same as relatives selects);
   - reject `depth` on history (flat revision log, not a graph);
   - reject `offset` on history (the JSONL is small, no pagination
     beyond `limit`);
   - leniently ignore `includeBody` (history always includes the full
     body — that's its entire point).

5. **`UpdateSourceNodeBodyTool`** hook: AFTER the mutation lands
   (so a crashed mutate can't leave a false-positive revision),
   `projects.appendSourceNodeHistory(...)` is called with the **old**
   body + current wall clock. A redundant no-op update (body unchanged)
   explicitly skips the append so identical-body rewrites don't
   pollute history.

**Axis.** n/a — net-new feature (adds a select + a write hook + a
bundle subdir); not a split/extract/dedup/refactor.

**Alternatives considered.**

- **Inline `bodyHistory: List<BodyRevision>` on `SourceNode`** (bullet's
  default proposal). Rejected per §3a #3 write-amplification concern:
  every body edit currently re-encodes the whole `talevia.json`. Adding
  an append-only history inline would multiply each edit's re-encode
  cost by (20 revisions × N nodes × body size). A project with 100
  nodes × 20 revisions × ~1 KB = ~2 MB rewritten on every single
  body edit. Per-file JSONL keeps `talevia.json` size stable and
  parallels the existing `<bundle>/media/` convention for per-entity
  bundle artefacts.

- **One combined `<bundle>/source-history.jsonl`** (single file for all
  nodes). Rejected: every append would rewrite the whole file as
  history grows across the project, plus per-node reads would require
  scanning every line to filter. Per-file indexes by filename (no
  scan, no filter).

- **Git log as the history source.** Rejected: requires shelling out
  to git, doesn't work for bundles not in a git repo, and the LLM-
  facing query would have to parse git-formatted diffs to reconstruct
  whole-body snapshots. Per-file JSONL is a first-class, structured
  audit log independent of VCS.

- **Cap writes at 20 (read-modify-write to drop oldest).** Rejected:
  adds read-modify-write cost to every append; per-file JSONL is
  already bounded by "users don't edit one node 10,000 times". Cap at
  read with a high max-limit (100). If a user's history file legitimately
  exceeds MB-scale, a dedicated prune cycle can land. Decision
  explicitly notes this so future ring-buffer work is scoped.

- **Synchronous mutation + history in one atomic block.** Rejected:
  history is orthogonal to canonical state (the new body is the
  canonical post-edit answer; history is auxiliary). Landing history
  after the mutate + swallowing errors means a degraded filesystem
  doesn't block user edits. The mutate itself is already per-project
  mutex-guarded, so concurrent history appends to the same node are
  serialised through the same lock.

- **Append NEW body rather than OLD body.** Rejected: the current body
  is always readable from `SourceNode.body` — duplicating it into
  history is free memory overhead with no informational value. Storing
  the pre-edit state is strictly more useful: history = "drafts I
  overwrote", current body = "what's there now". No overlap.

**Engineering caveat.** First implementation used `fs.appendingSink(file)
.buffer().use { ... }` for append semantics. This compiled on JVM but
failed on Kotlin/Native with "AutoCloseable vs BufferedSink" resolution
— Okio's `Sink` isn't `java.lang.AutoCloseable` on Native, and the
stdlib `use` extension doesn't cover it. Switched to
read-existing + `atomicWrite` (which the store already uses for
`talevia.json`) for portable KMP semantics. One-line-per-revision
means the "rewrite whole file per append" cost is negligible.

**Coverage.**

- `UpdateSourceNodeBodyToolTest.appendsBodyHistoryRevisionOnUpdate`:
  two updates → two past revisions in newest-first order; content
  matches pre-edit bodies.
- `UpdateSourceNodeBodyToolTest.noHistoryAppendWhenBodyUnchanged` (§3a
  #9 bounded-edge): identical-body update must NOT pollute history
  with a no-op revision.
- `SourceQueryHistoryTest.returnsRevisionsNewestFirst`: 3 appended
  revisions → read returns them in reverse (JSONL oldest-first →
  API newest-first).
- `SourceQueryHistoryTest.limitCapsTheWindowShorterThanPersistedCount`:
  5 revisions, `limit=2` → returns newest 2.
- `SourceQueryHistoryTest.unknownNodeReturnsEmptyAndAnnotatesNarrative`:
  narrative distinguishes "unknown id" from "zero revisions".
- `SourceQueryHistoryTest.existingNodeWithZeroRevisionsHasDistinctNarrative`:
  existing node, no updates → narrative flag differs from "unknown".
- `SourceQueryHistoryTest.rootIsRequiredForHistorySelect`: missing
  root fails loud.
- `SourceQueryHistoryTest.incompatibleKindFilterRejectedOnHistorySelect`:
  `kind` filter (nodes-only) rejected on history select.

Gradle: `:core:jvmTest` + `:apps:cli:test` + `:apps:server:test` +
`:apps:desktop:assemble` + `:core:compileKotlinIosSimulatorArm64` +
`:apps:android:assembleDebug` + ktlintCheck all green.

**Registration.** No new tool — `source_query` already registered in
all 4 JVM AppContainers + iOS skips tool registration entirely. The
new select is automatically reachable through existing dispatch. The
`UpdateSourceNodeBodyTool` constructor gained a default `clock: Clock
= Clock.System` parameter; existing `UpdateSourceNodeBodyTool(store)`
callsites compile unchanged.

**§3a arch-tax check (#12).** `source_query` select count: 5 → 6.
Dispatcher select threshold for `debt-unified-dispatcher-select-
plugin-shape` upgrade is 20. No upgrade.

**Session-binding note (§3a #6).** `source_query(select=history)`
takes `projectId` in input today, matching every other `source_query`
select. When `session-project-binding` lands, this should switch to
reading from ToolContext.currentProjectId — same migration that will
apply to every `*_query` tool with explicit projectId, not a
history-specific debt.

**Follow-up** (not for this cycle): if a user ever reports
`<bundle>/source-history/<id>.jsonl` exceeding ~1 MB on a
frequently-edited node, add a `prune_source_node_history` tool that
drops all but the last N revisions. Deferred until the signal exists;
not speculative infra.
