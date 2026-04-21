## 2026-04-19 — `list_lockfile_entries` tool (VISION §3.1 — agent project orientation)

**Context.** The lockfile has been load-bearing since the AIGC lane landed:
`find_stale_clips` reads it to answer "what needs regenerating?",
`generate_image` / `synthesize_speech` write to it for cache hits. But the
agent had no way to introspect it — no answer to "what have we generated so
far?", "do we already have a Mei portrait we can crop instead of
re-generating?", or "show me the last 5 TTS calls so I can reuse a voice
line". Without that orientation step, planning tools get proposed that
duplicate existing artifacts.

**Decision.** New read-only tool `core.tool.builtin.project.ListLockfileEntriesTool`.
Input `(projectId, toolId?, limit=20, max=200)`. Returns entries most-recent
first with `(inputHash, toolId, assetId, providerId, modelId, seed,
createdAtEpochMs, sourceBindingIds)`. Permission `project.read`.

**Why most-recent-first in the response but append-only on disk.** The
lockfile's natural ordering is insertion-order (an audit trail — append-only).
But the agent's dominant query shape is "what did I generate recently?", so
reversing client-side saves the model a re-sort. The on-disk ordering stays
canonical; only the tool response is flipped.

**Why `toolId` filter instead of a kind/modality enum.** The lockfile records
tool ids verbatim (`"generate_image"`, `"synthesize_speech"`). A higher-level
enum (`image | audio | video`) would drift out of sync with the tool
registry every time a new AIGC tool lands. Filtering by the raw tool id
means the schema stays stable as the compiler surface grows.

**Why no cursor/pagination.** v0 target is projects with <1000 entries;
the 200-entry cap is enough for interactive orientation. When a user's
lockfile outgrows that, `limit` already supports exact slicing by the caller,
and real pagination can be added as a `offset` input without schema break.

**Placement under `project/`, not `aigc/`.** The lockfile is per-project
state — the same organization as `find_stale_clips` (also lockfile-driven).
AIGC tools *produce* entries; project tools *query* them. Co-locating the
query with the snapshot/fork/state tools keeps the agent's "planning" lane
in one namespace.

**System prompt + regression guard.** System prompt gains a short paragraph
naming the tool + the two canonical use cases (orientation, reuse).
`TaleviaSystemPromptTest` asserts `list_lockfile_entries` still appears so a
prompt-refactor can't silently drop it.

**Surface area.** Wired into all 4 composition roots (server, desktop,
Android, iOS).

---
