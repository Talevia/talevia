## 2026-04-21 — export_project / import_project_from_json (VISION §3.4 可组合 / §5.1 可版本化)

Commit: `922da25`

**Context.** VISION §3.4 claims Project / Timeline should behave like a
codebase — readable, diffable, versionable, composable. The
intra-instance paths already existed:
- `fork_project` clones a project inside the same store.
- `save_project_snapshot` / `restore_project_snapshot` give named
  points-in-time.
- `describe_project` + `get_project_state` surface the structure.

The cross-instance leg was missing. A user who wanted to:
- back up a project before a risky refactor,
- share a project with a collaborator running a different Talevia
  instance,
- check a project into version control alongside source assets,
- ship a pre-baked project as a reusable template,

had no tool to produce the portable envelope. Earlier this loop,
`export_source_node` / `import_source_node_from_json` solved the same
problem at the node level. Project is the other natural unit.

**Decision.** Matching pair with `formatVersion =
"talevia-project-export-v1"`:

- `ExportProjectTool(projectId, prettyPrint?)` — serializes the full
  `Project` via `Project.serializer()` + `JsonConfig.default`. Output
  exposes the envelope string on `data.envelope` plus per-section
  counts (tracks, clips, assets, source nodes, lockfile entries,
  snapshots) so the agent can summarise the payload without
  re-parsing.
- `ImportProjectFromJsonTool(envelope, newProjectId?, newTitle?)` —
  decodes, asserts formatVersion match, checks target id isn't
  already taken (fail loud unless `newProjectId` renames), upserts.
  Default keeps the envelope's original id; default title comes from
  the envelope.

Sessions are **not** included in the envelope. They reference
projects (via `Session.projectId`), not the other way around, and a
session's meaning is tied to the specific Agent conversation that
produced it. Cross-instance import would leave orphan sessions that
break every session-lane tool.

Asset **bytes** are not bundled — the envelope is metadata-only. The
target instance needs access to the underlying media paths
(`MediaSource.File("/path/to/...")`) the assets reference. Help text
calls this out so the agent warns the user.

Reuses `project.read` / `project.write` permissions. Registered in
all five AppContainers.

**Alternatives considered.**

1. *Bundle asset bytes in the envelope (e.g. base64).* Rejected — a
   Vlog project can reference 10+ GB of footage; base64 adds 33%
   overhead and the envelope becomes unusable as a text artifact.
   Industry precedent (git LFS, S3 metadata indices) is to keep
   pointers in the envelope and let a separate mechanism move bytes.
2. *Include sessions in the envelope.* Rejected — sessions are
   conversation state, not project state. A session cross-loaded
   against a different project id would break every session-lane
   tool (parentId backlinks, message model refs, TimelineSnapshot
   references). Sessions belong in their own envelope format if we
   ever need cross-instance conversation share.
3. *Strip `renderCache` from the envelope (it's a perf cache,
   regenerable).* Rejected — `renderCache` is small (maps input-hash
   → output file pointer) and letting it ride along means a freshly-
   imported project doesn't re-render everything from scratch on the
   first export call. If the cache entries point at files the target
   doesn't have, `ExportTool`'s stale-guard rebuilds them. YAGNI on
   the strip-at-export optimization.
4. *Wrap `newProjectId` in a separate tool (`rename_project_on_import`)
   for safety.* Rejected — it's a single-call operation and the
   collision failure is loud enough on its own. Matches the shape of
   `import_source_node`'s `newNodeId` parameter.

**Coverage.** `ProjectExportImportToolsTest` — eight tests: round-trip
preserves timeline + source; reuses original id when no rename; ID
collision fails loud; unknown formatVersion rejected (spoofing a
valid envelope's version field so the parser succeeds and the
require() surfaces); malformed JSON fails with structured error;
newTitle override propagates to `ProjectSummary`; pretty-print
produces a larger envelope; missing source project fails loud on
export.

**Registration.** `ExportProjectTool` + `ImportProjectFromJsonTool`
registered in `CliContainer.kt`, `apps/desktop/AppContainer.kt`,
`apps/server/ServerContainer.kt`, `apps/android/AndroidAppContainer.kt`,
`apps/ios/Talevia/Platform/AppContainer.swift`. No new permissions
(reuses `project.read` / `project.write`).
