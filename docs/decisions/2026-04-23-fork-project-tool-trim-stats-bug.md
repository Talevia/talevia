## 2026-04-23 — Unskip ForkProjectTool trim-stats test; bug was already fixed (VISION §3a-3 correctness)

**Context.** Backlog bullet `fork-project-tool-trim-stats-bug` claimed
`ForkProjectTool.Output.clipsDroppedByTrim` / `clipsTruncatedByTrim`
always returned `(0, 0)` because the tool was applying `variantSpec`
twice — once at persist, then a second recount on the already-trimmed
project. The test
`variantSpecDurationDropsTailClipsAndTruncatesStraddlers` was
`@Ignore`d with a `TODO(file-bundle-migration):` comment describing
the fix ("use the FIRST reshape's stats in Output instead of
recomputing").

Walking the code today (`ForkProjectTool.kt:285-348`), that fix is
**already in place**: `applyVariantSpec(baseFork, spec)` runs once
per create-path, before `projects.mutate` / `projects.upsert`
persists the reshaped body, and `Output` pulls `clipsDropped` /
`clipsTruncated` directly from that single `reshape` local. The
second-apply pattern no longer exists. Somebody fixed the bug
without unskipping the test or deleting the TODO — typical
"fix-but-forget-to-flip-the-gate" drift. Rubric delta §3a-9: a
correctness test that should have been guarding the fix was silently
inert; unskipping it restores the guard.

**Decision.** Delete the stale 5-line TODO block and the
`@kotlin.test.Ignore` annotation on the test. Test runs green
immediately: `(clipsDroppedByTrim=1, clipsTruncatedByTrim=1)`
matches the expected "c-3 drops, c-2 truncates" at a 3-second cap.
No source change to `ForkProjectTool.kt` — the fix was already
correct; only the test gate and stale doc needed to flip.

**Alternatives considered.**
- **Re-investigate whether the bug was actually fixed** — could have
  applied a synthetic second `applyVariantSpec` call against a
  post-persist project to confirm it returns (0, 0), but that's a
  hypothetical regression check. The current code structure (one
  `reshape` local, used in both `forkBody` persist and `Output`
  stats) makes it architecturally impossible to recount a
  post-persist project. Don't write a regression test for a shape
  the code can't produce; trust the structure.
- **Leave the TODO but unskip the test** — rejected: stale TODOs
  rot. If the code matches the "fix" the TODO describes, the TODO
  has done its job and should be deleted in the same commit that
  unskips. Leaving a "fix this" note next to code that no longer
  needs fixing is noise that a future reader will waste cycles
  double-checking.
- **Keep the test `@Ignore`d and delete the stale TODO only** —
  rejected outright: a passing-but-disabled test is worse than no
  test, because it implies future coverage while providing none
  (and that's what let the original bug mask itself — whoever
  fixed the code in a past cycle didn't need to confront the test,
  so they could quietly forget the `@Ignore`).

**Coverage.** `:core:jvmTest` green, including the specific
test class. `./gradlew ktlintCheck` green.

**Registration.** No registration change — source behaviour
unchanged, only test gating and a stale comment removed.
