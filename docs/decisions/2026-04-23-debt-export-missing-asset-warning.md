## 2026-04-23 — CLI surfaces BusEvent.AssetsMissing on project open (VISION §5.4)

**Context.** Cycle-14's `bundle-asset-relink-ux` shipped the Core half of
the cross-machine missing-asset story: `FileProjectStore.openAt`
publishes `BusEvent.AssetsMissing(projectId, missing)` whenever a newly
loaded bundle has `MediaSource.File` assets whose absolute paths don't
resolve on this machine, and `RelinkAssetTool` rewrites those paths.
The CLI-side consumption (operator warning) was explicitly listed as
follow-up in the backlog — until landed, a collaborator who `git pull`s a
bundle from another machine would see no warning, attempt `export`, and
get a cryptic render-time failure from the ffmpeg engine instead of a
clear "3 assets don't resolve — call relink_asset" cue.

Rubric §5.4: bundle portability depends on the operator knowing *at open
time* that their local filesystem doesn't satisfy the project; a silent
bundle is a fragile bundle.

**Decision.** Wire a new subscription in `EventRouter.start()` for
`BusEvent.AssetsMissing`. On each event it calls a new
`Renderer.assetsMissingNotice(paths)` method that:

- breaks any open assistant line so the warning sits on its own row,
- prints a bold-yellow header:
  `! ⚠ N asset(s) don't resolve on this machine — export will fail or
  render a broken clip. Call relink_asset to fix.`,
- lists up to 5 original paths verbatim (dim style), and
- summarises any overflow as `(+N more)` so a dozen-missing-asset
  project doesn't flood the transcript.

Unlike the other router subscriptions, this one does NOT filter by
`activeSessionId` — `BusEvent.AssetsMissing` is project-scope (no
sessionId field), fires once per `openAt` that detects dangling paths,
and the warning is information the operator needs regardless of which
REPL session they're in. `FileProjectStore` already de-dupes: a
subsequent `relink_asset` call that rewrites the paths re-opens without
republishing, so the operator sees exactly one warning per stale load.

The bullet's "Desktop banner" half is deferred — CLAUDE.md platform
priority puts CLI before Desktop, and Desktop hasn't yet reached the
CLI-parity bar that would unlock feature additions there. The CLI fix
here closes the higher-leverage half; follow-up captured in a debt
append.

**Axis.** n/a (wiring, not a structural refactor).

**Alternatives considered.**

- **Pre-export guard in a CLI slash command.** The bullet's literal
  phrasing is "export 命令前打印警告". A pre-export check would require
  `Renderer` to cache "current project has unresolved assets" state
  keyed by project id, plus an intercept in whatever slash command or
  tool boundary triggers export. Rejected — the `BusEvent.AssetsMissing`
  event already fires at the earliest moment the information is
  actionable (project-open time); warning the operator then lets them
  fix it before they even attempt export, which is strictly better UX
  than a warning that appears only at the moment they've committed to
  exporting. The state cache would also drift if projects were opened
  and closed in the same session.

- **Log-only warning.** Could route to `Logger` instead of the
  transcript. Rejected — the CLI's structured logger writes to stderr
  and is invisible to the operator reading the interactive transcript.
  `retryNotice` sets the precedent: operationally meaningful events
  belong in-band.

- **Auto-run `relink_asset` prompt.** Could have the warning offer an
  interactive "retry with path X?" prompt. Rejected — the paths on
  alice's NAS aren't reachable by bob; the fix requires bob to point at
  HIS local copy, which is local knowledge only the operator has. A
  loud warning + `relink_asset` call hint is the right amount of
  intervention.

- **Skip this bullet until Desktop half is also ready.** Would deliver
  both halves together. Rejected — CLI is the higher-priority platform
  per CLAUDE.md, and the Core event has been ready for multiple
  cycles; every day this ships late is a day a collaborator can silently
  misuse a stale bundle.

**Coverage.** New `AssetsMissingNoticeTest` exercises four cases:
1. Header + all paths when under the 5-path preview cap.
2. Singular header when exactly one asset missing.
3. Overflow summary (`+7 more`) when more than 5 paths; confirms the
   6th-and-later paths do NOT print.
4. Empty input is a no-op (no bus event should arrive with empty list,
   but guard against it anyway).

The EventRouter wiring is implicit-tested: the subscription just maps
`ev.missing.map { it.originalPath }` into the renderer method that owns
its own tests, so a regression in the renderer will still surface on
every CLI run that opens a bundle with missing assets.

`:apps:cli:test`, `:core:jvmTest`, `:apps:server:test`, `ktlintCheck`
all green.

**Registration.** No new tool. `EventRouter` already lived in the CLI
container's wiring; this adds one `bus.subscribe<BusEvent.AssetsMissing>()`
subscription to its `start()` block. No iOS / Android / Desktop /
Server changes — those platforms handle the same event separately
(Desktop banner follow-up listed as debt).
