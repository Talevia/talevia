## 2026-04-21 — validate_project checks source DAG integrity (VISION §5.1 structural lint)

Commit: `b5f9866`

**Context.** `ValidateProjectTool` covered clip- and timeline-level structural
invariants (dangling asset ids, non-positive durations, audio envelope
bounds, timeline-duration mismatch) but was silent on the source side.
Two classes of source-DAG bug slip past today:

1. **Dangling parent** — `SourceRef` pointing at a nodeId not present in
   `project.source.byId`. The likely cause is `remove_source_node` (which
   the tool's own kdoc says explicitly does not cascade) followed by the
   user forgetting to clean up descendants. Downstream impact: `Source.stale`
   walks parents and silently skips missing ids, so an edit upstream of the
   dangling edge never marks the orphaned descendant stale. The bug is
   invisible until a visual regression lands on export.
2. **Parent cycle** — a→b→c→a in the `parents` relation. Not currently
   prevented at the mutation boundary (`addNode` / `replaceNode` /
   `set_source_node_parents` accept any parent list). Breaks DFS walkers
   that assume acyclicity — `ExportSourceNodeTool.topoCollect` is the most
   load-bearing case today; staleness propagation is the other.
`describe_source_dag.computeMaxDepth` has its own cycle-guard, which is
exactly the shape of "we already know DFS isn't naturally cycle-safe;
each walker has to defend itself." A pre-export lint is cheaper than
fixing every walker.

**Decision.** Extend `ValidateProjectTool` with a `sourceDagIssues`
helper that:

- iterates every node and every `parents` edge, emitting one
  `source-parent-dangling` error per edge whose target isn't in
  `project.source.byId`,
- runs an iterative white/grey/black DFS across the whole DAG, emitting
  one `source-parent-cycle` error per distinct cycle (dedup'd on the
  `Set<SourceNodeId>` of nodes involved so two cycles through the same
  set aren't double-reported). Iterative, not recursive, so deep or
  adversarial DAGs don't blow the stack.

Both are errors (not warnings) — they silently break downstream behaviour
and `passed=false` should block export until fixed. No registration
changes: the tool is already registered in all five AppContainers.

**Alternatives considered.**

1. *Prevent cycle creation at the mutation boundary (`addNode` /
   `set_source_node_parents` reject edges that would form a cycle).*
   Rejected for this cycle as scope creep — it would touch
   `SourceMutations.kt` + two tools + their tests + changes the contract
   of load-bearing primitives. The reactive check is a self-contained
   lint extension that lets the agent diagnose existing broken projects
   today; proactive prevention is a cleaner follow-up once the
   diagnosis is in hand. Industry precedent: `git fsck` exists even
   though `git commit` doesn't let you create broken trees.
2. *Single combined error code for "DAG integrity".* Rejected — the two
   bugs have different autofix paths (`set_source_node_parents` for
   cycle breaking, `set_source_node_parents` or `remove_source_node` for
   dangling refs). Distinct codes let an autofix pipeline dispatch
   correctly, matching the pattern every other code in this tool follows
   (`volume-range` vs `fade-overlap` are both audio invariants with
   different remediations).
3. *Run cycle detection only when dangling check passes.* Rejected —
   dangling nodes don't create false cycle reports (the DFS skips the
   missing target via `if (next !in byId) continue`), and the two checks
   address different bug shapes anyway. Running both unconditionally is
   cheap and gives the user a full diagnostic in one pass.
4. *Report every cycle member as a separate issue.* Rejected — would
   double-count a 5-node cycle as 5 issues. One issue per distinct cycle
   (dedup'd on the node set) matches the user's mental model: "you have
   one cycle; here are the nodes in it."

**Coverage.** `ValidateProjectToolTest` — four new tests: dangling-parent
detection, 3-node cycle (a→b→c→a) flags all three in the message,
acyclic DAG (world→scene→shot) passes, self-loop as cycle. Existing
tests unchanged.

**Registration.** No-op — `ValidateProjectTool` already registered.
