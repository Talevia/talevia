## 2026-04-23 — Bundle cross-machine relink UX: AssetsMissing bus event + relink_asset tool (VISION §5.4)

**Context.** Cycle 4's `BundleCrossMachineExportSmokeTest` proved the
bundle format is portable when every asset is `BundleFile`-backed.
Cycle 13's `consolidate_media_into_bundle` added the bulk "copy
everything in" path. But the transient "bob just opened alice's bundle
and her `/Users/alice/raw.mp4` absolute paths don't resolve on his
machine" case still had no feedback: `ExportTool` would just fail at
render with a vague ffmpeg "file not found" message, and there was no
tool the LLM could invoke to rewire the asset to bob's copy of the
file. Rubric delta §5.4 (cross-machine open UX): `无 → 部分` — bus
event + relink tool land, the app-side "show missing before export"
UX is a follow-up (P2 debt note below).

**Decision.** Two Core deliverables, one commit:

1. **`BusEvent.AssetsMissing(projectId, missing: List<MissingAsset>)`**
   fired from `FileProjectStore.openAt` and `get` when the project
   contains any `MediaSource.File(absolutePath)` asset whose path
   doesn't resolve on the current machine. Batched (one event per
   open / get call, listing every missing asset) rather than per-asset
   so UI renders one "relink panel" instead of coalescing N separate
   events. `BundleFile` / `Http` / `Platform` sources are excluded by
   design — they're either bundle-local (a missing bundle-file is a
   different corruption class) or not filesystem-based.

   Implementation shape mirrors `maybeEmitValidationWarning` — same
   `bus == null` short-circuit so pure-store tests don't pay for the
   scan, same `logger.warn` + single `bus.publish(...)` pattern.
   `Metrics.counterName` sink extended to `project.assets.missing`
   counter for Prometheus scrape visibility.

2. **`RelinkAssetTool(projects)`** — new tool under
   `core/tool/builtin/video/`. Input `(assetId, newPath, projectId?)`.
   Looks up the target asset's current `MediaSource.File.path`, then
   cascades: **every** asset whose source equals that same absolute
   path flips to `MediaSource.File(newPath)`. Cascade is the
   load-bearing semantic — a user who imported multiple clips off one
   recording typically has many assets sharing the same original path,
   so one relink call fixes them all. Output reports `relinkedAssetIds`
   so the caller sees the cascade in action.

   Lax on file existence: does NOT verify `newPath` resolves at call
   time. User might `relink_asset` before actually copying the file
   into place; export will fail at render with a clear error if still
   missing. The laxness is deliberate — it matches how the LLM
   typically sequences these calls.

   Rejects `BundleFile` / `Http` / `Platform` sources with a loud
   error — those aren't path-based in the File sense. Registered in
   all 5 `AppContainer`s; `RegisteredToolsContractTest` (cycle 8)
   confirms.

**Alternatives considered.**
- **Emit one `AssetMissing` per asset instead of batching**, matching
  the bullet's literal singular phrasing. Rejected: batched matches
  `ProjectValidationWarning` convention (batched issues list) and
  collapses UI "show missing assets" rendering to one event-driven
  panel update. Per-asset events scale N bus.publish calls per open
  and force the UI to coalesce them with a debounce timer — pure cost
  for no gain.
- **Verify `newPath` exists in `RelinkAssetTool`**. Rejected: user
  typically runs relink to PRE-configure the new pointer before
  physically copying the file (e.g. scripted setup: "relink all alice
  paths to my NAS layout, then rsync the files"). Enforcing existence
  would block the scripted flow without improving the actual error
  story (missing files still fail at render).
- **Cascade by `assetId` match alone, not by `originalPath`**. That
  is, only flip the asset the user named. Rejected: misses the common
  case bullet flagged — "多个 clip 同一 footage 原素材" — which is
  exactly where cascade pays off. Cascade by `originalPath` is the
  right key; the caller naming any one of the N siblings triggers all.
- **Split into `bus event` cycle and `tool` cycle** for smaller PRs.
  Rejected: the two land as one UX ("alice's bundle opens → you see
  AssetsMissing → you call relink_asset → cascaded flip → retry
  export"). Shipping only the event without the tool would be a
  "detect but can't fix" half-state; shipping only the tool without
  the event would leave the user guessing which asset to relink.

**App-side UX cut as follow-up.** The bullet's third piece (`CLI /
Desktop 在 export 前显式列出 missing`) is deferred to a separate cycle
— it's an app-level event consumer (CLI prints a warning, Desktop
shows a banner) that depends only on the bus event this cycle
emits. Logged as `debt-export-missing-asset-warning` in the P2 backlog.

**Coverage.** New `RelinkAssetToolTest`:
- `relinkFlipsEveryAssetSharingTheOriginalPath` — cascade across two
  assets with the same original path plus one untouched control.
- `unknownAssetIdFailsLoud` — error path with `project_query` hint.
- `bundleFileSourceRejectedByRelink` — loud reject on non-File source.
- `blankNewPathFailsLoud` — input validation.
- `openAtEmitsAssetsMissingForNonExistentFilePaths` — end-to-end:
  three assets (one real file, one ghost, one BundleFile), subscribe
  on the bus BEFORE `openAt`, assert exactly one `AssetsMissing` event
  carrying only the ghost.
- `openAtEmitsNothingWhenAllFilePathsExist` — no-event path with
  bounded `withTimeoutOrNull`.

`:core:jvmTest`, `:core:compileKotlinIosSimulatorArm64`,
`:apps:android:assembleDebug`, `:apps:desktop:assemble`, `ktlintCheck`
all green. `Metrics.kt` `when` had to grow its `is BusEvent.AssetsMissing
-> ...` branch — exhaustive `when` on `BusEvent` catches the "forgot
to route the new event to metrics" case at compile time, which is
exactly the shape §3a would warn about and the compiler already
enforces.

**Registration.** `RelinkAssetTool(projects)` added to all 5
`AppContainer`s (CLI / Desktop / Server / Android / iOS) with the
now-familiar 8-edit-site pattern — import line + register line in
each. `ktlintFormat` re-sorted the 4 Kotlin container import blocks
after the edit (pain-point logged again this cycle). No changes to
existing container wiring otherwise.
