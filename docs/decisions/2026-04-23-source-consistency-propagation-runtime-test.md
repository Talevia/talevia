## 2026-04-23 — Pin the consistency-kind × transitive-DAG × tool-runtime intersection with one end-to-end stale-clip test (VISION §5.5 rubric axis)

**Context.** P2 backlog bullet `source-consistency-propagation-runtime-test`
(from the cycle-16 repopulate). The bullet claimed
`core/domain/source/consistency/` had propagation rules without an
end-to-end runtime test for "父改→子 stale". A §2.5 liveness pre-check
showed the bullet's premise is **partially** stale — the lane is
actually covered by two *halves*, but **their intersection** is not:

1. `core/src/jvmTest/kotlin/io/talevia/core/domain/TransitiveSourceHashTest.staleClipsFromLockfileFlagsClipWhenGrandparentEdited`
   already proves grandparent-edit → grandchild-deep-hash-drift →
   `Project.staleClipsFromLockfile()` reports the bound clip stale.
   But the nodes it uses are `kind = "test.generic"` — a bespoke test
   fixture that bypasses the consistency helpers
   (`addCharacterRef` / `addStyleBible`) and bypasses the tool
   runtime (it asserts against the domain extension directly).
2. `FindStaleClipsToolTest.characterEditFlagsBoundClip` already
   proves a consistency-node edit surfaces through the tool, but
   only for a *directly-bound* character_ref; the parent-chain
   path isn't exercised. `reportsOnlyTheBoundNodesThatChanged`
   adds a two-node case but both nodes are direct bindings, not
   a parent-child pair.

Neither test pins **the combined shape** the bullet actually cares
about: a DAG where a `character_ref` child declares a `style_bible`
parent (consistency kinds, not test-generic), the clip binds the
*child*, the user edits the *parent*, and the **tool-runtime** path
(not a bare domain call) reports the child-bound clip stale with
the child's id in `changedSourceIds` — deep-hash drift surfacing
on the descendant even though its shallow `contentHash` is
unchanged. A future refactor that either (a) re-introduces
shallow-hash-only comparison, or (b) silently drops the
`parents = […]` argument from `addCharacterRef` / `addStyleBible`,
or (c) short-circuits `deepContentHashOf` for consistency kinds
specifically, would quietly break this path and no existing test
would fire.

Rubric delta §5.5 (source-layer propagation coverage): "部分" →
"有". The three-way intersection (consistency-kind bodies ×
transitive-DAG hash drift × tool-runtime dispatch) moves from
"inferred from two adjacent tests" to "one test asserts it
explicitly end-to-end".

**Decision.** Add one test to the existing
`FindStaleClipsToolTest.kt` file:

```kotlin
@Test fun transitiveConsistencyEditFlagsGrandchildBoundClip() = runTest {
    // noir (style_bible) ← mei (character_ref, parents = [noir])
    // clip c-1 binds mei only.
    // Snapshot mei's deep hash at generation time into the lockfile.
    // Edit only noir.body → mei's shallow contentHash unchanged,
    // deep hash drifts, lockfile snapshot mismatches → tool reports
    // c-1 stale with changedSourceIds = [mei].
    …
}
```

No new test file, no new helper, no production-code change.
Reuses the file's existing `newStore() / seedProjectWithClip() /
appendLockfile() / fakeProvenance()` scaffolding + the
`addCharacterRef(parents = …)` / `addStyleBible(…)` consistency
helpers that already exist in
`core/src/commonMain/kotlin/io/talevia/core/domain/source/consistency/ConsistencySourceExt.kt`.

Extra guard lines in the test:

1. **Baseline assertion before the edit** — `tool.execute(...)`
   once before the mutation must return `staleClipCount == 0`.
   This catches the failure mode where a future refactor makes
   `deepContentHashOf` non-deterministic (e.g. includes a
   wall-clock timestamp by accident) and the lockfile-snapshot
   mismatch fires on a **fresh** project. Without this guard,
   the subsequent "count == 1 after the edit" could pass for
   the wrong reason.
2. **Explicit contract assertion on `changedSourceIds`** — the
   report names the bound-and-drifted ids, not the root-cause
   ancestors. `noir` drove the drift but isn't in the clip's
   binding, so `changedSourceIds = [mei]` — the *child* that
   the clip actually binds. This pins the reporting contract
   against a future refactor that might try to "surface the
   actual changed node" by walking upward (which would be a
   silent regression in the report shape consumers depend on).

**Axis.** n/a — additive test coverage, not a structural refactor.
The thing that would re-invalidate this test: a semantic change in
which node ids end up in `StaleClipReport.changedSourceIds`. If
future work genuinely wants that contract to change ("report the
root-cause ancestor, not the bound descendant"), the decision
lives in a new file — not a silent test-update.

**Alternatives considered.**

- **Skip-close the bullet** (§2.5) — argue both existing tests
  cover it "between them" and no new test is needed. Rejected:
  the intersection specifically isn't covered; a future refactor
  that breaks the consistency-kind × transitive × tool-runtime
  combination wouldn't fire any existing test. The whole point
  of having a test is that the three axes combine in ways a
  sum of two-axis tests can't prove. Skip-closing would leave
  the bullet's stated invariant genuinely untested.

- **Move the existing `TransitiveSourceHashTest.staleClipsFromLockfileFlagsClipWhenGrandparentEdited`
  from `test.generic` nodes to `character_ref` / `style_bible`.**
  More parsimonious — no new test, just broaden the fixture.
  Rejected: `TransitiveSourceHashTest` is deliberately named for
  the hash-propagation invariant at the *domain* layer; mixing
  in consistency-kind fixtures muddles what the test is *for*,
  and the test doesn't go through the tool. The tool-runtime
  assertion is exactly the coverage the bullet asks for. Better
  to keep the two tests focused on distinct axes and have them
  intersect in one explicit additional test.

- **Add a generic "DAG-level" integration test under
  `core/src/jvmTest/kotlin/io/talevia/core/domain/source/`**
  instead of `tool/builtin/project/`. Rejected: the bullet's
  "端到端 runtime 测试" phrasing explicitly wants the **tool**
  dispatch lane, not just another domain-level assertion.
  The closest-fit layer is the tool test file.

- **Also test a **3-level** DAG (`a ← b ← c`, bind clip to c,
  edit a).** More thorough. Rejected: the 3-level case is
  already covered at the deep-hash layer by
  `TransitiveSourceHashTest.grandparentEditChangesGrandchildDeepHash`;
  the 2-level consistency-kind × tool case is what's genuinely
  missing. Adding a 3-level variant would be zero-marginal-value
  given the hash-propagation recursion is already tested — and
  the §3a #9 "cover reality, not variation" discipline says stop
  at the minimal test that pins the specific uncovered
  intersection.

**Coverage.** The new test:
- Builds the DAG through the real consistency helpers
  (`addStyleBible` + `addCharacterRef` with `parents = […]`).
- Uses a real `FileProjectStore` via `ProjectStoreTestKit.create()`
  (same as the rest of the file).
- Snapshots the deep hash into a real `LockfileEntry` then mutates.
- Dispatches through `FindStaleClipsTool.execute(...)` — the
  actual LLM-facing tool surface, not the raw
  `staleClipsFromLockfile()` extension.
- Asserts the exact report shape
  (`staleClipCount == 1`, `reports.single().clipId == "c-1"`,
  `changedSourceIds == ["mei"]`).

Gradle: `:core:jvmTest --tests 'io.talevia.core.tool.builtin.project.FindStaleClipsToolTest'`
green including the new method; ktlintFormat + ktlintCheck clean.

**Registration.** None — pure test addition. No new tool, no
AppContainer touch, no platform files, no serialization surface
change.
