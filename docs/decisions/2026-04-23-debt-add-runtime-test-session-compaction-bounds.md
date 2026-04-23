## 2026-04-23 — Pin Compactor default-bound prune invariant with a runtime test (VISION §5.7 rubric axis)

**Context.** `Compactor` has a production-default `pruneProtectTokens =
40_000` hard upper bound, yet none of the three pruning tests in
`CompactorTest.kt` (`pruneDropsOldCompletedToolOutputsButKeepsRecentTurns`,
`pruneDropsBiggestToolOutputFirstWhenBudgetAllowsOneDrop`,
`pruneHonoursEstimatedTokensOverrideOverByteHeuristic`) exercise that
default — all three override it to 100 or 200 to force drops with
tiny histories. A future refactor that short-circuits the prune
branch only at the default threshold — e.g. a misguided
`if (fixedTokens < 10_000) return emptySet()` guard — would still
pass all three existing tests and silently degrade compaction in
prod (the prune branch that drops old tool outputs at the 40k
boundary would never fire; sessions past the context window would
overflow without dropping anything).

Rubric delta §5.7: runtime coverage of the session-compaction bound
"部分" → "有". Sibling axis §5.6 #10 (critical-path runtime-tested)
picks up Compactor.process as now-guarded at its production default.

**Decision.** Add one new test method to
`core/src/jvmTest/kotlin/io/talevia/core/compaction/CompactorTest.kt`:

```kotlin
@Test
fun pruneDropsAtLeastOnePartWhenHistoryExceedsDefaultBound() = runTest {
    // …
    val compactor = Compactor(
        provider = FakeProvider(listOf(summaryTurn)),
        store = store,
        bus = bus,
        // No pruneProtectTokens override — exercise the production default.
    )
    val result = compactor.process(sid, history, ModelRef("fake", "test"))
    assertTrue(result is Compactor.Result.Compacted, "…")
    assertTrue((result as Compactor.Result.Compacted).prunedCount > 0, "…")
}
```

Shape:

- 3 user turns (`protectUserTurns=2` default leaves `[u1, a1]` in
  pre-window).
- `a1` holds a single `ToolState.Completed` stamped at
  `estimatedTokens = 55_000` — deterministically above the 40k
  bound via the `estimatedTokens` override path (byte heuristic
  would drift with unrelated string changes; the stamp is the
  stable lever the bound relies on in prod).
- `FakeProvider` returns a one-step summary turn so `process()`
  reaches its `Result.Compacted` branch.
- **No `pruneProtectTokens` override** on the Compactor — the
  point of this test is to pin behaviour at the production
  default.

Asserts: `result is Result.Compacted` and `result.prunedCount > 0`.
No changes to `Compactor.kt` itself — this is additive test
coverage.

**Axis.** n/a — this is a test addition, not a structural
refactor. (The nearby §3a #12 "architecture tax" check doesn't
fire either: no new tool registered, no `project_query` select
added.)

**Alternatives considered.**

- **Assert on `compactor.prune(history).size > 0` instead of
  `process()`.** Simpler (no store, no FakeProvider, no session
  setup). Rejected: `prune()` is `internal`; the bullet explicitly
  asks for "`Result.Compacted` with prunedCount > 0", which
  exercises the user-facing `process()` path end-to-end including
  the `markPartCompacted` + `upsertPart(compactionPart)` store
  writes. Testing the private helper alone would not catch a
  regression that breaks the `process()` flow between `prune()` and
  the returned `Result.Compacted`. Bullet wording is load-bearing
  here — the public contract is what we want to pin.

- **Use byte-heuristic-sized tool output (long string) instead of
  `estimatedTokens` stamp to hit 55k tokens.** Would require a
  ~220 000-char `outputForLlm` to cross the bound
  (`TokenEstimator` approximates ~4 bytes/token). Rejected: the
  byte heuristic is an approximation with off-by-factor slack; a
  future tweak to the estimator (e.g. smarter tokenization of
  JSON payloads) could shift where the bound bites. The
  `estimatedTokens` stamp is the authoritative override the
  pruning budget math uses (see `pruneHonoursEstimatedTokensOverrideOverByteHeuristic`),
  and pinning it at exactly 55 000 makes the test's pass/fail
  boundary deterministic across estimator changes.

- **Pin the bound via a property-based test (QuickCheck-style)
  over random (token-count, bound, protectUserTurns) triples.**
  Would catch more failure modes. Rejected for this cycle: the
  bullet scope was a single invariant ("超长 history 必 drop"),
  and a property-based test would require a generator + shrinker
  apparatus we don't have set up for compaction yet. The
  one-case test is the minimum to close the bullet; a broader
  invariant sweep is a separate bullet if profiling motivates it.

**Coverage.** `./gradlew :core:jvmTest --tests
'io.talevia.core.compaction.CompactorTest'` green (4 tests, was 3;
the new case is the default-bound guard). Full
`:core:ktlintFormat` + `:core:ktlintCheck` also green.

**Registration.** No registration change — test-only addition in
`core/src/jvmTest/kotlin/io/talevia/core/compaction/CompactorTest.kt`.
