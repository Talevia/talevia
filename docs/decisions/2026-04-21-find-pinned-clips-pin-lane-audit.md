## 2026-04-21 — find_pinned_clips (VISION §3.1 产物可 pin / §5.4 专家路径)

Commit: `1a3fecd`

**Context.** VISION §3.1 surfaces pinning at the lockfile layer (cycle 1 of
loop-2 added `pin_lockfile_entry`; cycle 2 of loop-3 added the clip-level
shortcuts). What was still missing: the **audit query** on the timeline —
"show me every hero shot I've frozen in this edit." The workaround was
`list_lockfile_entries` → filter by `pinned=true` client-side → cross-ref
each `assetId` to a clip via `describe_project` / `list_timeline_clips`.
Two round-trips per audit; and the lockfile-layer view doesn't distinguish
between a pin that still backs a clip on the timeline and a pin whose
asset has since been replaced on the timeline but survives in the ledger.

**Decision.** `FindPinnedClipsTool` walks the timeline once, resolves each
`Clip.Video` / `Clip.Audio` to `Lockfile.findByAssetId` (same most-recent-
match semantics `regenerate_stale_clips` uses so the two reports agree
about what a clip's "current entry" is), and emits one `Report(clipId,
trackId, assetId, inputHash, toolId)` per pinned clip. Counterpart of
`find_stale_clips` on the pin lane — same `Input(projectId)` shape, same
`project.read` permission, same Output-list style with an aggregate count +
a per-row report.

Exclusions by construction, each documented in the help text: text clips
(no asset), imported media (asset has no lockfile entry). These aren't
errors — they're just not pinnable by definition, so the tool reports
`pinnedClipCount=0` and keeps `totalMediaClipCount` honest so the agent
can reason about coverage ("2/3 media clips pinned").

**Alternatives considered.**

1. *Extend `list_lockfile_entries` with `onlyPinned=true` + bolt on a
   `trackId` / `clipId` cross-ref.* Rejected — would conflate a lockfile
   query with a timeline-audit query. Lockfile entries can outlive their
   clips (prune_lockfile / gc_lockfile sweeps are the inverse), and the
   audit question is "what's frozen **on my timeline now?**" which is a
   timeline question. Two verbs for two mental models matches
   `find_stale_clips` vs `list_lockfile_entries`.
2. *Bundle pin state into `find_stale_clips` as a boolean field.*
   Rejected — orthogonal concerns. A pinned clip is *either* stale or
   fresh; conflating would force callers to filter out pinned rows to get
   a regeneration plan. Today `regenerate_stale_clips` already handles
   pinned-stale clips by skipping them (reason `"pinned"`), and the agent
   can call `find_pinned_clips` before or after `find_stale_clips`
   depending on its question.
3. *Expose the lockfile entry's full `provenance` / `sourceBinding` in
   the report.* Rejected for now — matches the terse shape of
   `find_stale_clips.Report`. Agents who want full provenance call
   `list_lockfile_entries` with the returned `inputHash`. YAGNI until a
   concrete flow needs it.

**Coverage.** `FindPinnedClipsToolTest` — six tests: empty project returns
zero, mixed pinned/unpinned reports only pinned (with multi-track
asset-kind coverage), text clips excluded, imported media (no lockfile
row) excluded, missing project fails loud, trackId surfaced.

**Registration.** `FindPinnedClipsTool` registered in `CliContainer.kt`,
`apps/desktop/AppContainer.kt`, `apps/server/ServerContainer.kt`,
`apps/android/AndroidAppContainer.kt`,
`apps/ios/Talevia/Platform/AppContainer.swift`.
