## 2026-04-23 — BundleBlobWriter.writeBlobStreaming + Import/Consolidate migration (VISION §5.6)

**Context.** `BundleBlobWriter.writeBlob(bytes: ByteArray)` was the only
entry point for landing bytes into `<bundleRoot>/media/<assetId>.<ext>`.
Three callers needed to copy file-backed media into the bundle:
- `ImportMediaTool.probeOne` (the `copy_into_bundle=true` branch)
- `ConsolidateMediaIntoBundleTool.consolidateOne`
- `FileBundleBlobWriter.writeBlob` itself

The two tools bypassed the writer entirely and inlined their own
`fs.source(path).use { … fs.sink(tmp).buffer().use { sink.writeAll(it) } }`
plus `fs.atomicMove(tmp, target)` logic — copy-pasted 16-line blocks with
identical tmpfile-and-atomicMove semantics and identical ext-normalisation
rules. Worse: the one sanctioned writer couldn't help them because it
forced the bytes into memory first (`ByteArray` parameter). A 4 GB
gigabit-camera import ate 4 GB of heap for no reason.

Rubric §5.6: three callers, one shared concern (atomic streaming write
into `<bundleRoot>/media/`), one implementation asymmetry. Classic
signal that the interface is fighting the callers.

**Decision.** Promote `BundleBlobWriter` from a single-method
`fun interface` to a two-method regular interface:

- `writeBlob(bytes: ByteArray)` — kept as the abstract primitive (so the
  ~10 test fakes that override it continue to compile without changes).
  The byte form is the natural fit for AIGC callers that receive a
  complete payload from a provider.
- `writeBlobStreaming(source: okio.Source)` — new. Default implementation
  consumes the source into a `Buffer` and delegates to `writeBlob` — good
  enough for small test payloads and any fake writer. Production
  implementations override to stream directly.

`FileBundleBlobWriter` overrides both: `writeBlobStreaming` does the real
work (`fs.source → fs.sink(tmp).buffer().use { writeAll } → atomicMove`),
and `writeBlob` wraps the bytes in a `Buffer` and delegates to
`writeBlobStreaming`. This collapses the `tmpfile + atomicMove` logic to
exactly one copy in the codebase.

`ImportMediaTool.probeOne` and `ConsolidateMediaIntoBundleTool.consolidateOne`
gain a `bundleBlobWriter: BundleBlobWriter = FileBundleBlobWriter(projects, fs)`
constructor param (defaulted so no call site needs updating). Their
inline 14-16 line copy blocks collapse to a single `bundleBlobWriter
.writeBlobStreaming(projectId, newAssetId, fs.source(path), ext)` call.
`ImportMediaTool`'s post-copy `sourcePath` (handed to the proxy generator)
is now computed from `bundleRoot.resolve(returned.relativePath)` instead
of the now-gone `target` local.

`ConsolidateMediaIntoBundleTool.execute` keeps its early "project must be
registered at a path" guard even though `writeBlobStreaming` would throw
later — the specific-next-step hint (`open_project / create_project(path=…)
first`) is a better UX for the LLM than the generic
"project is not registered".

**Axis.** "New path-to-bytes producer." If a 4th caller appears that also
needs to land external bytes into `<bundleRoot>/media/`, it uses
`writeBlobStreaming` from day 1 — tmpfile / atomicMove / ext-normalisation
stays in exactly one place.

**Alternatives considered.**

- **Extract a free helper `streamMediaFileIntoBundle(fs, bundleRoot, assetId,
  format, source): okio.Path`** sitting alongside `BundleBlobWriter`.
  Would dedupe the three callers without changing the writer interface.
  Rejected because the helper still needs the `projects.pathOf(pid)` guard
  + random-tmp-suffix + ext normalisation, which is exactly what
  `BundleBlobWriter` already encapsulates. A parallel helper would
  drift out of sync with any future change to the writer's atomicity
  semantics. The bullet explicitly asked for the method on the interface.

- **Make `writeBlob(bytes)` the default and `writeBlobStreaming(source)`
  the abstract primitive.** Would force every test fake to implement
  streaming, which is exactly the kind of test churn the bullet's design
  should avoid. Current design keeps `writeBlob` abstract so the ~10
  `FakeBlobWriter` test classes need zero edits; only
  `FileBundleBlobWriter` pays the cost of implementing both (and it's
  genuinely the place that should stream). Standard Kotlin
  "convenience-method-default, primitive-abstract" pattern is exactly
  what kotlinx.serialization's `Encoder` uses for the same reason.

- **Remove `writeBlob(bytes)` entirely and force all callers to `Buffer
  { write(bytes) }` themselves.** Cleaner abstraction at the cost of
  every AIGC tool gaining two lines of Buffer boilerplate. Not worth it;
  the byte form is the natural shape for provider-returned payloads.

- **Keep `fun interface BundleBlobWriter` and add
  `writeBlobStreaming` as a free extension function using internal state.**
  `fun interface` requires exactly one abstract member, so the extension
  can't access per-instance state (like `projects`). Dead on arrival.

**Coverage.** `FileBundleBlobWriterTest` gains four cases:
1. `streamsSourceIntoBundleMediaDir` — direct Buffer source round-trips
   the written bytes.
2. `streamingFromExternalFileOnFakeFilesystem` — the production shape
   (`fs.source(externalPath)` into bundle). Writes 4 KiB of known pattern
   to external path, streams via `writeBlobStreaming`, verifies every
   byte matches on the bundle side.
3. `streamingStillThrowsWhenProjectNotRegistered` — unregistered-project
   guard still fires on the streaming path.
4. `writeBlobBytesDelegatesToStreamingUnderTheHood` — bytes-path
   round-trip proves `writeBlob` correctly wraps `writeBlobStreaming`.

Existing tests (`writesBlobIntoBundleMediaDir`, `normalizesEmptyFormatToBin`,
`stripsLeadingDotInFormat`, `throwsWhenProjectNotRegistered`,
`overwritesExistingFileAtomically`) continue to pass via the
`writeBlob` → `writeBlobStreaming` delegation, proving the bytes-path
behaviour is unchanged.

`:core:jvmTest`, `:apps:server:test`, iOS-sim compile, android
`assembleDebug`, and `ktlintCheck` all green.

**Registration.** No container registration change — the five
`AppContainer` sites that construct `ImportMediaTool(engine, projects,
…)` and `ConsolidateMediaIntoBundleTool(projects)` keep working via the
defaulted `bundleBlobWriter = FileBundleBlobWriter(projects, fs)`. Ten-
plus test call sites likewise unchanged.
