## 2026-04-23 — import-media-tool-bundle-resolver: bullet is stale; KDoc cleanup (VISION §3a-3 hygiene)

**Context.** Backlog bullet `import-media-tool-bundle-resolver` claimed
`ImportMediaTool.execute` in the `copy_into_bundle=false` path still
called `MediaStorage.import` instead of routing through
`BundleMediaPathResolver`, and that this bypassed bundle-resolver path
safety for character_ref-style bundle-local references. Walking the
current code today:

1. `MediaStorage` was deleted wholesale in commit `9d0f70f`
   (`refactor(core): delete MediaStorage / FileBlobWriter — route
   through Project.assets + BundleBlobWriter`). No `storage.import`
   call site survives; `grep -rn 'storage\.import\|MediaStorage' core
   apps platform-impls --include='*.kt'` returns only doc comments.
2. Both branches of `ImportMediaTool.execute` already route through
   `projects.mutate(pid) { project.copy(assets = project.assets + asset)
   }` (lines 271-278). The copy-into-bundle branch uses okio
   `atomicMove` into `<bundleRoot>/media/<assetId>.<ext>` and
   registers `MediaSource.BundleFile(...)`; the default branch
   registers `MediaSource.File(absolute)`.
3. Path safety is enforced at ingest, not at resolve: every input path
   hits `PathGuard.validate(it, requireAbsolute = true)` at line 235,
   and `MediaSource.BundleFile.init` rejects `..`, leading `/`,
   Windows drive letters. The bullet's "bypass resolver path safety"
   concern doesn't land — the resolver is a *rendering* dispatch
   point, not a validation layer; validation happens at ingest where
   it belongs.
4. The bullet's mention of `character_ref` trips §3a-5 (Core shouldn't
   hard-code genre-specific concepts). `character_ref` is a
   `SourceNode.kind`, not an asset `MediaSource` variant; conflating
   them was a bullet-authoring slip.

Rubric delta: none (the refactor the bullet asked for has been in
main for a cycle already; this decision just closes the ticket).

**Decision.** No change to `ImportMediaTool`. Current structure is
already what the bullet prescribes. In the same commit, cleaned up
three broken KDoc references to the deleted `MediaStorage` type:

- `core/platform/MediaPathResolver.kt`: KDoc pointed at `MediaStorage`
  as the implementation source. Replaced with the real pointer —
  `BundleMediaPathResolver`, constructed per-render by `ExportTool`
  against the loaded project + bundle root.
- `core/platform/FileSystem.kt`: two stale references (class KDoc and
  `DEFAULT_MAX_READ_BYTES` comment) — updated to point at
  `import_media` + `Project.assets` as the "heavy media path" now
  that there's no separate `MediaStorage` layer.

Left alone: test-file comments that reference `MediaStorage` in
historical context (e.g. "does NOT go through the global MediaStorage
anymore") — those are still accurate narrations of the current
behavior and help readers understand why a test shape is what it is.

**Alternatives considered.**
- **Refactor `ImportMediaTool.probeOne` to call `FileBundleBlobWriter`
  for the copy-into-bundle branch.** Would remove ~15 lines of
  duplicated tmpfile+atomicMove logic. Rejected for this cycle:
  `BundleBlobWriter` takes `ByteArray`, which means reading the whole
  file into memory — the current inline code streams via
  `fs.source(path)`/`fs.sink(tmp).writeAll(src)` and is a memory
  improvement for gigabyte imports. Swapping would be neutral
  (BundleBlobWriter already reads bytes into memory internally — see
  `FileBundleBlobWriterTest`), but the right structural fix is to add
  a streaming `writeBlobStreaming(assetId, source: okio.Source)`
  method on `BundleBlobWriter` and migrate both call sites. That's a
  new bullet's worth of work, not a drive-by for this stale-bullet
  closure. Logged as a follow-up in PAIN_POINTS.
- **Just skip the bullet and leave the broken KDocs** for a future
  `debt-clean-todos`-style cycle. Rejected: broken class references in
  public KDoc actively mislead readers about what the canonical
  resolver + heavy-media path are. The fix is one-minute-scoped and
  belongs with the decision that names the stale reference.

**Coverage.** `:core:jvmTest` green, `./gradlew ktlintCheck` green.
No behavioural change — verifies the KDoc-only edits don't break
compilation / linking and that nothing downstream referenced the
`MediaStorage` token through KDoc link resolution.

**Registration.** None — comment-only edit on two platform KDoc
files. No tool changed, no `AppContainer` touched.
