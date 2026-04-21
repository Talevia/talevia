## 2026-04-19 — ExportTool stale-guard — refuse stale renders by default

**Context.** Gap-analysis against VISION §5 rubric flagged the highest-leverage
§3.2 gap: `staleClipsFromLockfile` was computed and surfaced via
`find_stale_clips`, but `ExportTool` happily rendered a timeline with stale
AIGC clips, and even worse, the render cache (keyed on timeline JSON + output
spec) would hand back the same stale output on re-run. A source-only edit
(e.g. "make Mei's hair red") doesn't change timeline JSON — drift only shows
up via `clip.sourceBinding` → lockfile hash comparison — so the existing
cache correctness argument ("DAG is respected implicitly because AIGC rewrites
assetId on cache miss") only holds when something has actually triggered a
regeneration. Pre-regeneration, the cache lies.

**Decision.**
- Add `allowStale: Boolean = false` to `ExportTool.Input`.
- Before fingerprinting / cache lookup / engine invocation, compute
  `project.staleClipsFromLockfile()`. If non-empty and `!allowStale`, fail
  fast with an `error(...)` message naming up to 5 stale clip ids and the
  drifted source-node ids, pointing the agent at `find_stale_clips` +
  regeneration before retry.
- On successful render (or cache hit) while `allowStale=true`, surface the
  stale ids on `Output.staleClipsIncluded` and append a `[allowStale: N
  stale clip(s)]` tail to the LLM-facing string so the model can't quietly
  ship drifted content.
- `forceRender` and `allowStale` are orthogonal flags: stale-guard is
  checked first, then the cache; `forceRender` only bypasses the cache.

**Alternatives considered.**
- **Auto-regenerate stale clips before export.** Attractive UX-wise but
  wrong layering: `ExportTool` would need to know how to dispatch
  `generate_image` / `generate_video` for arbitrary `clip.sourceBinding`
  graphs, which turns it into a meta-tool. The agent is the right layer
  for that planning — a clear error message that names the right tool is
  sufficient.
- **Mark the render cache stale-aware instead of refusing at export.**
  Would still produce drifted output, just not from the cache. Cache-only
  fix doesn't address the underlying correctness issue.
- **Warn (log) and continue.** The whole point of §3.2 is that reproducible
  builds require refusing suspect inputs, not burying warnings. Opt-in via
  `allowStale` preserves the escape hatch for the rare "ship it anyway"
  case.
- **Default `allowStale=true` for backward compat.** Rejected — no users
  yet, and the silent-stale behavior is exactly the anti-pattern VISION
  calls out. Fail-loud default is correct for a one-developer pre-v1.

**Why.** VISION §3.2 bet ("只重编译必要的部分") only pays off if the system
refuses to reuse or emit output that the DAG knows is invalid.
`find_stale_clips` alone makes the gap visible to the agent; this change
makes it visible to the export pipeline too, closing the loop.

**How to apply.** When adding future renderers or export paths (e.g. audio
export, per-clip render in task #6), call `staleClipsFromLockfile` +
respect `allowStale` before invoking the engine. The guard belongs
anywhere we turn the current project state into a user-visible artifact.

---
