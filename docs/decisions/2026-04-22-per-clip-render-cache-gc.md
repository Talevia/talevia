## 2026-04-22 — gc_clip_render_cache tool with age + count policies (VISION §5.3 storage reclaim)

Commit: `9bc47f0`

**Context.** Cycle 30 shipped `per-clip-incremental-render` (the
fine-cut of §3.2 "只重编译必要的部分") — every cache-miss clip
render appends a new `ClipRenderCacheEntry` pointing at an on-disk
`.mp4` mezzanine. Cycle 33 extracted the cache to its own
SQLDelight sibling table (`debt-extract-clip-render-cache-table`)
so unrelated mutations don't re-encode it. But eviction was never
added: `ClipRenderCache.append(...)` grows-only, and the
`.talevia-render-cache/<projectId>/` directory on disk grows in
lockstep. Long-lived projects (months of source drift → dozens of
renders per clip) accumulate orphan mezzanines with no primitive to
reclaim the disk short of `rm -rf` on the cache directory — which
also nukes anything that's still useful.

Backlog bullet `per-clip-render-cache-gc` (P2) called for either a
dedicated `gc_clip_render_cache` tool or auto-pruning inside
`save_project_snapshot`. This cycle implements the standalone tool
— the snapshot-path auto-prune is a separate ergonomic layer that
can layer on top of this primitive when a driver surfaces.

**Decision.** One new tool — `gc_clip_render_cache(projectId,
maxAgeDays?, keepLastN?, dryRun?)` — backed by a new engine-layer
primitive for on-disk deletion.

1. **`VideoEngine.deleteMezzanine(path: String): Boolean`** —
   default returns `false` (engines without a per-clip cache never
   wrote mezzanines, so there's nothing to delete; cache rows still
   update cleanly). `FfmpegVideoEngine` overrides with
   `Files.deleteIfExists(path)`. Return value is "did a file
   actually go?" so the tool output stays honest when the user has
   already manually deleted files.

2. **`ClipRenderCache.retainByFingerprint(keep: Set<String>)`** —
   new method returning a new cache with only the listed entries.
   Preserves append order so the "latest wins" contract of
   `findByFingerprint` (used by `ExportTool`'s per-clip path)
   survives GC.

3. **`GcClipRenderCacheTool`** — new tool in
   `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/project/`.
   Input: `(projectId, maxAgeDays?, keepLastN?, dryRun = false)`.
   Output: `(projectId, totalEntries, prunedCount, keptCount,
   prunedEntries: List<PrunedSummary(fingerprint, path,
   createdAtEpochMs, reason, fileDeleted)>, dryRun,
   policiesApplied)`. Policies:
   - `maxAgeDays` — drop entries with `createdAtEpochMs < (now - N*day)`.
   - `keepLastN` — keep the N most-recent entries (by
     createdAtEpochMs, tie-break by list index so same-timestamp
     entries fall back to insertion order).
   - Both null → explicit no-op (returns empty `policiesApplied`,
     surfaces an output-for-llm nudge suggesting a policy arg).
   - Both set → OR semantics: an entry failing EITHER drops
     (tagged with reason `"age"` / `"count"` / `"age+count"`).
   - `dryRun=true` → compute the prune set, never call
     `deleteMezzanine`, never mutate the cache. Every
     `PrunedSummary.fileDeleted` stays `false`.

4. **Permission: `project.write`** (NOT `project.destructive`).
   This diverges from `delete_session`'s destructive tier. Rationale:
   the mezzanine cache is **re-derivable** — a pruned entry just
   becomes a cache miss on the next export, which re-renders the
   clip. No Project-state or Session-state data is lost. Same
   rationale governs why `prune_lockfile` / `gc_lockfile` use
   `project.write`: they drop re-derivable bookkeeping, not
   user-authored content.

5. **Deletion ordering.** On-disk files go FIRST, cache rows
   SECOND (inside a `projects.mutate { retainByFingerprint(...) }`
   transaction). If a delete throws mid-loop, the cache row still
   points at the (possibly-still-present) path so a retry can
   notice and finish cleanup. The inverse order would leave orphan
   rows pointing at deleted files — harder for the user to
   reconcile manually.

6. **Container registration** — all 5 AppContainers (CLI, Desktop,
   Server, Android, iOS). CLI / Desktop / Server / Android pass
   `(projects, engine)`; iOS passes `(projects, engine, clock)`
   because SKIE likes explicit `Clock` rather than the default
   `Clock.System`.

§3a rundown:
- Rule 1 (tool growth): +1 net. Justified — no existing tool
  covers mezzanine eviction; the bullet's alternative
  "auto-prune inside save_project_snapshot" would silently
  destroy state without explicit user intent, a known antipattern
  in this codebase. Standalone tool keeps the destructive scope
  visible.
- Rule 3 (Project blob): No new Project field. `ClipRenderCache`
  is already in the `ProjectClipRenderCache` sibling table (cycle
  33 extraction); GC writes through `ProjectStore.mutate` same as
  any other mutation.
- Rule 4 (binary state): `dryRun` is genuinely binary (run or
  not) — no Unknown state to model. `fileDeleted` on the per-entry
  summary is tri-valued in effect (true = file gone, false =
  already gone OR no-cache engine OR dryRun); the flags disambiguate
  which case hit.
- Rule 6 (session-project binding): `projectId` stays required
  input; future session-binding pass will flip it to
  `ctx.currentProjectId` alongside every other `projectId`-taking
  tool.
- Rule 7 (serialization forward-compat): all new output fields
  exist with sensible defaults on `PrunedSummary`. `Input.dryRun`
  defaults to `false`.
- Rule 8 (5-platform wiring): registered in all 5 AppContainers
  (CLI / Desktop / Server / Android / iOS).
- Rule 9 (semantic-boundary tests): 11 cases covering
  no-policy / age-only / count-only / age+count / dryRun /
  no-cache engine / empty cache / unknown project / negative
  maxAgeDays / negative keepLastN / keepLastN=0.
- Rule 10 (LLM context): ~220 tokens for the new tool spec +
  helpText. One-shot cost on container init; the tool is on every
  turn's tool list. Bounded by the rule-10 500-token threshold.

**Alternatives considered.**

1. **Auto-prune inside `save_project_snapshot`.** Rejected. The
   bullet listed this as an alternative direction, but implicit
   destruction of disk resources coupled to an unrelated user
   action (save a snapshot) is exactly the "surprise mutation"
   shape this codebase has rejected elsewhere (§3a rule 4
   binary-state trap comes from the same family). A standalone
   tool keeps the blast radius visible.

2. **Single policy knob: only `keepLastN`.** Simpler API; loses
   the "drop anything older than last-Tuesday" workflow that the
   bullet highlighted. Kept both because the cost is one extra
   Input field with a clear null-disables-policy default.

3. **Engine-layer `VideoEngine.gcCache()` that both chooses
   entries and deletes files.** Rejected. Policy should live at
   the tool layer (platform-agnostic); the engine just knows how
   to delete a file on its native filesystem. Moving policy into
   the engine would force each engine (Media3, AVFoundation,
   FFmpeg) to re-implement age + count logic. The current split —
   engine owns `deleteMezzanine(path)`, tool owns policy — is the
   same split `ExportTool` already uses with `renderClip` /
   `concatMezzanines` (engine = atomic primitive, tool = policy).

4. **Expose raw `ProjectStore.gcClipRenderCache(policy)` without a
   tool.** Would let programmatic callers (tests, the CLI) use the
   GC without an LLM in the loop. Rejected for this cycle —
   LLM-facing is the primary driver; once a programmatic caller
   surfaces, extracting the policy into a helper is mechanical.
   `gc_lockfile` has the same shape today.

**Coverage.** `core/src/jvmTest/kotlin/io/talevia/core/tool/builtin/project/GcClipRenderCacheToolTest.kt`
— 11 cases, §3a rule 9 semantic-boundary oriented:

- `noPolicyPresentShortCircuitsWithEmptyPoliciesApplied` — both
  policies null → no-op, no engine call, `policiesApplied =
  emptyList()`.
- `maxAgeDaysDropsOldOnlyAgeReason` — 10-day-old + 1-day-old
  entries; `maxAgeDays = 7` drops old, keeps fresh, reason
  `"age"`, engine deleted the right path, store rewritten.
- `keepLastNKeepsTheMostRecentAndDropsOlder` — 3 entries
  separated by 100 days; `keepLastN = 2` keeps the two most
  recent, drops the oldest, reason `"count"`.
- `bothPoliciesComposeAndLabelReason` — 3 entries at 30d / 3d /
  1d; `maxAgeDays=7, keepLastN=1` → old is `"age+count"`, mid is
  `"count"` only, fresh survives.
- `dryRunReportsWithoutMutatingStoreOrTouchingEngine` — drop set
  populated, `fileDeleted = false` for all, `engine.deletedPaths`
  empty, store unchanged.
- `noCachePerClipEngineReturnsFalseButRowsStillDrop` — engine
  returns `false` (Media3 / AVFoundation default); row still
  removed from store, `fileDeleted = false`.
- `emptyCacheReturnsZeros` — empty-input no-op.
- `unknownProjectErrorsLoud` — wrong projectId fails with
  "not found".
- `negativeMaxAgeDaysRejected` / `negativeKeepLastNRejected` —
  `require(... >= 0)` guards fire loud.
- `keepLastNZeroDropsEveryEntry` — `keepLastN = 0` is meaningful
  ("clear the cache") and actually clears it.

Full cross-platform: `:core:jvmTest`, `:platform-impls:video-ffmpeg-jvm:test`,
`:apps:server:test`, `:apps:desktop:assemble`,
`:apps:android:assembleDebug`,
`:core:compileKotlinIosSimulatorArm64`, `ktlintCheck` — all green.

**Registration.** New tool registered in all 5 `AppContainer`
files:
- `apps/cli/src/main/kotlin/io/talevia/cli/CliContainer.kt`
- `apps/desktop/src/main/kotlin/io/talevia/desktop/AppContainer.kt`
- `apps/server/src/main/kotlin/io/talevia/server/ServerContainer.kt`
- `apps/android/src/main/kotlin/io/talevia/android/AndroidAppContainer.kt`
- `apps/ios/Talevia/Platform/AppContainer.swift` (3-arg form with
  explicit clock).

Also extended `VideoEngine` contract with the `deleteMezzanine`
default method; `FfmpegVideoEngine` overrides. Media3 / AVFoundation
engines inherit the no-op default, matching the existing pattern
for `renderClip` / `concatMezzanines` gated behind
`supportsPerClipCache`.

---
