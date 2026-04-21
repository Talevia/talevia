## 2026-04-20 — `diff_source_nodes` for node-level deltas

**Context.** `diff_projects` already answers "what changed
between v1 and v2?" at whole-project granularity (timeline +
source DAG + lockfile). It does **not** answer "what changed
between *these two nodes*?" — the common follow-ups:

- Compare a `fork_source_node` variant against its origin in
  the same project ("how did this Mei-with-red-hair diverge
  from the base Mei?").
- Compare a node in a forked project against its origin
  ("what did the fork actually do to the style_bible?").
- Walk a generate→update history ("what did
  `update_character_ref` change on the body?").
- Debug consistency drift ("did this node's contentHash really
  bump, and by what body fields?").

The workaround — two `describe_source_node` calls plus a manual
JSON-diff in the agent's head — is tokens-heavy and
error-prone.

**Decision.** Ship `diff_source_nodes(projectId,
leftNodeId, rightNodeId, leftProjectId?, rightProjectId?)`:

1. Flat input with `projectId` required and optional
   `leftProjectId` / `rightProjectId` overrides. Covers
   within-project and cross-project modes without a sealed
   input or two separate tools.
2. Output reports:
   - `kindChanged` + `leftKind` / `rightKind`
   - `contentHashChanged` + `leftContentHash` /
     `rightContentHash`
   - `bodyFieldDiffs`: list of `{path, leftValue, rightValue}`
     where `path` is dotted (`style.secondary`) with `[n]`
     suffixes for arrays.
   - `parentsAdded` / `parentsRemoved` (set delta over
     `SourceRef.nodeId`).
   - `leftExists` / `rightExists` / `bothExist` — missing
     nodes are **structural** rather than thrown.
3. Missing **projects** still fail loud (that's a caller bug,
   not a knowable outcome). Missing **nodes** are soft because
   the agent may legitimately ask "did this node still exist
   after my rename?".
4. Read-only — permission `project.read`.

**Body diff algorithm — deliberately simple.** Private helper
inside the tool file:
- Objects: descend key-by-key, paths join with `.` (root
  empty string). One-sided keys emit one diff with the missing
  side `= null`. Both-side keys recurse.
- Arrays: element-wise by index, path suffix `[n]`. Extra
  elements on one side emit per-index diffs with the missing
  side `= null`. No move/rename detection.
- Scalars / type mismatches: one leaf diff at the current
  path. `JsonNull` is preserved verbatim — `null` in the
  output means "absent", not `JsonNull`.

**Alternatives considered.**

- *Sealed two-variant input (WithinProject /
  CrossProject).* Rejected — the flat input is strictly
  cheaper to express in JSON Schema and in test setup, and
  there's no constraint the sealed shape enforces that the
  flat one doesn't (both modes resolve through the same
  `projects.get(ProjectId(...))` call).
- *LCS / move-aware JSON diff.* Rejected — the agent does
  not need "moved entry" detection; a flat per-path delta
  is easier to read back. Adding LCS complexity now would
  be speculative (CLAUDE.md anti-requirement: don't design
  for hypothetical future needs).
- *Fail loud on missing nodes.* Rejected — describe_source_node
  already fails loud, but *diff* is where the agent most wants
  to ask "did this still exist?". Structural answer is more
  useful than an exception.
- *Extend `diff_projects` with a per-node filter.* Rejected
  — `diff_projects` compares whole payloads across timeline +
  source + lockfile; adding per-node body-field output would
  conflate two different granularities. Separate tool is
  cleaner.

**Coverage.** 12 JVM tests:
- Identical node vs itself → empty diffs, matching hashes.
- Scalar body field change → one `BodyFieldDiff` with
  correct path + values.
- Array body field with per-index + trailing-drop changes.
- Nested object field change with dotted path.
- `kind` changed → `kindChanged=true` + both sides reported.
- Parents added / removed sets reflect correct differences
  (sorted; order-insensitive).
- Missing left node → `leftExists=false`, `bothExist=false`.
- Missing right node → symmetric.
- Cross-project diff (parent / fork).
- Missing project → fails loud.
- One-sided absent field → `rightValue=null`.
- `replaceNode` contentHash bump sanity (generate→update
  history marker).

**Registration.** Registered next to `DescribeSourceNodeTool`
in all five containers (cli / desktop / server / android /
ios). Diff-family and describe-family verbs co-located.

**Prompt.** One paragraph in the system prompt right after the
`diff_projects` blurb; `TaleviaSystemPromptTest` key-phrase
guard updated with `diff_source_nodes`.

**SHA.** 8003e5bea3d921827ef169cf93310322e86bafce
