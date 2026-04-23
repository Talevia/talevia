## 2026-04-23 — debt-import-media-auto-in-bundle-default (VISION §3a-4 / 状态字段不做二元)

**Context.** `ImportMediaTool.Input.copy_into_bundle: Boolean = false` forces the agent to choose between two rigid modes: reference-by-path (bytes don't travel with `git push`) vs bundle-copy (bytes inflate the bundle). The right choice depends on file size — a 10 KB LUT wants to travel with the bundle, a 4 GB rush doesn't. `bundle-source-footage-consolidate` (cycle 13) intentionally deferred flipping the default because changing `false → true` would silently inflate every existing caller's bundles. The bullet calls out the shape: move to tri-state `Boolean? = null` where null = "decide by size", explicit true/false preserved. This is exactly §3a-4's rule: before introducing a binary stale/fresh/dirty/pinned flag, check whether there's a third `Unknown` / `Auto` term — here the third term is genuinely what the agent usually wants. Rubric delta §3a-4: `copy_into_bundle` binary → tri-state with auto default.

**Decision.** `ImportMediaTool.Input.copy_into_bundle` is now `Boolean? = null` (default). Effective behaviour:
- `true` — always copy into bundle, regardless of file size.
- `false` — always reference by absolute path (the pre-change default behaviour for callers who want it).
- `null` (auto) — copy when file size ≤ `autoInBundleThresholdBytes` (default 50 MiB; injectable via ctor for testing); reference otherwise.

Implementation points:
- New ctor param `autoInBundleThresholdBytes: Long = 50L * 1024L * 1024L` on `ImportMediaTool`, plus `companion object { DEFAULT_AUTO_IN_BUNDLE_THRESHOLD_BYTES }`. Injectable so tests can trip both branches without literal 50 MiB fixtures.
- `shouldAutoCopy(path)` reads `fs.metadata(path).size` and falls back to `false` on any stat failure — auto mode is conservative by construction (don't copy what we can't measure).
- `bundleRoot` is still resolved once up-front when `copy_into_bundle != false`. Explicit `true` without a registered bundle path still fails loud; auto + no bundle path degrades silently to reference (in-memory test stores, etc.).
- helpText + JSON schema description rewritten to document tri-state semantics and signal "auto is the sensible default — explicit values are overrides".

Tests updated:
- `ImportMediaCopyIntoBundleTest`: existing `copyIntoBundleFalseRegistersFileSourceAsBefore` now passes explicit `copy_into_bundle = false` (was relying on the old default). Added `autoModeCopiesSmallFilesIntoBundle`, `autoModeReferencesFilesAboveThreshold`, `explicitTrueForcesBundleCopyEvenAboveThreshold`.
- `ImportMediaBatchTest` + `ImportMediaProxyTest` existing call sites now pass explicit `copy_into_bundle = false` — those tests use `ProjectStoreTestKit` (FakeFileSystem) while the tool's `fs` defaults to `FileSystem.SYSTEM`; auto-mode's default behaviour (attempt to copy to the fake FS path via real FS) would fail and the tests were never about storage mode in the first place.

**Alternatives considered.**
- *Flip default from `false → true`.* Rejected per the bullet: every existing caller who didn't explicitly pass `false` would suddenly start copying gigabytes. Silent regression is strictly worse than a tri-state default.
- *Read a Project-level config key rather than per-call input.* Rejected: import decisions are per-file (a batch can mix a 4 GB rush with a 10 KB LUT and want both behaviours). Project-level config would force a batch to choose one mode for all files.
- *Infer "is this LUT/font/small-image" from file extension.* Rejected: extension-based inference is genre-coupling (cycle-5 style pain point — Core hard-coding specific MIME types). Size is a neutral, universally applicable signal. The agent can still force the explicit value when the size heuristic gets it wrong.
- *Auto threshold as a `Project.importAutoThreshold` field.* Rejected: not yet a driver. One threshold value fits most editing workflows; if the heuristic bites, move to per-project config then. §3a-3 (don't grow Project blob without concrete driver).

**Coverage.** 4 auto-mode tests in `ImportMediaCopyIntoBundleTest` (under-threshold auto-copy, over-threshold auto-reference, explicit-true-overrides-threshold, plus the existing explicit-false happy path). `./gradlew :core:jvmTest` green. `./gradlew :apps:server:test` stays green (cycle 17 fix holds).

**Registration.** No `Tool<I, O>` added. `ImportMediaTool` is already wired in all 5 `AppContainer`s (CLI / Desktop / Server / Android / iOS) — the ctor param has a default so the containers don't need to be updated unless they want to override. None do today.
