## 2026-04-22 — project-diff-source-graphs already shipped (no-op)

Commit: `(docs-only — no code change; see reasoning below)`

**Context.** Backlog bullet `project-diff-source-graphs` said:
`DiffProjectsTool` 对比 timeline；source DAG 的 diff (哪些 SourceNode
被加 / 删 / 改) 没 entry point. Fork 项目后想看 "我动了哪些 source
节点" 只能人肉. 方向: 扩 `diff_projects` 的 Output 加
`sourceAdds / sourceRemoves / sourceModifies`，或单独
`diff_source_graphs(a, b)`；对比口径用 `SourceNode.id` + `contentHash`.

**Status.** The first path ("extend `diff_projects`") is **already
implemented** — and has been since `diff_projects` itself landed
([6128672](../../core/src/commonMain/kotlin/io/talevia/core/tool/builtin/project/DiffProjectsTool.kt)
`diff_projects — closes VISION §3.4 "可 diff"`). The tool's
`Output.source: SourceDiff` has exactly the three lists the backlog
bullet asked for:

```kotlin
@Serializable data class SourceDiff(
    val nodesAdded: List<SourceNodeRef> = emptyList(),
    val nodesRemoved: List<SourceNodeRef> = emptyList(),
    val nodesChanged: List<SourceNodeRef> = emptyList(),
)
```

Matching rule is `SourceNodeId` + `contentHash` (identical to what
the bullet proposed):

```kotlin
private fun diffSource(from: Project, to: Project): SourceDiff {
    val fromNodes = from.source.nodes.associateBy { it.id.value }
    val toNodes = to.source.nodes.associateBy { it.id.value }
    …
    val changed = (fromNodes.keys intersect toNodes.keys).mapNotNull { id ->
        val f = fromNodes.getValue(id)
        val t = toNodes.getValue(id)
        if (f.contentHash == t.contentHash) null
        else SourceNodeRef(id, t.kind)
    }
    …
}
```

Test coverage: [`DiffProjectsToolTest.sourceDiffDetectsAddRemoveChange`](../../core/src/jvmTest/kotlin/io/talevia/core/tool/builtin/project/DiffProjectsToolTest.kt:161)
exercises all three buckets on a snapshot-vs-current diff:

```kotlin
assertEquals(setOf("c"), out.data.source.nodesAdded.map { it.nodeId }.toSet())
assertEquals(setOf("b"), out.data.source.nodesRemoved.map { it.nodeId }.toSet())
assertEquals(setOf("a"), out.data.source.nodesChanged.map { it.nodeId }.toSet())
```

Both same-project snapshot-vs-snapshot and cross-project (fork vs
parent via `toProjectId`) modes flow through the same `diffSource`
helper — no separate entry point required.

**Decision.** Close the backlog bullet with no code change. Both
shapes the bullet offered would have been redundant:

1. **Extending `diff_projects` output with `sourceAdds /
   sourceRemoves / sourceModifies`** — already present as
   `nodesAdded / nodesRemoved / nodesChanged`. Different naming (the
   shipped version uses `nodes*` to parallel `clips*` / `tracks*` in
   the same Output), same semantics.

2. **Standalone `diff_source_graphs(a, b)` tool** — rejected under
   §3a Rule 1 (net tool count). A sibling would duplicate
   `diff_projects`' `resolve()` (snapshot-or-current per side) and
   split the "compare two projects" answer across two tools. Today's
   pairing of `diff_projects` (whole-project, node-granularity
   add/remove/change) + `diff_source_nodes` (pairwise node-level
   body-field + parent-set detail) already covers both "what moved
   across the whole DAG" and "what specifically changed inside this
   one node" — distinct questions with distinct tools, no gap.

**Coverage check.** For the three cross-project DAG questions the
bullet called out:

- "what did this fork add over its parent?" →
  `diff_projects(fromProjectId=parent, toProjectId=fork).source.nodesAdded`.
- "what did the fork remove?" → same output, `.source.nodesRemoved`.
- "what was modified in place?" → same output, `.source.nodesChanged`
  (matched by id + contentHash).

All three covered by one tool dispatch, no seam.

**Why the bullet existed.** The backlog was repopulated by the
rubric-driven `/iterate-gap` sweep on `24ade91` (2026-04-22), whose
recent-activity scan evidently didn't notice that `SourceDiff` was
already part of `DiffProjectsTool` when it landed in `6128672`.
Same failure mode as the `cross-session-spend-aggregator` stale
bullet closed earlier today — the rubric leaned on naming
("`source-graphs`" ≠ "Source") to decide "this isn't covered".
Recording this decision so the next sweep doesn't re-open it.

**Impact.**
- No code change. No tests modified.
- Backlog bullet `project-diff-source-graphs` removed.
- Decision doc preserves the "evaluated → already done" outcome.

**Follow-ups.** If a future concrete driver asks for edge-level
DAG diff at the project scope (e.g. "node X was rewired: parent
removed, different parent added, but contentHash unchanged because
the body didn't change"), the extension point is `DiffProjectsTool.
SourceDiff` — a new `edgesAdded / edgesRemoved: List<EdgeRef>`
field can be added with a default `emptyList()` for forward-compat.
Not planned today. Today, `diff_source_nodes(…).parentsAdded /
parentsRemoved` answers the same question one node at a time.
