## 2026-04-20 — `validate_project` structural lint tool

**Context.** Multi-step autonomous runs accumulate state: the agent
defines a `character_ref`, references it from three AIGC clips, then a
follow-up turn removes the node, and the clips are now pointing at a
ghost source binding. Or an imported asset gets deleted, but three
clips still reference its `assetId`. Or hand-authored snapshots
stored a `volume=5.0` that the schema happily round-trips but the
engine will misbehave on. Today there's no way to catch any of this
pre-export short of actually running the engine, which (a) is slow,
and (b) either silently mis-renders or fails loudly with a message
the agent can't cleanly map back to the offending clip.

**Decision.** Ship a read-only `validate_project` tool that walks the
project and reports one `Issue { severity, code, message, trackId?,
clipId? }` per invariant violation. Current rule vocabulary (stable
`code` strings so callers can switch on them):

- `dangling-asset` (error) — `Clip.Video/Audio.assetId` not in `Project.assets`.
- `dangling-source-binding` (error) — `Clip.sourceBinding` references a
  `SourceNodeId` that is not in `Project.source.byId`.
- `non-positive-duration` (error) — `timeRange.duration <= 0`.
- `volume-range` (error) — audio `volume ∉ [0, 4]`.
- `fade-negative` (error) — `fadeInSeconds < 0` or `fadeOutSeconds < 0`.
- `fade-overlap` (error) — `fadeIn + fadeOut > timeRange.duration`.
- `duration-mismatch` (warn) — `timeline.duration < max(clip.end)`.

`passed: Boolean = errorCount == 0` is the top-level assertion the
caller branches on; warnings are informational. The tool never fails
loudly for a project-state problem (that's the whole point — surface
it instead of swallowing it); it fails loudly only on "project not
found", which is a caller bug.

**Alternatives considered.**

- **Fold validation into `ExportTool`.** Already partly done (stale
  guard) but export is the wrong place to surface lint-style issues:
  by the time you're exporting you've spent the planning cycle, and
  the agent has no cheap way to *ask* whether a project is healthy
  without committing to a full render. A read-only tool decouples
  the question from the action.
- **Auto-heal.** Tempting — dangling assets / bindings could in theory
  be patched. But each rule has a different right answer (should a
  dangling sourceBinding be dropped, or the node re-created? should
  a clamped volume be set to 1 or 0?) and deciding that is a creative
  call, not a mechanical one. Linter-now, autofix-later if we see
  the patterns repeat.
- **Add staleness (`find_stale_clips`) as another rule here.** No:
  staleness is a first-class DAG concern with its own tool. Merging
  concerns makes both less clear.

**Why this now.** VISION §3.2 (build-system mental model) needs a
"before you render, is the source graph well-formed?" check, and the
professional path (§4) needs an explicit pass the user can ask for
without guessing. Also keeps autonomous loops honest: the agent can
call `validate_project` as its last step before claiming a multi-edit
turn is done.

**Files.**
- `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/project/ValidateProjectTool.kt`
- `core/src/commonMain/kotlin/io/talevia/core/agent/TaleviaSystemPrompt.kt` — new section
- `core/src/jvmTest/kotlin/io/talevia/core/tool/builtin/project/ValidateProjectToolTest.kt` — 9 tests
- Registered in `apps/cli`, `apps/desktop`, `apps/server`, `apps/android`, `apps/ios`.

---
