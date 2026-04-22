## 2026-04-22 — source_query gains scope=all_projects (VISION §5.1 rubric)

Commit: `b724d04`

**Context.** The §5.1 "跨 project 复用" rubric asked for a primitive to
find previously-built source nodes across a user's whole store — think
"I made a cyberpunk character_ref for project A six months ago, let me
find it and bind from project Z." `source_query(select=nodes)` already
handled single-project search (kind / kindPrefix / contentSubstring /
id filters, sorted, paginated). Its `projectId: String` parameter was
**required**, so cross-project search required the agent to iterate
`list_projects` + N × `source_query` calls client-side. For a store
with ~10 projects this is pure grep-by-hand friction.

Backlog bullet: `cross-project-source-similarity` (P2). Seventh skip on
`per-clip-incremental-render` (stays deferred per the 2026-04-19 multi-
day-refactor decision).

**Decision.** Single Input-level addition — **`scope: String? = null`**
with accepted values `project` (default, current behaviour) and
`all_projects`. Cross-project semantics:

1. **`scope=all_projects` requires `select=nodes`** (the only select
   whose rows make sense across projects — a DAG summary is per-project
   by definition).
2. **`scope=all_projects` rejects `projectId`** (mutually exclusive —
   all_projects *is* the "enumerate every project" verb).
3. **`projectId` becomes optional** in the schema (was required).
   Validated in `execute(...)` — `scope='project'` still requires it
   and fails loud with a hint pointing at `scope='all_projects'`.
4. **Per-row `projectId: String?`** on `NodeRow` — populated only under
   `scope=all_projects`, null for single-project queries (the owning
   project is already echoed in the Input context). §3a rule 4 three-
   state: null (single-project) vs string (cross-project hit) are
   different signals that legit callers need to distinguish.
5. **Pagination applies post-union**: every project's matches are
   collected, globally sorted by the existing `sortBy` with a
   deterministic `projectId` tiebreaker, then `offset` + `limit` clip
   the page. This keeps `total` honest (sum across projects) and page
   navigation stable across calls.

Output `sourceRevision` is `0L` for the cross-project path — there is
no single authoritative revision across a union of DAGs; per-row
`projectId` + downstream `source_query(scope=project)` is the way to
re-anchor to one project's revision.

Net tool count: 0. Net LLM-context cost: +1 field in the schema +
~3 lines of help text (~80 tokens). Well under the §3a rule 10
threshold.

**Alternatives considered.**

1. **New dedicated tool `search_source_nodes_all_projects`** — rejected
   per §3a rule 1. The tool would clone ~90% of `source_query(select=
   nodes)`'s filter surface; a new scope flag is the cheaper shape.
   This matches the 2026-04-21 `search-source-nodes-body-content-lookup`
   decision, which already folded `search_source_nodes` into
   `source_query(select=nodes)` — inverting that now would undo the
   consolidation.
2. **Return a `Map<ProjectId, List<NodeRow>>`** — rejected. Breaks the
   uniform `{select, total, returned, rows: JsonArray}` shape every
   other `*_query` tool emits; downstream parsers would have to branch
   on `scope` rather than the uniform row decoder. A `projectId` field
   on each row keeps decoding symmetric.
3. **Special tool id `search_source_nodes` that forwards to
   `source_query` with `scope=all_projects`** — rejected, same net-tool
   concern + LLM confusion about which to call first. A single tool
   with a scope flag is easier to reason about.
4. **Union the new `scope` with the existing `projectId`-null fallback
   (pick any project)** — rejected. Silently picking a project when
   `projectId` is null would make cross-project opt-in dangerous. The
   explicit `scope='all_projects'` is the keyword the LLM must type to
   opt into multi-project reads; fail loud otherwise.

**Coverage.**

- `source.SourceQueryAllProjectsTest` — 9 tests: enumerate across N
  projects with per-row projectId; `kind` filter works cross-project;
  `contentSubstring` surfaces snippet + owning projectId; empty store
  yields empty result; all_projects + projectId → loud fail
  ("mutually exclusive" hint); all_projects + dag_summary → loud fail;
  scope=project still requires projectId ("projectId is required"
  hint); unknown scope value fails loud; single-project scope
  unchanged (projectId field stays null on rows).
- Existing `SourceQueryToolTest` passes unchanged — the new scope
  field defaults to null, preserving the legacy code path completely.
- Full JVM + Android + desktop + iOS compile + ktlintCheck all green.

**Registration.** Pure schema + new query-body change; no new tool
classes, no `AppContainer` registrations. A new file
`source/query/NodesAllProjectsQuery.kt` sits next to `NodesQuery.kt`
to keep the two code paths side-by-side for future maintainers.

---
