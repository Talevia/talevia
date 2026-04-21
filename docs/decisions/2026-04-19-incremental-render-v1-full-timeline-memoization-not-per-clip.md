## 2026-04-19 — Incremental render v1: full-timeline memoization, not per-clip

**Context.** VISION §3.2 calls for "只重编译必要的部分". Two levels of caching could
realize this:
1. **Coarse:** memoize the whole export — same `(timeline, outputSpec)` → reuse the
   previous output.
2. **Fine-grained:** render only the clips whose `sourceBinding` intersects the stale
   closure; reuse the rest at the clip level.

**Decision.** Ship the coarse cache (Level 1) now; defer Level 2 to per-platform work.

- `Project.renderCache: RenderCache` stores `RenderCacheEntry(fingerprint, outputPath,
  resolution, duration, createdAtEpochMs)`.
- `ExportTool` hashes the canonical `Timeline` JSON + `OutputSpec` fields with
  `fnv1a64Hex` to produce the fingerprint. A second call with identical inputs hits
  the cache — zero engine invocations, same `Output.outputPath`.
- `Input.forceRender` bypasses the cache for debugging / user-driven re-render.

**Why this respects the DAG without a separate stale check.** `Clip.sourceBinding` is
inside the Timeline. When an upstream source node changes, AIGC tools rewrite the
corresponding asset id on cache miss (Task 3 lockfile), which changes the timeline JSON,
which changes the fingerprint. So the DAG's stale-propagation lane flows through
`Source.stale → AIGC rewrite → timeline rewrite → render cache miss` without this
layer knowing anything about source nodes directly. Clean separation.

**Why Level 2 is deferred.** Per-clip render requires the `VideoEngine` to expose a
per-clip render path (render one clip to an intermediate file; compose at the end).
FFmpeg-JVM could do this today; AVFoundation and Media3 can too, but each is a distinct
compositing pipeline. Doing it uniformly across engines is a multi-week engine-side
project, not a core-side abstraction. Better to ship the coarse cache first so VISION §3.2
has *some* realization end-to-end, then revisit fine-grained once we're exercising the
coarse cache in real workflows.

**Staleness of the file on disk.** Cache entries aren't re-validated against the file
system. If the user deletes `/tmp/out.mp4` between exports, a cache hit returns the
path anyway. The user's remedy is `forceRender=true` — adding a file-existence check
in `commonMain` requires platform-specific plumbing we don't need yet.
