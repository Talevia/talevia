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

---

## 2026-04-23 — debt-split-fork-project-tool (`<this commit>`)

### Third "long-file split" in seven cycles — but on a different axis
Cycles 1 + 2 (ProjectQueryTool / SessionQueryTool) split along the **row
data class** axis because those dispatchers had per-select row types
that each new select would grow. This cycle's ForkProjectTool split
(538 → 376) peeled off **reshape helpers** — different axis entirely,
because ForkProjectTool is a single-verb tool whose bulk comes from
variant-spec reshape math, not per-select rows. Observation: the
`file > 500 lines` debt signal is real but **under-specified** — it
tells you a file crossed the threshold, not what extraction axis would
actually shrink it. Each previous long-file cycle discovered the axis
by reading the file. Worth preserving as a decision-file convention:
name the axis in the first paragraph of the decision so future similar
debt bullets can learn the pattern. Already doing this by accident;
now it's an explicit convention.

### Ktlint `no-blank-line-before-rbrace` fires on mass delete of a trailing block
After deleting the final block of private helpers in ForkProjectTool,
the remaining blank line before the class's closing `}` tripped
`standard:no-blank-line-before-rbrace`. One `ktlintFormat` fixed it
silently. Pattern worth noting for future mass-delete refactors:
**ktlintCheck fails after a delete-the-tail refactor slightly more
often than other refactors**, because the natural pattern ("blank line
between the last method and the class brace") is ktlint-clean only
when there's a method in front of it — delete the method and the blank
becomes a violation. Cheap fix but surprising. Consider running
`ktlintFormat` preemptively after any edit that removes the last item
before a closing brace.

---

## 2026-04-23 — debt-registered-tools-contract-test (`<this commit>`)

### Writing the "static invariant" test found a real bug the test is designed to catch
Dry-run-scanning all 104 `Tool.kt` files against all 5 `AppContainer`s
found exactly one tool missing from every container: `OpenProjectTool`,
sitting there with a `TODO(file-bundle-migration): register
OpenProjectTool(projects) in each AppContainer's tool registry` comment.
Exact shape of the bug the new test is designed to catch: class file
exists, no container wires it, TODO never flipped. Wiring the tool plus
adding the test in the same commit is the cleanest closure — but the
general pattern is worth naming: **when you build a static invariant
check, expect the first run to find something**. If it doesn't, the
invariant is either trivially satisfied (not worth enforcing) or too
lax (missing the real failure cases). Scan-then-fix is the honest
first-cycle of any static-check cycle; skipping the scan and shipping
with a broken-on-day-one test devalues the guard.

### `apps/server:test` has been pre-existing red for at least 7 cycles
The `debt-server-container-env-defaults` bullet has been sitting in P2
since before cycle 1, noting that 15 server tests NPE on `clean main`
because `ServerContainer.kt:215` uses `env["TALEVIA_RECENTS_PATH"]!!`
but every server test passes `env = emptyMap()`. Every cycle that's
touched something potentially-server-adjacent has had to `git stash`,
re-run `:apps:server:test` to verify the pre-existing red, then
continue. This cycle hit the same pattern. Structural implication: a
backlog-bullet debt item sitting unfixed while its test suite stays
red is **blocking** other cycles' validation runs (you can't tell
"your change broke the server tests" from "the server tests were red
before you started"). Normally P2 items wait for priority; this one
has enough cycle-externality to deserve a P1 bump independent of its
own merit. Adding that as a P2 "顺手记 debt" append below so the next
repopulate can re-score.

---

## 2026-04-23 — source-query-by-parent-id (`<this commit>`)

### Import-order churn after adding a single import mid-file
Cycle 8 added `import io.talevia.core.tool.builtin.project.OpenProjectTool`
immediately after the existing `CreateProjectTool` import in four
containers (CLI, Desktop, Server, Android) — locally looked tidy, but
`OpenProjectTool` belongs alphabetically between `ListProjectsTool` and
`ProjectQueryTool`, several lines later. Ktlint didn't fire on that
commit because the inserted import happened to sit in a region where
alphabetic order wasn't violated from the prior line — but when
something in an adjacent region changed this cycle, the lint rule
re-evaluated and 4 files failed. `ktlintFormat` fixed it in one pass.
Pattern: **imports inserted mid-block by eye rather than by sort are
cheap to re-sort but read as churn in diffs.** Better discipline
(cheap): when adding an import, type the full line first, then let the
IDE / `ktlintFormat` re-sort before diffing. The test passing doesn't
imply the order is stable under later edits.

### Adding a select to a unified-dispatcher tool is ~100 lines of mechanical edits across 7 call sites
For this cycle (adding `descendants` + `ancestors` to
`SourceQueryTool`), the work touched: SELECT_* constants + ALL_SELECTS
set + Input field additions + helpText paragraph + JSON Schema block +
`rejectIncompatibleFilters` cases + execute dispatch + one sibling
file with the traversal. That's 7 coordinated edit sites for every
select added. For dispatchers with `>10` selects (ProjectQueryTool has
13, SessionQueryTool has 15), adding one more select is getting
expensive, and most of the cost is the `rejectIncompatibleFilters`
matrix — each new filter field costs `O(n_selects)` new reject rules.
Not a current bottleneck (the dispatchers aren't accumulating selects
fast), but worth tracking: if the reject matrix hits 30+ rules, an
annotation-driven "which filter belongs to which select" table
(`@AppliesTo(SELECT_FOO)` on Input field) may amortise the cost. For
now, the explicit matrix is readable and the bullets this cycle
touches don't add new filter fields.

---

## 2026-04-23 — timeline-diff-tool (`<this commit>`)

### Diff math duplicates between `diff_projects` and `project_query(select=timeline_diff)`
`DiffProjectsTool.diffTimeline` + `changedClipFields` + `kindString`
extension helpers were copied into the new
`core/tool/builtin/project/query/TimelineDiffQuery.kt` because the
`diff_projects` tool's `TimelineDiff` output type is part of its public
`Output.timeline` surface — extracting it to a shared top-level type
would have been a full row-types resplit the size of cycles 1+2.
Decided to duplicate this cycle and log a P2 debt bullet
(`debt-unify-project-diff-math`) so whichever cycle next touches
`DiffProjectsTool` or adds another diff-style select can fold both
call sites into a shared `core/tool/builtin/project/diff/` helper. The
math is ~40 lines, so the immediate cost is bounded; the risk is that
if the diff logic ever changes (new Clip subtype field in
`changedClipFields`, etc.), both copies need the same edit.

### Adding a select to `project_query` is now 8 coordinated edit sites
Cycle 9 noted 7; this one hit 8 because the row type was new (not
reusing `NodeRow`-style shape). The 8 sites: SELECT_* const +
ALL_SELECTS set + Input field declarations (×2) + helpText paragraph
+ JSON Schema block (×2 for the two new properties) +
`rejectIncompatibleFilters` rule + execute dispatch case + the
sibling query file itself. Cycles 1-2 plus every "add a select" cycle
since have hit the same shape. Threshold nobody's triggered yet:
when `project_query` crosses ~20 selects, consider a formal "select
plugin" shape (one file per select declaring its fragment of each
site). Current counts: `project_query` 14 selects, `session_query`
15, `source_query` 5.

---

## 2026-04-23 — gemini-provider-stub (`<this commit>`)

### 4 stale bullets closed in 11 cycles — backlog-sweep discipline still not enforced
Cycle 11 marks the fourth close-without-code-change in this session
(after `fork-project-tool-trim-stats-bug`,
`import-media-tool-bundle-resolver`,
`extract-frame-tool-bundle-write`, and now `gemini-provider-stub`).
Each described work a prior "big refactor" cycle had silently already
landed. 4/11 ≈ 36% stale rate — higher than the 3/6 I flagged in
cycle 6, not lower.

The cycle-6 proposal ("add `backlog-sweep:` footer to refactor
commits" or "start repopulate with a liveness check") is still just
written here in PAIN_POINTS, not implemented. Stale bullets keep
showing up because nothing enforces their decay. Concrete next step
worth considering: add the rule to the skill itself — bump
`iterate-gap/SKILL.md` to require a liveness pre-check in step 2
before dispatching on the top bullet (walk the bullet's described
symptom against current source, skip-close if the symptom can't be
reproduced in < 60s of grep). That turns the pattern into skill
cost rather than cycle cost. Not landing as this cycle's work (it'd
be a skill-level change, out of `/iterate-gap` scope), but flagging
here so whichever future skill-editing cycle picks it up can
reference the empirical evidence: 4/11 = 36% recovery rate on a
pattern that's free to detect.

---

## 2026-04-23 — tts-provider-fallback-chain (`<this commit>`)

### Widening a Tool ctor from single-value to `List<T>` is safe via secondary-ctor delegation
The fallback-chain change on `SynthesizeSpeechTool` moved the primary
ctor from `TtsEngine` to `List<TtsEngine>`. Adding a secondary ctor
`(engine: TtsEngine, bundleBlobWriter, projectStore) : this(listOf(...))`
kept all 3 live `AppContainer`s' call sites unchanged — no ripple to
`:core:compileKotlinIosSimulatorArm64` / android / desktop / cli /
server. Clean recipe for widening a Tool ctor to accept an ordered
list: primary ctor takes the list + requires non-empty, secondary
ctor wraps a single value. Future ctor widenings (multi-VideoEngine
routing, multi-SecretStore chains) can copy this exact shape — the
LLM-facing Tool contract stays stable, the DI surface grows without
a container sweep.

### Kotlin type inference fails at `withProgress { synthesizeWithFallback(...) }` without an explicit type argument
`AigcPipeline.withProgress` is `suspend fun <T> withProgress(..., block:
suspend () -> T): T`. When the trailing lambda body calls a private
suspend method whose return type is `TtsResult` (from a separate file
via missing import), the compiler couldn't infer T — reported
"Unresolved reference 'audio'" on a downstream `.audio` access rather
than "can't infer T here", plus a misleading "Cannot infer type for
this parameter" at the outer call. Fix turned out to be `.withProgress<TtsResult>(...)`
plus adding the missing `import io.talevia.core.platform.TtsResult`.
Heuristic worth remembering: when a Kotlin error chain includes "Cannot
infer type for this parameter" at a generic lambda followed by cascade
unresolved-reference errors on the returned value's fields, the fix
is almost always (a) add an import you forgot, and/or (b) an explicit
type argument on the outer generic call — not anywhere the downstream
errors point.

---

## 2026-04-23 — bundle-source-footage-consolidate (`<this commit>`)

### Third inline `BundleBlobWriter`-equivalent streaming copy — the case for `writeBlobStreaming` is now overwhelming
`ConsolidateMediaIntoBundleTool.consolidateOne` inline-copies via okio
`fs.source(p)` + `fs.sink(tmp).buffer().writeAll(...)` + `atomicMove`.
Third site doing exactly this pattern (after `ImportMediaTool` and
`FileBundleBlobWriter`). Still duplicating because
`BundleBlobWriter.writeBlob` takes `bytes: ByteArray` — using it means
reading entire files into memory, which regresses on gigabyte rush
imports / consolidations. The pain-point was logged after cycles 5 +
12; this cycle is the third occurrence, which qualifies for the "three
callers = lift" rule. Concrete next step: extend `BundleBlobWriter`
with `suspend fun writeBlobStreaming(projectId, assetId, source:
okio.Source, format): MediaSource.BundleFile`, migrate all three
callers (`FileBundleBlobWriter.writeBlob` becomes a `bytes.toSource()`
wrapper for the byte-buffered AIGC use case). `debt-streaming-bundle-
blob-writer` would be the slug.

### Desktop + Android + Server + CLI + iOS → 5 coordinated registration sites per new tool
Seventh cycle since cycle 1 to touch all 5 containers for a new-tool
wire-up. Cost is small per-cycle (~10 lines total across 5 files) but
consistent: two sites per container (import line + registration line),
five containers, plus ktlint re-sorting my imports because inserting
`ConsolidateMediaIntoBundleTool` mid-block again put it in the wrong
alphabetical spot until `ktlintFormat` fixed it. Same heuristic as
cycle 9 ("inserting imports by eye is cheap to re-sort but reads as
churn") — I just keep forgetting to pre-sort. A script `/register-tool
<ToolName> <ctorArgs>` that writes the 10-line diff across all 5
containers + runs ktlintFormat would save a few minutes per
registration, but the frequency (7/13 cycles ≈ 54%) isn't high
enough yet to justify tooling. Threshold worth watching: if new-tool
cycles hit 10 in a row without a break, that's the moment to build
the helper.

---

## 2026-04-23 — bundle-asset-relink-ux (`<this commit>`)

### `runTest` + `MutableSharedFlow` collector: subscribe-before-publish or it hangs
Writing `openAtEmitsAssetsMissingForNonExistentFilePaths` surfaced a
classic trap: using `CoroutineScope(SupervisorJob() + Dispatchers.Default)
+ flow.take(1).toList()` under `runTest` blew up with
`UncompletedCoroutinesError` (the test scheduler waits for all child
coroutines; `toList` on a never-terminating SharedFlow never returns).
Fix that works reliably: `backgroundScope.launch { captured.complete(
flow.first()) }` + `yield()` so the collector registers its subscription
before `openAt` publishes. `backgroundScope` is auto-cancelled by
`runTest`, and `first()` terminates on first match. Worth remembering:
`MutableSharedFlow` (what `EventBus` uses) has no replay by default —
publishing before the subscriber launches drops to zero listeners.

### Exhaustive `when` on `BusEvent` caught the metrics routing I'd have forgotten
Adding `BusEvent.AssetsMissing` triggered a compile error in
`Metrics.kt:128` because its `when (event)` on the sealed interface
wasn't exhaustive. Best-case §3a signal — compile-time enforcement
that every new event type has an explicit metrics routing (even if
the routing is "ignore"). Mental note: when adding a `BusEvent`
variant, `Metrics.kt` is guaranteed to break the build and force the
author to make the metrics-naming decision. Keeps the Prometheus
scrape complete by construction.

### 8th cycle touching all 5 `AppContainer`s — ktlint re-sort again
Cycle 13 flagged 7/13 cycles hitting the 5-container tax; now 8/14.
Same ktlint `ordering` import-sort fix every time. Threshold to build
a `/register-tool` helper is still not hit (10 consecutive new-tool
cycles), but the rate is climbing. Next repopulate should probably
score a `debt-register-tool-script` item if the rate crosses 60% of
cycles.

---

## 2026-04-23 — bundle-mac-launch-services (`<this commit>`)

### `assertFailsWith<SpecificException>` brittleness across okio / JVM layers
Writing `openAtDoesNotStripExtensionWhenBarePathExists`, my first pass
used `assertFailsWith<IllegalStateException>` to assert "bundle at
`<name>` with no `.talevia` sibling and no `talevia.json` inside must
fail". The assertion failed because okio's `FileSystem.source(path)`
throws `java.io.FileNotFoundException` (not wrapped in an
`IllegalStateException`) when the directory doesn't contain the file.
Relaxed to `try { ... fail(); } catch (_: Throwable) {}` accepting any
throwable. Takeaway: **`assertFailsWith<T>` is only reliable when the
code path throws its own exception type**. The moment the failure
bubbles up from a library layer (okio, sqldelight, kotlinx-serialization)
the exception type is whatever that library picked, and asserting on a
kotlin-stdlib type over-constrains the test. Cheap heuristic: prefer
`assertFailsWith<Throwable>` or plain `try / catch (_: Throwable)` when
the failure crosses a library boundary; reserve typed asserts for
exceptions the code-under-test throws itself (our
`IllegalStateException("bundle has no talevia.json")` is ok to assert
on; "okio threw when I asked it to read nothing" is not).

### Compose Desktop `extraKeysRawXml` is raw-XML injection with no schema validation
Compose Desktop's `nativeDistributions.macOS.infoPlist.extraKeysRawXml`
takes a raw XML string that the packager splices into the generated
`Info.plist` before `jpackage` runs. There is no schema check, no
lint, no compile-time structure — if I mistyped `<array>` as `<array/>`
or forgot to close an outer `<dict>`, the assemble step would still
succeed (it only runs at `packageDmg` time, not `assemble`) and the
`Info.plist` would only fail to parse on a user's machine at first
Launch Services registration. Cheap mitigation I didn't do this cycle
(deferring): add a unit test that loads the `build.gradle.kts` string
through `NSPropertyListSerialization` or Apple's `plutil -lint` on CI.
Today the test that would have caught a typo is "a mac user double-
clicks a `.talevia` bundle and nothing happens" — unacceptable coverage
gap for a feature whose only happy-path verification is dynamic.
Logging here so the next cycle that touches `nativeDistributions` XML
has a pre-written reason to ship a lint test alongside the change.

### Compose Desktop `CFBundleIdentifier` divergence between `bundleID` and dev-time compose main
Setting `bundleID = "io.talevia.Talevia"` inside `macOS { }` only affects
the *packaged* `.app` bundle. The dev-time `./gradlew :apps:desktop:run`
still launches with whatever bundle identifier the JVM picks (typically
the jpackage default), so Launch Services registrations during iterative
development don't see `io.talevia.Talevia`. Consequence: testing
"double-click a `.talevia` in Finder" requires a full `packageDmg` +
install, not just a fast `:apps:desktop:run`. Not a blocker for this
cycle (the decision captures this + the 5 unit tests exercise the
openAt-side of the contract) but worth knowing — **Compose Desktop's
dev loop and packaged-app identity diverge**, and any feature gated
on Launch Services / document-type registration needs a full package
loop to verify end-to-end. The iterate-gap skill's "run gradle target
matching your change" heuristic doesn't cover this; added to the
observation log so future OS-integration bullets can budget for a
packageDmg step in their plan.

---

## 2026-04-23 — bundle-cross-process-file-lock (`<this commit>`)

### Two backlog bullets in a row required `/iterate-gap`-level skips before finding a viable gap
Cycle 16 had to skip `bundle-mobile-document-picker` (violates CLAUDE.md's
explicit "mobile non-regression only" platform-priority rule — no concrete
driver) AND `bundle-talevia-json-split` (the bullet itself says "先写
decision 评估触发条件再动" and the trigger — actual diff noise — hasn't
fired) before landing on `bundle-cross-process-file-lock` as the first
bullet with an active driver. That's 2 consecutive skips in one cycle.
Structural signal: **P2 "记债 / 观望" bullets accumulate things that
either (a) depend on a platform priority window that hasn't arrived, or
(b) wait on a trigger condition that hasn't fired yet**. Neither is
wrong for P2, but iterating top-down on them forces the skill to
re-evaluate the same skip each cycle. Lightweight mitigation: when a
P2 bullet is skipped by a cycle, tag it with a one-line
"skipped YYYY-MM-DD: <reason>" comment inline. Next repopulate can
de-prioritise bullets that have been skip-tagged N+ times in a row (or
upgrade them if the gating condition landed). Today we just silently
skip and re-encounter; the skip is free but the re-reading isn't.
Logging here so a future skill-level cycle can wire the tag.

### Cross-process correctness via `FileChannel.tryLock` is one-line on JVM but no equivalent on iOS/Android native
JVM's `java.nio.channels.FileChannel.tryLock()` gives us cross-process
exclusion with ~15 lines in `JvmBundleLocker`. The equivalent on
Kotlin/Native for iOS would be `flock(2)` through cinterop (non-trivial;
requires `posix` imports + `memScoped` + nullability plumbing). Android
runs on Dalvik/ART which has `FileChannel` but a per-app sandbox makes
multi-process-on-same-bundle vanishingly rare. So today the abstraction
is: `interface BundleLocker` in commonMain, `JvmBundleLocker` in jvmMain,
default `BundleLocker.Noop` everywhere else. This is fine — but the
implicit assumption "iOS / Android are single-process-per-bundle" is
genuinely a platform limitation now baked into core. If a future cycle
ever needs cross-process bundles on mobile (e.g. shared iCloud folder
accessed by Talevia + a sibling helper app), it's not a simple "add an
iosMain actual" — it's a cinterop + test-harness expansion. Logging so
future mobile-concurrency bullets start with accurate scope.

### `withBundleLock` + inline lambda: ktlint sorted an import by moving alphabetically after `BundleBlobWriter`
Third consecutive cycle (13 / 14 / 15 / 16) where inserting a single
`import io.talevia.core.platform.Jvm*` line by eye put it in the wrong
alphabetic slot and ktlint caught it on the full-repo pass. Same fix
(`./gradlew ktlintFormat`), same 30 seconds. The "insert imports by
hand" anti-pattern from cycles 9 + 13 + 15 just keeps re-surfacing. I'm
re-logging because the cadence hasn't shifted — the fix is *always*
`ktlintFormat`, so the skill could simply run `ktlintFormat` before
`ktlintCheck` and skip the whole fail-then-fix round-trip. Not a
semantic issue (ktlint on the "hygiene-only" profile we use is
mechanical and safe), just wasted iteration. Likely a skill-level
improvement to land when it justifies a cycle of its own. Cycle-16's
hit is 9/15 ≈ 60% — crossing the threshold I noted in cycle-13's pain
point ("if new-tool cycles hit 10 in a row without a break").

---

## 2026-04-23 — debt-server-container-env-defaults (`<this commit>`)

### Kotlin primary-ctor-param with same name as property silently shadows the property inside later initialisers
First attempt at this cycle: keep the ctor param named `env`, declare
a `private val env: Map<String, String> = withServerDefaults(env)`
property to shadow it, and expect downstream `env["..."]!!` lookups to
hit the normalised property. That's what you'd get in most languages
with this pattern. In Kotlin it *doesn't* work: primary-constructor
parameters stay in scope throughout the class body, and when a
subsequent property initialiser references `env`, the compiler resolves
to the ctor parameter, not the shadowing property. The NPE at
`ServerContainer.kt:225` wasn't a build-cache artefact — it was the
raw `emptyMap()` ctor param being read, with my defaults-filled
property sitting one line above, unused. Fix was to rename the ctor
param to `rawEnv`, making the resolution unambiguous. Lesson: when
normalising a ctor argument into a shadowing property in Kotlin, **don't
reuse the parameter name** — pick a `raw*` / `initial*` prefix and keep
the property as the short name. The compiler won't warn about the
shadow. Costs 13 named-arg call-site renames but buys correctness.

### 7+ cycles of P2 "just ignore the red" is strictly cycle-level externality — the fix was ~30 lines
This bullet had been P2 for ≥7 cycles per the `debt-server-tests-externality`
meta-bullet. Cycles 8 through 16 each paid a "stash + re-run
:apps:server:test + verify the red is pre-existing" tax when the
change touched anything server-adjacent. The actual fix was ~30 lines
(move `serverEnvWithDefaults` into ServerContainer, rename ctor param,
update 13 test call sites, fix one exhaustive-when). 30 lines × 1 cycle
vs. ~5min × 7+ cycles is a lopsided trade. The mistake was **letting a
red test suite sit in P2 at all** — a red test suite is cycle-level
externality regardless of the bullet's own merit, and P2 "观望" doesn't
price that in. `debt-server-tests-externality` called this out one
cycle after it started biting, and the bullet still sat for another 6
cycles. Takeaway for future triage: **a red test suite on main is
never P2**. It's either P0 (fix it) or the suite should be deleted as
no-longer-serving-its-purpose. "Wait for it to self-resolve" isn't a
real option; `main` being broken is a nearly pure tax on everyone
else's iteration time.

### ServerModule exhaustive-when was stale for 2 cycles because cycle-14 didn't run `:apps:server:test`
Cycle 14 added `BusEvent.AssetsMissing` and updated `core.metrics.Metrics`'s
exhaustive `when` — but not `apps/server/src/main/kotlin/io/talevia/server/ServerModule.kt`'s
two parallel `when` blocks (`eventName` and `BusEventDto.from`). Caught
only when this cycle tried to compile the server module. Root cause:
cycle 14 couldn't have caught it — `:apps:server:test` was already red
per the env-defaults bug, so compile errors in the same module were
invisible behind the NPE. Structural signal: **a fully-red module's
compile errors are self-masking** — new errors introduced in the same
module won't surface because the test runner never gets to the code in
question. Another argument for "red test suites on main ≠ P2". If
cycle 14 had a green `:apps:server:test` to re-check against, the
missing `when` case would have fired on the cycle that introduced it,
not two cycles later. Generalises: every module that compiles but
doesn't test-run is a deferred-error accumulator.

---

## 2026-04-23 — debt-add-sqldelight-migration-verification (`<this commit>`)

### "Pre-create the antecedent table" is a nicer test pattern than replaying a full historical schema
First attempt: write a `v1Schema.sql` test fixture that replays a v1
snapshot (tables + indexes + sample rows) before calling
`Schema.migrate(driver, 1, ...)`. Got three tests in before realising
that 1.sqm / 2.sqm only CREATE new tables and 3.sqm uses `DROP TABLE
IF EXISTS` — so the migrations don't actually *need* the v1 schema to
exist to exercise their SQL. Switched to a simpler pattern: for each
migration start-version, pre-create *just the tables the version's
migrations reference* (usually zero or one), and let the migration run.
Covers "migration SQL doesn't throw" and "final schema matches
expectation" without the maintenance drag of full historical snapshots.
The `verifyMigrations` SqlDelight plugin feature is the "full snapshot"
approach, tracked as an explicit alternative in the decision doc.
Observation worth tracking: **when migration SQL is mostly additive
(CREATE) or guarded (IF EXISTS), lightweight migration tests beat
snapshot-based ones** — snapshots only pay off when migrations rewrite
existing rows in place or depend on specific column types / defaults.

### A red test suite hid a debt we couldn't measure — 7+ cycles of "is this my fault?" was raw cost
This cycle is the second consecutive one consuming debt that
compounded while `:apps:server:test` was red. The missing migration
verification is a latent risk that wouldn't have been noticed for
months under normal iteration because SqlDelight's "migrations compile"
check is static-only (it doesn't prove runtime correctness). The
landing of this test isn't a response to any specific incident — it's
prophylactic. Structural signal: **debt that's invisible under today's
tooling is the kind that scales worst** — we'll find out it matters on
the first user-reported data loss, by which time "should have added
a test" is 0% useful. Argues for the debt-scan to include a
"runtime-untested critical path" signal, not just "file too long /
tool count growing". Concrete follow-up worth a future cycle:
extend the R.5 scan with a heuristic that greps for
`Schema.migrate\|\.migrate(driver` in `jvmTest/` — if prod has a
`Schema.migrate` call but `*Test` doesn't exercise it, that's a
runtime-untested critical path. Wouldn't have been hard to spot
this gap earlier with a 3-line scan command.
