## 2026-04-21 — list_sessions.includeArchived closes archive round-trip (VISION §5.4 专家路径)

Commit: `8a1f149`

**Context.** `archive_session` / `unarchive_session` landed in a prior
cycle this loop with a documented asymmetry: the only way to recover an
archived session was to keep its id — the store's `selectAll` /
`selectByProject` queries both hardcoded `archived = 0`, and the
`listSessionsIncludingArchived` method that would expose a different
query didn't exist. The archive/unarchive pair was useful on its own
(archive by id → unarchive by id), but a user with 40 archived
sessions who just remembered the title of one couldn't rediscover it
through the agent. This cycle closes the loop.

Three-layer fix:

**1. SQL (Sessions.sq).** Two new queries paralleling the existing pair:
  - `selectAllIncludingArchived` — everything, by `time_updated DESC`.
  - `selectByProjectIncludingArchived` — scoped equivalent.
Both omit the `WHERE archived = 0` clause. Leaves the non-archive-inclusive
queries untouched so the hot path (session picker rendering) stays
covered by the existing index + filter.

**2. SessionStore interface + impl.** New
`listSessionsIncludingArchived(projectId: ProjectId? = null)` method
on the interface; `SqlDelightSessionStore` delegates to the two new
queries. Kdoc points at the non-archived counterpart and explains
archived rows intersperse with live ones (distinguishable by
`Session.archived`).

**3. Tool input (ListSessionsTool).** New `includeArchived: Boolean =
false` field on `Input`. When true, routes through
`listSessionsIncludingArchived`; when false (default), uses the
existing `listSessions` path. Output shape unchanged — each `Summary`
already carried the `archived` flag so consumers can filter / render
without additional schema changes.

**Alternatives considered.**

1. *Flip `archived = 0` off in the existing SQL queries and add a new
   `archived = 0` filter at the store layer.* Rejected — the hot path
   (default listing) loses index coverage and the non-archived-only
   case (which is 99% of calls) becomes slightly slower for no
   benefit. Two SQL queries with the filter baked in is cheaper.
2. *Flip SQLite `foreign_keys` on + rely on a schema-level view.*
   Unrelated to this gap and would defer the fix indefinitely. The
   FK-cascade discussion is a separate cycle (touched on in the
   delete_session fix earlier this same loop).
3. *Add a third tool `list_archived_sessions`.* Rejected — three
   tools for two behaviors doubles the agent's mental-model load and
   the `includeArchived` boolean is the industry-standard shape for
   "soft-deleted records included?" (Postgres's `WHERE deleted IS
   NULL` vs `WHERE TRUE`, GitHub's API `state=closed,archived,open`,
   JIRA's `include_archived` flag).
4. *Name the Output field `includeArchived` too.* Rejected — the
   Output already surfaces `archived` per row, which is more useful
   than an aggregate flag (the agent can count archived vs live rows
   directly). No aggregate `includeArchivedInResult` Output field.

**Coverage.** `ListSessionsToolTest` — adjusted the renamed
`archivedSessionsAreFilteredByDefault` test (previously proved the
asymmetry; now proves the *default* behavior) and added two new tests:
`includeArchivedFlagSurfacesArchivedSessions` (asserts both rows
appear with correct `archived` flags) and
`includeArchivedRespectsProjectFilter` (the project narrows scope
through the new query path).

**Registration.** No AppContainer / permission changes — the tool was
already registered, and `session.read` permission already covered the
new input.
