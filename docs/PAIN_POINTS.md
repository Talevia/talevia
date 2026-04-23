# Architectural pain points

Running log of architectural friction observed while doing `/iterate-gap`
work. Each entry grounded in the cycle that surfaced it — so a future
contributor can correlate "this felt wrong" with the code + commit that
made it feel wrong.

**Shape of entries.** Decisions (`docs/decisions/`) record *what we chose
and why*. This file records *what the act of implementing told us is
wrong today* — observations, not prescriptions. An entry can later
become a backlog bullet if the fix is actionable, or just sit here as
accumulated evidence that a deeper refactor is warranted.

**Conventions.** Append-only. Newest section at the bottom. Never edit
or re-order past entries — pain points are snapshots of what hurt then,
even if we've since fixed it.

---

## 2026-04-22 — debt-resplit-project-query-tool (`b9d0da3`)

### Row data classes leak to call sites via `Owner.Type.serializer()` coupling
23 call sites across 4 test files + `SnapshotPanel.kt` had to be touched
to relocate `ProjectQueryTool.TrackRow` etc. to the `query.` package.
The churn happened because the tool exposes its output as a raw
`JsonArray` and every caller has to know the per-select row serializer
by name. The tool "unifies" 13 selects into one dispatcher on the LLM
side but fans out 13 different typed decoding contracts on the Kotlin
side. A typed facade (`ProjectQueryTool.decodeRows<T>(output, select)`
or a select-indexed `KSerializer` table) would make the row type an
internal detail — next time we re-organise rows, zero call sites
change. This is the missing abstraction.

### Incremental splits drift back at the same rate when they only attack symptoms
`ProjectQueryTool.kt`: 638 → 540 (cycle `6e7bd8f`, 2026-04-21) → 547
one month later → 233 (this cycle). The first split extracted
`run<Select>` handlers but deliberately left row data classes nested,
citing "API stability". Every new select (consistency_propagation,
spend, snapshots, the 3 `describe_*` rollups) brought its own ~20-line
row class back into the main file. The long-file signal was a
symptom; the structural cause was "every new select adds lines to the
dispatcher file". The first split cut the wrong axis. Rule of thumb:
before a split, identify *what grows with new <feature-unit>* and cut
along that axis — not just along the current hotspot.

### "Stay nested for API stability" was net-negative because the file was at a structural limit
The prior cycle deferred call-site churn by keeping rows nested. This
cycle had to do that churn anyway (23 sites) because the file grew
back over the long-file threshold. So the stability argument *delayed*
the churn without preventing it — and the delay cost was a whole
repeated split cycle + a re-bumped entry on the backlog. Takeaway:
don't defer call-site churn for API-stability arguments when the
containing file is already at a structural limit. Take the hit once,
at the natural extraction point, not twice across two cycles.

---

## 2026-04-23 — debt-resplit-session-query-tool (`<this commit>`)

### "Unified query dispatcher" is a recurring shape, not a one-off
Back-to-back cycles had to apply the exact same refactor recipe — extract
nested row data classes to sibling files — to `ProjectQueryTool` then
`SessionQueryTool`. The convention ("rows nested on the dispatcher so callers
use `Owner.Row.serializer()`") was applied uniformly at design time across
both tools, and broke down at the same structural limit in both. A third
unified-query tool (e.g. a future `AgentQueryTool`) would hit the same wall
by default. Takeaway: when a structural pattern — dispatcher + N
per-discriminator handlers + per-discriminator rows — ships twice, the
convention of "rows live on the dispatcher" should be dropped project-wide
before the third instance lands. Better yet, introduce a `QueryDispatcher<I,
O>` base abstraction (maybe inside `core.tool.query`) that owns the
select-to-handler routing and forces rows to be top-level from day 1. Today
that abstraction is implicit and each dispatcher re-invents it.

### The "row decoding" contract duplicates across 11+ test helpers
Each session-query test file has a private `rows(out): List<FooRow>` helper
that unwraps `out.rows: JsonArray` via
`JsonConfig.default.decodeFromJsonElement(ListSerializer(FooRow.serializer()),
out.rows)`. That 3-line helper now appears in
`SessionQueryToolTest`, `SessionQueryCacheStatsTest`,
`SessionQueryContextPressureTest`, `SessionQueryRunStateHistoryTest`,
`SessionQuerySpendTest`, `SessionQueryStatusTest`,
`SessionQueryToolSpecBudgetTest`, plus the analogous versions in every
project-query test (see 2026-04-22 entry). That's 10+ copies of the same
"decode the untyped JsonArray into typed rows" operation. A 5-line
test-kit helper like `Output.decodeRows(ser: KSerializer<T>): List<T>`
collapses all of them. Evidence that we need a typed-output facade
(same missing abstraction as the 2026-04-22 first entry).

---

## 2026-04-23 — fork-project-tool-trim-stats-bug (`<this commit>`)

### "Fix-but-forget-to-flip-the-gate" drift — passing tests that are still `@Ignore`d
The backlog bullet for this cycle described a real bug: `ForkProjectTool`
was re-applying `variantSpec` against an already-trimmed project and
always getting `(0, 0)` trim-stats. Walking the code today, that bug is
**gone** — `applyVariantSpec` runs once on the pre-persist `baseFork` and
`Output` pulls stats directly from that single `reshape` local. But the
guarding test (`variantSpecDropsTailClipsAndTruncatesStraddlers`) was
still `@Ignore`d, wearing a `TODO(file-bundle-migration):` comment that
described the fix as if it hadn't landed yet. Whoever fixed the source
didn't unskip the test or delete the TODO, so the backlog bullet (and a
future reader — me) spent the first half of this cycle verifying a bug
that wasn't live. Structural signal: every `@Ignore` / `@Disabled` is a
**silently-lying gate** — promises future coverage it doesn't actually
enforce, and lets bug-fix commits skip confronting the coverage contract.
Concrete mitigations worth considering:
1. Debt-scan already counts `@Ignore`s; tightening enforcement so any
   `@Ignore` surviving > 1 repopulate cycle graduates to P0
   "unskip-or-delete" would have caught this before the bullet description
   rotted.
2. A ktlint / detekt rule requiring `@Ignore` to carry either an issue-id
   or an expiry date would force skips to be self-dating.
3. Backlog bullets describing bugs should include a one-liner on how to
   verify the bug still exists (typically the test name), so future
   cycles start with `git grep @Ignore ${testName}` rather than inferring
   live-or-dead from the source. This is more important than it sounds —
   when backlog descriptions decouple from reality, the `/iterate-gap`
   loop can spend cycles on ghost work.

---

## 2026-04-23 — bundle-cross-machine-export-smoke (`<this commit>`)

### "FakeFileSystem" has no recursive copy; every bundle-level test reinvents it
Writing this test needed a `cp -r` primitive on Okio's `FakeFileSystem`.
There isn't one — and grepping the repo shows earlier bundle tests have
either avoided needing it, or open-coded ad-hoc walks (none currently but
the pattern is about to repeat). The helper I wrote in this test
(`copyDirectoryRecursive(fs, src, dst)`) is 12 lines and obviously belongs
in `ProjectStoreTestKit` or a sibling `BundleFsTestKit`. Mitigation: when
a second bundle-test needs this, lift it to a test-kit helper at that
point, not before (don't pre-abstract on N=1). Logging it here so the
"three bundle tests handrolled the same walker" anti-pattern doesn't
sneak up later.

### Bullet-to-code location mismatch: `apps/cli or apps/server` intent didn't match the natural test layer
The backlog bullet asked for the test "in `apps/cli` or `apps/server`" but
the invariant under test — bundle portability across machines — is a Core
contract (`FileProjectStore` + `BundleMediaPathResolver`). Implementing
the bullet literally would have required either driving the CLI REPL from
a test (heavy fake-LLM scaffolding) or duplicating 200 lines of
`CliContainer` wiring in the test for zero added coverage. Structural
signal: **backlog bullets specifying a file location (not just the
invariant) can steer the work into the wrong layer**. When reading a
bullet, separate "what invariant must be guarded" from "where the test
file should sit" — the first is load-bearing, the second is guidance.
For bullets authored in future repopulates, prefer phrasing like
"guard the invariant X; place the test wherever its dependencies
naturally live" over prescribing the path. The current bullet format
(`**direction:** ...`) already leaves room for this — just be deliberate
about not over-specifying when writing new bullets.

---

## 2026-04-23 — import-media-tool-bundle-resolver (`<this commit>`)

### Second stale bullet this sprint — backlog-decoupling pattern is generalising
Cycle 3 (`fork-project-tool-trim-stats-bug`) closed a bullet whose described
bug was silently fixed; this cycle closes a bullet whose described refactor
(`MediaStorage.import` → `projects.mutate`) was silently completed in an
earlier unrelated cycle (`9d0f70f`). Two stale bullets in five cycles is no
longer an anomaly — it's a rate. Root cause: large refactors (like the
`MediaStorage` deletion) that touch many files don't go back and sweep the
backlog for bullets they obsoleted, so the bullets keep riding the P1
queue. Mitigation for future large refactor commits: the decision file for
a "delete / consolidate / unify" refactor should include a
"backlog-sweep:" line listing bullet slugs the refactor obsoletes, and
the refactor's PR should also delete those bullets in the same commit.
Then `docs(backlog): repopulate` sees only live bullets, and cycles like
this one don't waste time re-verifying ghost work. Logged alongside the
earlier "fix-but-forget-to-flip-the-gate" observation as evidence that
backlog freshness needs an explicit step in refactor-cycle hygiene, not
just in repopulate-cycle hygiene.

### `BundleBlobWriter` contract takes `ByteArray` — blocks streaming large imports
`ImportMediaTool`'s copy-into-bundle branch re-implements
`FileBundleBlobWriter.writeBlob`'s atomic tmpfile+move pattern rather than
calling it, because the writer interface takes `bytes: ByteArray` while
ImportMediaTool has an `okio.Source` it wants to stream (a gigabyte 4K
rush shouldn't live in memory just to be bundled). Short-term this is
fine — both paths use the same okio atomic-move pattern so the bundle
layout is identical. Structural follow-up: add
`writeBlobStreaming(projectId, assetId, source: okio.Source, format)` to
`BundleBlobWriter` and migrate both the AIGC byte-buffer callers and
`ImportMediaTool` onto it. Then "bundle write" is genuinely one code
path. Not worth its own cycle yet (no active import failures, no active
imports large enough to notice), but worth tracking here so the third
caller (likely `ExtractFrameTool` per an active P1 bullet) can push this
off the pain point list once it shows up.

---

## 2026-04-23 — extract-frame-tool-bundle-write (`<this commit>`)

### Three stale bullets in six cycles — P1 queue is aging out from under the backlog
This was the third bullet in a row (after `fork-project-tool-trim-stats-bug`
cycle 3 and `import-media-tool-bundle-resolver` cycle 5) whose described
work had silently already landed in a prior refactor. 3/6 ≈ 50% stale
rate is no longer a blip — the P1 queue is being outrun by the actual
codebase. Root cause confirmed across all three: a large consolidation
refactor (`9d0f70f` deleted `MediaStorage`) fixed the underlying
symptoms for multiple P1 bullets at once, but the refactor commit
didn't sweep those bullets out of the backlog. They kept riding P1
until the loop ran through them one by one, paying the "walk the code
to verify, then close with a decision" tax on each.

**Proposed remediation for the iterate-gap skill itself** (not
actionable from a cycle, but logging for a future skill-level cycle):
- Extend the `/iterate-gap` hard rules with: when a commit's message
  body contains `backlog-sweep:` lines listing slugs, those slugs are
  deleted from `docs/BACKLOG.md` in the same commit. Refactor authors
  opt-in by naming the bullets their work invalidates.
- Alternatively, extend R (repopulate) to start with a "is this bullet
  still live?" walk over the current backlog before the debt-scan step,
  auto-closing bullets whose described symptom can't be reproduced in
  one file grep.
- Either way, the invariant to enforce is: "P1 queue age ≤ N cycles";
  right now it's unbounded.

### "cross-commit architectural sweep" observations don't belong in decision files
Pattern I'm now doing across three cycles: close a stale bullet, use
the decision file to document that the described work is already done,
and use PAIN_POINTS to note the backlog-decoupling meta-observation.
Decision files are single-cycle artifacts — the cross-cycle pattern of
"three stale bullets in a row, all tied to the same consolidation
refactor" doesn't fit any one of them. PAIN_POINTS is the right home,
but finding the trend required manually re-reading three entries.
Lightweight addition worth considering: a header at the top of
PAIN_POINTS grouping entries by theme (backlog-hygiene /
abstraction-gap / test-gate-drift / etc.) once there are 3+ entries in
a theme. Not doing this yet — N=3 is still below the threshold where
theming pays for itself — but logging here so the next time a theme
hits 3+ it's obvious what to do.
