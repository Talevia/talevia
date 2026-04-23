## 2026-04-22 — Auto-revert fresh mezzanines on failed per-clip export (VISION §5.2 correctness)

**Context.** Backlog bullet `auto-revert-on-failed-export`. `runPerClipRender`
writes per-clip mezzanine files to `<outputDir>/.talevia-render-cache/<projectId>/<fingerprint>.mp4`
and, only after the final `concatMezzanines` succeeds, persists the
corresponding `ClipRenderCacheEntry` rows via `store.mutate`. If ffmpeg
crashes mid-loop (e.g. out of disk, corrupt input asset, CodecNotFound) or
the coroutine is cancelled, the files already written to disk are
**orphaned**: no cache record points to them, so `gc_clip_render_cache`
can't find them, and the fingerprint (keyed on `clip JSON + neighbour fades
+ bound-source deep hashes + output spec`) won't exactly recur unless every
upstream bit is byte-identical — typical source drift over time guarantees
orphans accumulate forever.

`VideoEngine.deleteMezzanine` already exists (`core.platform.VideoEngine:112`)
and is used by `GcClipRenderCacheTool`; the only gap was that the failure
path in the export loop never invoked it. Rubric delta: §5.2 "正确性 / 失败
资源清理" 部分→有 (per-clip path).

**Decision.** Wrap the per-clip loop + `concatMezzanines` + cache persist in
a try/catch in `runPerClipRender`:

```kotlin
try {
    // existing loop + concat + store.mutate
    return PerClipStats(hits, misses)
} catch (t: Throwable) {
    for (entry in newCacheEntries) {
        runCatching { engine.deleteMezzanine(entry.mezzaninePath) }
    }
    throw t
}
```

`newCacheEntries` is the in-memory list of `ClipRenderCacheEntry` built
inside the miss branch — it only contains fresh writes from the current
call, so hits (previously-cached mezzanines that we reused) are untouched.
Individual `deleteMezzanine` calls go through `runCatching` so a single
failing delete doesn't mask the original exception; re-throwing `t`
unchanged preserves `CancellationException` semantics (parent scope still
cancels correctly).

Whole-timeline path (`runWholeTimelineRender`) is untouched: ffmpeg writes
straight to `output.targetPath` without intermediate mezzanines, so there's
no equivalent "orphaned intermediate" to clean — a partial target file at
the user's requested path is the user's to manage (and they might even
want to keep a 50%-done render for debugging).

**Alternatives considered.**

1. **Delete `output.targetPath` too on failure.** Rejected: that path is
   caller-supplied (user chose `/tmp/out.mp4` or wherever). Silently
   deleting user-chosen paths on error is surprising and destructive —
   worse than the current "partial file, clear error message" state. If we
   want this later, it should be opt-in (e.g. `Input.deletePartialOutput`),
   not default.
2. **Run cleanup via `finally` not `catch`.** Would also run on success.
   Rejected: success path needs the mezzanines to remain (they're the
   output of the work we just did, now referenced by the persisted
   `ClipRenderCacheEntry`). The asymmetry `catch-then-rethrow` is exactly
   what we want.
3. **Defer cleanup to `gc_clip_render_cache` eventually.** Rejected:
   `gc_clip_render_cache` only walks directories listed in the cache; since
   failed-run mezzanines were never added to the cache, GC can't see them.
   The orphan-by-construction nature needs synchronous cleanup.
4. **Persist partial entries to the cache so GC can eventually walk them.**
   Rejected: that would encode failure state into user-visible cache rows,
   making "how much was rendered last time" semantically ambiguous.
   Cleaner to leave the cache consistent ("every row = a complete
   mezzanine") and clean up the disk synchronously.

业界共识对照:
- Idiomatic Kotlin cleanup-on-failure pattern is `try { … } catch (t:
  Throwable) { cleanup; throw t }` — standard enough that kotlinx.coroutines'
  structured-concurrency docs explicitly endorse `catch (t: Throwable)
  { cleanup; throw t }` as the right way to clean in cancellable code
  (cancel-on-Throwable is fine as long as we re-throw unchanged).
- FFmpeg's own pipeline library exposes a `cleanup_on_failure` concept
  keyed on exit code ≠ 0; our try/catch is the analog at the coroutine
  boundary.

**Coverage.**
- New test `perClipRenderFailureCleansUpFreshMezzanines` in
  `ExportToolTest.kt`: 3-clip timeline, `FailingPerClipEngine` crashes on
  the 3rd `renderClip`, assert:
  - The original `IllegalStateException("simulated ffmpeg crash…")`
    propagates (caller sees the real error).
  - `engine.deleted` set equals `engine.rendered` set (every fresh write
    gets deleted).
  - Exactly 2 mezzanines were deleted (1 and 2; the 3rd crashed before
    write, so nothing to delete).
  - `project.clipRenderCache.entries` is empty post-crash — cache stays
    consistent with on-disk state (post-cleanup both are empty).
- Existing happy-path tests (`perClipEngineRendersEveryClipOnFirstExport`,
  `perClipEngineReusesCachedMezzanineOnIdenticalRerun`,
  `perClipEngineReRendersOnlyTheStalyClip`) remain green — try/catch only
  activates on throw.
- `:core:jvmTest` ✓, `:apps:cli:test` ✓, `:apps:desktop:assemble` ✓,
  `:apps:android:assembleDebug` ✓, `:core:compileKotlinIosSimulatorArm64` ✓,
  `ktlintCheck` ✓.

**Registration.** None — internal behavioural fix in one function;
`VideoEngine.deleteMezzanine` signature is unchanged and every engine impl
(FFmpeg JVM, Media3 Android no-op default, AVFoundation iOS no-op default)
already implements it.
