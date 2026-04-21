## 2026-04-20 — `gc_lockfile` for policy-based lockfile GC

**Context.** The AIGC lockfile (VISION §3.1 "把 AIGC 驯成
『随机编译器』") is append-only. Every successful
`generate_image` / `generate_video` / `synthesize_speech` /
`generate_music` / `upscale_asset` writes one
`LockfileEntry`. `PruneLockfileTool` already drops rows whose
`assetId` is no longer in `project.assets` — the orphan
sweep — but there was no way to bound the ledger by age or
by per-tool count. Over a long-running project the lockfile
becomes noise in `list_lockfile_entries` and dead weight in
the serialized project blob. A lockfile that's 90% dead
weight erodes the trust the "random compiler" story needs.

**Decision.** Ship `gc_lockfile(projectId, maxAgeDays?,
keepLatestPerTool?, preserveLiveAssets=true, dryRun=false)`
in `core.tool.builtin.project`. Age policy drops entries
whose `provenance.createdAtEpochMs < now - maxAgeDays`;
count policy keeps the top-N most recent per `toolId`
bucket. Both policies optional. Registered in all five
composition roots next to `PruneLockfileTool`. The system
prompt picks up a paragraph teaching the two-tool split
(prune = orphan rows, gc = policy).

**Why a separate tool from `prune_lockfile`.** Signature
bloat plus semantic confusion. `prune_lockfile` is a
"sweep referential dead" operation — no knobs, idempotent,
safe to call unconditionally. `gc_lockfile` takes policy
arguments where the *choice of policy* is the meaningful
axis. Cramming them together would force every prune caller
to pass `maxAgeDays=null, keepLatestPerTool=null` and would
make the helpText a choose-your-own-adventure. Splitting
also lets the system prompt teach the two cleanup shapes
distinctly. Mirrors `remove_asset` vs
`find_unreferenced_assets` — one-shot verb per cleanup
flavor, not a mega-tool.

**Why AND not OR for policies.** OR makes the size cap
meaningless. A user who says "keep only the last 20 per
tool *or* anything under 7 days old" with 1000 old entries
and 200 recent ones ends up with 220 entries still in the
lockfile — the count cap failed to bound size because the
age cap let too much through. AND semantics match the
user's actual mental model: "the lockfile should be the
last 20 per tool, pruned to 30 days". Either policy alone
still works (the other becomes pass-through when null), so
users who want "keep anything under 7 days" just omit
`keepLatestPerTool`. Both-null is treated as a no-op with a
pointer at `prune_lockfile` rather than silently dropping
nothing — that would be confusing UX (the user would wonder
if the tool was broken).

**Why `preserveLiveAssets` defaults true.** The lockfile
exists to serve cache hits. If an asset is still in
`project.assets`, the row that produced it is still useful
— a future `generate_image` with the same
`(prompt, seed, model, bindings)` hash will return
`cacheHit=true` without a provider call. Dropping that row
because it's "old" silently costs the user money the next
time they re-run the same prompt. Safe default is "never
drop something that's still serving a live asset, even if
the policy says you should". The `=false` override exists
for the narrow "strict policy sweep" case — e.g. a user
preparing a project for long-term archival who wants the
lockfile trimmed regardless of liveness. Mirrors the
catalog's safe-by-default posture (`remove_asset` refuses
when clips reference it unless `force=true`).

**Clock source.** `kotlinx.datetime.Clock.System` by
default, injectable for tests. Matches
`SaveProjectSnapshotTool` and `TodoWriteTool`. The
`ToolContext` doesn't carry a clock today, and wiring one
through just for this tool would force every other
time-aware tool (export, snapshot, todo) to migrate — not
worth the churn. Tests pin a fixed clock directly through
the constructor, same pattern the iOS composition root
already uses.

**Age semantics.** Strictly-older drops. An entry
created exactly at `now - maxAgeDays` is kept. Matches how
users phrase policies: "keep everything from the last 7
days" includes the 7-day-old boundary row. Equivalently,
the cutoff is a keep-fence, not a drop-fence. The test
suite locks this in with a dedicated case.

**Count semantics.** `keepLatestPerTool` operates
per-`toolId`, not globally. Rationale: tools have wildly
different output costs. A project that's hammered
`generate_image` 500 times but only ran `generate_video`
twice shouldn't lose the two video rows because they got
crowded out by the image bucket. Per-tool bounds give the
user a more intuitive knob ("keep the last 20 per
modality"). `keepLatestPerTool=0` is a valid input that
drops every row in the count policy (but `preserveLiveAssets`
still rescues live ones) — rarely what you want, but the
zero case is well-defined and locked in by a test.

**Tests.** 13 cases:
- Age-only drops strictly older, keeps equal-to-threshold.
- Count-only keeps most-recent N per `toolId`, two-bucket
  (generate_image + synthesize_speech) at N=2 with 4 each.
- Combined age+count with mixed reasons (`age`, `count`,
  `age+count` surfaced in `PrunedSummary.reason`).
- `preserveLiveAssets=true` rescues a would-drop entry
  whose asset is still in `project.assets`.
- `preserveLiveAssets=false` ignores the guard.
- `dryRun=true` doesn't mutate; counts still computed.
- Both policies null is a no-op with a pointer at
  `prune_lockfile`.
- `maxAgeDays=0` edge: drops anything not created in the
  same millisecond as `now`.
- Empty lockfile: safe no-op.
- `keepLatestPerTool=0` drops every row in the count
  policy.
- Missing project fails loudly.
- Negative `maxAgeDays` / `keepLatestPerTool` rejected at
  input validation.

**Registration.** Registered in all five composition
roots (CLI, desktop, server, Android, iOS) next to
`PruneLockfileTool`. iOS passes the container's injected
`clock`; the other four use the default `Clock.System` —
matches how `SaveProjectSnapshotTool` is wired today.

**SHA.** ae9683121929380cfb0976ac9b1c7271fbaff133

---
