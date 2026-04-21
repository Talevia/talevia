## 2026-04-21 — find_stale_clips gains limit + deterministic sort (VISION §3.2 增量编译)

Commit: `2102c84`

**Context.** `find_stale_clips` is the lockfile-driven "what
needs regenerating?" query that sits at the center of VISION §3.2's
incremental-compilation story. Today it has two practical problems
against a real-sized project:

1. **Unbounded output.** After a mass-palette / style-bible edit on a
   project with 40+ AIGC clips, the tool serialises *every* stale
   report into the agent's tool-result. A single call can push
   hundreds of lines of `clipId / assetId / changedSourceIds` into
   context — which is exactly when the agent is also about to dispatch
   `regenerate_stale_clips` and other bulk tools, so the context budget
   is most contested.
2. **Non-deterministic ordering.** `staleClipsFromLockfile()` iterates
   the timeline then the lockfile; the lockfile is a list but the
   "which clip plays this asset" lookup collapses through a `Map`, and
   callers shouldn't rely on insertion order either way. Two
   back-to-back calls against the same `Project` could return the same
   rows in different orders depending on the store — which silently
   breaks any agent prompt that says "the first 5 stale clips are…"
   because "first" isn't stable.

**Decision.** Add a bounded, ordered surface — no filter creep yet:

- `limit: Int? = null` — default 50, max 500, rejected otherwise (same
  envelope as `list_project_snapshots`, `list_messages`, `list_assets`).
  When the true stale list exceeds the cap, `staleClipCount` stays the
  **true** total while `reports` is trimmed to the cap; the
  LLM-facing summary explicitly flags the truncation
  (`"showing first N of M — raise limit to see more"`) so the agent
  knows it's looking at a partial view. `totalClipCount` is unchanged
  in shape.
- **Sort: `reports.sortedBy { it.clipId }` (ASC).** Pick one and commit
  to it. Two alternatives were weighed (track order → clip time is
  arguably more "visually natural"; content-hash order would be cheap
  to compute). `clipId` ASC wins because: (a) `ClipId` is the primary
  handle callers use to dispatch follow-up tools, so sorted-by-id is
  also sorted-by-what-the-agent-prints; (b) it's stable across timeline
  edits — reordering a track doesn't reorder the stale list — which is
  the property we actually want for reproducible agent prompts; (c)
  it's the cheapest to implement and lets us ship without touching the
  detector.

**Alternatives considered.**

1. *Sort by track order then clip start time.* Appealing because it
   mirrors the "reading order" a human sees in a UI. Rejected because
   it couples the tool to `Timeline` ordering: drag a clip across
   tracks and the stale list reshuffles even though the staleness is
   unchanged. For "I'm iterating on the same regeneration prompt",
   `clipId`-sorted is more stable.
2. *Sort by `assetId`.* Rejected — two clips can legitimately share an
   asset id (same AIGC output dropped twice on the timeline), so the
   order is non-unique among ties and we'd be back to implicit
   insertion order for those ties.
3. *Leave output unbounded, rely on the LLM's summary being short.*
   Rejected. The summary is already short (first 5 clips + "…"), but
   the structured `reports` payload is what the tool actually returns
   through `ToolResult.data` and gets serialised into the tool-result
   part. Bounding `reports` is the only way to bound the real cost.
4. *Add `sourceId` / `kind` filters in the same cycle.* Deliberately
   deferred. The expected-file-changes envelope called out scope creep;
   limit+order is the minimum coherent unit. Filters can layer on top
   without touching this schema (new optional fields).
5. *Default `limit` to unlimited (0 / null).* Rejected — the whole
   point of this cycle is that "return every stale clip" is the
   behavior we're stepping back from. 50 is generous for normal editing
   sessions and the ceiling (500) is still plenty to audit a mass
   regeneration. Callers who legitimately need more can paginate by
   bumping it.

**Coverage.** `FindStaleClipsToolTest` gains three tests alongside the
existing lockfile-semantics suite:

- `limitCapsReportsButKeepsTrueStaleCount` — 12 stale clips,
  `limit=5`, asserts `staleClipCount == 12` and `reports.size == 5`.
- `reportOrderIsDeterministicAcrossCalls` — runs the tool twice
  against identical state, asserts the two `reports` lists are equal
  *and* that the shared order is ascending by `clipId`.
- `omittedLimitFallsBackToDefault50` — 60 stale clips with no
  `limit` in the input; asserts `reports.size == DEFAULT_LIMIT` (50)
  and `staleClipCount == 60`.

The new helper `seedManyStale` inserts clips in *reverse*-sorted order
so the deterministic-order test isn't accidentally satisfied by the
store handing us a pre-sorted stream — sort correctness has to come
from the tool.

**Registration.** no-op — no new tool registered. `FindStaleClipsTool`
is already wired in every `AppContainer`; the new `limit` input is a
nullable field with a default, so existing callers continue to work
and pick up the new cap transparently.
