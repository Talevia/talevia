## 2026-04-22 — Run `validate_project` checks at envelope-import time (VISION §5.3 correctness)

**Context.** `ImportProjectFromJsonTool` decoded a
`ProjectEnvelope` and immediately `projects.upsert(...)`'d it,
relying on the caller to hand in a well-formed project. In
practice the envelope is a **trust boundary** — it can arrive
from another Talevia instance, a hand-edited JSON file, or a
template shipped on disk — and nothing stopped a payload whose
`Clip.assetId` pointed at a missing `MediaAsset`, whose
`Clip.sourceBinding` referenced a removed `SourceNode`, or
whose `Source.nodes` contained a parent-cycle from ever landing
in the store. The only safety net was `find_stale_clips` /
`validate_project` *after the fact*, which is the wrong phase:
the project is already corrupt, the lockfile and render cache
already indexed it, and the failure mode surfaces later as "why
did export blow up?" instead of "the envelope was broken".

Backlog bullet `project-import-validator` (P2) named the fix:
run the same checks as `validate_project` before upsert, reject
on errors unless forced.

**Decision.** Three surgical edits.

1. **`ValidateProjectTool.Companion.computeIssues(project)`**
   — extract the pure structural-check logic out of the
   instance method. The three private helpers
   (`timelineDurationIssues`, `clipIssues`, `sourceDagIssues`)
   were moved to top-level file-private functions so the
   companion can call them without needing a
   `ProjectStore`. `execute()` now delegates to
   `computeIssues()` → zero behavior change.

   Also adds `Companion.renderIssues(issues, maxLines = 5)`
   — a short multi-line summariser for inclusion in
   `error { ... }` messages at ingest boundaries. Caps at
   `maxLines` and appends `… (N more)` for the tail so a
   hundred-dangling-parent envelope doesn't flood the error
   message.

2. **`ImportProjectFromJsonTool` runs the gate.** After the
   envelope is decoded, the format-version check passes, and
   the target id is confirmed free, the tool calls
   `ValidateProjectTool.computeIssues(rehomed)`. If
   `errorIssues.isNotEmpty()` and `force=false` → `error { ... }`
   with a rendered summary, **before** `projects.upsert`.
   Warnings (`duration-mismatch`) never block. The upsert is
   reached only when the envelope is clean or the caller opted
   out via `force=true`.

3. **`Input.force: Boolean = false` + Output counters.** The
   flag bypasses the gate for import-to-fix workflows (the
   caller wants the project in the store specifically so they
   can run fixing tools against it). The Output grows
   `validationIssueCount` / `validationErrorCount` /
   `validationWarnCount` / `validationIssueCodes` (first ten
   issue codes) so successful-but-warning imports and
   force-through imports both surface *what* went wrong without
   the caller needing to re-run `validate_project`.

**Alternatives considered.**

- **A: sidecar `project-import-validator` tool.** Keep the
  importer dumb, add a separate tool the caller can run before
  import. Rejected: the gate belongs at the trust boundary, not
  as something the caller has to remember. A validator the
  agent might skip is not a validator.

- **B: validate in `SqlDelightProjectStore.upsert`.** Centralise
  the gate in the store so every write path (not just import)
  benefits. Rejected for now: `upsert` is called from
  `ProjectStore.mutate(...)` under a mutex on every timeline edit
  — making each mutation pay a full DAG walk would change the
  cost profile of the hot path. The existing load-path
  `ProjectSourceDagValidator` warning already catches drift at
  `get()`; import is the distinct "untrusted payload" case.

- **C: extract a `ProjectValidator` object in
  `core/domain`.** Cleaner namespace but forces a second home
  for `Issue` / `Output` schemas (tool-layer types). Rejected:
  keeps the issue vocabulary bound to the tool surface that
  already documents it. Cross-file coupling grows without
  payoff.

**Implementation.**

- `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/project/ValidateProjectTool.kt`
  — extract `computeIssues` + `renderIssues` onto the companion;
  move the three helpers to file-private top-level functions.
- `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/project/ImportProjectFromJsonTool.kt`
  — call `computeIssues` pre-upsert; fail loudly on errors
  unless `force=true`; add `Input.force` + 4 Output counters;
  fold `validationNote` into `outputForLlm`.
- `core/src/jvmTest/kotlin/io/talevia/core/tool/builtin/project/ProjectExportImportToolsTest.kt`
  — 6 new tests: dangling-asset rejected, dangling-source-binding
  rejected, source-parent-cycle rejected, `force=true`
  bypasses, warning-only (duration-mismatch) imports with
  `validationWarnCount=1`, clean import reports zero issues.

**Verification.**

- `./gradlew :core:jvmTest --tests 'io.talevia.core.tool.builtin.project.ProjectExportImportToolsTest' --tests 'io.talevia.core.tool.builtin.project.ValidateProjectToolTest'`
  — 6 new + all existing tests pass.
- `./gradlew :core:jvmTest` — full core suite green (no
  regressions from the helper extraction).
- `./gradlew :core:ktlintCheck` — clean.

**Why a nullable `force` flag, not a separate
`force_import_broken_project` tool.** The tool-count budget is
real (CLAUDE.md "don't add tools for hypothetical needs"). A
boolean input is the minimal surface, and the gate is on by
default — the bypass is an opt-in, not the common path.

**Follow-up (not this cycle).**

- Could extend the same gate to `fork_project` and the
  `variantSpec` path so forks of a broken project don't
  propagate the corruption. Probably worth a cycle when a
  concrete driver appears; today forks come from existing
  stored projects which already passed the load-path
  `ProjectSourceDagValidator` check.
- The Output could grow a `validationIssues: List<Issue>` full
  payload (not just codes) so callers can branch without
  re-running `validate_project`. Deferred — codes cover the
  current "what went wrong?" surface; full issues are one tool
  call away.
