## 2026-04-23 — consolidate_media_into_bundle tool for cross-machine bundle portability (VISION §3.1 / §5.4)

**Context.** `baad43f` shipped the file-bundle `ProjectStore` with the
goal that `git push` / `cp -R` reproduces a project on another machine.
Cycle 4 added a smoke test proving the invariant holds for bundle-local
AIGC assets (`MediaSource.BundleFile`). But the user's own source
footage still ships as `MediaSource.File(absolutePath)` — alice's
`/Users/alice/raw.mp4` won't resolve on bob's machine and `ExportTool`
dies with "file not found" at render. The bullet asked for a
`consolidate_media_into_bundle` tool that walks every `MediaSource.File`
asset, copies the bytes into `<bundle>/media/<assetId>.<ext>`, and
rewrites the source to `BundleFile`. Rubric delta §3.1 (产物可 pin):
bundle-portability `部分 → 有` for the case of projects that run
consolidate before `git push`.

**Decision.** New tool
`core/tool/builtin/video/ConsolidateMediaIntoBundleTool.kt`:

- Input: optional `projectId` (defaults to session binding).
- Walks `project.assets`, partitions into (File / BundleFile /
  Http / Platform). Only `File` sources are processed; everything
  else counts in the Output.
- For each `File` source: okio `source`/`sink.writeAll` stream-copy
  (bounded memory even for gigabyte rushes) into a tmpfile +
  `atomicMove` into `<bundleRoot>/media/<assetId>.<ext>`, then
  rewrites the asset's source to `MediaSource.BundleFile("media/...")`.
- Per-asset I/O failures (missing file, permission denied) are
  captured in `Output.failures` rather than aborting the batch — one
  unreadable drive must not lose the other 39 consolidations.
- Idempotent: already-bundled assets are skipped (`alreadyBundled`
  counter); `Http` / `Platform` sources counted in
  `unsupportedSourceCount` with no behaviour change.
- Registered in all 5 `AppContainer`s (CLI / Desktop / Server /
  Android / iOS). `RegisteredToolsContractTest` (cycle 8) confirms.

Deliberate scope cut: the bullet's second half ("smart default on
`import_media` — auto in-bundle for files < 50MB") is **not** in
this commit. Changing `import_media`'s default behaviour is a
behavioural regression for callers who expect the current
reference-by-default semantics; it needs a tri-state
`copy_into_bundle: Boolean? = null` where null means "auto by size"
and both explicit values preserve their current meaning. Logged as
a P2 debt bullet (`debt-import-media-auto-in-bundle-default`) so a
future cycle can land that separately.

**Alternatives considered.**
- **Fold consolidation into `import_media` via a new `consolidate`
  mode parameter.** Rejected: `import_media` takes `paths: List<String>`
  as input and walks external paths; this tool walks the project's
  own asset catalog. Input shapes and semantics are different —
  parametric `mode` would make the schema confusing. Separate tool
  with its own input fits LLM mental model cleaner.
- **Make it a `select=consolidate` on `project_query`.** Rejected:
  `project_query` is explicitly read-only (its `helpText` promises
  "read-only query primitive"); this tool mutates the project. Dual-
  purpose dispatcher erodes §5.3 discipline that read-only surfaces
  don't write.
- **Use `BundleBlobWriter.writeBlob` instead of inline okio
  stream-copy.** Rejected: `BundleBlobWriter` takes `ByteArray`,
  forcing gigabyte rushes into memory. Same pain-point already
  logged for `ImportMediaTool`. Inline streaming here matches that
  tool's approach; the eventual `BundleBlobWriter.writeBlobStreaming`
  follow-up will consolidate both sites.
- **Process-in-parallel** (fan out the per-asset copies via
  `coroutineScope + async`). Rejected: okio `atomicMove` touches the
  same `media/` directory; modest parallelism in a single directory
  has marginal throughput wins against the added complexity of
  per-asset concurrent `ProjectStore.mutate` (which is serialised by
  a mutex anyway). If large N becomes a profiling bottleneck, the
  fan-out is a follow-up.

**Coverage.** New `ConsolidateMediaIntoBundleToolTest` covers 4
semantic cases:
- `consolidatesFileSourceAndLeavesBundleFileAlone` — file flips to
  BundleFile, already-bundled asset untouched, byte-copy verified
  on disk.
- `missingSourceReportsFailureWithoutAbortingBatch` — one ghost
  path fails, the rest still migrate; good asset ends as BundleFile,
  ghost stays as File.
- `idempotent_SecondCallIsANoOp` — first call consolidates 1,
  second call reports `consolidated=0, alreadyBundled=1`.
- `httpAndPlatformSourcesCountedAsUnsupported` — unsupported
  counter fires, no source changes.

`:core:jvmTest`, `:core:compileKotlinIosSimulatorArm64`,
`:apps:android:assembleDebug`, `:apps:desktop:assemble`,
`ktlintCheck` all green. `RegisteredToolsContractTest` confirms the
new tool is registered in all five `AppContainer` files.

**Registration.** `ConsolidateMediaIntoBundleTool(projects)` wired
in `CliContainer.kt`, `AppContainer.kt` (desktop), `ServerContainer.kt`,
`AndroidAppContainer.kt`, and `AppContainer.swift` (iOS) —
positioned next to the existing `ExtractFrameTool` registration
block. `ktlintFormat` re-sorted the newly-added import into
alphabetical position per the cycle-9 + cycle-12 pain-point about
import-order churn on mid-file insertions.
