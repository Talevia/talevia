## 2026-04-19 — Per-clip incremental render — deferred, rationale recorded

**Context.** The highest §3.2 gap called out by the gap-analysis Explore
round was "per-clip incremental render" — `RenderCache` memoizes whole
timeline exports; there is no mechanism to render a single stale clip and
reuse intermediate files for the rest. VISION §3.2 ("只重编译必要的部分")
is a load-bearing bet; this is the clearest way to honour it at export time.

**Decision.** Defer. Keep the full-timeline memoization + the stale-guard
from this round; document what a per-clip path would need and why it
doesn't fit the current iteration.

**Why deferring.** A correct per-clip pipeline has to address, at minimum:
1. **Per-clip render API on every engine.** FFmpeg can do it (render each
   clip to an intermediate .mp4, concat demux at the end). AVFoundation
   requires an `AVMutableComposition` per clip and a master composition at
   stitch time. Media3 is per-`MediaItem` already but shares a
   `Transformer` pipeline — per-clip outputs require composing two
   transforms stages. Three engines, three shapes, all of which must agree
   on "what does an intermediate clip file look like" (codec, container,
   colour space, frame rate) for concat at the end.
2. **Transitions span clip boundaries.** A dip-to-black between clip A and
   clip B uses the tail of A + head of B. If A is cache-fresh and B is
   stale, you can't just stitch cached A + freshly-rendered B — you need
   to re-render the transition region. That means clip fingerprints need a
   neighbour-aware component, or the cache is keyed at "clip + transition
   context" granularity, not just clip.
3. **Cache correctness under source-stale drift.** `staleClipsFromLockfile`
   operates at the clip-binding level — a clip is stale when its *bound
   source nodes* drifted. A per-clip render cache key must include both
   the clip's own content hash *and* its bound-source hashes, otherwise a
   source edit that marks the clip stale but doesn't change the clip's
   `assetId` (yet) would spuriously cache-hit.
4. **Storage / eviction policy.** Per-clip intermediates are typically
   larger than finished exports (concat-friendly mezzanine codec). A
   user editing a 30-clip project would materialise 30+ intermediate
   files that need retention rules, path conventions, and a cleanup
   path — all new surface area.

Each item is tractable; the combination is a multi-day refactor that would
regress today's coarse cache correctness if shipped half-built.

**Partial paths considered and rejected for this round.**
- **Only-AIGC-clips per-clip cache.** The real saving on AIGC clips is
  already captured by the lockfile — a cached generate_image call is a
  no-op. Per-clip render cache on top would re-save the rendered pixels,
  not the generation cost. Marginal gain.
- **Fingerprint-only prep (compute per-clip hashes, don't wire).**
  Architectural doodling without user-visible value; fingerprint design
  depends on the engine decisions above.
- **FFmpeg-only per-clip render.** Would fork desktop (FFmpeg) behaviour
  from iOS / Android, exactly the cross-engine parity we just finished
  closing. Not an option in a codebase where CLAUDE.md §Architecture
  rules are "Timeline is owned by Core" / cross-platform parity.

**How to apply when we revisit.** Start with FFmpeg (desktop-first per
platform priority). Define a `PerClipRenderSpec` that includes the clip +
its transition-context neighbours; compute a `clipFingerprint` that hashes
(a) clip content fields, (b) bound-source content hashes from the
lockfile, (c) transition-overlap context. Store intermediates under a
project-scoped mezzanine directory with a retention policy tied to
`RenderCache` entries. Extend `ExportTool` to walk clips, cache-hit/miss
per clip, stitch. Only then lift to iOS / Android.

**Impact on the rest of the system.** None. Today's `ExportTool` +
stale-guard + lockfile already refuse stale outputs and reuse whole-
timeline renders on identical inputs. The missing piece is "render only
the delta"; the system is still correct in its absence, just less
efficient on long projects with a single small edit.

---
